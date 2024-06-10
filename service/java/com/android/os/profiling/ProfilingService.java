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
import android.os.IBinder;
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
import java.io.FilenameFilter;
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

    private static final int DEFAULT_MAX_RESULT_REDELIVERY_COUNT = 3;

    private static final int REDACTION_DEFAULT_MAX_RUNTIME_ALLOTTED_MS = 20  * 1000;

    private static final int REDACTION_DEFAULT_CHECK_FREQUENCY_MS = 2 * 1000;

    // The cadence at which the profiling process will be checked after the initial delay
    // has elapsed.
    private static final int PROFILING_DEFAULT_RECHECK_DELAY_MS = 5 * 1000;

    private static final int CLEAR_TEMPORARY_DIRECTORY_FREQUENCY_DEFAULT_MS = 24 * 60 * 60 * 1000;
    private static final int CLEAR_TEMPORARY_DIRECTORY_BOOT_DELAY_DEFAULT_MS = 5 * 60 * 1000;

    private final Context mContext;
    private final Object mLock = new Object();
    private final HandlerThread mHandlerThread = new HandlerThread("ProfilingService");

    @VisibleForTesting public RateLimiter mRateLimiter = null;

    // Timeout for Perfetto process to successfully stop after we try to stop it.
    private int mPerfettoDestroyTimeoutMs;
    private int mMaxResultRedeliveryCount;
    private int mProfilingRecheckDelayMs;

    @GuardedBy("mLock")
    private long mLastClearTemporaryDirectoryTimeMs = 0;
    private int mClearTemporaryDirectoryFrequencyMs;
    private final int mClearTemporaryDirectoryBootDelayMs;

    private int mRedactionCheckFrequencyMs;
    private int mRedactionMaxRuntimeAllottedMs;

    private Handler mHandler;

    private Calendar mCalendar = null;
    private SimpleDateFormat mDateFormat = null;

    // uid indexed collection of lists of callbacks for results.
    @VisibleForTesting
    public SparseArray<List<IProfilingResultCallback>> mResultCallbacks = new SparseArray<>();

    // Request UUID key indexed storage of active tracing sessions. Currently only 1 active session
    // is supported at a time, but this will be used in future to support multiple.
    @VisibleForTesting
    public ArrayMap<String, TracingSession> mTracingSessions = new ArrayMap<>();

    // uid indexed storage of completed tracing sessions that have not yet successfully handled the
    // result.
    @VisibleForTesting
    public SparseArray<List<TracingSession>> mQueueTracingResults = new SparseArray<>();

    /** To be disabled for testing only. */
    @GuardedBy("mLock")
    private boolean mKeepUnredactedTrace = false;

    @VisibleForTesting
    public ProfilingService(Context context) {
        mContext = context;

        mPerfettoDestroyTimeoutMs = DeviceConfigHelper.getInt(
                DeviceConfigHelper.PERFETTO_DESTROY_TIMEOUT_MS,
                PERFETTO_DESTROY_DEFAULT_TIMEOUT_MS);

        mMaxResultRedeliveryCount = DeviceConfigHelper.getInt(
                DeviceConfigHelper.MAX_RESULT_REDELIVERY_COUNT,
                DEFAULT_MAX_RESULT_REDELIVERY_COUNT);

        mProfilingRecheckDelayMs = DeviceConfigHelper.getInt(
                DeviceConfigHelper.PROFILING_RECHECK_DELAY_MS,
                PROFILING_DEFAULT_RECHECK_DELAY_MS);

        mClearTemporaryDirectoryFrequencyMs = DeviceConfigHelper.getInt(
                DeviceConfigHelper.CLEAR_TEMPORARY_DIRECTORY_FREQUENCY_MS,
                CLEAR_TEMPORARY_DIRECTORY_FREQUENCY_DEFAULT_MS);

        mClearTemporaryDirectoryBootDelayMs = DeviceConfigHelper.getInt(
                DeviceConfigHelper.CLEAR_TEMPORARY_DIRECTORY_BOOT_DELAY_MS,
            CLEAR_TEMPORARY_DIRECTORY_BOOT_DELAY_DEFAULT_MS);

        mRedactionCheckFrequencyMs = DeviceConfigHelper.getInt(
                DeviceConfigHelper.REDACTION_CHECK_FREQUENCY_MS,
                REDACTION_DEFAULT_CHECK_FREQUENCY_MS);

        mRedactionMaxRuntimeAllottedMs = DeviceConfigHelper.getInt(
                DeviceConfigHelper.REDACTION_MAX_RUNTIME_ALLOTTED_MS,
                REDACTION_DEFAULT_MAX_RUNTIME_ALLOTTED_MS);


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

                            mMaxResultRedeliveryCount = properties.getInt(
                                    DeviceConfigHelper.MAX_RESULT_REDELIVERY_COUNT,
                                    mMaxResultRedeliveryCount);

                            mProfilingRecheckDelayMs = properties.getInt(
                                    DeviceConfigHelper.PROFILING_RECHECK_DELAY_MS,
                                    mProfilingRecheckDelayMs);

                            mClearTemporaryDirectoryFrequencyMs = properties.getInt(
                                    DeviceConfigHelper.CLEAR_TEMPORARY_DIRECTORY_FREQUENCY_MS,
                                    mClearTemporaryDirectoryFrequencyMs);

                            // No need to handle updates for
                            // {@link mClearTemporaryDirectoryBootDelayMs} as it's only used on
                            // initialization of this class so by the time this occurs it will never
                            // be used again.

                            mRedactionCheckFrequencyMs = properties.getInt(
                                    DeviceConfigHelper.REDACTION_CHECK_FREQUENCY_MS,
                                    mRedactionCheckFrequencyMs);

                            mRedactionMaxRuntimeAllottedMs = properties.getInt(
                                    DeviceConfigHelper.REDACTION_MAX_RUNTIME_ALLOTTED_MS,
                                    mRedactionMaxRuntimeAllottedMs);
                        }
                    }
                });

        // Schedule initial storage cleanup after delay so as not to increase non-critical work
        // during boot.
        getHandler().postDelayed(new Runnable() {
            @Override
            public void run() {
                maybeCleanupTemporaryDirectory();
            }
        }, mClearTemporaryDirectoryBootDelayMs);
    }

    /** Perform a temporary directory cleanup if it has been long enough to warrant one. */
    private void maybeCleanupTemporaryDirectory() {
        synchronized (mLock) {
            if (mLastClearTemporaryDirectoryTimeMs + mClearTemporaryDirectoryFrequencyMs
                    < System.currentTimeMillis()) {
                cleanupTemporaryDirectoryLocked(TEMP_TRACE_PATH);
            }
        }
    }


    /** Cleanup untracked data stored in provided directory. */
    @GuardedBy("mLock")
    @VisibleForTesting
    public void cleanupTemporaryDirectoryLocked(String temporaryDirectoryPath) {
        // Obtain a list of all currently tracked files and create a filter with it. Filter is set
        // to null if the list is empty as that will efficiently accept all files.
        final List<String> trackedFilenames = getTrackedFilenames();
        FilenameFilter filenameFilter = trackedFilenames.isEmpty() ? null : new FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
                // We only want to accept files which are not in the tracked files list.
                return !trackedFilenames.contains(name);
            }
        };

        // Now obtain a list of files in the provided directory that are not tracked.
        File directory = new File(temporaryDirectoryPath);
        File[] files = null;
        try {
            files = directory.listFiles(filenameFilter);
        } catch (SecurityException e) {
            // Couldn't get a list of files, can't cleanup anything.
            if (DEBUG) {
                Log.d(TAG, "Failed to get file list from temporary directory. Cleanup aborted.", e);
            }
            return;
        }

        if (files == null) {
            // The path doesn't exist or an I/O error occurred.
            if (DEBUG) {
                Log.d(TAG, "Temporary directory doesn't exist or i/o error occurred. "
                        + "Cleanup aborted.");
            }
            return;
        }

        // Set time here as we'll either return due to no files or attempt to delete files, after
        // either of which we should wait before checking again.
        mLastClearTemporaryDirectoryTimeMs = System.currentTimeMillis();

        if (files.length == 0) {
            // No files, nothing to cleanup.
            if (DEBUG) Log.d(TAG, "No files in temporary directory to cleanup.");
            return;
        }

        // Iterate through and delete them.
        for (int i = 0; i < files.length; i++) {
            try {
                files[i].delete();
            } catch (SecurityException e) {
                // Exception deleting file, keep trying for the others.
                if (DEBUG) Log.d(TAG, "Exception deleting file from temp directory.", e);
            }
        }
    }

    /**
     * Return a list of all filenames that are currently tracked in either the in progress
     * collections or the queued results, including both redacted and unredacted.
     */
    private List<String> getTrackedFilenames() {
        List<String> filenames = new ArrayList<String>();

        // If active sessions is not empty, iterate through and add the filenames from each.
        if (!mTracingSessions.isEmpty()) {
            for (int i = 0; i < mTracingSessions.size(); i++) {
                TracingSession session = mTracingSessions.valueAt(i);
                String filename = session.getFileName();
                if (filename != null) {
                    filenames.add(filename);
                }
                String redactedFilename = session.getRedactedFileName();
                if (redactedFilename != null) {
                    filenames.add(redactedFilename);
                }
            }
        }

        // If queued sessions is not empty, iterate through and add the filenames from each.
        if (mQueueTracingResults.size() != 0) {
            for (int i = 0; i < mQueueTracingResults.size(); i++) {
                List<TracingSession> perUidSessions = mQueueTracingResults.valueAt(i);
                if (!perUidSessions.isEmpty()) {
                    for (int j = 0; j < perUidSessions.size(); j++) {
                        TracingSession session = perUidSessions.get(j);
                        String filename = session.getFileName();
                        if (filename != null) {
                            filenames.add(filename);
                        }
                        String redactedFilename = session.getRedactedFileName();
                        if (redactedFilename != null) {
                            filenames.add(redactedFilename);
                        }
                    }
                }
            }
        }

        return filenames;
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

    /** Call from application to register a callback object. */
    public void registerResultsCallback(boolean isGeneralCallback,
            IProfilingResultCallback callback) {
        int callingUid = Binder.getCallingUid();
        List<IProfilingResultCallback> perUidCallbacks = mResultCallbacks.get(callingUid);
        if (perUidCallbacks == null) {
            perUidCallbacks = new ArrayList<IProfilingResultCallback>();
            mResultCallbacks.put(callingUid, perUidCallbacks);
        }
        perUidCallbacks.add(callback);

        ProfilingDeathRecipient deathRecipient = new ProfilingDeathRecipient(callingUid);
        try {
            callback.asBinder().linkToDeath(deathRecipient, 0);
        } catch (RemoteException e) {
            // Failed to link death recipient. Ignore.
            if (DEBUG) Log.d(TAG, "Exception linking death recipient", e);
        }

        // Only handle queued results when a new general listener has been added.
        if (isGeneralCallback) {
            handleQueuedResults(callingUid);
        }
    }

    /**
     * Call from application to notify that a new global listener was added and can be accessed
     * through the existing callback object.
     */
    public void generalListenerAdded() {
        handleQueuedResults(Binder.getCallingUid());
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
        int postProcessingInitialDelayMs;
        byte[] config;
        String suffix;
        String tag;
        try {
            postProcessingInitialDelayMs = session.getPostProcessingScheduleDelayMs();
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
                    TEMP_TRACE_PATH + session.getFileName(), "-c", "-");
            Process activeTrace = pb.start();
            activeTrace.getOutputStream().write(config);
            activeTrace.getOutputStream().close();
            // If we made it this far the trace is running, save the session.
            session.setActiveTrace(activeTrace);
            session.setProfilingStartTimeMs(System.currentTimeMillis());
            mTracingSessions.put(session.getKey(), session);
        } catch (Exception e) {
            // Catch all exceptions related to starting process as they'll all be handled similarly.
            if (DEBUG) Log.d(TAG, "Trace couldn't be started", e);
            processResultCallback(session, ProfilingResult.ERROR_FAILED_EXECUTING, null);
            return;
        }

        session.setState(TracingSession.TracingState.PROFILING_STARTED);

        // Create post process runnable, store it, and schedule it.
        session.setProcessResultRunnable(new Runnable() {
            @Override
            public void run() {
                // Check if the profiling process is complete or reschedule the check.
                checkProfilingCompleteRescheduleIfNeeded(session);
            }
        });
        getHandler().postDelayed(session.getProcessResultRunnable(), postProcessingInitialDelayMs);
    }

    /**
        This method will check if the profiling subprocess is still alive. If it's still alive and
        there is still time permitted to run, another check will be scheduled. If the process is
        still alive but max allotted processing time has been exceeded, the profiling process will
        be stopped and results processed and returned to client. If the profiling process is
        complete results will be processed and returned to the client.
     */
    private void checkProfilingCompleteRescheduleIfNeeded(TracingSession session) {

        long processingTimeRemaining = session.getMaxProfilingTimeAllowedMs()
                - (System.currentTimeMillis() - session.getProfilingStartTimeMs());

        if (session.getActiveTrace().isAlive()
                && processingTimeRemaining >= 0) {
            // still running and under max allotted processing time, reschedule the check.
            getHandler().postDelayed(session.getProcessResultRunnable(),
                    Math.min(mProfilingRecheckDelayMs, processingTimeRemaining));
        } else if (session.getActiveTrace().isAlive()
                && processingTimeRemaining < 0) {
            // still running but exceeded max allotted processing time, stop profiling and deliver
            // what results are available.
            stopProfiling(session.getKey());
        } else {
            // complete, process results and deliver.
            session.setProcessResultRunnable(null);
            processResult(session);
        }
    }

    /** Stop any active profiling sessions belonging to the provided uid. */
    private void stopAllProfilingForUid(int uid) {
        if (mTracingSessions.isEmpty()) {
            // If there are no active traces, then there are none for this uid.
            return;
        }

        // Iterate through active sessions and stop profiling if they belong to the provided uid.
        // Note: Currently, this will only ever have 1 session.
        for (int i = 0; i < mTracingSessions.size(); i++) {
            TracingSession session = mTracingSessions.valueAt(i);
            if (session.getUid() == uid) {
                stopProfiling(session);
            }
        }
    }

    private void stopProfiling(String key) throws RuntimeException {
        TracingSession session = mTracingSessions.get(key);
        stopProfiling(session);
    }

    private void stopProfiling(TracingSession session) throws RuntimeException {
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
                throw new RuntimeException("Stopping of running trace process timed out.");
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
     * Finish processing profiling result:
     * - Move the result file from temporary storage to the apps internal storage.
     * - Delete the temporary file.
     * - Finally, call back to app.
     *
     * Moving the file is is done by requesting {@link ProfilingManager} create a file from within
     * app context and return a {@link ParcelFileDescriptor} to copy the temporary file contents to.
     */
    @VisibleForTesting
    public void finishProcessingResult(TracingSession session) {
        if (session.getState() == TracingSession.TracingState.DISCARDED) {
            // This should not have happened, if the session was discarded why are we trying to
            // continue processing it? Remove from all data stores just in case.
            if (DEBUG) {
                Log.d(TAG, "Attempted finishProcessingResult on a session with status discarded or"
                        + " an invalid status.");
            }
            mTracingSessions.remove(session.getKey());
            cleanupTracingSession(session);
            return;
        }

        List<IProfilingResultCallback> perUidCallbacks = mResultCallbacks.get(session.getUid());
        if (perUidCallbacks == null || perUidCallbacks.isEmpty()) {
            // No callback so no way to obtain a file to populate with result.
            if (DEBUG) Log.d(TAG, "No callback to ProfilingManager, callback dropped.");
            // TODO: b/333456916 run a cleanup of old results based on a max size and time.
            return;
        }

        File tempResultFile = new File(TEMP_TRACE_PATH
                + (session.getProfilingType() == ProfilingManager.PROFILING_TYPE_SYSTEM_TRACE
                ? session.getRedactedFileName() : session.getFileName()));
        FileInputStream tempPerfettoFileInStream = null;
        FileOutputStream appFileOutStream = null;
        ParcelFileDescriptor fileDescriptor = null;
        boolean failed = false;

        // Only copy the file if we haven't previously.
        if (session.getState().getValue() < TracingSession.TracingState.COPIED_FILE.getValue()) {
            // Setup file streams.
            try {
                tempPerfettoFileInStream = new FileInputStream(tempResultFile);
            } catch (IOException e) {
                // IO Exception opening temp perfetto file. No result.
                if (DEBUG) Log.d(TAG, "Exception opening temp perfetto file.", e);
                failed = true;
            }

            // Obtain a file descriptor for the result file in app storage from
            // {@link ProfilingManager}
            if (!failed) {
                fileDescriptor = obtainFileForResult(perUidCallbacks,
                        session.getAppFilePath() + OUTPUT_FILE_RELATIVE_PATH,
                        tempResultFile.getName());
                if (fileDescriptor != null) {
                    appFileOutStream = new FileOutputStream(fileDescriptor.getFileDescriptor());
                }

                if (appFileOutStream == null) {
                    failed = true;
                }
            }

            // Now copy the file over.
            if (!failed) {
                try {
                    FileUtils.copy(tempPerfettoFileInStream, appFileOutStream);
                    session.setState(TracingSession.TracingState.COPIED_FILE);
                } catch (IOException e) {
                    // Exception writing to local app file. Attempt to delete the bad copy.
                    deleteBadCopiedFile(session);
                    if (DEBUG) Log.d(TAG, "Exception writing to local app file.", e);
                    failed = true;
                }
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
            cleanupTracingSession(session);
        } else {
            // Couldn't move file. File is still in temp directory and will be tried later.
            if (DEBUG) Log.d(TAG, "Couldn't move file to app storage.");
            processResultCallback(session, ProfilingResult.ERROR_FAILED_POST_PROCESSING, null);
        }

        // Clean up temporary directory if it has been long enough to warrant it.
        maybeCleanupTemporaryDirectory();
    }

    /**
     * Delete a file which failed to copy via ProfilingManager.
     */
    private void deleteBadCopiedFile(TracingSession session) {
        List<IProfilingResultCallback> perUidCallbacks = mResultCallbacks.get(session.getUid());
        for (int i = 0; i < perUidCallbacks.size(); i++) {
            try {
                String fileName =
                        session.getProfilingType() == ProfilingManager.PROFILING_TYPE_SYSTEM_TRACE
                        ? session.getRedactedFileName() : session.getFileName();
                if (perUidCallbacks.get(i).deleteFile(session.getAppFilePath()
                        + OUTPUT_FILE_RELATIVE_PATH + fileName)) {
                    // Only need one successful call, return.
                    return;
                }
            } catch (RemoteException e) {
                // Binder exception deleting file. Continue trying other callbacks for this process.
                if (DEBUG) Log.d(TAG, "Binder exception deleting file. Trying next callback", e);
            }
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
        // Move this session from active to queued results.
        List<TracingSession> queuedResults = mQueueTracingResults.get(session.getUid());
        if (queuedResults == null) {
            queuedResults = new ArrayList<TracingSession>();
            mQueueTracingResults.put(session.getUid(), queuedResults);
        }
        queuedResults.add(session);
        mTracingSessions.remove(session.getKey());

        session.setState(TracingSession.TracingState.PROFILING_FINISHED);

        if (session.getProfilingType() == ProfilingManager.PROFILING_TYPE_SYSTEM_TRACE) {
            handleTraceResult(session);
        } else {
            finishProcessingResult(session);
        }
    }

    /** Handle a trace result by attempting to kick off redaction process. */
    @VisibleForTesting
    public void handleTraceResult(TracingSession session) {
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
            // mRedactionMaxRuntimeAllottedMs to complete. Redaction status will be checked every
            // mRedactionCheckFrequencyMs.
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

        getHandler().postDelayed(session.getProcessResultRunnable(),
                mRedactionCheckFrequencyMs);
    }

    private void checkRedactionStatus(TracingSession session) {
        // Check if redaction is complete.
        if (!session.getActiveRedaction().isAlive()) {
            handleRedactionComplete(session);
            session.setProcessResultRunnable(null);
            return;
        }

        // Check if we are over the mRedactionMaxRuntimeAllottedMs threshold.
        if ((System.currentTimeMillis() - session.getRedactionStartTimeMs())
                > mRedactionMaxRuntimeAllottedMs) {
            if (DEBUG) Log.d(TAG, "Redaction process has timed out");

            session.getActiveRedaction().destroyForcibly();
            session.setProcessResultRunnable(null);
            processResultCallback(session, ProfilingResult.ERROR_FAILED_POST_PROCESSING,
                    null);

            return;
        }
        getHandler().postDelayed(session.getProcessResultRunnable(),
                Math.min(mRedactionCheckFrequencyMs, mRedactionMaxRuntimeAllottedMs
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
                maybeDeleteUnredactedTrace(session);
            }
        }

        session.setState(TracingSession.TracingState.REDACTED);

        finishProcessingResult(session);
    }

    /**
     * Called whenever a new global listener has been added to the specified uid.
     * Attempts to process queued results if present.
     */
    @VisibleForTesting
    public void handleQueuedResults(int uid) {
        List<TracingSession> queuedSessions = mQueueTracingResults.get(uid);
        if (queuedSessions == null || queuedSessions.isEmpty()) {
            // No queued results, nothing to handle.
            return;
        }

        // Triggering the callbacks may result in the object being removed from the class level
        // queue list, ensure this remains safe by using a unmodifiable shallow copy of the list.
        List<TracingSession> unmodifiableQueuedSessions = List.copyOf(queuedSessions);
        for (int i = 0; i < unmodifiableQueuedSessions.size(); i++) {
            TracingSession session = unmodifiableQueuedSessions.get(i);

            // Check if we already retried too many times and discard the result if we have.
            if (session.getRetryCount() >= mMaxResultRedeliveryCount) {
                cleanupTracingSession(session, queuedSessions);
                continue;
            }
            session.incrementRetryCount();

            switch (session.getState()) {
                case NOT_STARTED:
                case PROFILING_STARTED:
                    // This should never happen as the session should not be in queuedSessions until
                    // past this state, but run stop and cleanup just in case.
                    stopProfiling(session);
                    cleanupTracingSession(session);
                    break;
                case PROFILING_FINISHED:
                    if (session.getProfilingType()
                            == ProfilingManager.PROFILING_TYPE_SYSTEM_TRACE) {
                        handleTraceResult(session);
                    } else {
                        finishProcessingResult(session);
                    }
                    break;
                case REDACTED:
                case COPIED_FILE:
                    finishProcessingResult(session);
                    break;
                case DISCARDED:
                    // This should never happen as this state should only occur after cleanup of
                    // this file.
                    cleanupTracingSession(session, queuedSessions);
                    break;
            }
        }
    }


    /**
     * Cleanup is intended for when we're done with a queued trace session, whether successful or
     * not.
     *
     * Cleanup will attempt to delete the temporary file(s) and then remove it from the queue.
     */
    private void cleanupTracingSession(TracingSession session) {
        List<TracingSession> queuedSessions = mQueueTracingResults.get(session.getUid());
        cleanupTracingSession(session, queuedSessions);
    }

    /**
     * Cleanup is intended for when we're done with a queued trace session, whether successful or
     * not.
     *
     * Cleanup will attempt to delete the temporary file(s) and then remove it from the queue.
     */
    private void cleanupTracingSession(TracingSession session,
            List<TracingSession> queuedSessions) {
        // Delete all files
        if (session.getProfilingType() == ProfilingManager.PROFILING_TYPE_SYSTEM_TRACE) {
            // If type is trace, try to delete the temp file only if {@link mKeepUnredactedTrace} is
            // false, and always try to delete redacted file.
            maybeDeleteUnredactedTrace(session);
            try {
                Files.delete(Path.of(TEMP_TRACE_PATH + session.getRedactedFileName()));
            } catch (Exception exception) {
                if (DEBUG) Log.e(TAG, "Failed to delete file for discarded record.", exception);
            }
        } else {
            // If type is not trace, try to delete the temp file. There is no redacted file.
            try {
                Files.delete(Path.of(TEMP_TRACE_PATH + session.getFileName()));
            } catch (Exception exception) {
                if (DEBUG) Log.e(TAG, "Failed to delete file for discarded record.", exception);
            }

        }

        session.setState(TracingSession.TracingState.DISCARDED);
        queuedSessions.remove(session);

        if (queuedSessions.isEmpty()) {
            mQueueTracingResults.remove(session.getUid());
        }
    }

    /**
     * Attempt to delete unredacted trace unless mKeepUnredactedTrace is enabled.
     *
     * Note: only to be called for types that support redaction.
     */
    private void maybeDeleteUnredactedTrace(TracingSession session) {
        synchronized (mLock) {
            if (mKeepUnredactedTrace) {
                return;
            }
            try {
                Files.delete(Path.of(TEMP_TRACE_PATH + session.getFileName()));
            } catch (Exception exception) {
                if (DEBUG) Log.e(TAG, "Failed to delete file.", exception);
            }
        }
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

    private class ProfilingDeathRecipient implements IBinder.DeathRecipient {
        private final int mUid;

        ProfilingDeathRecipient(int uid) {
            mUid = uid;
        }

        @Override
        public void binderDied() {
            if (DEBUG) Log.d(TAG, "binderDied without who should not have been called");
        }

        @Override
        public void binderDied(IBinder who) {
            // Synchronize because multiple binder died callbacks may occur simultaneously
            // on different threads and we want to ensure that when an app dies (i.e. all
            // binder objects die) we attempt to stop profiling exactly once.
            synchronized (mLock) {
                List<IProfilingResultCallback> callbacks = mResultCallbacks.get(mUid);

                if (callbacks == null) {
                    // No callbacks list for this uid, this likely means profiling was already
                    // stopped (i.e. this is not the first binderDied call for this death).
                    return;
                }

                // Callbacks aren't valid anymore, remove the list.
                mResultCallbacks.remove(mUid);

                // Finally, attempt to stop profiling. Once the profiling is stopped, processing
                // will continue as usual and will fail at copy to app storage which is the next
                // step that requires the now dead binder objects. The failure will result in the
                // session being added to {@link mQueueTracingResults} and being delivered to the
                // app the next time it registers a general listener.
                stopAllProfilingForUid(mUid);
            }
        }
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
