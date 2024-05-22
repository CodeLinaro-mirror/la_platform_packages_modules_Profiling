/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.os.profiling;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.icu.text.SimpleDateFormat;
import android.icu.util.Calendar;
import android.icu.util.TimeZone;
import android.os.Binder;
import android.os.Bundle;
import android.os.FileUtils;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IProfilingResultCallback;
import android.os.IProfilingService;
import android.os.ParcelFileDescriptor;
import android.os.ProfilingManager;
import android.os.ProfilingResult;
import android.os.RemoteException;
import android.provider.DeviceConfig;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Log;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.SystemService;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class ProfilingService extends IProfilingService.Stub {
    private static final String TAG = ProfilingService.class.getSimpleName();
    private static final boolean DEBUG = false;

    private static final String TEMP_TRACE_PATH = "/data/misc/perfetto-traces/profiling/";
    private static final String OUTPUT_FILE_RELATIVE_PATH = "/profiling/";
    private static final String OUTPUT_FILE_SECTION_SEPARATOR = "_";
    private static final String OUTPUT_FILE_PREFIX = "profile";
    // Keep in sync with {@link ProfilingFrameworkTests}.
    private static final String OUTPUT_FILE_JAVA_HEAP_DUMP_SUFFIX = ".perfetto-java-heap-dump";
    private static final String OUTPUT_FILE_HEAP_PROFILE_SUFFIX = ".perfetto-heap-profile";
    private static final String OUTPUT_FILE_STACK_SAMPLING_SUFFIX = ".perfetto-stack-sample";
    private static final String OUTPUT_FILE_TRACE_SUFFIX = ".perfetto-trace";
    private static final String OUTPUT_FILE_UNREDACTED_TRACE_SUFFIX = ".perfetto-trace-unredacted";

    private static final int TAG_MAX_CHARS_FOR_FILENAME = 20;

    private static final int PERFETTO_DESTROY_DEFAULT_TIMEOUT_MS = 10 * 1000;

    private static final int REDACTION_MAX_RUNTIME_ALLOTTED_MS = 20  * 1000;

    private static final int REDACTION_CHECK_FREQUENCY_MS = 2 * 1000;

    private final Context mContext;
    private final Object mLock = new Object();
    private final HandlerThread mHandlerThread = new HandlerThread("ProfilingService");

    @VisibleForTesting public RateLimiter mRateLimiter = null;

    // Timeout for Perfetto process to successfully stop after we try to stop it.
    private int mPerfettoDestroyTimeoutMs;

    private Handler mHandler;

    private Calendar mCalendar = null;
    private SimpleDateFormat mDateFormat = null;

    // uid indexed collecion of lists of JNI callbacks for results.
    @VisibleForTesting
    public SparseArray<List<IProfilingResultCallback>> mResultCallbacks = new SparseArray<>();

    // Request UUID key indexed storage of active tracing sessions. Currently only 1 active session
    // is supported at a time, but this will be used in future to support multiple.
    @VisibleForTesting
    public ArrayMap<String, TracingSession> mTracingSessions = new ArrayMap<>();

    /** To be disabled for testing only. */
    @GuardedBy("mLock")
    private boolean mKeepUnredactedTrace = false;

    @VisibleForTesting
    public ProfilingService(Context context) {
        mContext = context;

        mPerfettoDestroyTimeoutMs = DeviceConfigHelper.getInt(
                DeviceConfigHelper.PERFETTO_DESTROY_TIMEOUT_MS,
                PERFETTO_DESTROY_DEFAULT_TIMEOUT_MS);

        mHandlerThread.start();

        // Get initial value for whether unredacted trace should be retained.
        // This is used for (automated and manual) testing only.
        synchronized (mLock) {
            mKeepUnredactedTrace = DeviceConfigHelper.getTestBoolean(
                    DeviceConfigHelper.DISABLE_DELETE_UNREDACTED_TRACE, false);
        }
        // Now subscribe to updates on test config.
        DeviceConfig.addOnPropertiesChangedListener(DeviceConfigHelper.NAMESPACE_TESTING,
                mContext.getMainExecutor(), new DeviceConfig.OnPropertiesChangedListener() {
                    @Override
                    public void onPropertiesChanged(@NonNull DeviceConfig.Properties properties) {
                        synchronized (mLock) {
                            mKeepUnredactedTrace = properties.getBoolean(
                                    DeviceConfigHelper.DISABLE_DELETE_UNREDACTED_TRACE, false);
                            getRateLimiter().maybeUpdateRateLimiterDisabled(properties);
                        }
                    }
                });

        // Subscribe to updates on the main config.
        DeviceConfig.addOnPropertiesChangedListener(DeviceConfigHelper.NAMESPACE,
                mContext.getMainExecutor(), new DeviceConfig.OnPropertiesChangedListener() {
                    @Override
                    public void onPropertiesChanged(@NonNull DeviceConfig.Properties properties) {
                        synchronized (mLock) {
                            getRateLimiter().maybeUpdateConfigs(properties);
                            Configs.maybeUpdateConfigs(properties);
                            mPerfettoDestroyTimeoutMs = properties.getInt(
                                    DeviceConfigHelper.PERFETTO_DESTROY_TIMEOUT_MS,
                                    mPerfettoDestroyTimeoutMs);
                        }
                    }
                });
    }

    /**
     * This method validates the request, arguments, whether the app is allowed to profile now,
     * and if so, starts the profiling.
     */
    public void requestProfiling(int profilingType, Bundle params, String filePath, String tag,
            long keyMostSigBits, long keyLeastSigBits) {
        int uid = Binder.getCallingUid();

        if (profilingType != ProfilingManager.PROFILING_TYPE_JAVA_HEAP_DUMP
                && profilingType != ProfilingManager.PROFILING_TYPE_HEAP_PROFILE
                && profilingType != ProfilingManager.PROFILING_TYPE_STACK_SAMPLING
                && profilingType != ProfilingManager.PROFILING_TYPE_SYSTEM_TRACE) {
            if (DEBUG) Log.d(TAG, "Invalid request profiling type: " + profilingType);
            processResultCallback(uid, keyMostSigBits, keyLeastSigBits,
                    ProfilingResult.ERROR_FAILED_INVALID_REQUEST, null, tag,
                    "Invalid request profiling type");
            return;
        }

        // Check if we're running another trace so we don't run multiple at once.
        try {
            if (areAnyTracesRunning()) {
                processResultCallback(uid, keyMostSigBits, keyLeastSigBits,
                        ProfilingResult.ERROR_FAILED_PROFILING_IN_PROGRESS, null, tag, null);
                return;
            }
        } catch (RuntimeException e) {
            if (DEBUG) Log.d(TAG, "Error communicating with perfetto", e);
            processResultCallback(uid, keyMostSigBits, keyLeastSigBits,
                    ProfilingResult.ERROR_UNKNOWN, null, tag, "Error communicating with perfetto");
            return;
        }

        // Get package name for requesting process. We can't request the trace without it.
        String packageName = mContext.getPackageManager().getNameForUid(uid);
        if (packageName == null) {
            if (DEBUG) Log.d(TAG, "Could not get package name for UID: " + uid);
            processResultCallback(uid, keyMostSigBits, keyLeastSigBits,
                    ProfilingResult.ERROR_UNKNOWN, null, tag, "Couldn't determine package name");
            return;
        }

        // Check with rate limiter if this request is allowed.
        final int status = getRateLimiter().isProfilingRequestAllowed(Binder.getCallingUid(),
                profilingType, params);
        if (DEBUG) Log.d(TAG, "Rate limiter status: " + status);
        if (status == RateLimiter.RATE_LIMIT_RESULT_ALLOWED) {
            // Rate limiter approved, try to start the request.
            try {
                TracingSession session = new TracingSession(profilingType, params, filePath, uid,
                        packageName, tag, keyMostSigBits, keyLeastSigBits);
                startProfiling(session);
            } catch (IllegalArgumentException e) {
                // This should not happen, it should have been caught when checking rate limiter.
                // Issue with the request. Apps fault.
                if (DEBUG) {
                    Log.d(TAG,
                            "Invalid request at config generation. This should not have happened.",
                            e);
                }
                processResultCallback(uid, keyMostSigBits, keyLeastSigBits,
                        ProfilingResult.ERROR_FAILED_INVALID_REQUEST, null, tag, e.getMessage());
                return;
            } catch (RuntimeException e) {
                // Perfetto error. Systems fault.
                if (DEBUG) Log.d(TAG, "Perfetto error", e);
                processResultCallback(uid, keyMostSigBits, keyLeastSigBits,
                        ProfilingResult.ERROR_UNKNOWN, null, tag, "Perfetto error");
                return;
            }
        } else {
            // Rate limiter denied, notify caller.
            if (DEBUG) Log.d(TAG, "Request denied with status: " + status);
            processResultCallback(uid, keyMostSigBits, keyLeastSigBits,
                    RateLimiter.statusToResult(status), null, tag, null);
        }
    }

    public void registerResultsCallback(IProfilingResultCallback callback) {
        int callingUid = Binder.getCallingUid();
        List<IProfilingResultCallback> perUidCallbacks = mResultCallbacks.get(callingUid);
        if (perUidCallbacks == null) {
            perUidCallbacks = new ArrayList<IProfilingResultCallback>();
            mResultCallbacks.put(callingUid, perUidCallbacks);
        }
        perUidCallbacks.add(callback);
    }

    public void requestCancel(long keyMostSigBits, long keyLeastSigBits) {
        String key = (new UUID(keyMostSigBits, keyLeastSigBits)).toString();
        if (!isTraceRunning(key)) {
            // No trace running, nothing to cancel.
            if (DEBUG) {
                Log.d(TAG, "Exited requestCancel without stopping trace key:" + key
                        + " due to no trace running.");
            }
            return;
        }
        stopProfiling(key);
    }

    private void processResultCallback(TracingSession session, int status, @Nullable String error) {
        processResultCallback(session.getUid(), session.getKeyMostSigBits(),
                session.getKeyLeastSigBits(), status,
                session.getDestinationFileName(OUTPUT_FILE_RELATIVE_PATH),
                session.getTag(), error);
    }

    /**
     * An app can register multiple callbacks between this service and {@link ProfilingManager}, one
     * per context that the app created a manager instance with. As we do not know on this service
     * side which callbacks need to be triggered with this result, trigger all of them and let them
     * decide whether to finish delivering it.
     */
    private void processResultCallback(int uid, long keyMostSigBits, long keyLeastSigBits,
            int status, @Nullable String filePath, @Nullable String tag, @Nullable String error) {
        List<IProfilingResultCallback> perUidCallbacks = mResultCallbacks.get(uid);
        if (perUidCallbacks == null || perUidCallbacks.isEmpty()) {
            // No callbacks, nowhere to notify with result or failure.
            if (DEBUG) Log.d(TAG, "No callback to ProfilingManager, callback dropped.");
            return;
        }

        List<IProfilingResultCallback> remove = new ArrayList<IProfilingResultCallback>();
        for (int i = 0; i < perUidCallbacks.size(); i++) {
            try {
                if (!perUidCallbacks.get(i).sendResult(filePath, keyMostSigBits,
                        keyLeastSigBits, status, tag, error)) {
                    // sendResult will return false if there are no more listeners using this
                    // connection. Global listeners use the connection continuously and will thus
                    // not return false.
                    remove.add(perUidCallbacks.get(i));
                }
            } catch (RemoteException e) {
                // Failed to send result. Ignore.
                if (DEBUG) Log.d(TAG, "Exception processing result callback", e);
            }
        }
        if (!remove.isEmpty()) {
            mResultCallbacks.get(uid).removeAll(remove);
        }
    }

    private void startProfiling(final TracingSession session)
            throws RuntimeException {
        // Parse config and post processing delay out of request first, if we can't get these
        // we can't start the trace.
        int postProcessingDelayMs;
        byte[] config;
        String suffix;
        String tag;
        try {
            postProcessingDelayMs = session.getPostProcessingScheduleDelayMs();
            config = session.getConfigBytes();
            suffix = getFileSuffixForRequest(session.getProfilingType());

            // Create a version of tag that is non null, containing only valid filename chars,
            // and shortened to class defined max size.
            tag = session.getTag() == null
                    ? "" : removeInvalidFilenameChars(session.getTag());
            if (tag.length() > TAG_MAX_CHARS_FOR_FILENAME) {
                tag = tag.substring(0, TAG_MAX_CHARS_FOR_FILENAME);
            }
        } catch (IllegalArgumentException e) {
            // Request couldn't be processed. This shouldn't happen.
            if (DEBUG) Log.d(TAG, "Request couldn't be processed", e);
            processResultCallback(session, ProfilingResult.ERROR_FAILED_INVALID_REQUEST,
                    e.getMessage());
            return;

        }

        String baseFileName = OUTPUT_FILE_PREFIX
                + (tag.isEmpty() ? "" : OUTPUT_FILE_SECTION_SEPARATOR + tag)
                + OUTPUT_FILE_SECTION_SEPARATOR + getFormattedDate();

        // Only trace files will go through the redaction process, set the name here for the file
        // that will be created later when results are processed.
        if (session.getProfilingType() == ProfilingManager.PROFILING_TYPE_SYSTEM_TRACE) {
            session.setRedactedFileName(baseFileName + OUTPUT_FILE_TRACE_SUFFIX);
        }

        session.setFileName(baseFileName + suffix);

        try {
            ProcessBuilder pb = new ProcessBuilder("/system/bin/perfetto", "-o",
                    TEMP_TRACE_PATH + session.getFileName(), "-c", "-", "--txt");
            Process activeTrace = pb.start();
            activeTrace.getOutputStream().write(config);
            activeTrace.getOutputStream().close();
            // If we made it this far the trace is running, save the session.
            session.setActiveTrace(activeTrace);
            mTracingSessions.put(session.getKey(), session);
        } catch (Exception e) {
            // Catch all exceptions related to starting process as they'll all be handled similarly.
            if (DEBUG) Log.d(TAG, "Trace couldn't be started", e);
            processResultCallback(session, ProfilingResult.ERROR_FAILED_EXECUTING, null);
            return;
        }

        // Create post process runnable, store it, and schedule it.
        session.setProcessResultRunnable(new Runnable() {
            @Override
            public void run() {
                // TODO: confirm perfetto is done and reschedule if not
                session.setProcessResultRunnable(null);
                processResult(session);
            }
        });
        getHandler().postDelayed(session.getProcessResultRunnable(), postProcessingDelayMs);
    }

    private void stopProfiling(String key) throws RuntimeException {
        TracingSession session = mTracingSessions.get(key);
        if (session == null || session.getActiveTrace() == null) {
            if (DEBUG) Log.d(TAG, "No active trace, nothing to stop.");
            return;
        }

        if (session.getProcessResultRunnable() == null) {
            if (DEBUG) {
                Log.d(TAG,
                        "No runnable, it either stopped already or is in the process of stopping.");
            }
            return;
        }

        // Remove the post processing runnable set with the default timeout. After stopping use the
        // same runnable to process immediately.
        getHandler().removeCallbacks(session.getProcessResultRunnable());

        // End the tracing session.
        session.getActiveTrace().destroyForcibly();
        try {
            if (!session.getActiveTrace().waitFor(mPerfettoDestroyTimeoutMs,
                    TimeUnit.MILLISECONDS)) {
                if (DEBUG) Log.d(TAG, "Stopping of running trace process timed out.");
                throw new RuntimeException("topping of running trace process timed out.");
            }
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        // If we made it here the result is ready, now run the post processing runnable.
        getHandler().post(session.getProcessResultRunnable());
    }

    public boolean areAnyTracesRunning() throws RuntimeException {
        for (int i = 0; i < mTracingSessions.size(); i++) {
            if (isTraceRunning(mTracingSessions.keyAt(i))) {
                return true;
            }
        }
        return false;
    }

    public boolean isTraceRunning(String key) throws RuntimeException {
        TracingSession session = mTracingSessions.get(key);
        if (session == null || session.getActiveTrace() == null) {
            // No subprocess, nothing running.
            if (DEBUG) Log.d(TAG, "No subprocess, nothing running.");
            return false;
        } else if (session.getActiveTrace().isAlive()) {
            // Subprocess exists and is alive.
            if (DEBUG) Log.d(TAG, "Subprocess exists and is alive, trace is running.");
            return true;
        } else {
            // Subprocess exists but is not alive, nothing running. Clean up before returning.
            if (DEBUG) Log.d(TAG, "Subprocess exists but is not alive, nothing running.");
            stopProfiling(key);
            if (DEBUG) Log.d(TAG, "Non running process cleaned up.");
            return false;
        }
    }

    /**
     * Move the result file from temporary storage to the apps internal storage.
     *
     * This is done by requesting {@link ProfilingManager} create a file from within app context
     * and return a {@link ParcelFileDescriptor} to copy the temporary file contents to.
     * Finally, delete the temporary file.
     *
     */
    private void moveFileToAppStorage(TracingSession session) {
        List<IProfilingResultCallback> perUidCallbacks = mResultCallbacks.get(session.getUid());
        if (perUidCallbacks == null || perUidCallbacks.isEmpty()) {
            // No callback so no way to obtain a file to populate with result.
            if (DEBUG) Log.d(TAG, "No callback to ProfilingManager, callback dropped.");
            // TODO: b/333456430 queue this and try next time the uid registers a receiver.
            // TODO: b/333456916 run a cleanup of old results based on a max size and time.
            return;
        }

        // Setup file streams.
        File tempResultFile = new File(TEMP_TRACE_PATH
                + (session.getProfilingType() == ProfilingManager.PROFILING_TYPE_SYSTEM_TRACE
                ? session.getRedactedFileName() : session.getFileName()));
        FileInputStream tempPerfettoFileInStream = null;
        FileOutputStream appFileOutStream = null;
        ParcelFileDescriptor fileDescriptor = null;
        boolean failed = false;
        try {
            tempPerfettoFileInStream = new FileInputStream(tempResultFile);
        } catch (IOException e) {
            // IO Exception opening temp perfetto file. No result.
            if (DEBUG) Log.d(TAG, "Exception opening temp perfetto file.", e);
            failed = true;
        }

        // Obtain a file descriptor for the result file in app storage from {@link ProfilingManager}
        if (!failed) {
            fileDescriptor = obtainFileForResult(perUidCallbacks,
                    session.getAppFilePath() + OUTPUT_FILE_RELATIVE_PATH, tempResultFile.getName());
            if (fileDescriptor != null) {
                appFileOutStream = new FileOutputStream(fileDescriptor.getFileDescriptor());
            }

            if (appFileOutStream == null) {
                failed = true;
                // TODO: b/333456430 queue this and try next time the uid registers a receiver.
            }
        }

        // Now copy the file over.
        if (!failed) {
            try {
                FileUtils.copy(tempPerfettoFileInStream, appFileOutStream);
            } catch (IOException e) {
                // Exception writing to local app file.
                if (DEBUG)  Log.d(TAG, "Exception writing to local app file.", e);
                // TODO: b/333456430 queue this and try again later.
                failed = true;
            }
        }

        // Finally delete the temp file.
        if (!failed) {
            try {
                tempResultFile.delete();
            } catch (SecurityException e) {
                // Exception deleting temp file.
                if (DEBUG) Log.d(TAG, "Permissions exception deleting temp file.", e);
            }
        }

        // Now cleanup.
        if (tempPerfettoFileInStream != null) {
            try {
                tempPerfettoFileInStream.close();
            } catch (IOException e) {
                if (DEBUG) Log.d(TAG, "Failed to close temp perfetto input stream.", e);
            }
        }
        if (fileDescriptor != null) {
            try {
                fileDescriptor.close();
            } catch (IOException e) {
                if (DEBUG) Log.d(TAG, "Failed to close app file output file FileDescriptor.", e);
            }
        }
        if (appFileOutStream != null) {
            try {
                appFileOutStream.close();
            } catch (IOException e) {
                if (DEBUG) Log.d(TAG, "Failed to close app file output file stream.", e);
            }
        }

        if (!failed) {
            processResultCallback(session, ProfilingResult.ERROR_NONE, null);
            mTracingSessions.remove(session.getKey());
        } else {
            // Couldn't move file. File is still in temp directory and can be tried later.
            // TODO queue and try later when another listener is registered to this uid.
            if (DEBUG) Log.d(TAG, "Couldn't move file to app storage.");
            processResultCallback(session, ProfilingResult.ERROR_FAILED_POST_PROCESSING, null);
        }
    }

    /**
     * Try each callback for the current process until we successfully obtain a
     * {@link ParcelFileDescriptor} to a new file in app storage. Returns null if all callbacks
     * fail.
     *
     * Result file is created by {@link ProfilingManager} from within app context. We only need a
     * single file which can be created from any of the requesting apps contexts so it does not
     * matter which callback we use.
     */
    @Nullable
    private ParcelFileDescriptor obtainFileForResult(
            @NonNull List<IProfilingResultCallback> perUidCallbacks, String filePath,
            String fileName) {
        for (int i = 0; i < perUidCallbacks.size(); i++) {
            try {
                ParcelFileDescriptor fileDescriptor = perUidCallbacks.get(i).generateFile(filePath,
                        fileName);
                if (fileDescriptor != null) {
                    return fileDescriptor;
                }
            } catch (RemoteException e) {
                // Binder exception getting file. Continue trying other callbacks for this process.
                if (DEBUG) Log.d(TAG, "Binder exception getting file. Trying next callback", e);
            }
        }
        if (DEBUG) Log.d(TAG, "Failed to obtain file descriptor from callbacks.");
        return null;
    }


    // processResult will be called after every profiling type is collected, traces will go
    // through a redaction process before being returned to the client.  All other profiling types
    // can be returned as is.
    private void processResult(TracingSession session) {
        if (session.getProfilingType() == ProfilingManager.PROFILING_TYPE_SYSTEM_TRACE) {
            handleTraceResult(session);
        } else {
            moveFileToAppStorage(session);
        }
    }

    //TODO b/331684767 need to figure out what errors from redaction can be retried.
    private void handleTraceResult(TracingSession session) {
        try {
            // We need to create an empty file for the redaction process to write the output into.
            File emptyRedactedTraceFile = new File(TEMP_TRACE_PATH
                    + session.getRedactedFileName());
            emptyRedactedTraceFile.createNewFile();
        } catch (Exception exception) {
            if (DEBUG) Log.e(TAG, "Creating empty redacted file failed.", exception);
            processResultCallback(session, ProfilingResult.ERROR_FAILED_POST_PROCESSING, null);
            return;
        }

        try {
            // Start the redaction process and log the time of start.  Redaction has
            // REDACTION_MAX_RUNTIME_ALLOTTED_MS to complete. Redaction status will be checked every
            // REDACTION_CHECK_FREQUENCY_MS.
            ProcessBuilder redactionProcess = new ProcessBuilder("/system/bin/trace_redactor",
                    TEMP_TRACE_PATH + session.getFileName(),
                    TEMP_TRACE_PATH + session.getRedactedFileName(),
                    session.getPackageName());
            session.setActiveRedaction(redactionProcess.start());
            session.setRedactionStartTimeMs(System.currentTimeMillis());
        } catch (Exception exception) {
            if (DEBUG) Log.e(TAG, "Redaction failed to run completely.", exception);
            processResultCallback(session, ProfilingResult.ERROR_FAILED_POST_PROCESSING, null);
            return;
        }
        session.setProcessResultRunnable(new Runnable() {

            @Override
            public void run() {
                checkRedactionStatus(session);
            }
        });
        // TODO b/333476809 adjust frequency time once we have a better
        //  understanding of redaction performance.
        getHandler().postDelayed(session.getProcessResultRunnable(),
                REDACTION_CHECK_FREQUENCY_MS);
    }

    private void checkRedactionStatus(TracingSession session) {
        // Check if redaction is complete.
        if (!session.getActiveRedaction().isAlive()) {
            handleRedactionComplete(session);
            session.setProcessResultRunnable(null);
            return;
        }

        // Check if we are over the REDACTION_MAX_RUNTIME_ALLOTTED_MS threshold.
        if ((System.currentTimeMillis() - session.getRedactionStartTimeMs())
                > REDACTION_MAX_RUNTIME_ALLOTTED_MS) {
            if (DEBUG) Log.d(TAG, "Redaction process has timed out");

            session.getActiveRedaction().destroyForcibly();
            session.setProcessResultRunnable(null);
            processResultCallback(session, ProfilingResult.ERROR_FAILED_POST_PROCESSING,
                    null);

            return;
        }
        getHandler().postDelayed(session.getProcessResultRunnable(),
                Math.min(REDACTION_CHECK_FREQUENCY_MS, REDACTION_MAX_RUNTIME_ALLOTTED_MS
                        - (System.currentTimeMillis() - session.getRedactionStartTimeMs())));

    }

    private void handleRedactionComplete(TracingSession session) {
        int redactionErrorCode = session.getActiveRedaction().exitValue();
        if (redactionErrorCode != 0) {
            // Redaction process failed.
            if (DEBUG) {
                Log.d(TAG, String.format("Redaction processed failed with error code: %s",
                        redactionErrorCode));
            }
            processResultCallback(session, ProfilingResult.ERROR_FAILED_POST_PROCESSING, null);
            return;
        }

        // At this point redaction has completed successfully it is safe to delete the
        // unredacted trace file unless {@link mKeepUnredactedTrace} has been enabled.
        synchronized (mLock) {
            if (mKeepUnredactedTrace) {
                Log.i(TAG, "Unredacted trace file retained at: "
                        + TEMP_TRACE_PATH + session.getFileName());
            } else {
                // TODO b/331988161 Delete after file is delivered to app.
                try {
                    Files.delete(Path.of(TEMP_TRACE_PATH + session.getFileName()));
                } catch (Exception exception) {
                    if (DEBUG) Log.e(TAG, "Failed to delete unredacted file.", exception);
                }
            }
        }

        moveFileToAppStorage(session);
    }

    private Handler getHandler() {
        if (mHandler == null) {
            mHandler = new Handler(mHandlerThread.getLooper());
        }
        return mHandler;
    }

    private RateLimiter getRateLimiter() {
        if (mRateLimiter == null) {
            mRateLimiter = new RateLimiter(new RateLimiter.HandlerCallback() {
                @Override
                public Handler obtainHandler() {
                    return getHandler();
                }
            });
        }
        return mRateLimiter;
    }

    private String getFormattedDate() {
        if (mCalendar == null) {
            mCalendar = Calendar.getInstance(TimeZone.getTimeZone("GMT"));
        }
        if (mDateFormat == null) {
            mDateFormat = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.US);
        }
        mCalendar.setTimeInMillis(System.currentTimeMillis());
        return mDateFormat.format(mCalendar.getTime());
    }

    private static String getFileSuffixForRequest(int profilingType) {
        switch (profilingType) {
            case ProfilingManager.PROFILING_TYPE_JAVA_HEAP_DUMP:
                return OUTPUT_FILE_JAVA_HEAP_DUMP_SUFFIX;
            case ProfilingManager.PROFILING_TYPE_HEAP_PROFILE:
                return OUTPUT_FILE_HEAP_PROFILE_SUFFIX;
            case ProfilingManager.PROFILING_TYPE_STACK_SAMPLING:
                return OUTPUT_FILE_STACK_SAMPLING_SUFFIX;
            case ProfilingManager.PROFILING_TYPE_SYSTEM_TRACE:
                return OUTPUT_FILE_UNREDACTED_TRACE_SUFFIX;
            default:
                throw new IllegalArgumentException("Invalid profiling type");
        }
    }

    private static String removeInvalidFilenameChars(String original) {
        if (TextUtils.isEmpty(original)) {
            return "";
        }
        final StringBuilder sb = new StringBuilder(original.length());
        for (int i = 0; i < original.length(); i++) {
            final char c = Character.toLowerCase(original.charAt(i));
            if (isValidFilenameChar(c)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static boolean isValidFilenameChar(char c) {
        if (c >= 'a' && c <= 'z') {
            return true;
        }
        if (c >= '0' && c <= '9') {
            return true;
        }
        if (c == '-') {
            return true;
        }
        return false;
    }

    public static final class Lifecycle extends SystemService {
        final ProfilingService mService;

        public Lifecycle(Context context) {
            this(context, new ProfilingService(context));
        }

        @VisibleForTesting
        public Lifecycle(Context context, ProfilingService service) {
            super(context);
            mService = service;
        }

        @Override
        public void onStart() {
            try {
                publishBinderService("profiling_service", mService);
            } catch (Exception e) {
                if (DEBUG) Log.d(TAG, "Failed to publish service", e);
            }
        }

        @Override
        public void onBootPhase(int phase) {
            super.onBootPhase(phase);
        }
    }
}
