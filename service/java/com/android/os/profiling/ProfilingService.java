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

import static android.os.Process.SYSTEM_UID;

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
import android.os.ProfilingTrigger;
import android.os.ProfilingTriggerValueParcel;
import android.os.ProfilingTriggersWrapper;
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
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public class ProfilingService extends IProfilingService.Stub {
    private static final String TAG = ProfilingService.class.getSimpleName();
    private static final boolean DEBUG = false;

    private static final String TEMP_TRACE_PATH = "/data/misc/perfetto-traces/profiling/";
    private static final String OUTPUT_FILE_RELATIVE_PATH = "/profiling/";
    private static final String OUTPUT_FILE_SECTION_SEPARATOR = "_";
    private static final String OUTPUT_FILE_FIELD_SEPARATOR = "-";
    private static final String OUTPUT_FILE_PREFIX = "profile";
    // Keep in sync with {@link ProfilingFrameworkTests}.
    private static final String OUTPUT_FILE_JAVA_HEAP_DUMP_SUFFIX = ".perfetto-java-heap-dump";
    private static final String OUTPUT_FILE_HEAP_PROFILE_SUFFIX = ".perfetto-heap-profile";
    private static final String OUTPUT_FILE_STACK_SAMPLING_SUFFIX = ".perfetto-stack-sample";
    private static final String OUTPUT_FILE_TRACE_SUFFIX = ".perfetto-trace";
    private static final String OUTPUT_FILE_UNREDACTED_TRACE_SUFFIX = ".perfetto-trace-unredacted";
    private static final String OUTPUT_FILE_TRIGGER = "trigger-type";
    private static final String OUTPUT_FILE_IN_PROGRESS = "in-progress";

    private static final String PERSIST_SYSTEM_DIR = "system";
    private static final String PERSIST_STORE_DIR = "profiling_service_data";
    private static final String QUEUED_RESULTS_INFO_FILE = "profiling_queued_results_info";
    private static final String APP_TRIGGERS_INFO_FILE = "profiling_app_triggers_info";

    // Used for unique session name only, not filename.
    private static final String SYSTEM_TRIGGERED_SESSION_NAME_PREFIX = "system_triggered_session_";

    private static final String RATE_LIMITER_DISABLED_ERROR_MESSAGE =
            "Rate limiter disabled manually via adb.";

    private static final int TAG_MAX_CHARS_FOR_FILENAME = 20;

    private static final int PERFETTO_DESTROY_DEFAULT_TIMEOUT_MS = 10 * 1000;

    private static final int DEFAULT_MAX_RESULT_REDELIVERY_COUNT = 3;

    private static final int REDACTION_DEFAULT_MAX_RUNTIME_ALLOTTED_MS = 20 * 1000;

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

    private static final int PERSIST_TO_DISK_DEFAULT_FREQUENCY_MS = 30 * 60 * 1000;

    // Targeting a period of around 24 hours, so set max and min to 24 +/- 6 hours, respectively.
    private static final int DEFAULT_SYSTEM_TRIGGERED_TRACE_MIN_PERIOD_SECONDS = 18 * 60 * 60;
    private static final int DEFAULT_SYSTEM_TRIGGERED_TRACE_MAX_PERIOD_SECONDS = 30 * 60 * 60;

    private final Context mContext;
    private final Object mLock = new Object();
    private final HandlerThread mHandlerThread = new HandlerThread("ProfilingService");

    /** Do not access directly, use {@link #getRateLimiter}. */
    @VisibleForTesting @Nullable public RateLimiter mRateLimiter = null;

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

    // System triggered trace is another actively running profiling session, but not included in
    // the active sessions above as it's not associated with a TracingSession until it has been
    // cloned.
    @VisibleForTesting
    @GuardedBy("mLock")
    public Process mSystemTriggeredTraceProcess = null;

    @VisibleForTesting public String mSystemTriggeredTraceUniqueSessionName = null;
    private long mLastStartedSystemTriggeredTraceMs = 0;

    /**
     * Map of uid + package name to a sparse array of trigger objects.
     *
     * <p>Adding items to this data structure must only be done using {@link
     * #addTrigger(ProfilingTriggerData, boolean)} which validates the added triggers.
     */
    @VisibleForTesting
    public ProcessMap<SparseArray<ProfilingTriggerData>> mAppTriggers = new ProcessMap<>();

    @VisibleForTesting public boolean mAppTriggersLoaded = false;

    // uid indexed storage of completed tracing sessions that have not yet successfully handled the
    // result.
    @VisibleForTesting
    public SparseArray<List<TracingSession>> mQueuedTracingResults = new SparseArray<>();

    private boolean mPersistScheduled = false;

    // Frequency of 0 would result in immediate persist.
    @GuardedBy("mLock")
    private AtomicInteger mPersistFrequencyMs;

    @GuardedBy("mLock")
    private long mLastPersistedTimestampMs = 0L;

    private Runnable mPersistRunnable = null;

    /** The path to the directory which includes all persisted results from this class. */
    @VisibleForTesting public File mPersistStoreDir = null;

    /** The queued results data file, persisted in the storage. */
    @VisibleForTesting public File mPersistQueueFile = null;

    /** The app triggers results data file, persisted in the storage. */
    @VisibleForTesting public File mPersistAppTriggersFile = null;

    /** To be disabled for testing only. */
    @GuardedBy("mLock")
    private boolean mKeepResultInTempDir = false;

    /** Executor for scheduling system triggered profiling trace. */
    private ScheduledExecutorService mScheduledExecutorService = null;

    /** Future for the start system triggered trace. */
    @VisibleForTesting public ScheduledFuture<?> mStartSystemTriggeredTraceScheduledFuture = null;

    @GuardedBy("mLock")
    private AtomicInteger mSystemTriggeredTraceMinPeriodSeconds;

    @GuardedBy("mLock")
    private AtomicInteger mSystemTriggeredTraceMaxPeriodSeconds;

    /**
     * Package name of app being debugged, or null if no app is being debugged. To be used both for
     * automated testing and developer manual debugging.
     *
     * <p>Setting this package name will: - Ensure a system triggered trace is always running. -
     * Allow all triggers for the specified package name to be executed.
     *
     * <p>This is not intended to be set directly. Instead, set this package name by using
     * device_config commands described at {@link ProfilingManager}.
     *
     * <p>There is no time limit on how long this can be left enabled for.
     */
    private String mDebugPackageName = null;

    /**
     * State the {@link TracingSession} is in.
     *
     * <p>State represents the most recently confirmed completed step in the process. Steps
     * represent save points which the process would have to go back to if it did not successfully
     * reach the next step.
     *
     * <p>States are sequential. It can be expected that state value will only increase throughout a
     * sessions life.
     *
     * <p>At different states, the containing object can be assumed to exist in different data
     * structures as follows:
     *
     * <ul>
     *   <li>REQUESTED - Local only, not in any data structure.
     *   <li>APPROVED - Local only, not in any data structure.
     *   <li>PROFILING_STARTED - Stored in {@link mActiveTracingSessions}.
     *   <li>PROFILING_FINISHED - Stored in {@link mQueuedTracingResults}.
     *   <li>REDACTED - Stored in {@link mQueuedTracingResults}.
     *   <li>COPIED_FILE - Stored in {@link mQueuedTracingResults}.
     *   <li>ERROR_OCCURRED - Stored in {@link mQueuedTracingResults}.
     *   <li>NOTIFIED_REQUESTER - Stored in {@link mQueuedTracingResults}.
     *   <li>CLEANED_UP - Local only, not in any data structure.
     * </ul>
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

        mPerfettoDestroyTimeoutMs =
                DeviceConfigHelper.getInt(
                        DeviceConfigHelper.PERFETTO_DESTROY_TIMEOUT_MS,
                        PERFETTO_DESTROY_DEFAULT_TIMEOUT_MS);

        mMaxResultRedeliveryCount =
                DeviceConfigHelper.getInt(
                        DeviceConfigHelper.MAX_RESULT_REDELIVERY_COUNT,
                        DEFAULT_MAX_RESULT_REDELIVERY_COUNT);

        mProfilingRecheckDelayMs =
                DeviceConfigHelper.getInt(
                        DeviceConfigHelper.PROFILING_RECHECK_DELAY_MS,
                        PROFILING_DEFAULT_RECHECK_DELAY_MS);

        mClearTemporaryDirectoryFrequencyMs =
                DeviceConfigHelper.getInt(
                        DeviceConfigHelper.CLEAR_TEMPORARY_DIRECTORY_FREQUENCY_MS,
                        CLEAR_TEMPORARY_DIRECTORY_FREQUENCY_DEFAULT_MS);

        mClearTemporaryDirectoryBootDelayMs =
                DeviceConfigHelper.getInt(
                        DeviceConfigHelper.CLEAR_TEMPORARY_DIRECTORY_BOOT_DELAY_MS,
                        CLEAR_TEMPORARY_DIRECTORY_BOOT_DELAY_DEFAULT_MS);

        mRedactionCheckFrequencyMs =
                DeviceConfigHelper.getInt(
                        DeviceConfigHelper.REDACTION_CHECK_FREQUENCY_MS,
                        REDACTION_DEFAULT_CHECK_FREQUENCY_MS);

        mRedactionMaxRuntimeAllottedMs =
                DeviceConfigHelper.getInt(
                        DeviceConfigHelper.REDACTION_MAX_RUNTIME_ALLOTTED_MS,
                        REDACTION_DEFAULT_MAX_RUNTIME_ALLOTTED_MS);

        mHandlerThread.start();

        // Get initial value for whether unredacted trace should be retained.
        // This is used for (automated and manual) testing only.
        synchronized (mLock) {
            mKeepResultInTempDir =
                    DeviceConfigHelper.getTestBoolean(
                            DeviceConfigHelper.DISABLE_DELETE_TEMPORARY_RESULTS, false);

            mPersistFrequencyMs =
                    new AtomicInteger(
                            DeviceConfigHelper.getInt(
                                    DeviceConfigHelper.PERSIST_TO_DISK_FREQUENCY_MS,
                                    PERSIST_TO_DISK_DEFAULT_FREQUENCY_MS));

            mSystemTriggeredTraceMinPeriodSeconds =
                    new AtomicInteger(
                            DeviceConfigHelper.getInt(
                                    DeviceConfigHelper.SYSTEM_TRIGGERED_TRACE_MIN_PERIOD_SECONDS,
                                    DEFAULT_SYSTEM_TRIGGERED_TRACE_MIN_PERIOD_SECONDS));

            mSystemTriggeredTraceMaxPeriodSeconds =
                    new AtomicInteger(
                            DeviceConfigHelper.getInt(
                                    DeviceConfigHelper.SYSTEM_TRIGGERED_TRACE_MAX_PERIOD_SECONDS,
                                    DEFAULT_SYSTEM_TRIGGERED_TRACE_MAX_PERIOD_SECONDS));
        }
        // Now subscribe to updates on test config.
        DeviceConfig.addOnPropertiesChangedListener(
                DeviceConfigHelper.NAMESPACE_TESTING,
                mContext.getMainExecutor(),
                new DeviceConfig.OnPropertiesChangedListener() {
                    @Override
                    public void onPropertiesChanged(@NonNull DeviceConfig.Properties properties) {
                        synchronized (mLock) {
                            // Update value, using the current value as the default to ensure that
                            // the value is unchanged when the specific config is not present in the
                            // update config.
                            mKeepResultInTempDir =
                                    properties.getBoolean(
                                            DeviceConfigHelper.DISABLE_DELETE_TEMPORARY_RESULTS,
                                            mKeepResultInTempDir);

                            getRateLimiter().maybeUpdateRateLimiterDisabled(properties);

                            // Use null as default since we're assigning to a new variable and
                            // handleDebugPackageChangeLocked will handle null as unchanged.
                            String newDebugPackageName =
                                    properties.getString(
                                            DeviceConfigHelper.SYSTEM_TRIGGERED_DEBUG_PACKAGE_NAME,
                                            null);
                            handleDebugPackageChangeLocked(newDebugPackageName);
                        }
                    }
                });

        // Subscribe to updates on the main config.
        DeviceConfig.addOnPropertiesChangedListener(
                DeviceConfigHelper.NAMESPACE,
                mContext.getMainExecutor(),
                new DeviceConfig.OnPropertiesChangedListener() {
                    @Override
                    public void onPropertiesChanged(@NonNull DeviceConfig.Properties properties) {
                        synchronized (mLock) {
                            getRateLimiter().maybeUpdateConfigs(properties);
                            Configs.maybeUpdateConfigs(properties);

                            mPerfettoDestroyTimeoutMs =
                                    properties.getInt(
                                            DeviceConfigHelper.PERFETTO_DESTROY_TIMEOUT_MS,
                                            mPerfettoDestroyTimeoutMs);

                            mMaxResultRedeliveryCount =
                                    properties.getInt(
                                            DeviceConfigHelper.MAX_RESULT_REDELIVERY_COUNT,
                                            mMaxResultRedeliveryCount);

                            mProfilingRecheckDelayMs =
                                    properties.getInt(
                                            DeviceConfigHelper.PROFILING_RECHECK_DELAY_MS,
                                            mProfilingRecheckDelayMs);

                            mClearTemporaryDirectoryFrequencyMs =
                                    properties.getInt(
                                            DeviceConfigHelper
                                                    .CLEAR_TEMPORARY_DIRECTORY_FREQUENCY_MS,
                                            mClearTemporaryDirectoryFrequencyMs);

                            // No need to handle updates for
                            // {@link mClearTemporaryDirectoryBootDelayMs} as it's only used on
                            // initialization of this class so by the time this occurs it will never
                            // be used again.

                            mRedactionCheckFrequencyMs =
                                    properties.getInt(
                                            DeviceConfigHelper.REDACTION_CHECK_FREQUENCY_MS,
                                            mRedactionCheckFrequencyMs);

                            mRedactionMaxRuntimeAllottedMs =
                                    properties.getInt(
                                            DeviceConfigHelper.REDACTION_MAX_RUNTIME_ALLOTTED_MS,
                                            mRedactionMaxRuntimeAllottedMs);

                            mPersistFrequencyMs.set(
                                    properties.getInt(
                                            DeviceConfigHelper.PERSIST_TO_DISK_FREQUENCY_MS,
                                            mPersistFrequencyMs.get()));

                            mSystemTriggeredTraceMinPeriodSeconds.set(
                                    DeviceConfigHelper.getInt(
                                            DeviceConfigHelper
                                                    .SYSTEM_TRIGGERED_TRACE_MIN_PERIOD_SECONDS,
                                            mSystemTriggeredTraceMinPeriodSeconds.get()));

                            mSystemTriggeredTraceMaxPeriodSeconds.set(
                                    DeviceConfigHelper.getInt(
                                            DeviceConfigHelper
                                                    .SYSTEM_TRIGGERED_TRACE_MAX_PERIOD_SECONDS,
                                            mSystemTriggeredTraceMaxPeriodSeconds.get()));
                        }
                    }
                });

        // Schedule initial storage cleanup and system triggered trace start after a delay so as not
        // to increase non-critical or work during boot.
        getHandler()
                .postDelayed(
                        new Runnable() {
                            @Override
                            public void run() {
                                scheduleNextSystemTriggeredTraceStart();
                                maybeCleanupTemporaryDirectory();
                            }
                        },
                        mClearTemporaryDirectoryBootDelayMs);

        // Load the queue and triggers right away.
        loadQueueFromPersistedData();
        loadAppTriggersFromPersistedData();
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

    /**
     * Load persisted app triggers from disk.
     *
     * <p>If any issue is encountered during loading, mark as completed and delete the file.
     * Persisted app triggers will be lost.
     */
    @VisibleForTesting
    public void loadAppTriggersFromPersistedData() {
        // Setup persist files
        try {
            if (!setupPersistAppTriggerFiles()) {
                // If setting up the directory and file was unsuccessful then just return without
                // marking loaded so it can be tried again.
                if (DEBUG) Log.d(TAG, "Failed to setup app trigger persistence directory/files.");
                return;
            }
        } catch (SecurityException e) {
            // Can't access files.
            Log.w(TAG, "Failed to setup app trigger persistence directory/files.", e);
            return;
        }

        // Check if file exists
        try {
            if (!mPersistAppTriggersFile.exists()) {
                // No file, nothing to load. This is an expected state for before the feature has
                // ever been used or if the triggers were empty.
                if (DEBUG) {
                    Log.d(
                            TAG,
                            "App trigger persistence file does not exist, skipping load from "
                                    + "disk.");
                }
                mAppTriggersLoaded = true;
                return;
            }
        } catch (SecurityException e) {
            // Can't access file.
            if (DEBUG) Log.e(TAG, "Exception accessing app triggers persistence file", e);
            return;
        }

        // Read the file
        AtomicFile persistFile = new AtomicFile(mPersistAppTriggersFile);
        byte[] bytes;
        try {
            bytes = persistFile.readFully();
        } catch (IOException e) {
            Log.w(TAG, "Exception reading app triggers persistence file", e);
            // Failed to read the file. No reason to believe we'll have better luck next time,
            // delete the file and return. Persisted triggers will be lost until the app re-adds
            // them.
            deletePersistAppTriggersFile();
            mAppTriggersLoaded = true;
            return;
        }
        if (bytes.length == 0) {
            if (DEBUG) Log.d(TAG, "App triggers persistence file empty, skipping load from disk.");
            // Empty app triggers persist file. Delete the file, mark loaded, and return.
            deletePersistAppTriggersFile();
            mAppTriggersLoaded = true;
            return;
        }

        // Parse file bytes to proto
        ProfilingTriggersWrapper wrapper;
        try {
            wrapper = ProfilingTriggersWrapper.parseFrom(bytes);
        } catch (Exception e) {
            Log.w(TAG, "Error parsing proto from persisted bytes", e);
            // Failed to parse the file contents. No reason to believe we'll have better luck next
            // time, delete the file, mark loaded, and return. Persisted app triggers will be lost
            // until re-added by the app.
            deletePersistAppTriggersFile();
            mAppTriggersLoaded = true;
            return;
        }

        // Populate in memory app triggers store
        for (int i = 0; i < wrapper.getTriggersCount(); i++) {
            ProfilingTriggersWrapper.ProfilingTrigger triggerProto = wrapper.getTriggers(i);
            try {
                addTrigger(new ProfilingTriggerData(triggerProto), false);
            } catch (IllegalArgumentException e) {
                // If this exception is thrown, then a trigger was added with an unsupported type.
                // Ignore and continue.
                if (DEBUG) Log.w(TAG, "Trigger loaded from storage with invalid type", e);
            }
        }

        mAppTriggersLoaded = true;
    }

    /** Setup the directory and file for persisting queue. */
    @VisibleForTesting
    public boolean setupPersistQueueFiles() {
        if (mPersistStoreDir == null) {
            if (!setupPersistDir()) {
                return false;
            }
        }
        mPersistQueueFile = new File(mPersistStoreDir, QUEUED_RESULTS_INFO_FILE);
        return true;
    }

    /** Setup the directory and file for persisting app triggers. */
    @VisibleForTesting
    public boolean setupPersistAppTriggerFiles() {
        if (mPersistStoreDir == null) {
            if (!setupPersistDir()) {
                return false;
            }
        }
        mPersistAppTriggersFile = new File(mPersistStoreDir, APP_TRIGGERS_INFO_FILE);
        return true;
    }

    /** Setup the directory and file for persisting. */
    @VisibleForTesting
    public boolean setupPersistDir() {
        File dataDir = Environment.getDataDirectory();
        File systemDir = new File(dataDir, PERSIST_SYSTEM_DIR);
        mPersistStoreDir = new File(systemDir, PERSIST_STORE_DIR);
        return createDir(mPersistStoreDir);
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

    /** Delete the persist app triggers file. */
    @VisibleForTesting
    public void deletePersistAppTriggersFile() {
        try {
            mPersistAppTriggersFile.delete();
            if (DEBUG) Log.d(TAG, "Deleted app triggers persist file.");
        } catch (SecurityException e) {
            // Can't delete file.
            if (DEBUG) Log.d(TAG, "Failed to delete app triggers persist file", e);
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
     * Schedule the next start of system triggered profiling trace for a random time between min and
     * max period.
     */
    @VisibleForTesting
    public void scheduleNextSystemTriggeredTraceStart() {
        if (!Flags.systemTriggeredProfilingNew()) {
            // Feature disabled.
            return;
        }

        if (mStartSystemTriggeredTraceScheduledFuture != null) {
            // If an existing start is already scheduled, don't schedule another.
            // This should not happen.
            Log.e(
                    TAG,
                    "Attempted to schedule a system triggered trace start with one already "
                            + "scheduled.");
            return;
        }

        if (mScheduledExecutorService == null) {
            mScheduledExecutorService = Executors.newSingleThreadScheduledExecutor();
        }

        int scheduledDelaySeconds;

        synchronized (mLock) {
            // It's important that trace doesn't always run at the same time as this will bias the
            // results, so grab a random number between min and max.
            scheduledDelaySeconds =
                    mSystemTriggeredTraceMinPeriodSeconds.get()
                            + (new Random())
                                    .nextInt(
                                            mSystemTriggeredTraceMaxPeriodSeconds.get()
                                                    - mSystemTriggeredTraceMinPeriodSeconds.get());

            if (DEBUG) {
                Log.d(
                        TAG,
                        String.format(
                                "System triggered trace scheduled in %d seconds for params"
                                        + " min %d and max %d seconds.",
                                scheduledDelaySeconds,
                                mSystemTriggeredTraceMinPeriodSeconds.get(),
                                mSystemTriggeredTraceMaxPeriodSeconds.get()));
            }
        }

        // Scheduling of system triggered trace setup is done out of the lock to avoid a potential
        // deadlock in the case of really frequent triggering due to low min/max values for period.
        mStartSystemTriggeredTraceScheduledFuture =
                mScheduledExecutorService.schedule(
                        () -> {
                            // Start the system triggered trace.
                            startSystemTriggeredTrace();

                            mStartSystemTriggeredTraceScheduledFuture = null;

                            // In all cases, schedule again. Feature flagged off is handled earlier
                            // in this method, and all return cases in
                            // {@link #startSystemTriggeredTrace} should result in trying again at
                            // the next regularly scheduled time.
                            scheduleNextSystemTriggeredTraceStart();
                        },
                        scheduledDelaySeconds,
                        TimeUnit.SECONDS);
    }

    /**
     * This is the core method that keeps the profiling flow moving.
     *
     * <p>This is the only way that state should be set. Do not use {@link TracingSession#setState}
     * directly.
     *
     * <p>The passed newState represents the state that was just completed. Passing null for new
     * state will continue using the current state as the last completed state, this is intended
     * only for resuming the queue.
     *
     * <p>Generally, this should be the last call in a method before returning.
     */
    @VisibleForTesting
    public void advanceTracingSession(TracingSession session, @Nullable TracingState newState) {
        if (DEBUG) {
            Log.d(
                    TAG,
                    "Advance Tracing State to "
                            + (newState != null ? newState.getValue() : "null"));
        }
        if (newState == null) {
            if (session.getRetryCount() == 0) {
                // The new state should only be null if this is triggered from the queue in which
                // case the retry count should be greater than 0. If retry count is 0 here then
                // we're in an unexpected state. Cleanup and discard. Result will be lost.
                if (DEBUG) {
                    Log.d(TAG, "advanceTracingSession error: unexpected null state");
                }
                cleanupTracingSession(session);
                return;
            }
        } else if (newState == session.getState()) {
            // This should never happen.
            // If the state is not actually changing then we may find ourselves in an infinite
            // loop. Terminate this attempt and increment the retry count to ensure there's a
            // path to breaking out of a potential infinite queue retries.
            session.incrementRetryCount();
            if (DEBUG) {
                Log.d(TAG, "advanceTracingSession error: trying to set same state as before");
            }
            return;
        } else if (newState.getValue() < session.getState().getValue()) {
            // This should also never happen.
            // States should always move forward. If the state is trying to move backwards then
            // we don't actually know what to do next. Clean up the session and delete
            // everything. Results will be lost.
            if (DEBUG) {
                Log.d(TAG, "advanceTracingSession error: moving backwards");
            }
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
                if (session.getActiveTrace() == null
                        || !session.getActiveTrace().isAlive()
                        || session.getProcessResultRunnable() == null) {
                    // This really should not happen, but if profiling is not in correct started
                    // state then try to stop and continue processing it.
                    stopProfiling(session, LoggingHelper.PROFILING_STOPPED_REASON_ERROR);
                } // else: do nothing. The runnable we just verified exists will return us to this
                // method when profiling is finished.
                break;
            case PROFILING_FINISHED:
                if (!tempProfileExists(session)) {
                    session.setError(ProfilingResult.ERROR_FAILED_EXECUTING);
                    long profilingTime =
                            System.currentTimeMillis() - session.getProfilingStartTimeMs();
                    if (DEBUG) {
                        Log.d(
                                TAG,
                                "No profile data was produced by perfetto during "
                                        + profilingTime
                                        + " ms profiling session");
                    }
                    if (profilingTime > 500) {
                        session.setErrorMessage("No profile data was produced by perfetto.");
                    } else {
                        session.setErrorMessage(
                                "No profile data was produced by perfetto."
                                        + " Profiling session duration (ms): "
                                        + profilingTime
                                        + ". Profiling may have stopped too soon.");
                    }
                    advanceTracingSession(session, TracingState.ERROR_OCCURRED);
                    return;
                }

                // Next step depends on whether or not the result requires redaction.
                if (needsRedaction(session)) {
                    // Redaction needed, kick it off.
                    handleRedactionRequiredResult(session);
                } else {
                    // For results that don't require redaction, maybe log the location of the
                    // retained result after profiling completes.
                    handleRetainedTempFiles(session);

                    // No redaction needed, move straight to copying to app storage.
                    beginMoveFileToAppStorage(session);
                }
                break;
            case REDACTED:
                // For results that require redaction, maybe log the location of the retained result
                // after redaction completes.
                handleRetainedTempFiles(session);

                // Redaction completed, move on to copying to app storage.
                beginMoveFileToAppStorage(session);
                break;
            case COPIED_FILE:
                // File has already been copied to app storage, proceed to callback.
                if (Flags.addRateLimiterDisabledToResult()) {
                    // Set error using existing error message to retain message in case it was set
                    // to indicate that rate limiter was disabled.
                    session.setError(ProfilingResult.ERROR_NONE, session.getErrorMessage());
                } else {
                    session.setError(ProfilingResult.ERROR_NONE);
                }
                processTracingSessionResultCallback(session, true /* Continue advancing session */);

                // This is a good place to persist the queue if possible because the processing work
                // is complete and we tried to send a callback to the app. If the callback
                // succeeded, then we will already have recursed on this method with new state of
                // NOTIFIED_REQUESTER and the only potential remaining work to be repeated will be
                // cleanup. If the callback failed, then we won't have recursed here and we'll pick
                // back up this stage next time thereby minimizing repeated work.
                maybePersistToDisk();
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
                maybePersistToDisk();
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
        if (mKeepResultInTempDir) {
            // Don't clean up any temporary files while {@link mKeepResultInTempDir} is enabled as
            // files are being retained for testing purposes.
            return;
        }

        // Obtain a list of all currently tracked files and create a filter with it. Filter is set
        // to null if the list is empty as that will efficiently accept all files.
        final List<String> trackedFilenames = getTrackedFilenames();
        FilenameFilter filenameFilter =
                trackedFilenames.isEmpty()
                        ? null
                        : new FilenameFilter() {
                            @Override
                            public boolean accept(File dir, String name) {
                                // We only want to accept files which are not in the tracked files
                                // list.
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
                Log.d(
                        TAG,
                        "Temporary directory doesn't exist or i/o error occurred. "
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
     * Enforce that the caller's UID matches the provided package name.
     *
     * @throws SecurityException if the package name does not match the calling UID.
     */
    private void enforceCallerMatchesPackageName(String packageName) {
        if (packageName == null || packageName.isEmpty()) {
            // Empty package cannot be valid.
            throw new SecurityException("Package name empty, is not associated with caller.");
        }

        int callingUid = Binder.getCallingUid();
        String[] uidPackages = mContext.getPackageManager().getPackagesForUid(callingUid);
        if (uidPackages == null || uidPackages.length == 0) {
            // Failed to get packages for this uid, cannot validate package name.
            throw new SecurityException("Failed to resolve package name for uid: " + callingUid);
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
            throw new SecurityException(
                    "Package name "
                            + packageName
                            + " not associated with "
                            + "calling uid: "
                            + callingUid);
        }
    }

    private void enforceSystemCaller() {
        if (Binder.getCallingUid() != SYSTEM_UID) {
            throw new SecurityException("Calling system only method from non system process.");
        }
    }

    /**
     * This method validates the request, arguments, whether the app is allowed to profile now, and
     * if so, starts the profiling.
     */
    public void requestProfiling(
            int profilingType,
            Bundle params,
            String tag,
            long keyMostSigBits,
            long keyLeastSigBits,
            String packageName) {
        enforceCallerMatchesPackageName(packageName);

        int uid = Binder.getCallingUid();

        if (profilingType != ProfilingManager.PROFILING_TYPE_JAVA_HEAP_DUMP
                && profilingType != ProfilingManager.PROFILING_TYPE_HEAP_PROFILE
                && profilingType != ProfilingManager.PROFILING_TYPE_STACK_SAMPLING
                && profilingType != ProfilingManager.PROFILING_TYPE_SYSTEM_TRACE) {
            if (DEBUG) Log.d(TAG, "Invalid request profiling type: " + profilingType);
            processResultCallback(
                    uid,
                    keyMostSigBits,
                    keyLeastSigBits,
                    ProfilingResult.ERROR_FAILED_INVALID_REQUEST,
                    null,
                    tag,
                    "Invalid request profiling type",
                    getTriggerTypeNone(),
                    profilingType);
            LoggingHelper.logProfilingRequest(
                    uid,
                    profilingType,
                    params,
                    LoggingHelper.REQUEST_RESULT_INVALID,
                    getRateLimiter().isRateLimiterDisabled());
            return;
        }

        cleanupActiveTracingSessions();

        // Check if we're running another trace so we don't run multiple at once.
        try {
            if (areAnyTracesRunning()) {
                processResultCallback(
                        uid,
                        keyMostSigBits,
                        keyLeastSigBits,
                        ProfilingResult.ERROR_FAILED_PROFILING_IN_PROGRESS,
                        null,
                        tag,
                        null,
                        getTriggerTypeNone(),
                        profilingType);
                LoggingHelper.logProfilingRequest(
                        uid,
                        profilingType,
                        params,
                        LoggingHelper.REQUEST_RESULT_PROFILING_IN_PROGRESS,
                        getRateLimiter().isRateLimiterDisabled());
                return;
            }
        } catch (RuntimeException e) {
            if (DEBUG) Log.d(TAG, "Error communicating with perfetto", e);
            processResultCallback(
                    uid,
                    keyMostSigBits,
                    keyLeastSigBits,
                    ProfilingResult.ERROR_UNKNOWN,
                    null,
                    tag,
                    "Error communicating with perfetto",
                    getTriggerTypeNone(),
                    profilingType);
            LoggingHelper.logProfilingRequest(
                    uid,
                    profilingType,
                    params,
                    LoggingHelper.REQUEST_RESULT_ERROR,
                    getRateLimiter().isRateLimiterDisabled());
            return;
        }

        // Check with rate limiter if this request is allowed.
        final int status =
                getRateLimiter()
                        .isProfilingRequestAllowed(
                                Binder.getCallingUid(), profilingType, false, params);
        if (DEBUG) Log.d(TAG, "Rate limiter status: " + status);
        if (status == RateLimiter.RATE_LIMIT_RESULT_ALLOWED) {
            // Rate limiter approved, try to start the request.
            try {
                TracingSession session =
                        new TracingSession(
                                profilingType,
                                params,
                                uid,
                                packageName,
                                tag,
                                keyMostSigBits,
                                keyLeastSigBits,
                                getTriggerTypeNone());
                if (Flags.addRateLimiterDisabledToResult()
                        && getRateLimiter().isRateLimiterDisabled()) {
                    session.setErrorMessage(RATE_LIMITER_DISABLED_ERROR_MESSAGE);
                }
                advanceTracingSession(session, TracingState.APPROVED);
                return;
            } catch (IllegalArgumentException e) {
                // This should not happen, it should have been caught when checking rate limiter.
                // Issue with the request. Apps fault.
                if (DEBUG) {
                    Log.d(
                            TAG,
                            "Invalid request at config generation. This should not have happened.",
                            e);
                }
                processResultCallback(
                        uid,
                        keyMostSigBits,
                        keyLeastSigBits,
                        ProfilingResult.ERROR_FAILED_INVALID_REQUEST,
                        null,
                        tag,
                        e.getMessage(),
                        getTriggerTypeNone(),
                        profilingType);
                LoggingHelper.logProfilingRequest(
                        uid,
                        profilingType,
                        params,
                        LoggingHelper.REQUEST_RESULT_INVALID,
                        getRateLimiter().isRateLimiterDisabled());
                return;
            } catch (RuntimeException e) {
                // Perfetto error. Systems fault.
                if (DEBUG) Log.d(TAG, "Perfetto error", e);
                processResultCallback(
                        uid,
                        keyMostSigBits,
                        keyLeastSigBits,
                        ProfilingResult.ERROR_UNKNOWN,
                        null,
                        tag,
                        "Perfetto error",
                        getTriggerTypeNone(),
                        profilingType);
                LoggingHelper.logProfilingRequest(
                        uid,
                        profilingType,
                        params,
                        LoggingHelper.REQUEST_RESULT_ERROR,
                        getRateLimiter().isRateLimiterDisabled());
                return;
            }
        } else {
            // Rate limiter denied, notify caller.
            if (DEBUG) Log.d(TAG, "Request denied with status: " + status);
            processResultCallback(
                    uid,
                    keyMostSigBits,
                    keyLeastSigBits,
                    RateLimiter.statusToResult(status),
                    null,
                    tag,
                    null,
                    getTriggerTypeNone(),
                    profilingType);
            int rateLimitType =
                    status == RateLimiter.RATE_LIMIT_RESULT_BLOCKED_PROCESS
                            ? LoggingHelper.REQUEST_RESULT_RATE_LIMIT_PROCESS
                            : LoggingHelper.REQUEST_RESULT_RATE_LIMIT_SYSTEM;
            LoggingHelper.logProfilingRequest(
                    uid,
                    profilingType,
                    params,
                    rateLimitType,
                    getRateLimiter().isRateLimiterDisabled());
        }
    }

    /**
     * Convenience method to make checking the flag for obtaining trigger type none value in code
     * cleaner. When cleaning up the system triggered flag, remove this method and inline the value.
     */
    private int getTriggerTypeNone() {
        if (Flags.systemTriggeredProfilingNew()) {
            return ProfilingTrigger.TRIGGER_TYPE_NONE;
        }
        return 0;
    }

    /** Call from application to register a callback object. */
    public void registerResultsCallback(
            boolean isGeneralCallback, IProfilingResultCallback callback) {
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

        // When a new general listener has been added, call through to {@link generalListenerAdded}.
        // This method is called directly by manager if the callback was already registered before
        // the general listener added.
        if (isGeneralCallback) {
            generalListenerAdded();
        }
    }

    /**
     * Iterate through and delete any callbacks for which binder is not alive.
     *
     * <p>Each binder object has a registered linkToDeath which also handles removal. This mechanism
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
        int callingUid = Binder.getCallingUid();
        handleQueuedResults(callingUid);
        LoggingHelper.logGlobalListenerRegister(callingUid);
    }

    /**
     * Call from application to request the stopping of an active profiling with the provided key.
     */
    public void requestCancel(long keyMostSigBits, long keyLeastSigBits) {
        String key = (new UUID(keyMostSigBits, keyLeastSigBits)).toString();
        if (!isTraceRunning(key)) {
            // No trace running, nothing to cancel.
            if (DEBUG) {
                Log.d(
                        TAG,
                        "Exited requestCancel without stopping trace key:"
                                + key
                                + " due to no trace running.");
            }
            return;
        }
        stopProfiling(key, LoggingHelper.PROFILING_STOPPED_REASON_APP_REQUESTED);
    }

    /**
     * Add the provided list of validated triggers with the provided package name and the callers
     * uid being applied to all.
     */
    public void addProfilingTriggers(
            List<ProfilingTriggerValueParcel> triggers, String packageName) {
        enforceCallerMatchesPackageName(packageName);

        int uid = Binder.getCallingUid();
        for (int i = 0; i < triggers.size(); i++) {
            ProfilingTriggerValueParcel trigger = triggers.get(i);
            addTrigger(uid, packageName, trigger.triggerType, trigger.rateLimitingPeriodHours);
        }
    }

    /** Add an all profiling trigger for the provided package name and the callers uid. */
    public void addAllProfilingTriggers(String packageName) {
        enforceCallerMatchesPackageName(packageName);

        addTrigger(Binder.getCallingUid(), packageName, ProfilingTriggerData.TRIGGER_ALL, 0);
    }

    /**
     * Remove the provided list of validated trigger codes from a process with the provided package
     * name and the uid of the caller.
     */
    public void removeProfilingTriggers(int[] triggerTypesToRemove, String packageName) {
        enforceCallerMatchesPackageName(packageName);

        SparseArray<ProfilingTriggerData> triggers =
                mAppTriggers.get(packageName, Binder.getCallingUid());

        for (int i = 0; i < triggerTypesToRemove.length; i++) {
            int index = triggers.indexOfKey(triggerTypesToRemove[i]);
            if (index >= 0) {
                triggers.removeAt(index);
            }
        }

        if (triggers.size() == 0) {
            // Nothing left, remove.
            mAppTriggers.remove(packageName, Binder.getCallingUid());
        }
    }

    /**
     * Remove all triggers from a process with the provided packagename and the uid of the caller.
     */
    public void clearProfilingTriggers(String packageName) {
        enforceCallerMatchesPackageName(packageName);

        mAppTriggers.remove(packageName, Binder.getCallingUid());
    }

    /**
     * Method called by manager, after creating a file from within application context, to send a
     * file descriptor for service to write the result of the profiling session to.
     *
     * <p>Note: only expected to be called in response to a generateFile request sent to manager.
     */
    public void receiveFileDescriptor(
            ParcelFileDescriptor fileDescriptor, long keyMostSigBits, long keyLeastSigBits) {
        List<TracingSession> sessions = mQueuedTracingResults.get(Binder.getCallingUid());
        if (sessions == null) {
            // No sessions for this uid, so no profiling result to write to this file descriptor.
            // Attempt to cleanup.
            finishReceiveFileDescriptor(
                    null,
                    fileDescriptor,
                    null,
                    null,
                    false,
                    "No profiling sessions found for process.");
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
            finishReceiveFileDescriptor(
                    session,
                    fileDescriptor,
                    null,
                    null,
                    false,
                    "No profiling sessions found for key.");
            return;
        }

        // At this point we've identified the session that has sent us this file descriptor.
        // Now, we'll create a temporary file pointing to the profiling output for that session.
        // If that file looks good, we'll copy it to the app's local file descriptor.
        File tempResultFile =
                new File(
                        TEMP_TRACE_PATH
                                + (session.getProfilingType()
                                                == ProfilingManager.PROFILING_TYPE_SYSTEM_TRACE
                                        ? session.getRedactedFileName()
                                        : session.getFileName()));
        FileInputStream tempPerfettoFileInStream = null;
        FileOutputStream appFileOutStream = null;

        try {
            if (!tempResultFile.exists() || tempResultFile.length() == 0L) {
                // The profiling process output file does not exist or is empty, nothing to copy.
                if (DEBUG) {
                    Log.d(
                            TAG,
                            "Temporary profiling output file is missing or empty, nothing to"
                                    + " copy.");
                }
                finishReceiveFileDescriptor(
                        session,
                        fileDescriptor,
                        tempPerfettoFileInStream,
                        appFileOutStream,
                        false,
                        "Profiling output missing or empty.");
                return;
            }
        } catch (SecurityException e) {
            // If we hit a security exception checking file exists or size then we won't be able to
            // copy it, attempt to cleanup and return.
            if (DEBUG) {
                Log.d(TAG, "Exception checking if temporary file exists and is non-empty", e);
            }
            finishReceiveFileDescriptor(
                    session,
                    fileDescriptor,
                    tempPerfettoFileInStream,
                    appFileOutStream,
                    false,
                    "Exception accessing temporary profiling output file.");
            return;
        }

        // Setup file streams.
        try {
            tempPerfettoFileInStream = new FileInputStream(tempResultFile);
        } catch (IOException e) {
            // IO Exception opening temp perfetto file. No result.
            if (DEBUG) Log.d(TAG, "Exception opening temp perfetto file.", e);
            finishReceiveFileDescriptor(
                    session,
                    fileDescriptor,
                    tempPerfettoFileInStream,
                    appFileOutStream,
                    false,
                    "Exception opening temporary profiling output file.");
            return;
        }

        // Obtain a file descriptor for the result file in app storage from
        // {@link ProfilingManager}
        if (fileDescriptor != null) {
            appFileOutStream = new FileOutputStream(fileDescriptor.getFileDescriptor());
        }

        if (appFileOutStream == null) {
            finishReceiveFileDescriptor(
                    session,
                    fileDescriptor,
                    tempPerfettoFileInStream,
                    appFileOutStream,
                    false,
                    "Failed to open temporary profiling output file stream.");
            return;
        }

        // Now copy the file over.
        try {
            FileUtils.copy(tempPerfettoFileInStream, appFileOutStream);
        } catch (IOException e) {
            // Exception writing to local app file. Attempt to delete the bad copy.
            deleteBadCopiedFile(session);
            if (DEBUG) Log.d(TAG, "Exception writing to local app file.", e);
            finishReceiveFileDescriptor(
                    session,
                    fileDescriptor,
                    tempPerfettoFileInStream,
                    appFileOutStream,
                    false,
                    "Exception writing to local app file.");
            return;
        }

        finishReceiveFileDescriptor(
                session, fileDescriptor, tempPerfettoFileInStream, appFileOutStream, true, null);
    }

    private void finishReceiveFileDescriptor(
            TracingSession session,
            ParcelFileDescriptor fileDescriptor,
            FileInputStream tempPerfettoFileInStream,
            FileOutputStream appFileOutStream,
            boolean succeeded,
            @Nullable String errorMessage) {
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
                session.setError(ProfilingResult.ERROR_FAILED_POST_PROCESSING, errorMessage);
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
     * <p>Call this method if a {@link TracingSession} already exists. If no session exists yet,
     * call {@link #processResultCallback} directly instead.
     *
     * @param session The session for which to callback and potentially advance.
     * @param continueAdvancing Whether to continue advancing or stop after attempting the callback.
     */
    @VisibleForTesting
    public void processTracingSessionResultCallback(
            TracingSession session, boolean continueAdvancing) {
        boolean succeeded =
                processResultCallback(
                        session.getUid(),
                        session.getKeyMostSigBits(),
                        session.getKeyLeastSigBits(),
                        session.getErrorStatus(),
                        session.getDestinationFileName(OUTPUT_FILE_RELATIVE_PATH),
                        session.getTag(),
                        session.getErrorMessage(),
                        session.getTriggerType(),
                        session.getProfilingType());

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
     * <p>Call this directly only if no {@link TracingSession} exists yet. If a session already
     * exists, call {@link #processTracingSessionResultCallback} instead.
     *
     * @return whether at least one callback was successfully sent to the app.
     */
    private boolean processResultCallback(
            int uid,
            long keyMostSigBits,
            long keyLeastSigBits,
            int status,
            @Nullable String fileResultPathAndName,
            @Nullable String tag,
            @Nullable String error,
            int triggerType,
            int profilingType) {
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
                    perUidCallbacks
                            .get(i)
                            .sendResult(
                                    fileResultPathAndName,
                                    keyMostSigBits,
                                    keyLeastSigBits,
                                    status,
                                    tag,
                                    error,
                                    triggerType);
                } else {
                    perUidCallbacks
                            .get(i)
                            .sendResult(
                                    null,
                                    keyMostSigBits,
                                    keyLeastSigBits,
                                    status,
                                    tag,
                                    error,
                                    triggerType);
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

        LoggingHelper.logProfilingResultCallbackSent(uid, profilingType, triggerType, status);

        return succeeded;
    }

    private void startProfiling(final TracingSession session) throws RuntimeException {
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
            tag = session.getTag() == null ? "" : removeInvalidFilenameChars(session.getTag());
            if (tag.length() > TAG_MAX_CHARS_FOR_FILENAME) {
                tag = tag.substring(0, TAG_MAX_CHARS_FOR_FILENAME);
            }
        } catch (IllegalArgumentException e) {
            // Request couldn't be processed. This shouldn't happen.
            if (DEBUG) Log.d(TAG, "Request couldn't be processed", e);
            session.setError(ProfilingResult.ERROR_FAILED_INVALID_REQUEST, e.getMessage());

            LoggingHelper.logProfilingRequest(
                    session.getUid(),
                    session.getProfilingType(),
                    session.getParams(),
                    LoggingHelper.REQUEST_RESULT_INVALID,
                    getRateLimiter().isRateLimiterDisabled());
            // Don't bother adding the session to the queue as there is no real value in trying to
            // deliver this error callback again later in the case that the app no longer has a
            // registered listener.
            advanceTracingSession(session, TracingState.ERROR_OCCURRED);
            return;
        }

        String baseFileName =
                OUTPUT_FILE_PREFIX
                        + (tag.isEmpty() ? "" : OUTPUT_FILE_SECTION_SEPARATOR + tag)
                        + OUTPUT_FILE_SECTION_SEPARATOR
                        + getFormattedDate();

        // Only trace files will go through the redaction process, set the name here for the file
        // that will be created later when results are processed.
        if (session.getProfilingType() == ProfilingManager.PROFILING_TYPE_SYSTEM_TRACE) {
            session.setRedactedFileName(baseFileName + OUTPUT_FILE_TRACE_SUFFIX);
        }

        session.setFileName(baseFileName + suffix);

        Process activeProfiling =
                startProfilingProcess(config, TEMP_TRACE_PATH + session.getFileName());

        if (activeProfiling != null) {
            // Profiling is running, save the session.
            session.setActiveTrace(activeProfiling);
            session.setProfilingStartTimeMs(System.currentTimeMillis());
            mActiveTracingSessions.put(session.getKey(), session);

            LoggingHelper.logProfilingRequest(
                    session.getUid(),
                    session.getProfilingType(),
                    session.getParams(),
                    LoggingHelper.REQUEST_RESULT_PROFILING_STARTED,
                    getRateLimiter().isRateLimiterDisabled());
        } else {
            if (DEBUG) {
                Log.d(TAG, "Failed to start profiling.");
            }
            session.setError(ProfilingResult.ERROR_FAILED_EXECUTING, "Trace couldn't be started");

            LoggingHelper.logProfilingRequest(
                    session.getUid(),
                    session.getProfilingType(),
                    session.getParams(),
                    LoggingHelper.REQUEST_RESULT_ERROR,
                    getRateLimiter().isRateLimiterDisabled());

            // Don't bother adding the session to the queue as there is no real value in trying to
            // deliver this error callback again later in the case that the app no longer has a
            // registered listener.
            advanceTracingSession(session, TracingState.ERROR_OCCURRED);
            return;
        }

        // Create post process runnable, store it, and schedule it.
        session.setProcessResultRunnable(
                new Runnable() {
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
     * Start a trace to be used for system triggered profiling.
     *
     * <p>This should not be called while a system triggered trace is already running. If it is
     * called with a system triggered trace in progress, this request will be dropped.
     */
    @VisibleForTesting
    public void startSystemTriggeredTrace() {
        if (!Flags.systemTriggeredProfilingNew()) {
            // Flag disabled.
            return;
        }

        if (!mAppTriggersLoaded) {
            // Until the triggers are loaded we can't create a proper config so just return.
            if (DEBUG) {
                Log.d(TAG, "System triggered trace not started due to app triggers not loaded.");
            }
            LoggingHelper.logProfilingBackgroundTraceState(
                    LoggingHelper.BACKGROUND_TRACE_STATE_NOT_STARTED_TRIGGERS_NOT_LOADED);
            return;
        }

        synchronized (mLock) {
            // Everything from the check if a system triggered trace is in progress to updating the
            // object to the new running trace should be in a single synchronized block to ensure
            // that another system triggered start is not attempted while one is in progress.

            if (mSystemTriggeredTraceProcess != null && mSystemTriggeredTraceProcess.isAlive()) {
                // Only 1 system triggered trace should be running at a time. If one is already
                // running then this should not be called, return.
                if (DEBUG) {
                    Log.d(
                            TAG,
                            "System triggered trace not started due to a system triggered trace "
                                    + "already in progress.");
                }
                LoggingHelper.logProfilingBackgroundTraceState(
                        LoggingHelper.BACKGROUND_TRACE_STATE_NOT_STARTED_ALREADY_RUNNING);
                return;
            }

            String[] packageNames = getActiveTriggerPackageNames();
            if (packageNames.length == 0) {
                // No apps have registered interest in system triggered profiling, so don't bother
                // to start a trace for it.
                if (DEBUG) {
                    Log.d(
                            TAG,
                            "System triggered trace not started due to no apps registering "
                                    + "interest");
                }
                LoggingHelper.logProfilingBackgroundTraceState(
                        LoggingHelper.BACKGROUND_TRACE_STATE_NOT_STARTED_NO_TRIGGERS_REGISTERED);
                return;
            }

            String uniqueSessionName =
                    SYSTEM_TRIGGERED_SESSION_NAME_PREFIX + System.currentTimeMillis();

            byte[] config =
                    Configs.generateSystemTriggeredTraceConfig(
                            uniqueSessionName, packageNames, mDebugPackageName != null);
            String outputFile =
                    TEMP_TRACE_PATH
                            + SYSTEM_TRIGGERED_SESSION_NAME_PREFIX
                            + OUTPUT_FILE_IN_PROGRESS
                            + OUTPUT_FILE_UNREDACTED_TRACE_SUFFIX;

            Process activeTrace = startProfilingProcess(config, outputFile);

            if (activeTrace != null) {
                mSystemTriggeredTraceProcess = activeTrace;
                mSystemTriggeredTraceUniqueSessionName = uniqueSessionName;
                mLastStartedSystemTriggeredTraceMs = System.currentTimeMillis();
                LoggingHelper.logProfilingBackgroundTraceState(
                        LoggingHelper.BACKGROUND_TRACE_STATE_STARTED);
            }
        }
    }

    /**
     * Start the actual profiling process with necessary config details.
     *
     * @return the started process if it started successfully, or null if it failed to start.
     */
    @Nullable
    private Process startProfilingProcess(byte[] config, String outputFile) {
        try {
            if (DEBUG) {
                Log.d(TAG, "Starting perfetto process profile output file=" + outputFile);
            }
            ProcessBuilder processBuilder =
                    new ProcessBuilder("/system/bin/perfetto", "-o", outputFile, "-c", "-");
            Process activeProfiling = processBuilder.start();
            activeProfiling.getOutputStream().write(config);
            activeProfiling.getOutputStream().close();
            return activeProfiling;
        } catch (Exception e) {
            // Catch all exceptions related to starting process as they'll all be handled similarly.
            if (DEBUG) Log.e(TAG, "Profiling couldn't be started", e);
            return null;
        }
    }

    /**
     * Process a trigger for a uid + package name + trigger combination. This is done by verifying
     * that a trace is active, the app has registered interest in this combo, and that both system
     * and app provided rate limiting allow for it. If confirmed, it will proceed to clone the
     * active profiling and continue processing the result.
     *
     * <p>Cloning will fork the running trace, stop the new forked trace, and output the result to a
     * separate file. This leaves the original trace running.
     */
    public void processTrigger(
            int uid, @NonNull String packageName, int triggerType, @Nullable String tag) {
        if (!Flags.systemTriggeredProfilingNew()) {
            // Flag disabled.
            return;
        }

        if (triggerType == ProfilingTrigger.TRIGGER_TYPE_APP_REQUEST_RUNNING_TRACE) {
            // If this trigger is for an app requesting the running background trace then enforce
            // that the caller and the package match.
            enforceCallerMatchesPackageName(packageName);
        } else if (mDebugPackageName == null || !packageName.equals(mDebugPackageName)) {
            // If a debug package is set and equals to the package being supplied, then this is for
            // test/debug and we do not need to validate the system caller. Otherwise, enfore that
            // the caller is system.
            enforceSystemCaller();
        }

        // Don't block the calling thread.
        getHandler()
                .post(
                        new Runnable() {
                            @Override
                            public void run() {
                                processTriggerInternal(uid, packageName, triggerType, tag);
                            }
                        });
    }

    /**
     * Internal call to process trigger, not to be called on the thread that passed the trigger in.
     */
    @VisibleForTesting
    public void processTriggerInternal(
            int uid, @NonNull String packageName, int triggerType, @Nullable String tag) {
        synchronized (mLock) {
            if (mSystemTriggeredTraceProcess == null || !mSystemTriggeredTraceProcess.isAlive()) {
                // There is no active system triggered trace so there's nothing to clone, null out
                // the session name just in case and return. This is an expected state as the
                // background trace does not run all the time.
                mSystemTriggeredTraceUniqueSessionName = null;

                if (DEBUG) {
                    Log.d(TAG, "Requested clone system triggered trace but no trace active.");
                }

                LoggingHelper.logProfilingTriggerSent(
                        uid, triggerType, LoggingHelper.TRIGGER_STATUS_NOT_RUNNING);
                return;
            }

            if (mSystemTriggeredTraceUniqueSessionName == null) {
                // If we don't have the session name then we don't know how to clone the trace so
                // stop it if it's still running and then return.
                stopSystemTriggeredTraceLocked();

                if (DEBUG) {
                    Log.d(
                            TAG,
                            "Requested clone system triggered trace but we don't have the "
                                    + "session name.");
                }

                LoggingHelper.logProfilingTriggerSent(
                        uid, triggerType, LoggingHelper.TRIGGER_STATUS_MISSING_NAME);
                return;
            }
        }

        ProfilingTriggerData trigger = getTriggerDataObject(uid, packageName, triggerType);
        if (trigger == null) {
            // No trigger object, process isn't registered for this trigger.
            LoggingHelper.logProfilingTriggerSent(
                    uid, triggerType, LoggingHelper.TRIGGER_STATUS_NOT_REGISTERED);
            return;
        }

        // Then check rate limiting, both app and system.
        if (!isTriggerRateLimitingAllowed(trigger, ProfilingManager.PROFILING_TYPE_SYSTEM_TRACE)) {
            // Logging for this return case is done within {@link isTriggerRateLimitingAllowed}.
            return;
        }

        // Now that it's approved by both rate limiters, update the last run value.
        trigger.setLastTriggeredTimeMs(System.currentTimeMillis());

        // If we made it this far, a trace is running, the app has registered interest in this
        // trigger, and rate limiting allows for capturing the result.

        // Create the file names
        String baseFileName =
                OUTPUT_FILE_PREFIX
                        + OUTPUT_FILE_SECTION_SEPARATOR
                        + OUTPUT_FILE_TRIGGER
                        + OUTPUT_FILE_FIELD_SEPARATOR
                        + triggerType
                        + OUTPUT_FILE_SECTION_SEPARATOR
                        + getFormattedDate();
        String unredactedFullName = baseFileName + OUTPUT_FILE_UNREDACTED_TRACE_SUFFIX;

        try {
            // Try to clone the running trace.
            Process clone =
                    Runtime.getRuntime()
                            .exec(
                                    new String[] {
                                        "/system/bin/perfetto",
                                        "--clone-by-name",
                                        mSystemTriggeredTraceUniqueSessionName,
                                        "--out",
                                        TEMP_TRACE_PATH + unredactedFullName
                                    });

            // Wait for cloned process to stop.
            if (!clone.waitFor(mPerfettoDestroyTimeoutMs, TimeUnit.MILLISECONDS)) {
                // Cloned process did not stop, try to stop it forcibly.
                if (DEBUG) {
                    Log.d(
                            TAG,
                            "Cloned system triggered trace didn't stop on its own, trying to "
                                    + "stop it forcibly.");
                }
                clone.destroyForcibly();

                // Wait again to see if it stops now.
                if (!clone.waitFor(mPerfettoDestroyTimeoutMs, TimeUnit.MILLISECONDS)) {
                    // Nothing more to do, result won't be ready so return.
                    if (DEBUG) Log.d(TAG, "Cloned system triggered trace timed out.");
                    LoggingHelper.logProfilingTriggerSent(
                            uid, triggerType, LoggingHelper.TRIGGER_STATUS_ERROR);
                    return;
                }
            }
        } catch (IOException | InterruptedException e) {
            // Failed. There's nothing to clean up as we haven't created a session for this clone
            // yet so just fail quietly. The result for this trigger instance combo will be lost.
            if (DEBUG) Log.d(TAG, "Failed to clone running system triggered trace.", e);
            LoggingHelper.logProfilingTriggerSent(
                    uid, triggerType, LoggingHelper.TRIGGER_STATUS_ERROR);
            return;
        }

        LoggingHelper.logProfilingTriggerSent(
                uid, triggerType, LoggingHelper.TRIGGER_STATUS_FULFILLED);

        // If we get here the clone was successful. Create a new TracingSession to track this and
        // continue moving it along the processing process.
        TracingSession session =
                new TracingSession(
                        ProfilingManager.PROFILING_TYPE_SYSTEM_TRACE,
                        uid,
                        packageName,
                        triggerType,
                        tag);
        session.setRedactedFileName(baseFileName + OUTPUT_FILE_TRACE_SUFFIX);
        session.setFileName(unredactedFullName);
        session.setProfilingStartTimeMs(System.currentTimeMillis());
        if (Flags.addRateLimiterDisabledToResult() && packageName.equals(mDebugPackageName)) {
            session.setErrorMessage(RATE_LIMITER_DISABLED_ERROR_MESSAGE);
        }
        moveSessionToQueue(session, true);
        advanceTracingSession(session, TracingState.PROFILING_FINISHED);

        maybePersistToDisk();
    }

    /**
     * Get trigger data object for a specific process/trigger combo.
     *
     * <p>Return object:
     *
     * <ul>
     *   <li>With type matching provided triggerType if the provided process has explicitly
     *       registered for that trigger.
     *   <li>With type of TRIGGER_ALL if the provided process has registered for all triggers and
     *       has not explicitly registered for the provided trigger type.
     *   <li>Null if the provided process has not registered for the specific provided triggerType
     *       nor for all trigger types.
     * </ul>
     */
    @Nullable
    @VisibleForTesting
    public ProfilingTriggerData getTriggerDataObject(
            int uid, @NonNull String packageName, int triggerType) {
        SparseArray<ProfilingTriggerData> perProcessTriggers = mAppTriggers.get(packageName, uid);
        if (perProcessTriggers == null) {
            // This uid/package hasn't registered any triggers.
            if (DEBUG) {
                Log.d(
                        TAG,
                        String.format(
                                "Profiling triggered for uid %d with no registered " + "triggers",
                                uid));
            }
            return null;
        }

        ProfilingTriggerData trigger = perProcessTriggers.get(triggerType);

        if (trigger == null) {
            // This uid hasn't registered a trigger for this type. Check if they've registered for
            // all triggers.
            trigger = perProcessTriggers.get(ProfilingTriggerData.TRIGGER_ALL);

            if (trigger == null) {
                // This uid hasn't registered a trigger for this type or for all types.
                if (DEBUG) {
                    Log.d(
                            TAG,
                            String.format(
                                    "Profiling triggered for uid %d and trigger %d, but app has not"
                                            + " registered for this trigger type or all triggers.",
                                    uid, triggerType));
                }
                return null;
            }
        }

        return trigger;
    }

    /** Check rate limiting for a potential system triggered profiling run. */
    private boolean isTriggerRateLimitingAllowed(ProfilingTriggerData trigger, int profilingType) {
        // Check app provided rate limiting.
        if (System.currentTimeMillis() - trigger.getLastTriggeredTimeMs()
                < trigger.getRateLimitingPeriodHours() * 60L * 60L * 1000L) {
            // App provided rate limiting doesn't allow for this run, return.
            if (DEBUG) {
                Log.d(
                        TAG,
                        String.format(
                                "Profiling triggered for uid %d and trigger %d but blocked"
                                        + " by app provided rate limiting ",
                                trigger.getUid(), trigger.getTriggerType()));
            }
            LoggingHelper.logProfilingTriggerSent(
                    trigger.getUid(),
                    trigger.getTriggerType(),
                    LoggingHelper.TRIGGER_STATUS_RATE_LIMIT_APP);
            return false;
        }

        // Only perform system rate limiting if this is not the debug package.
        if (!trigger.getPackageName().equals(mDebugPackageName)) {
            // Lastly, check system rate limiting.
            int systemRateLimiterResult =
                    getRateLimiter()
                            .isProfilingRequestAllowed(trigger.getUid(), profilingType, true, null);
            if (systemRateLimiterResult != RateLimiter.RATE_LIMIT_RESULT_ALLOWED) {
                // Blocked by system rate limiter, return. Since this is system triggered there is
                // no callback and therefore no need to distinguish between per app and system
                // denials within the system rate limiter.
                if (DEBUG) {
                    Log.d(
                            TAG,
                            String.format(
                                    "Profiling triggered for uid %d and trigger %d but "
                                            + "blocked by system rate limiting ",
                                    trigger.getUid(), trigger.getTriggerType()));
                }

                int rateLimitType =
                        systemRateLimiterResult == RateLimiter.RATE_LIMIT_RESULT_BLOCKED_PROCESS
                                ? LoggingHelper.TRIGGER_STATUS_RATE_LIMIT_PROCESS
                                : LoggingHelper.TRIGGER_STATUS_RATE_LIMIT_SYSTEM;
                LoggingHelper.logProfilingTriggerSent(
                        trigger.getUid(), trigger.getTriggerType(), rateLimitType);
                return false;
            }
        }

        return true;
    }

    /** Add a profiling trigger to the supporting data structure. */
    @VisibleForTesting
    public void addTrigger(
            int uid, @NonNull String packageName, int triggerType, int rateLimitingPeriodHours) {
        addTrigger(
                new ProfilingTriggerData(uid, packageName, triggerType, rateLimitingPeriodHours),
                true);
    }

    /**
     * Add a profiling trigger to the supporting data structure.
     *
     * @param trigger The trigger to add.
     * @param maybePersist Whether to persist to disk, if eligible based on frequency. This is
     *     intended to be set to false only when loading triggers from disk.
     */
    @VisibleForTesting
    public void addTrigger(ProfilingTriggerData trigger, boolean maybePersist) {
        if (!Flags.systemTriggeredProfilingNew()) {
            // Flag disabled.
            return;
        }

        if (!ProfilingTrigger.isValidRequestTriggerType(trigger.getTriggerType())
                && trigger.getTriggerType() != ProfilingTriggerData.TRIGGER_ALL) {
            Log.w(TAG, "Attempted to add invalid profiling trigger.");
            throw new IllegalArgumentException("Trigger type is not supported");
        }

        SparseArray<ProfilingTriggerData> perProcessTriggers =
                mAppTriggers.get(trigger.getPackageName(), trigger.getUid());

        if (perProcessTriggers == null) {
            perProcessTriggers = new SparseArray<ProfilingTriggerData>();
            mAppTriggers.put(trigger.getPackageName(), trigger.getUid(), perProcessTriggers);
        }

        // Only 1 trigger is allowed per uid + trigger type so this will override any previous
        // triggers of this type registered for this uid.
        perProcessTriggers.put(trigger.getTriggerType(), trigger);

        LoggingHelper.logProfilingTriggerRegister(trigger.getUid(), trigger.getTriggerType(), null);

        if (maybePersist) {
            maybePersistToDisk();
        }
    }

    /** Get a list of all package names which have registered profiling triggers. */
    private String[] getActiveTriggerPackageNames() {
        // Since only system trace is supported for triggers, we can simply grab the key set of the
        // backing map for the ProcessMap which will contain all the package names. Once other
        // profiling types are supported, we'll need to filter these more intentionally to just the
        // ones that have an associated trace trigger.
        Set<String> packageNamesSet = mAppTriggers.getMap().keySet();
        return packageNamesSet.toArray(new String[packageNamesSet.size()]);
    }

    /**
     * This method will check if the profiling subprocess is still alive. If it's still alive and
     * there is still time permitted to run, another check will be scheduled. If the process is
     * still alive but max allotted processing time has been exceeded, the profiling process will be
     * stopped and results processed and returned to client. If the profiling process is complete
     * results will be processed and returned to the client.
     */
    private void checkProfilingCompleteRescheduleIfNeeded(TracingSession session) {
        long processingTimeRemaining =
                session.getMaxProfilingTimeAllowedMs()
                        - (System.currentTimeMillis() - session.getProfilingStartTimeMs());

        if (session.getActiveTrace().isAlive() && processingTimeRemaining >= 0) {
            if (DEBUG) {
                Log.d(
                        TAG,
                        "Profiling not yet finished. processingTimeRemaining="
                                + processingTimeRemaining
                                + " reschedule check in "
                                + Math.min(mProfilingRecheckDelayMs, processingTimeRemaining));
            }
            // still running and under max allotted processing time, reschedule the check.
            getHandler()
                    .postDelayed(
                            session.getProcessResultRunnable(),
                            Math.min(mProfilingRecheckDelayMs, processingTimeRemaining));
        } else if (session.getActiveTrace().isAlive() && processingTimeRemaining < 0) {
            // still running but exceeded max allotted processing time, stop profiling and deliver
            // what results are available.
            stopProfiling(session.getKey(), LoggingHelper.PROFILING_STOPPED_REASON_TIMED_OUT);
        } else {
            // complete, process results and deliver.
            LoggingHelper.logProfilingStopped(
                    session.getUid(),
                    session.getProfilingType(),
                    session.getTriggerType(),
                    LoggingHelper.PROFILING_STOPPED_REASON_TIMED_OUT);
            session.setProcessResultRunnable(null);
            moveSessionToQueue(session, true);
            advanceTracingSession(session, TracingState.PROFILING_FINISHED);
        }
    }

    /** Stop any active profiling sessions belonging to the provided uid. */
    private void stopAllProfilingForUid(int uid, int loggingReason) {
        if (mActiveTracingSessions.isEmpty()) {
            // If there are no active traces, then there are none for this uid.
            return;
        }

        // Iterate through active sessions and stop profiling if they belong to the provided uid.
        // Note: Currently, this will only ever have 1 session.
        for (int i = 0; i < mActiveTracingSessions.size(); i++) {
            TracingSession session = mActiveTracingSessions.valueAt(i);
            if (session.getUid() == uid) {
                stopProfiling(session, loggingReason);
            }
        }
    }

    /** Stop active profiling for the given session key. */
    private void stopProfiling(String key, int loggingReason) {
        TracingSession session = mActiveTracingSessions.get(key);
        if (DEBUG) {
            Log.d(
                    TAG,
                    "stopProfiling for session="
                            + session.getFileName()
                            + " loggingReason="
                            + loggingReason);
        }
        stopProfiling(session, loggingReason);
    }

    /** Stop active profiling for the given session. */
    private void stopProfiling(TracingSession session, int loggingReason) {
        if (session == null || session.getActiveTrace() == null) {
            if (DEBUG) Log.d(TAG, "No active trace, nothing to stop.");
            return;
        }

        if (session.getProcessResultRunnable() == null) {
            if (DEBUG) {
                Log.d(
                        TAG,
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
            if (!session.getActiveTrace()
                    .waitFor(mPerfettoDestroyTimeoutMs, TimeUnit.MILLISECONDS)) {
                if (DEBUG) Log.d(TAG, "Stopping of running trace process timed out.");
                return;
            }
            if (DEBUG) {
                Log.d(TAG, "Stopped running trace process.");
            }
        } catch (InterruptedException e) {
            if (DEBUG) Log.d(TAG, "Stopping of running trace error occurred.", e);
            return;
        }

        LoggingHelper.logProfilingStopped(
                session.getUid(),
                session.getProfilingType(),
                session.getTriggerType(),
                loggingReason);

        // If we made it here the result is ready, now run the post processing runnable.
        getHandler().post(session.getProcessResultRunnable());
    }

    /** Check whether a profiling session is running. Not specific to any process. */
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
     * present in {@link mActiveTracingSessions} as they would be moved to {@link
     * mQueuedTracingResults} when profiling completes. If a session is present but not running,
     * remove it. If a session has a not alive process, try to stop it.
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

        // If we have any sessions to stop, now is the time. This is not an expected state as
        // sessions should be moved to queue if they reach this state.
        if (!sessionsToStop.isEmpty()) {
            for (int i = 0; i < sessionsToStop.size(); i++) {
                stopProfiling(sessionsToStop.get(i), LoggingHelper.PROFILING_STOPPED_REASON_ERROR);
            }
        }
    }

    /** Check whether a profiling session with the provided key is currently running. */
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
     * Begin moving result to storage by validating and then sending a request to {@link
     * ProfilingManager} for a file to write to. File will be returned as a {@link
     * ParcelFileDescriptor} via {@link sendFileDescriptor}.
     */
    @VisibleForTesting
    public void beginMoveFileToAppStorage(TracingSession session) {
        if (session.getState().getValue() >= TracingState.ERROR_OCCURRED.getValue()) {
            // This should not have happened, if the session has a state of error or later then why
            // are we trying to continue processing it? Remove from all data stores just in case.
            if (DEBUG) {
                Log.d(
                        TAG,
                        "Attempted beginMoveFileToAppStorage on a session with status error"
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

    /** Delete a file which failed to copy via ProfilingManager. */
    private void deleteBadCopiedFile(TracingSession session) {
        List<IProfilingResultCallback> perUidCallbacks = mResultCallbacks.get(session.getUid());
        for (int i = 0; i < perUidCallbacks.size(); i++) {
            try {
                String fileName =
                        session.getProfilingType() == ProfilingManager.PROFILING_TYPE_SYSTEM_TRACE
                                ? session.getRedactedFileName()
                                : session.getFileName();
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
     * <p>The new file is created by {@link ProfilingManager} from within app context. We only need
     * a single file, which can be created from any of the contexts belonging to the app that
     * requested this profiling, so it does not matter which of the requesting app's callbacks we
     * use.
     */
    @Nullable
    private void requestFileForResult(
            @NonNull List<IProfilingResultCallback> perUidCallbacks, TracingSession session) {
        String fileName =
                session.getProfilingType() == ProfilingManager.PROFILING_TYPE_SYSTEM_TRACE
                        ? session.getRedactedFileName()
                        : session.getFileName();
        for (int i = 0; i < perUidCallbacks.size(); i++) {
            try {
                IProfilingResultCallback callback = perUidCallbacks.get(i);
                if (callback.asBinder().isBinderAlive()) {
                    // Great, this one works! Call it and exit if we don't hit an exception.
                    perUidCallbacks
                            .get(i)
                            .generateFile(
                                    OUTPUT_FILE_RELATIVE_PATH,
                                    fileName,
                                    session.getKeyMostSigBits(),
                                    session.getKeyLeastSigBits());
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
        if (TextUtils.isEmpty(session.getFileName())) {
            // This should not happen. If it does, then there is no file to redact. Set error and
            // advance state.
            if (DEBUG) {
                Log.w(TAG, "Session requires redaction but has no file to redact.");
            }
            session.setError(
                    ProfilingResult.ERROR_FAILED_POST_PROCESSING,
                    "Redaction failed due to missing file.");
            advanceTracingSession(session, TracingState.ERROR_OCCURRED);
            return;
        }

        try {
            if (DEBUG) {
                Log.d(
                        TAG,
                        "Start redaction, create empty file for redactor output="
                                + session.getRedactedFileName());
            }
            // We need to create an empty file for the redaction process to write the output into.
            File emptyRedactedTraceFile = new File(TEMP_TRACE_PATH + session.getRedactedFileName());
            emptyRedactedTraceFile.createNewFile();
        } catch (Exception exception) {
            if (DEBUG) Log.e(TAG, "Creating empty redacted file failed.", exception);
            session.setError(
                    ProfilingResult.ERROR_FAILED_POST_PROCESSING,
                    "Redaction failed to create file.");
            advanceTracingSession(session, TracingState.ERROR_OCCURRED);
            return;
        }

        try {
            // Start the redaction process and log the time of start.  Redaction has
            // mRedactionMaxRuntimeAllottedMs to complete. Redaction status will be checked every
            // mRedactionCheckFrequencyMs.
            ProcessBuilder redactionProcess =
                    new ProcessBuilder(
                            "/apex/com.android.profiling/bin/trace_redactor",
                            TEMP_TRACE_PATH + session.getFileName(),
                            TEMP_TRACE_PATH + session.getRedactedFileName(),
                            session.getPackageName());
            session.setActiveRedaction(redactionProcess.start());
            session.setRedactionStartTimeMs(System.currentTimeMillis());
        } catch (Exception exception) {
            if (DEBUG) Log.e(TAG, "Redaction failed to run completely.", exception);
            session.setError(
                    ProfilingResult.ERROR_FAILED_POST_PROCESSING, "Redaction failed to complete.");
            advanceTracingSession(session, TracingState.ERROR_OCCURRED);
            return;
        }

        session.setProcessResultRunnable(
                new Runnable() {

                    @Override
                    public void run() {
                        checkRedactionStatus(session);
                    }
                });
        getHandler().postDelayed(session.getProcessResultRunnable(), mRedactionCheckFrequencyMs);
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
            session.setError(ProfilingResult.ERROR_FAILED_POST_PROCESSING, "Redaction timed out.");
            advanceTracingSession(session, TracingState.ERROR_OCCURRED);
            return;
        }

        // Schedule the next check.
        getHandler()
                .postDelayed(
                        session.getProcessResultRunnable(),
                        Math.min(
                                mRedactionCheckFrequencyMs,
                                mRedactionMaxRuntimeAllottedMs
                                        - (System.currentTimeMillis()
                                                - session.getRedactionStartTimeMs())));
    }

    private void handleRedactionComplete(TracingSession session) {
        int redactionErrorCode = session.getActiveRedaction().exitValue();
        if (redactionErrorCode != 0) {
            // Redaction process failed. This failure cannot be recovered.
            if (DEBUG) {
                Log.d(
                        TAG,
                        String.format(
                                "Redaction processed failed with error code: %s",
                                redactionErrorCode));
            }
            session.setError(
                    ProfilingResult.ERROR_FAILED_POST_PROCESSING,
                    "Redaction failed with error code: " + redactionErrorCode);
            advanceTracingSession(session, TracingState.ERROR_OCCURRED);
            return;
        }

        // At this point redaction has completed successfully it is safe to delete the
        // unredacted trace file unless {@link mKeepResultInTempDir} has been enabled.
        synchronized (mLock) {
            if (!mKeepResultInTempDir) {
                deleteProfilingFiles(
                        session, false, /* Don't delete the newly redacted file */
                        true); /* Do delete the no longer needed unredacted file.*/
            }
        }

        advanceTracingSession(session, TracingState.REDACTED);
    }

    /**
     * Handle retained temporary files due to {@link mKeepResultInTempDir} being enabled, by
     * attempting to make them publicly readable and logging their location
     */
    private void handleRetainedTempFiles(TracingSession session) {
        synchronized (mLock) {
            if (!mKeepResultInTempDir) {
                // Results are only retained if {@link mKeepResultInTempDir} is enabled, so don't
                // log the locations if it's disabled.
                return;
            }

            // For all types, output the location of the original profiling output file. For trace,
            // this will be the unredacted copy. For all other types, this will be the only output
            // file.
            boolean makeReadableSucceeded = makeFileReadable(session.getFileName());
            logRetainedFileDetails(session.getFileName(), makeReadableSucceeded);

            if (session.getProfilingType() == ProfilingManager.PROFILING_TYPE_SYSTEM_TRACE) {
                // For a trace, output the location of the redacted file.
                makeReadableSucceeded = makeFileReadable(session.getRedactedFileName());
                logRetainedFileDetails(session.getFileName(), makeReadableSucceeded);
            }
        }
    }

    /** Wrapper to log all necessary information about retained file locations. */
    private void logRetainedFileDetails(String fileName, boolean readable) {
        if (readable) {
            Log.i(TAG, "Profiling file retained at: " + TEMP_TRACE_PATH + fileName);
        } else {
            Log.i(
                    TAG,
                    "Profiling file retained at: "
                            + TEMP_TRACE_PATH
                            + fileName
                            + " | File is not publicly accessible, root access is required to"
                            + " read.");
        }
    }

    /**
     * Make the provided file within the temp trace directory publicly readable. Access is still
     * limited by selinux so only adbd will be additionally able to access the file due to this
     * change.
     *
     * @return whether making the file readable succeeded.
     */
    @SuppressWarnings("SetWorldReadable")
    private boolean makeFileReadable(String fileName) {
        try {
            File file = new File(TEMP_TRACE_PATH + fileName);
            return file.setReadable(true, false);
        } catch (Exception e) {
            Log.w(TAG, "Failed to make file readable for testing.", e);
            return false;
        }
    }

    /**
     * Called whenever a new global listener has been added to the specified uid. Attempts to
     * process queued results if present.
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
     * <p>Cleanup will attempt to delete the temporary file(s) and then remove it from the queue.
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
     * <p>Cleanup will attempt to delete the temporary file(s) and then remove it from the queue.
     */
    private void cleanupTracingSession(
            TracingSession session, @Nullable List<TracingSession> queuedSessions) {
        if (DEBUG) {
            Log.d(TAG, "cleanupTracingSession for fileName=" + session.getFileName());
        }
        synchronized (mLock) {
            if (mKeepResultInTempDir) {
                // If {@link mKeepResultInTempDir} is enabled, don't cleanup anything. Continue
                // progressing as if cleanup is complete.
                advanceTracingSession(session, TracingState.CLEANED_UP);
                return;
            }
        }

        // Delete all files
        deleteProfilingFiles(session, true, true);

        if (queuedSessions != null) {
            queuedSessions.remove(session);
            if (queuedSessions.isEmpty()) {
                mQueuedTracingResults.remove(session.getUid());
            }
        }

        advanceTracingSession(session, TracingState.CLEANED_UP);
    }

    /**
     * Attempt to delete profiling output.
     *
     * <p>If both boolean params are false, this method expectedly does nothing.
     *
     * @param deleteRedacted Whether to delete the redacted file.
     * @param deleteUnredacted Whether to delete the unredacted file.
     */
    private void deleteProfilingFiles(
            TracingSession session, boolean deleteRedacted, boolean deleteUnredacted) {
        if (deleteRedacted) {
            try {
                if (DEBUG) {
                    Log.d(TAG, "delete redacted file=" + session.getRedactedFileName());
                }
                Files.deleteIfExists(Path.of(TEMP_TRACE_PATH + session.getRedactedFileName()));
            } catch (Exception exception) {
                if (DEBUG) Log.e(TAG, "Failed to delete file.", exception);
            }
        }

        if (deleteUnredacted) {
            try {
                if (DEBUG) {
                    Log.d(TAG, "delete unredacted file session=" + session.getFileName());
                }
                Files.deleteIfExists(Path.of(TEMP_TRACE_PATH + session.getFileName()));
            } catch (Exception exception) {
                if (DEBUG) Log.e(TAG, "Failed to delete file.", exception);
            }
        }
    }

    /**
     * Move session to list of queued sessions. Removes the session from the list of active
     * sessions, if it is present.
     *
     * <p>Sessions are expected to be in the queue when their states are between PROFILING_FINISHED
     * and NOTIFIED_REQUESTER, inclusive.
     *
     * <p>Sessions should only be added to the queue with a valid profiling start time. Sessions
     * added without a valid start time may be cleaned up in middle of their execution and fail to
     * deliver any result.
     *
     * @param session the session to move to the queue
     * @param maybePersist whether to persist the queue to disk if the queue is eligible to be
     *     persisted
     */
    private void moveSessionToQueue(TracingSession session, boolean maybePersist) {
        if (DEBUG && session.getProfilingStartTimeMs() == 0) {
            Log.e(
                    TAG,
                    "Attempting to move session to queue without a start time set.",
                    new Throwable());
        }

        if (DEBUG) {
            Log.d(TAG, "Add to queue session with file=" + session.getFileName());
        }

        List<TracingSession> queuedResults = mQueuedTracingResults.get(session.getUid());
        if (queuedResults == null) {
            queuedResults = new ArrayList<TracingSession>();
            mQueuedTracingResults.put(session.getUid(), queuedResults);
        }
        queuedResults.add(session);
        mActiveTracingSessions.remove(session.getKey());

        if (maybePersist) {
            maybePersistToDisk();
        }
    }

    /**
     * Checks whether a temporary profile has been saved for a tracing session.
     *
     * @param session Tracing session to evaluate.
     * @return true if there is a temporary profile for tracing session, false otherwise.
     */
    public boolean tempProfileExists(TracingSession session) {
        File perfettoOutputFile = new File(TEMP_TRACE_PATH + session.getFileName());
        if (!perfettoOutputFile.exists()) {
            return false;
        }
        return true;
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
            mRateLimiter =
                    new RateLimiter(
                            new RateLimiter.HandlerCallback() {
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
     * Persist service data to disk following the following rules:
     *
     * <ul>
     *   <li>If a persist is already scheduled, do nothing.
     *   <li>If a persist happened within the last {@link #mPersistFrequencyMs} then schedule a
     *       persist for {@link #mPersistFrequencyMs} after the last persist.
     *   <li>If no persist has occurred yet or the most recent persist was more than {@link
     *       #mPersistFrequencyMs} ago, persist immediately.
     * </ul>
     */
    @VisibleForTesting
    public void maybePersistToDisk() {
        if (!Flags.persistQueue() && !Flags.systemTriggeredProfilingNew()) {
            // No persisting is enabled.
            return;
        }

        synchronized (mLock) {
            if (mPersistScheduled) {
                // We're already waiting on a scheduled persist job, do nothing.
                return;
            }

            if (mPersistFrequencyMs.get() != 0
                    && (System.currentTimeMillis() - mLastPersistedTimestampMs
                            < mPersistFrequencyMs.get())) {
                // Schedule the persist job.
                if (mPersistRunnable == null) {
                    mPersistRunnable =
                            new Runnable() {
                                @Override
                                public void run() {
                                    if (Flags.persistQueue()) {
                                        persistQueueToDisk();
                                    }
                                    if (Flags.systemTriggeredProfilingNew()) {
                                        persistAppTriggersToDisk();
                                    }
                                    mPersistScheduled = false;
                                }
                            };
                }
                mPersistScheduled = true;
                long persistDelay =
                        mLastPersistedTimestampMs
                                + mPersistFrequencyMs.get()
                                - System.currentTimeMillis();
                getHandler().postDelayed(mPersistRunnable, persistDelay);
                return;
            }
        }

        // If we got here then either persist frequency is 0 or it has already been longer than
        // persist frequency since the last persist. Persist immediately.
        if (Flags.persistQueue()) {
            persistQueueToDisk();
        }
        if (Flags.systemTriggeredProfilingNew()) {
            persistAppTriggersToDisk();
        }
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
                mLastPersistedTimestampMs = System.currentTimeMillis();
            }
        } catch (IOException e) {
            if (DEBUG) Log.e(TAG, "Exception writing queued results", e);
            persistFile.failWrite(out);
        }
    }

    /** Persist the current app triggers to disk. */
    @VisibleForTesting
    public void persistAppTriggersToDisk() {
        // Check if file exists
        try {
            if (mPersistAppTriggersFile == null) {
                // Try again to create the necessary files.
                if (!setupPersistAppTriggerFiles()) {
                    // No file, nowhere to save.
                    if (DEBUG) {
                        Log.d(
                                TAG,
                                "Failed setting up app triggers persist files so nowhere to save"
                                        + " to.");
                    }
                    return;
                }
            }

            if (!mPersistAppTriggersFile.exists()) {
                // File doesn't exist, try to create it.
                mPersistAppTriggersFile.createNewFile();
            }
        } catch (Exception e) {
            if (DEBUG) Log.e(TAG, "Exception accessing persisted app triggers store.", e);
            return;
        }

        // Generate proto for queue.
        ProfilingTriggersWrapper.Builder builder = ProfilingTriggersWrapper.newBuilder();

        forEachTrigger(mAppTriggers.getMap(), (trigger) -> builder.addTriggers(trigger.toProto()));

        ProfilingTriggersWrapper queuedTriggersWrapper = builder.build();

        // Write to disk
        byte[] protoBytes = queuedTriggersWrapper.toByteArray();
        AtomicFile persistFile = new AtomicFile(mPersistAppTriggersFile);
        FileOutputStream out = null;
        try {
            out = persistFile.startWrite();
            out.write(protoBytes);
            persistFile.finishWrite(out);
            synchronized (mLock) {
                mLastPersistedTimestampMs = System.currentTimeMillis();
            }
        } catch (IOException e) {
            if (DEBUG) Log.e(TAG, "Exception writing app triggers", e);
            persistFile.failWrite(out);
        }
    }

    /** Receive a callback with each of the tracked profiling triggers. */
    private void forEachTrigger(
            ArrayMap<String, SparseArray<SparseArray<ProfilingTriggerData>>> triggersOuterMap,
            Consumer<ProfilingTriggerData> callback) {

        for (int i = 0; i < triggersOuterMap.size(); i++) {
            SparseArray<SparseArray<ProfilingTriggerData>> triggerUidList =
                    triggersOuterMap.valueAt(i);

            for (int j = 0; j < triggerUidList.size(); j++) {
                int uidKey = triggerUidList.keyAt(j);
                SparseArray<ProfilingTriggerData> triggersList = triggerUidList.get(uidKey);

                if (triggersList != null) {
                    for (int k = 0; k < triggersList.size(); k++) {
                        int triggerTypeKey = triggersList.keyAt(k);
                        ProfilingTriggerData trigger = triggersList.get(triggerTypeKey);

                        if (trigger != null) {
                            callback.accept(trigger);
                        }
                    }
                }
            }
        }
    }

    /** Handle updates to debug package config value. */
    @GuardedBy("mLock")
    private void handleDebugPackageChangeLocked(String newDebugPackageName) {
        if (newDebugPackageName == null) {

            // Debug package has been set to null, check whether it was null previously.
            if (mDebugPackageName != null) {

                // New null state is a changed from previous state, disable debug mode.
                mDebugPackageName = null;
                stopSystemTriggeredTraceLocked();
            }
            // If new state is unchanged from previous null state, do nothing.
        } else {

            // Debug package has been set with a value. Stop running system triggered trace if
            // applicable so we can start a new one that will have most up to date package names.
            // This should not be called when the debug package name matches the old one as
            // device config should not be sending an update for a value change when the value
            // remains the same, but no need to check as the best experience for caller is to always
            // stop the current trace and start a new one for most up to date package list.
            stopSystemTriggeredTraceLocked();

            // Now update the debug package name and start the system triggered trace.
            mDebugPackageName = newDebugPackageName;
            startSystemTriggeredTrace();
        }
    }

    /**
     * Stop the system triggered trace.
     *
     * <p>Locked because {link mSystemTriggeredTraceProcess} is guarded and all callers are already
     * locked.
     */
    @GuardedBy("mLock")
    private void stopSystemTriggeredTraceLocked() {
        // If the trace is alive, stop it.
        if (mSystemTriggeredTraceProcess != null) {
            if (mSystemTriggeredTraceProcess.isAlive()) {
                mSystemTriggeredTraceProcess.destroyForcibly();
                LoggingHelper.logProfilingBackgroundTraceState(
                        LoggingHelper.BACKGROUND_TRACE_STATE_STOPPED);
            }
            mSystemTriggeredTraceProcess = null;
        }

        // Set session name to null.
        mSystemTriggeredTraceUniqueSessionName = null;
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
                stopAllProfilingForUid(mUid, LoggingHelper.PROFILING_STOPPED_REASON_APP_DIED);
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
