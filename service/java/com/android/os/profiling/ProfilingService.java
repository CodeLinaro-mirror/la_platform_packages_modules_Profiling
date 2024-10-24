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
import android.os.Environment;
import android.os.FileUtils;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.IProfilingResultCallback;
import android.os.IProfilingService;
import android.os.ParcelFileDescriptor;
import android.os.ProfilingManager;
import android.os.ProfilingResult;
import android.os.QueuedResultsWrapper;
import android.os.RemoteException;
import android.provider.DeviceConfig;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.AtomicFile;
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
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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

    private static final String QUEUED_RESULTS_SYSTEM_DIR = "system";
    private static final String QUEUED_RESULTS_STORE_DIR = "profiling_queued_results_store";
    private static final String QUEUED_RESULTS_INFO_FILE = "profiling_queued_results_info";

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

    // The longest amount of time that we will retain a queued result and continue retrying to
    // deliver it. After this amount of time the result will be discarded.
    @VisibleForTesting
    public static final int QUEUED_RESULT_MAX_RETAINED_DURATION_MS = 7 * 24 * 60 * 60 * 1000;

    private static final int PERSIST_QUEUE_TO_DISK_FREQUENCY_MS = 30 * 60 * 1000;

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
    public ArrayMap<String, TracingSession> mActiveTracingSessions = new ArrayMap<>();

    // uid indexed storage of completed tracing sessions that have not yet successfully handled the
    // result.
    @VisibleForTesting
    public SparseArray<List<TracingSession>> mQueuedTracingResults = new SparseArray<>();

    private boolean mPersistQueueScheduled = false;
    // Frequency of 0 would result in immediate persist.
    @GuardedBy("mLock")
    private AtomicInteger mPersistQueueFrequencyMs;
    @GuardedBy("mLock")
    private long mLastPersistedQueueTimestampMs = 0L;
    private Runnable mPersistQueueRunnable = null;

    /**
     * The path to the directory which includes the queued results data file as specified in
     * {@link #mPersistQueueFile}.
     */
    @VisibleForTesting
    public File mPersistQueueStoreDir;

    /** The queued results data file, persisted in the storage. */
    @VisibleForTesting
    public File mPersistQueueFile;

    /** To be disabled for testing only. */
    @GuardedBy("mLock")
    private boolean mKeepUnredactedTrace = false;

    /**
     * State the {@link TracingSession} is in.
     *
     * State represents the most recently confirmed completed step in the process. Steps represent
     * save points which the process would have to go back to if it did not successfully reach the
     * next step.
     *
     * States are sequential. It can be expected that state value will only increase throughout a
     * sessions life.
     *
     * At different states, the containing object can be assumed to exist in different data
     * structures as follows:
     * REQUESTED - Local only, not in any data structure.
     * APPROVED - Local only, not in any data structure.
     * PROFILING_STARTED - Stored in {@link mActiveTracingSessions}.
     * PROFILING_FINISHED - Stored in {@link mQueuedTracingResults}.
     * REDACTED - Stored in {@link mQueuedTracingResults}.
     * COPIED_FILE - Stored in {@link mQueuedTracingResults}.
     * ERROR_OCCURRED - Stored in {@link mQueuedTracingResults}.
     * NOTIFIED_REQUESTER - Stored in {@link mQueuedTracingResults}.
     * CLEANED_UP - Local only, not in any data structure.
     */
    public enum TracingState {
        // Intentionally skipping 0 since proto, which will be used for persist, treats it as unset.
        REQUESTED(1),
        APPROVED(2),
        PROFILING_STARTED(3),
        PROFILING_FINISHED(4),
        REDACTED(5),
        COPIED_FILE(6),
        ERROR_OCCURRED(7),
        NOTIFIED_REQUESTER(8),
        CLEANED_UP(9);

        /** Data structure for efficiently mapping int values back to their enum values. */
        private static List<TracingState> sStatesList;

        static {
            sStatesList = Arrays.asList(TracingState.values());
        }

        private final int mValue;
        TracingState(int value) {
            mValue = value;
        }

        /** Obtain TracingState from int value. */
        public static TracingState of(int value) {
            if (value < 1 || value >= sStatesList.size() + 1) {
                return null;
            }

            return sStatesList.get(value - 1);
        }

        public int getValue() {
            return mValue;
        }
    }

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

            mPersistQueueFrequencyMs = new AtomicInteger(DeviceConfigHelper.getInt(
                    DeviceConfigHelper.PERSIST_QUEUE_TO_DISK_FREQUENCY_MS,
                    PERSIST_QUEUE_TO_DISK_FREQUENCY_MS));
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

                            mPersistQueueFrequencyMs.set(properties.getInt(
                                    DeviceConfigHelper.PERSIST_QUEUE_TO_DISK_FREQUENCY_MS,
                                    mPersistQueueFrequencyMs.get()));
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

        // Load the queue right away so we can start delivering results as apps register global
        // listeners.
        loadQueueFromPersistedData();
    }

    /**
     * Load persisted queue entries. If any issue is encountered reading/parsing the file, delete it
     * and return as failure to load queue does not block the feature.
     */
    @VisibleForTesting
    public void loadQueueFromPersistedData() {
        if (!Flags.persistQueue()) {
            return;
        }

        // Setup persist files
        try {
            if (!setupPersistQueueFiles()) {
                // If setting up the directory and file was unsuccessful then just return. Past and
                // future queued results will be lost, but the feature as a whole still works.
                if (DEBUG) Log.d(TAG, "Failed to setup queue persistence directory/files.");
                return;
            }
        } catch (SecurityException e) {
            // Can't access files.
            if (DEBUG) Log.e(TAG, "Failed to setup queue persistence directory/files.", e);
            return;
        }

        // Check if file exists
        try {
            if (!mPersistQueueFile.exists()) {
                // No file, nothing to load. This is an expected state for before the feature has
                // ever been used or if the queue was emptied.
                if (DEBUG) {
                    Log.d(TAG, "Queue persistence file does not exist, skipping load from disk.");
                }
                return;
            }
        } catch (SecurityException e) {
            // Can't access file.
            if (DEBUG) Log.e(TAG, "Exception accessing queue persistence file", e);
            return;
        }

        // Read the file
        AtomicFile persistFile = new AtomicFile(mPersistQueueFile);
        byte[] bytes;
        try {
            bytes = persistFile.readFully();
        } catch (IOException e) {
            if (DEBUG) Log.e(TAG, "Exception reading queue persistence file", e);
            // Failed to read the file. No reason to believe we'll have better luck next time,
            // delete the file and return. Results in the queue will be lost.
            deletePersistQueueFile();
            return;
        }
        if (bytes.length == 0) {
            if (DEBUG) Log.d(TAG, "Queue persistence file is empty, skipping load from disk.");
            // Empty queue persist file. Delete the file and return.
            deletePersistQueueFile();
            return;
        }

        // Parse file bytes to proto
        QueuedResultsWrapper wrapper;
        try {
            wrapper = QueuedResultsWrapper.parseFrom(bytes);
        } catch (Exception e) {
            if (DEBUG) Log.e(TAG, "Error parsing proto from persisted bytes", e);
            // Failed to parse the file contents. No reason to believe we'll have better luck next
            // time, delete the file and return. Results in the queue will be lost.
            deletePersistQueueFile();
            return;
        }

        // Populate in memory records store
        for (int i = 0; i < wrapper.getSessionsCount(); i++) {
            QueuedResultsWrapper.TracingSession sessionsProto = wrapper.getSessions(i);
            TracingSession session = new TracingSession(sessionsProto);
            // Since we're populating the in memory store from the persisted queue we don't want to
            // trigger a persist, so pass param false. If we did trigger the persist from here, it
            // would overwrite the file with the first record only and then queue the remaining
            // records for later, thereby leaving the persisted queue with less data than it
            // currently contains and potentially leading to lost data in event of shutdown before
            // the scheduled persist occurs.
            moveSessionToQueue(session, false);
        }
    }

    /** Setup the directory and file for persisting queue. */
    @VisibleForTesting
    public boolean setupPersistQueueFiles() {
        File dataDir = Environment.getDataDirectory();
        File systemDir = new File(dataDir, QUEUED_RESULTS_SYSTEM_DIR);
        mPersistQueueStoreDir = new File(systemDir, QUEUED_RESULTS_STORE_DIR);
        if (createDir(mPersistQueueStoreDir)) {
            mPersistQueueFile = new File(mPersistQueueStoreDir, QUEUED_RESULTS_INFO_FILE);
            return true;
        }
        return false;
    }

    /** Delete the persist queue file. */
    @VisibleForTesting
    public void deletePersistQueueFile() {
        try {
            mPersistQueueFile.delete();
            if (DEBUG) Log.d(TAG, "Deleted queue persist file.");
        } catch (SecurityException e) {
            // Can't delete file.
            if (DEBUG) Log.d(TAG, "Failed to delete queue persist file", e);
        }
    }

    private static boolean createDir(File dir) throws SecurityException {
        if (dir.mkdir()) {
            return true;
        }

        if (dir.exists()) {
            return dir.isDirectory();
        }

        return false;
    }

    /**
     * This is the core method that keeps the profiling flow moving.
     *
     * This is the only way that state should be set. Do not use {@link TracingSession#setState}
     * directly.
     *
     * The passed newState represents the state that was just completed. Passing null for new state
     * will continue using the current state as the last completed state, this is intended only for
     * resuming the queue.
     *
     * Generally, this should be the last call in a method before returning.
     */
    @VisibleForTesting
    public void advanceTracingSession(TracingSession session, @Nullable TracingState newState) {
        if (newState == null) {
            if (session.getRetryCount() == 0) {
                // The new state should only be null if this is triggered from the queue in which
                // case the retry count should be greater than 0. If retry count is 0 here then
                // we're in an unexpected state. Cleanup and discard. Result will be lost.
                cleanupTracingSession(session);
                return;
            }
        } else if (newState == session.getState()) {
            // This should never happen.
            // If the state is not actually changing then we may find ourselves in an infinite
            // loop. Terminate this attempt and increment the retry count to ensure there's a
            // path to breaking out of a potential infinite queue retries.
            session.incrementRetryCount();
            return;
        } else if (newState.getValue() < session.getState().getValue()) {
            // This should also never happen.
            // States should always move forward. If the state is trying to move backwards then
            // we don't actually know what to do next. Clean up the session and delete
            // everything. Results will be lost.
            cleanupTracingSession(session);
            return;
        } else {
            // The new state is not null so update the sessions state.
            session.setState(newState);
        }

        switch (session.getState()) {
            case REQUESTED:
                // This should never happen as requested state is expected to handled by the request
                // method, the first actionable state is approved. Ignore it.
                if (DEBUG) {
                    Log.e(TAG, "Session attempting to advance with REQUESTED state unsupported.");
                }
                break;
            case APPROVED:
                // Session has been approved by rate limiter, so continue on to start profiling.
                startProfiling(session);
                break;
            case PROFILING_STARTED:
                // Profiling has been successfully started. Next step depends on whether or not the
                // profiling is alive.
                if (session.getActiveTrace() == null || !session.getActiveTrace().isAlive()
                        || session.getProcessResultRunnable() == null) {
                    // This really should not happen, but if profiling is not in correct started
                    // state then try to stop and continue processing it.
                    stopProfiling(session);
                } // else: do nothing. The runnable we just verified exists will return us to this
                // method when profiling is finished.
                break;
            case PROFILING_FINISHED:
                // Next step depends on whether or not the result requires redaction.
                if (needsRedaction(session)) {
                    // Redaction needed, kick it off.
                    handleRedactionRequiredResult(session);
                } else {
                    // No redaction needed, move straight to copying to app storage.
                    beginMoveFileToAppStorage(session);
                }
                break;
            case REDACTED:
                // Redaction completed, move on to copying to app storage.
                beginMoveFileToAppStorage(session);
                break;
            case COPIED_FILE:
                // File has already been copied to app storage, proceed to callback.
                session.setError(ProfilingResult.ERROR_NONE);
                processTracingSessionResultCallback(session, true /* Continue advancing session */);

                // This is a good place to persist the queue if possible because the processing work
                // is complete and we tried to send a callback to the app. If the callback
                // succeeded, then we will already have recursed on this method with new state of
                // NOTIFIED_REQUESTER and the only potential remaining work to be repeated will be
                // cleanup. If the callback failed, then we won't have recursed here and we'll pick
                // back up this stage next time thereby minimizing repeated work.
                maybePersistQueueToDisk();
                break;
            case ERROR_OCCURRED:
                // An error has occurred, proceed to callback.
                processTracingSessionResultCallback(session, true /* Continue advancing session */);

                // This is a good place to persist the queue if possible because the processing work
                // is complete and we tried to send a callback to the app. If the callback
                // succeeded, then we will already have recursed on this method with new state of
                // NOTIFIED_REQUESTER and the only potential remaining work to be repeated will be
                // cleanup. If the callback failed, then we won't have recursed here and we'll pick
                // back up this stage next time thereby minimizing repeated work.
                maybePersistQueueToDisk();
                break;
            case NOTIFIED_REQUESTER:
                // Callback has been completed successfully, start cleanup.
                cleanupTracingSession(session);
                break;
            case CLEANED_UP:
                // Session was cleaned up, nothing left to do.
                break;
        }
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
        if (!mActiveTracingSessions.isEmpty()) {
            for (int i = 0; i < mActiveTracingSessions.size(); i++) {
                TracingSession session = mActiveTracingSessions.valueAt(i);
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
        if (mQueuedTracingResults.size() != 0) {
            for (int i = 0; i < mQueuedTracingResults.size(); i++) {
                List<TracingSession> perUidSessions = mQueuedTracingResults.valueAt(i);
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
    public void requestProfiling(int profilingType, Bundle params, String tag,
            long keyMostSigBits, long keyLeastSigBits, String packageName) {
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

        cleanupActiveTracingSessions();

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

        if (packageName == null) {
            // This shouldn't happen as it should be checked on the app side.
            if (DEBUG) Log.d(TAG, "PackageName is null");
            processResultCallback(uid, keyMostSigBits, keyLeastSigBits,
                    ProfilingResult.ERROR_UNKNOWN, null, tag, "Couldn't determine package name");
            return;
        }

        String[] uidPackages = mContext.getPackageManager().getPackagesForUid(uid);
        if (uidPackages == null || uidPackages.length == 0) {
            // Failed to get uids for this package, can't validate package name.
            if (DEBUG) Log.d(TAG, "Failed to resolve package name");
            processResultCallback(uid, keyMostSigBits, keyLeastSigBits,
                    ProfilingResult.ERROR_UNKNOWN, null, tag, "Couldn't determine package name");
            return;
        }

        boolean packageNameInUidList = false;
        for (int i = 0; i < uidPackages.length; i++) {
            if (packageName.equals(uidPackages[i])) {
                packageNameInUidList = true;
                break;
            }
        }
        if (!packageNameInUidList) {
            // Package name is not associated with calling uid, reject request.
            if (DEBUG) Log.d(TAG, "Package name not associated with calling uid");
            processResultCallback(uid, keyMostSigBits, keyLeastSigBits,
                    ProfilingResult.ERROR_FAILED_INVALID_REQUEST, null, tag,
                    "Package name not associated with calling uid.");
            return;
        }

        // Check with rate limiter if this request is allowed.
        final int status = getRateLimiter().isProfilingRequestAllowed(Binder.getCallingUid(),
                profilingType, params);
        if (DEBUG) Log.d(TAG, "Rate limiter status: " + status);
        if (status == RateLimiter.RATE_LIMIT_RESULT_ALLOWED) {
            // Rate limiter approved, try to start the request.
            try {
                TracingSession session = new TracingSession(profilingType, params, uid,
                        packageName, tag, keyMostSigBits, keyLeastSigBits);
                advanceTracingSession(session, TracingState.APPROVED);
                return;
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
        maybeCleanupResultsCallbacks();

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
     * Iterate through and delete any callbacks for which binder is not alive.
     *
     * Each binder object has a registered linkToDeath which also handles removal. This mechanism
     * serves as a backup to guarantee that the list stays in check.
     */
    private void maybeCleanupResultsCallbacks() {
        // Create a temporary list to hold callbacks to be removed.
        ArrayList<IProfilingResultCallback> callbacksToRemove =
                new ArrayList<IProfilingResultCallback>();

        // Iterate through the results callback, each iteration is for a uid which has registered
        // callbacks.
        for (int i = 0; i < mResultCallbacks.size(); i++) {
            // Ensure the temporary list is empty
            callbacksToRemove.clear();

            // Grab the current list of callbacks.
            List<IProfilingResultCallback> callbacks = mResultCallbacks.valueAt(i);

            if (callbacks != null && !callbacks.isEmpty()) {
                // Now iterate through each of the callbacks for this uid.
                for (int j = 0; j < callbacks.size(); j++) {
                    IProfilingResultCallback callback = callbacks.get(j);
                    // If the callback is no longer alive, add it to the list for removal.
                    if (callback == null || !callback.asBinder().isBinderAlive()) {
                        callbacksToRemove.add(callback);
                    }
                }

                // Now remove all the callbacks that were added to the list for removal.
                callbacks.removeAll(callbacksToRemove);
            }
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

    /**
     * Method called by manager, after creating a file from within application context, to send a
     * file descriptor for service to write the result of the profiling session to.
     *
     * Note: only expected to be called in response to a generateFile request sent to manager.
     */
    public void receiveFileDescriptor(ParcelFileDescriptor fileDescriptor, long keyMostSigBits,
            long keyLeastSigBits) {
        List<TracingSession> sessions = mQueuedTracingResults.get(Binder.getCallingUid());
        if (sessions == null) {
            // No sessions for this uid, so no profiling result to write to this file descriptor.
            // Attempt to cleanup.
            finishReceiveFileDescriptor(null, fileDescriptor, null, null, false);
            return;
        }

        TracingSession session = null;
        // Iterate through and try to find the session this file is associated with using the
        // key values. Key values were provided from the session to the generate file call that
        // triggered this.
        String key = (new UUID(keyMostSigBits, keyLeastSigBits)).toString();
        for (int i = 0; i < sessions.size(); i++) {
            TracingSession tempSession = sessions.get(i);
            if (tempSession.getKey().equals(key)) {
                session = tempSession;
                break;
            }
        }
        if (session == null) {
            // No session for the provided key, nothing to do with this file descriptor. Attempt
            // to cleanup.
            finishReceiveFileDescriptor(session, fileDescriptor, null, null, false);
            return;
        }

        // At this point we've identified the session that has sent us this file descriptor.
        // Now, we'll create a temporary file pointing to the profiling output for that session.
        // If that file looks good, we'll copy it to the app's local file descriptor.
        File tempResultFile = new File(TEMP_TRACE_PATH
                + (session.getProfilingType() == ProfilingManager.PROFILING_TYPE_SYSTEM_TRACE
                ? session.getRedactedFileName() : session.getFileName()));
        FileInputStream tempPerfettoFileInStream = null;
        FileOutputStream appFileOutStream = null;

        try {
            if (!tempResultFile.exists() || tempResultFile.length() == 0L) {
                // The profiling process output file does not exist or is empty, nothing to copy.
                if (DEBUG) {
                    Log.d(TAG, "Temporary profiling output file is missing or empty, nothing to"
                            + " copy.");
                }
                finishReceiveFileDescriptor(session, fileDescriptor, tempPerfettoFileInStream,
                        appFileOutStream, false);
                return;
            }
        } catch (SecurityException e) {
            // If we hit a security exception checking file exists or size then we won't be able to
            // copy it, attempt to cleanup and return.
            if (DEBUG) {
                Log.d(TAG, "Exception checking if temporary file exists and is non-empty", e);
            }
            finishReceiveFileDescriptor(session, fileDescriptor, tempPerfettoFileInStream,
                    appFileOutStream, false);
            return;
        }

        // Setup file streams.
        try {
            tempPerfettoFileInStream = new FileInputStream(tempResultFile);
        } catch (IOException e) {
            // IO Exception opening temp perfetto file. No result.
            if (DEBUG) Log.d(TAG, "Exception opening temp perfetto file.", e);
            finishReceiveFileDescriptor(session, fileDescriptor, tempPerfettoFileInStream,
                    appFileOutStream, false);
            return;
        }

        // Obtain a file descriptor for the result file in app storage from
        // {@link ProfilingManager}
        if (fileDescriptor != null) {
            appFileOutStream = new FileOutputStream(fileDescriptor.getFileDescriptor());
        }

        if (appFileOutStream == null) {
            finishReceiveFileDescriptor(session, fileDescriptor, tempPerfettoFileInStream,
                    appFileOutStream, false);
            return;
        }

        // Now copy the file over.
        try {
            FileUtils.copy(tempPerfettoFileInStream, appFileOutStream);
        } catch (IOException e) {
            // Exception writing to local app file. Attempt to delete the bad copy.
            deleteBadCopiedFile(session);
            if (DEBUG) Log.d(TAG, "Exception writing to local app file.", e);
            finishReceiveFileDescriptor(session, fileDescriptor, tempPerfettoFileInStream,
                    appFileOutStream, false);
            return;
        }

        finishReceiveFileDescriptor(session, fileDescriptor, tempPerfettoFileInStream,
                appFileOutStream, true);
    }

    private void finishReceiveFileDescriptor(TracingSession session,
            ParcelFileDescriptor fileDescriptor, FileInputStream tempPerfettoFileInStream,
            FileOutputStream appFileOutStream, boolean succeeded) {
        // Cleanup.
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

        if (session != null) {
            if (succeeded) {
                advanceTracingSession(session, TracingState.COPIED_FILE);
            } else {
                // Couldn't move file. File is still in temp directory and will be tried later.
                // Leave state unchanged so it can get triggered again from the queue, but update
                // the error and trigger a callback.
                if (DEBUG) Log.d(TAG, "Couldn't move file to app storage.");
                session.setError(ProfilingResult.ERROR_FAILED_POST_PROCESSING,
                        "Failed to copy result to app storage. May try again later.");
                processTracingSessionResultCallback(session, false /* Do not continue */);
            }

            // Clean up temporary directory if it has been long enough to warrant it.
            maybeCleanupTemporaryDirectory();
        }
    }

    /**
     * An app can register multiple callbacks between this service and {@link ProfilingManager}, one
     * per context that the app created a manager instance with. As we do not know on this service
     * side which callbacks need to be triggered with this result, trigger all of them and let them
     * decide whether to finish delivering it.
     *
     * Call this method if a {@link TracingSession} already exists. If no session exists yet, call
     * {@link #processResultCallback} directly instead.
     *
     * @param session           The session for which to callback and potentially advance.
     * @param continueAdvancing Whether to continue advancing or stop after attempting the callback.
     */
    @VisibleForTesting
    public void processTracingSessionResultCallback(TracingSession session,
            boolean continueAdvancing) {
        boolean succeeded = processResultCallback(session.getUid(), session.getKeyMostSigBits(),
                session.getKeyLeastSigBits(), session.getErrorStatus(),
                session.getDestinationFileName(OUTPUT_FILE_RELATIVE_PATH),
                session.getTag(), session.getErrorMessage());

        if (continueAdvancing && succeeded) {
            advanceTracingSession(session, TracingState.NOTIFIED_REQUESTER);
        }
    }

    /**
     * An app can register multiple callbacks between this service and {@link ProfilingManager}, one
     * per context that the app created a manager instance with. As we do not know on this service
     * side which callbacks need to be triggered with this result, trigger all of them and let them
     * decide whether to finish delivering it.
     *
     * Call this directly only if no {@link TracingSession} exists yet. If a session already exists,
     * call {@link #processTracingSessionResultCallback} instead.
     *
     * @return whether at least one callback was successfully sent to the app.
     */
    private boolean processResultCallback(int uid, long keyMostSigBits, long keyLeastSigBits,
            int status, @Nullable String fileResultPathAndName, @Nullable String tag,
            @Nullable String error) {
        List<IProfilingResultCallback> perUidCallbacks = mResultCallbacks.get(uid);
        if (perUidCallbacks == null || perUidCallbacks.isEmpty()) {
            // No callbacks, nowhere to notify with result or failure.
            if (DEBUG) Log.d(TAG, "No callback to ProfilingManager, callback dropped.");
            return false;
        }

        boolean succeeded = false;
        for (int i = 0; i < perUidCallbacks.size(); i++) {
            try {
                if (status == ProfilingResult.ERROR_NONE) {
                    perUidCallbacks.get(i).sendResult(
                            fileResultPathAndName, keyMostSigBits, keyLeastSigBits, status, tag,
                            error);
                } else {
                    perUidCallbacks.get(i).sendResult(
                            null, keyMostSigBits, keyLeastSigBits, status, tag, error);
                }
                // One success is all we need to know that a callback was sent to the app.
                // This is not perfect but sufficient given we cannot verify the success of
                // individual listeners without either a blocking binder call into the app or an
                // extra binder call back from the app.
                succeeded = true;
            } catch (RemoteException e) {
                // Failed to send result. Ignore.
                if (DEBUG) Log.d(TAG, "Exception processing result callback", e);
            }
        }

        return succeeded;
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
            session.setError(ProfilingResult.ERROR_FAILED_INVALID_REQUEST, e.getMessage());
            moveSessionToQueue(session, true);
            advanceTracingSession(session, TracingState.ERROR_OCCURRED);
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
            mActiveTracingSessions.put(session.getKey(), session);
        } catch (Exception e) {
            // Catch all exceptions related to starting process as they'll all be handled similarly.
            if (DEBUG) Log.d(TAG, "Trace couldn't be started", e);
            session.setError(ProfilingResult.ERROR_FAILED_EXECUTING, "Trace couldn't be started");
            moveSessionToQueue(session, true);
            advanceTracingSession(session, TracingState.ERROR_OCCURRED);
            return;
        }

        // Create post process runnable, store it, and schedule it.
        session.setProcessResultRunnable(new Runnable() {
            @Override
            public void run() {
                // Check if the profiling process is complete or reschedule the check.
                checkProfilingCompleteRescheduleIfNeeded(session);
            }
        });
        getHandler().postDelayed(session.getProcessResultRunnable(), postProcessingInitialDelayMs);

        advanceTracingSession(session, TracingState.PROFILING_STARTED);
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
            moveSessionToQueue(session, true);
            advanceTracingSession(session, TracingState.PROFILING_FINISHED);
        }
    }

    /** Stop any active profiling sessions belonging to the provided uid. */
    private void stopAllProfilingForUid(int uid) {
        if (mActiveTracingSessions.isEmpty()) {
            // If there are no active traces, then there are none for this uid.
            return;
        }

        // Iterate through active sessions and stop profiling if they belong to the provided uid.
        // Note: Currently, this will only ever have 1 session.
        for (int i = 0; i < mActiveTracingSessions.size(); i++) {
            TracingSession session = mActiveTracingSessions.valueAt(i);
            if (session.getUid() == uid) {
                stopProfiling(session);
            }
        }
    }

    private void stopProfiling(String key) throws RuntimeException {
        TracingSession session = mActiveTracingSessions.get(key);
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
        for (int i = 0; i < mActiveTracingSessions.size(); i++) {
            if (isTraceRunning(mActiveTracingSessions.keyAt(i))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Cleanup the data structure of active sessions. Non active sessions are never expected to be
     * present in {@link mActiveTracingSessions} as they would be moved to
     * {@link mQueuedTracingResults} when profiling completes. If a session is present but not
     * running, remove it. If a session has a not alive process, try to stop it.
     */
    public void cleanupActiveTracingSessions() throws RuntimeException {
        // Create a temporary list to store the keys of sessions to be stopped.
        ArrayList<String> sessionsToStop = new ArrayList<String>();

        // Iterate through in reverse order so we can immediately remove the non running sessions
        // that don't have to be stopped.
        for (int i = mActiveTracingSessions.size() - 1; i >= 0; i--) {
            String key = mActiveTracingSessions.keyAt(i);
            TracingSession session = mActiveTracingSessions.get(key);

            if (session == null || session.getActiveTrace() == null) {
                // Profiling isn't running, remove from list.
                mActiveTracingSessions.removeAt(i);
            } else if (!session.getActiveTrace().isAlive()) {
                // Profiling process exists but isn't alive, add to list of sessions to stop. Do not
                // stop here due to potential unanticipated modification of list being iterated
                // through.
                sessionsToStop.add(key);
            }
        }

        // If we have any sessions to stop, now is the time.
        if (!sessionsToStop.isEmpty()) {
            for (int i = 0; i < sessionsToStop.size(); i++) {
                stopProfiling(sessionsToStop.get(i));
            }
        }
    }

    public boolean isTraceRunning(String key) throws RuntimeException {
        TracingSession session = mActiveTracingSessions.get(key);
        if (session == null || session.getActiveTrace() == null) {
            // No subprocess, nothing running.
            if (DEBUG) Log.d(TAG, "No subprocess, nothing running.");
            return false;
        } else if (session.getActiveTrace().isAlive()) {
            // Subprocess exists and is alive.
            if (DEBUG) Log.d(TAG, "Subprocess exists and is alive, trace is running.");
            return true;
        } else {
            // Subprocess exists but is not alive, nothing running.
            if (DEBUG) Log.d(TAG, "Subprocess exists but is not alive, nothing running.");
            return false;
        }
    }

    /**
     * Begin moving result to storage by validating and then sending a request to
     * {@link ProfilingManager} for a file to write to. File will be returned as a
     * {@link ParcelFileDescriptor} via {@link sendFileDescriptor}.
     */
    @VisibleForTesting
    public void beginMoveFileToAppStorage(TracingSession session) {
        if (session.getState().getValue() >= TracingState.ERROR_OCCURRED.getValue()) {
            // This should not have happened, if the session has a state of error or later then why
            // are we trying to continue processing it? Remove from all data stores just in case.
            if (DEBUG) {
                Log.d(TAG, "Attempted beginMoveFileToAppStorage on a session with status error"
                        + " or an invalid status.");
            }
            mActiveTracingSessions.remove(session.getKey());
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

        requestFileForResult(perUidCallbacks, session);
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
                IProfilingResultCallback callback = perUidCallbacks.get(i);
                if (callback.asBinder().isBinderAlive()) {
                    callback.deleteFile(OUTPUT_FILE_RELATIVE_PATH + fileName);
                    // Only need one delete call, return.
                    return;
                }
            } catch (RemoteException e) {
                // Binder exception deleting file. Continue trying other callbacks for this process.
                if (DEBUG) Log.d(TAG, "Binder exception deleting file. Trying next callback", e);
            }
        }
    }

    /**
     * Request a {@link ParcelFileDescriptor} to a new file in app storage from the first live
     * callback for this uid.
     *
     * The new file is created by {@link ProfilingManager} from within app context. We only need a
     * single file, which can be created from any of the contexts belonging to the app that
     * requested this profiling, so it does not matter which of the requesting app's callbacks we
     * use.
     */
    @Nullable
    private void requestFileForResult(
            @NonNull List<IProfilingResultCallback> perUidCallbacks, TracingSession session) {
        String fileName = session.getProfilingType() == ProfilingManager.PROFILING_TYPE_SYSTEM_TRACE
                ? session.getRedactedFileName()
                : session.getFileName();
        for (int i = 0; i < perUidCallbacks.size(); i++) {
            try {
                IProfilingResultCallback callback = perUidCallbacks.get(i);
                if (callback.asBinder().isBinderAlive()) {
                    // Great, this one works! Call it and exit if we don't hit an exception.
                    perUidCallbacks.get(i).generateFile(OUTPUT_FILE_RELATIVE_PATH, fileName,
                            session.getKeyMostSigBits(), session.getKeyLeastSigBits());
                    return;
                }
            } catch (RemoteException e) {
                // Binder exception getting file. Continue trying other callbacks for this process.
                if (DEBUG) Log.d(TAG, "Binder exception getting file. Trying next callback", e);
            }
        }
        if (DEBUG) Log.d(TAG, "Failed to obtain file descriptor from callbacks.");
    }

    /** Handle a result which required redaction by attempting to kick off redaction process. */
    @VisibleForTesting
    public void handleRedactionRequiredResult(TracingSession session) {
        try {
            // We need to create an empty file for the redaction process to write the output into.
            File emptyRedactedTraceFile = new File(TEMP_TRACE_PATH
                    + session.getRedactedFileName());
            emptyRedactedTraceFile.createNewFile();
        } catch (Exception exception) {
            if (DEBUG) Log.e(TAG, "Creating empty redacted file failed.", exception);
            session.setError(ProfilingResult.ERROR_FAILED_POST_PROCESSING);
            advanceTracingSession(session, TracingState.ERROR_OCCURRED);
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
            session.setError(ProfilingResult.ERROR_FAILED_POST_PROCESSING);
            advanceTracingSession(session, TracingState.ERROR_OCCURRED);
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
            session.setError(ProfilingResult.ERROR_FAILED_POST_PROCESSING);
            advanceTracingSession(session, TracingState.ERROR_OCCURRED);
            return;
        }

        // Schedule the next check.
        getHandler().postDelayed(session.getProcessResultRunnable(),
                Math.min(mRedactionCheckFrequencyMs, mRedactionMaxRuntimeAllottedMs
                        - (System.currentTimeMillis() - session.getRedactionStartTimeMs())));

    }

    private void handleRedactionComplete(TracingSession session) {
        int redactionErrorCode = session.getActiveRedaction().exitValue();
        if (redactionErrorCode != 0) {
            // Redaction process failed. This failure cannot be recovered.
            if (DEBUG) {
                Log.d(TAG, String.format("Redaction processed failed with error code: %s",
                        redactionErrorCode));
            }
            cleanupTracingSession(session);
            session.setError(ProfilingResult.ERROR_FAILED_POST_PROCESSING);
            advanceTracingSession(session, TracingState.ERROR_OCCURRED);
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

        advanceTracingSession(session, TracingState.REDACTED);
    }

    /**
     * Called whenever a new global listener has been added to the specified uid.
     * Attempts to process queued results if present.
     */
    @VisibleForTesting
    public void handleQueuedResults(int uid) {
        List<TracingSession> queuedSessions = mQueuedTracingResults.get(uid);
        if (queuedSessions == null || queuedSessions.isEmpty()) {
            // No queued results for this uid, nothing to handle. Attempt to cleanup the queue for
            // all other uids before exiting.
            maybeCleanupQueue();
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

            // Advance with new state null so that it picks up where it left off.
            advanceTracingSession(session, null);
        }

        // Now attempt to cleanup the queue.
        maybeCleanupQueue();
    }

    /** Run through all queued sessions and clean up the ones that are too old. */
    private void maybeCleanupQueue() {
        List<TracingSession> sessionsToRemove = new ArrayList();
        // Iterate in reverse so we can remove the index if empty.
        for (int i = mQueuedTracingResults.size() - 1; i >= 0; i--) {
            List<TracingSession> sessions = mQueuedTracingResults.valueAt(i);
            if (sessions != null && !sessions.isEmpty()) {
                sessionsToRemove.clear();
                for (int j = 0; j < sessions.size(); j++) {
                    TracingSession session = sessions.get(j);
                    if (session.getProfilingStartTimeMs() + QUEUED_RESULT_MAX_RETAINED_DURATION_MS
                            < System.currentTimeMillis()) {
                        cleanupTracingSession(session);
                        sessionsToRemove.add(session);
                    }
                }
                sessions.removeAll(sessionsToRemove);
                if (sessions.isEmpty()) {
                    mQueuedTracingResults.removeAt(i);
                }
            } else {
                mQueuedTracingResults.removeAt(i);
            }
        }
    }

    /**
     * Cleanup is intended for when we're done with a queued trace session, whether successful or
     * not.
     *
     * Cleanup will attempt to delete the temporary file(s) and then remove it from the queue.
     */
    @VisibleForTesting
    public void cleanupTracingSession(TracingSession session) {
        List<TracingSession> queuedSessions = mQueuedTracingResults.get(session.getUid());
        cleanupTracingSession(session, queuedSessions);
    }

    /**
     * Cleanup is intended for when we're done with a queued trace session, whether successful or
     * not.
     *
     * Cleanup will attempt to delete the temporary file(s) and then remove it from the queue.
     */
    private void cleanupTracingSession(TracingSession session,
            @Nullable List<TracingSession> queuedSessions) {
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

        if (queuedSessions != null) {
            queuedSessions.remove(session);
            if (queuedSessions.isEmpty()) {
                mQueuedTracingResults.remove(session.getUid());
            }
        }

        advanceTracingSession(session, TracingState.CLEANED_UP);
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

    /**
     * Move session to list of queued sessions. Removes the session from the list of active
     * sessions, if it is present.
     *
     * Sessions are expected to be in the queue when their states are between PROFILING_FINISHED and
     * NOTIFIED_REQUESTER, inclusive.
     *
     * @param session      the session to move to the queue
     * @param maybePersist whether to persist the queue to disk if the queue is eligible to be
     *          persisted
     */
    private void moveSessionToQueue(TracingSession session, boolean maybePersist) {
        List<TracingSession> queuedResults = mQueuedTracingResults.get(session.getUid());
        if (queuedResults == null) {
            queuedResults = new ArrayList<TracingSession>();
            mQueuedTracingResults.put(session.getUid(), queuedResults);
        }
        queuedResults.add(session);
        mActiveTracingSessions.remove(session.getKey());

        if (maybePersist) {
            maybePersistQueueToDisk();
        }
    }

    private boolean needsRedaction(TracingSession session) {
        return session.getProfilingType() == ProfilingManager.PROFILING_TYPE_SYSTEM_TRACE;
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

    /**
     * Persist queued results to disk following the following rules:
     * - If a persist is already scheduled, do nothing.
     * - If a persist happened within the last {@link #mPersistQueueFrequencyMs} then schedule a
     *      persist for {@link #mPersistQueueFrequencyMs} after the last persist.
     * - If no persist has occurred yet or the most recent persist was more than
     *      {@link #mPersistQueueFrequencyMs} ago, persist immediately.
     */
    @VisibleForTesting
    public void maybePersistQueueToDisk() {
        if (!Flags.persistQueue()) {
            return;
        }

        synchronized (mLock) {
            if (mPersistQueueScheduled) {
                // We're already waiting on a scheduled persist job, do nothing.
                return;
            }

            if (mPersistQueueFrequencyMs.get() != 0
                    && (System.currentTimeMillis() - mLastPersistedQueueTimestampMs
                    < mPersistQueueFrequencyMs.get())) {
                // Schedule the persist job.
                if (mPersistQueueRunnable == null) {
                    mPersistQueueRunnable = new Runnable() {
                        @Override
                        public void run() {
                            persistQueueToDisk();
                            mPersistQueueScheduled = false;
                        }
                    };
                }
                mPersistQueueScheduled = true;
                long persistDelay = mLastPersistedQueueTimestampMs + mPersistQueueFrequencyMs.get()
                        - System.currentTimeMillis();
                getHandler().postDelayed(mPersistQueueRunnable, persistDelay);
                return;
            }
        }

        // If we got here then either persist frequency is 0 or it has already been longer than
        // persist frequency since the last persist. Persist immediately.
        persistQueueToDisk();
    }

    /** Persist the current queue to disk after cleaning it up. */
    @VisibleForTesting
    public void persistQueueToDisk() {
        if (!Flags.persistQueue()) {
            return;
        }

        // Check if file exists
        try {
            if (mPersistQueueFile == null) {
                // Try again to create the necessary files.
                if (!setupPersistQueueFiles()) {
                    // No file, nowhere to save.
                    if (DEBUG) {
                        Log.d(TAG, "Failed setting up queue persist files so nowhere to save to.");
                    }
                    return;
                }
            }

            if (!mPersistQueueFile.exists()) {
                // File doesn't exist, try to create it.
                mPersistQueueFile.createNewFile();
            }
        } catch (Exception e) {
            if (DEBUG) Log.e(TAG, "Exception accessing persisted records store.", e);
            return;
        }

        // Clean up queue to reduce extraneous writes
        maybeCleanupQueue();

        // Generate proto for queue.
        QueuedResultsWrapper.Builder builder = QueuedResultsWrapper.newBuilder();

        boolean recordAdded = false;

        for (int i = 0; i < mQueuedTracingResults.size(); i++) {
            List<TracingSession> perUidSessions = mQueuedTracingResults.valueAt(i);
            if (!perUidSessions.isEmpty()) {
                for (int j = 0; j < perUidSessions.size(); j++) {
                    builder.addSessions(perUidSessions.get(j).toProto());

                    if (!recordAdded) {
                        recordAdded = true;
                    }
                }
            }
        }

        if (!recordAdded) {
            // No results, nothing to persist, delete the file as it may contain results that are no
            // longer meaningful and will just increase future work and then return.
            deletePersistQueueFile();
            return;
        }

        QueuedResultsWrapper queuedResultsWrapper = builder.build();

        // Write to disk
        byte[] protoBytes = queuedResultsWrapper.toByteArray();
        AtomicFile persistFile = new AtomicFile(mPersistQueueFile);
        FileOutputStream out = null;
        try {
            out = persistFile.startWrite();
            out.write(protoBytes);
            persistFile.finishWrite(out);
            synchronized (mLock) {
                mLastPersistedQueueTimestampMs = System.currentTimeMillis();
            }
        } catch (IOException e) {
            if (DEBUG) Log.e(TAG, "Exception writing queued results", e);
            persistFile.failWrite(out);
        }
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
