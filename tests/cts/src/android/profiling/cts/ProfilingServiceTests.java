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

package android.profiling.cts;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

import android.app.Instrumentation;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Handler;
import android.os.IProfilingResultCallback;
import android.os.ParcelFileDescriptor;
import android.os.ProfilingManager;
import android.os.ProfilingResult;
import android.os.profiling.DeviceConfigHelper;
import android.os.profiling.ProfilingService;
import android.os.profiling.RateLimiter;
import android.os.profiling.TracingSession;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.compatibility.common.util.SystemUtil;

import com.google.errorprone.annotations.FormatMethod;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.File;
import java.util.UUID;

/**
 * Tests in this class are for testing the ProfilingService directly without the need to get a
 * reference to the service via the call to getSystemService().
 */

@RunWith(AndroidJUnit4.class)
public final class ProfilingServiceTests {

    private static final String APP_FILE_PATH = "/data/user/0/com.profiling.test/files";
    private static final String APP_PACKAGE_NAME = "com.profiling.test";
    private static final String REQUEST_TAG = "some unique string";

    private static final String OVERRIDE_DEVICE_CONFIG_INT = "device_config put %s %s %d";

    // Key most and least significant bits are used to generate a unique key specific to each
    // request. Key is used to pair request back to caller and callbacks so test to keep consistent.
    private static final long KEY_MOST_SIG_BITS = 456l;
    private static final long KEY_LEAST_SIG_BITS = 123l;

    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    @Mock private PackageManager mPackageManager;
    @Mock private Process mActiveTrace;

    private Context mContext = ApplicationProvider.getApplicationContext();
    private Instrumentation mInstrumentation;
    private ProfilingService mProfilingService;
    private RateLimiter mRateLimiter;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mInstrumentation = InstrumentationRegistry.getInstrumentation();
        mContext = spy(ApplicationProvider.getApplicationContext());
        mProfilingService = spy(new ProfilingService(mContext));
        mRateLimiter = spy(new RateLimiter(new RateLimiter.HandlerCallback() {
            @Override
            public Handler obtainHandler() {
                return null;
            }
        }));
        doReturn(mPackageManager).when(mContext).getPackageManager();
        mProfilingService.mRateLimiter = mRateLimiter;
        doReturn(APP_PACKAGE_NAME).when(mPackageManager).getNameForUid(anyInt());
    }

    /** Test that registering binder callbacks works as expected. */
    @Test
    public void testRegisterResultCallback() {
        ProfilingResultCallback callback = new ProfilingResultCallback();

        // Register callback.
        mProfilingService.registerResultsCallback(callback);

        // Confirm callback is registered.
        assertEquals(callback, mProfilingService.mResultCallbacks.get(Binder.getCallingUid()));
    }

    /** Test that only the callback belonging to the requesting uid is triggered. */
    @Test
    public void testRequestProfiling_OnlyRequestingProcessCallbackTriggered() {
        // Mock traces running check to simulate collection running so it fails early.
        doReturn(true).when(mProfilingService).areAnyTracesRunning();

        ProfilingResultCallback callback = new ProfilingResultCallback();
        ProfilingResultCallback mockProcessCallback = new ProfilingResultCallback();
        int mockProcessUid = 12345;

        // Register callback.
        mProfilingService.registerResultsCallback(callback);

        // Add other process callback manually to mock uid.
        mProfilingService.mResultCallbacks.put(mockProcessUid, mockProcessCallback);

        // Confirm both callbacks are registered.
        assertEquals(callback, mProfilingService.mResultCallbacks.get(Binder.getCallingUid()));
        assertEquals(mockProcessCallback, mProfilingService.mResultCallbacks.get(mockProcessUid));

        // Kick off request.
        mProfilingService.requestProfiling(ProfilingManager.PROFILING_TYPE_JAVA_HEAP_DUMP, null,
                APP_FILE_PATH, REQUEST_TAG, KEY_MOST_SIG_BITS, KEY_LEAST_SIG_BITS);

        // Confirm callbacks was triggered for callback registered to this process.
        assertTrue(callback.mResultSent);

        // Confirm callbacks was not triggered for callback registered to other process.
        assertFalse(mockProcessCallback.mResultSent);
    }

    /**
     * Test that requesting profiling while another profiling is in progress fails with correct
     * error codes.
     */
    @Test
    public void testRequestProfiling_ProfilingRunning_Fails() {
        // Mock traces running check to simulate collection running.
        doReturn(true).when(mProfilingService).areAnyTracesRunning();

        // Register callback.
        ProfilingResultCallback callback = new ProfilingResultCallback();
        mProfilingService.registerResultsCallback(callback);

        // Kick off request.
        mProfilingService.requestProfiling(ProfilingManager.PROFILING_TYPE_JAVA_HEAP_DUMP, null,
                APP_FILE_PATH, REQUEST_TAG, KEY_MOST_SIG_BITS, KEY_LEAST_SIG_BITS);

        // Confirm result matches failure expectation.
        confirmResultCallback(callback, null, KEY_MOST_SIG_BITS, KEY_LEAST_SIG_BITS,
                ProfilingResult.ERROR_FAILED_PROFILING_IN_PROGRESS, REQUEST_TAG, false);
    }

    /**
     * Test that requesting profiling with an invalid request byte array fails with correct error
     * codes.
     */
    @Test
    public void testRequestProfiling_InvalidRequest_Fails() {
        // Bypass traces running check, we're not testing that here.
        doReturn(false).when(mProfilingService).areAnyTracesRunning();

        // Register callback.
        ProfilingResultCallback callback = new ProfilingResultCallback();
        mProfilingService.registerResultsCallback(callback);

        // Kick off request.
        mProfilingService.requestProfiling(-1, null, APP_FILE_PATH, REQUEST_TAG,
                KEY_MOST_SIG_BITS, KEY_LEAST_SIG_BITS);

        // Confirm result matches failure expectation.
        confirmResultCallback(callback, null, KEY_MOST_SIG_BITS, KEY_LEAST_SIG_BITS,
                ProfilingResult.ERROR_FAILED_INVALID_REQUEST, REQUEST_TAG, true);
    }

    /** Test that requesting where we cannot access the package name fails. */
    @Test
    public void testRequestProfiling_PackageNameNotFound_Fails() {
        // Mock getNameForUid to simulate failure case.
        doReturn(null).when(mPackageManager).getNameForUid(anyInt());

        // Register callback.
        ProfilingResultCallback callback = new ProfilingResultCallback();
        mProfilingService.registerResultsCallback(callback);

        // Kick off request.
        mProfilingService.requestProfiling(ProfilingManager.PROFILING_TYPE_JAVA_HEAP_DUMP, null,
                APP_FILE_PATH, REQUEST_TAG, KEY_MOST_SIG_BITS, KEY_LEAST_SIG_BITS);

        // Confirm result matches failure expectation.
        confirmResultCallback(callback, null, KEY_MOST_SIG_BITS, KEY_LEAST_SIG_BITS,
                ProfilingResult.ERROR_UNKNOWN, REQUEST_TAG, true);
    }

    /** Test that failing rate limiting blocks trace from running. */
    @Test
    public void testRequestProfiling_RateLimitBlocked_Fails() {
        // Bypass traces running check, we're not testing that here.
        doReturn(false).when(mProfilingService).areAnyTracesRunning();

        // Mock rate limiter result to simulate failure case.
        doReturn(RateLimiter.RATE_LIMIT_RESULT_BLOCKED_PROCESS).when(mRateLimiter)
              .isProfilingRequestAllowed(anyInt(), anyInt(), any());

        // Register callback.
        ProfilingResultCallback callback = new ProfilingResultCallback();
        mProfilingService.registerResultsCallback(callback);

        // Kick off request.
        mProfilingService.requestProfiling(ProfilingManager.PROFILING_TYPE_JAVA_HEAP_DUMP, null,
                APP_FILE_PATH, REQUEST_TAG, KEY_MOST_SIG_BITS, KEY_LEAST_SIG_BITS);

        // Confirm result matches failure expectation.
        confirmResultCallback(callback, null, KEY_MOST_SIG_BITS, KEY_LEAST_SIG_BITS,
                ProfilingResult.ERROR_FAILED_RATE_LIMIT_PROCESS, REQUEST_TAG, false);
    }

    /** Test that if we can't contact Perfetto, we'll see an error callback. */
    @Test
    public void testRequestProfiling_Allowed_PerfettoPermissions_Fails() {
        // Throw a RuntimeException when we try to query Perfetto for running traces.
        // This implies that we can't contact Perfetto.
        doThrow(RuntimeException.class).when(mProfilingService).areAnyTracesRunning();

        // Register callback.
        ProfilingResultCallback callback = new ProfilingResultCallback();
        mProfilingService.registerResultsCallback(callback);

        // Kick off request.
        mProfilingService.requestProfiling(ProfilingManager.PROFILING_TYPE_JAVA_HEAP_DUMP, null,
                APP_FILE_PATH, REQUEST_TAG, KEY_MOST_SIG_BITS, KEY_LEAST_SIG_BITS);

        // Perfetto cannot be run from this context, ensure it was attempted and failed permissions.
        confirmResultCallback(callback, null, KEY_MOST_SIG_BITS, KEY_LEAST_SIG_BITS,
                ProfilingResult.ERROR_UNKNOWN, REQUEST_TAG, true);
        assertEquals("Error communicating with perfetto", callback.mError);
    }

    /** Test that checking if any traces are running works when trace is running. */
    @Test
    public void testAreAnyTracesRunning_True() {
        // Ensure no active tracing sessions tracked.
        mProfilingService.mTracingSessions.clear();
        assertFalse(mProfilingService.areAnyTracesRunning());

        // Create a tracing session.
        TracingSession tracingSession = new TracingSession(
                ProfilingManager.PROFILING_TYPE_JAVA_HEAP_DUMP, null, APP_FILE_PATH, 123,
                APP_PACKAGE_NAME, REQUEST_TAG, KEY_MOST_SIG_BITS, KEY_LEAST_SIG_BITS);

        // Mock tracing session to be running.
        doReturn(true).when(mActiveTrace).isAlive();

        // Add trace to session and session to ProfilingService tracked sessions.
        tracingSession.setActiveTrace(mActiveTrace);
        mProfilingService.mTracingSessions.put(
                (new UUID(KEY_MOST_SIG_BITS, KEY_LEAST_SIG_BITS)).toString(), tracingSession);

        // Confirm check returns that a trace is running.
        assertTrue(mProfilingService.areAnyTracesRunning());
    }

    /** Test that checking if any traces are running works when trace is not running. */
    @Test
    public void testAreAnyTracesRunning_False() {
        mProfilingService.mTracingSessions.clear();
        assertFalse(mProfilingService.areAnyTracesRunning());

        TracingSession tracingSession = new TracingSession(
                ProfilingManager.PROFILING_TYPE_JAVA_HEAP_DUMP, null, APP_FILE_PATH, 123,
                APP_PACKAGE_NAME, REQUEST_TAG, KEY_MOST_SIG_BITS, KEY_LEAST_SIG_BITS);
        mProfilingService.mTracingSessions.put(
                (new UUID(KEY_MOST_SIG_BITS, KEY_LEAST_SIG_BITS)).toString(), tracingSession);
        assertFalse(mProfilingService.areAnyTracesRunning());
    }

    /** Test that request cancel trace does nothing if no trace is running. */
    @Test
    public void testRequestCancel_NotRunning() {
        // Ensure no active tracing sessions tracked.
        mProfilingService.mTracingSessions.clear();
        assertFalse(mProfilingService.areAnyTracesRunning());

        // Register callback.
        ProfilingResultCallback callback = new ProfilingResultCallback();
        mProfilingService.registerResultsCallback(callback);

        // Request cancellation.
        mProfilingService.requestCancel(KEY_MOST_SIG_BITS, KEY_LEAST_SIG_BITS);

        // Confirm callback was not triggerd with a result because there was no trace to stop.
        assertFalse(callback.mResultSent);
    }

    /** Test that rate limiter correctly persists and restores data. */
    @Test
    public void testRateLimiter_PersistAndRestore() throws Exception {
        // Update DeviceConfig defaults to high enough limits, cost of 1, and persist frequency 0.
        overrideRateLimiterDefaults(5, 10, 20, 50, 50, 100, 1, 1, 1, 1, 0);

        // Override file path because test app context that can't access /data/system/
        mRateLimiter.mPersistStoreDir = new File(mContext.getFilesDir(), "testdir");
        mRateLimiter.mPersistStoreDir.mkdir();
        mRateLimiter.mPersistFile = new File(mRateLimiter.mPersistStoreDir, "testfile");

        // Remove all records
        long currentTimeMillis = System.currentTimeMillis();
        mRateLimiter.mPastRunsHour.removeOlderThan(currentTimeMillis);
        mRateLimiter.mPastRunsDay.removeOlderThan(currentTimeMillis);
        mRateLimiter.mPastRunsWeek.removeOlderThan(currentTimeMillis);

        // Add some records. Since records are being added directly rather than through normal
        // request flow, this will not trigger a persist regardless of persist frequency.
        mRateLimiter.mPastRunsHour.add(1, 1, currentTimeMillis - 1000);
        mRateLimiter.mPastRunsDay.add(1, 1, currentTimeMillis - 1000);
        mRateLimiter.mPastRunsWeek.add(1, 1, currentTimeMillis - 1000);
        mRateLimiter.mPastRunsDay.add(2, 1, currentTimeMillis - (60 * 60 * 1000) - 1000);
        mRateLimiter.mPastRunsWeek.add(2, 1, currentTimeMillis - (60 * 60 * 1000) - 1000);
        mRateLimiter.mPastRunsWeek.add(2, 1, currentTimeMillis - (24 * 60 * 60 * 1000) - 1000);

        // Store a copy of the backing data for each type
        RateLimiter.CollectionEntry[] hourEntriesOriginal =
                mRateLimiter.mPastRunsHour.getEntriesCopy();
        RateLimiter.CollectionEntry[] dayEntriesOriginal =
                mRateLimiter.mPastRunsDay.getEntriesCopy();
        RateLimiter.CollectionEntry[] weekEntriesOriginal =
                mRateLimiter.mPastRunsWeek.getEntriesCopy();

        // Confirm collections are correct size.
        assertEquals(1, hourEntriesOriginal.length);
        assertEquals(2, dayEntriesOriginal.length);
        assertEquals(3, weekEntriesOriginal.length);

        // Now persist the records to disk
        mRateLimiter.persistToDisk();

        // Remove all records again
        currentTimeMillis = System.currentTimeMillis();
        mRateLimiter.mPastRunsHour.removeOlderThan(currentTimeMillis);
        mRateLimiter.mPastRunsDay.removeOlderThan(currentTimeMillis);
        mRateLimiter.mPastRunsWeek.removeOlderThan(currentTimeMillis);

        // Confirm records have been removed
        assertEquals(0, mRateLimiter.mPastRunsHour.getEntriesCopy().length);
        assertEquals(0, mRateLimiter.mPastRunsDay.getEntriesCopy().length);
        assertEquals(0, mRateLimiter.mPastRunsWeek.getEntriesCopy().length);

        // Now load the persisted records from disk
        mRateLimiter.loadFromDisk();

        // Finally, verify the records.
        confirmRateLimiterEntriesEqual(hourEntriesOriginal,
                mRateLimiter.mPastRunsHour.getEntriesCopy());
        confirmRateLimiterEntriesEqual(dayEntriesOriginal,
                mRateLimiter.mPastRunsDay.getEntriesCopy());
        confirmRateLimiterEntriesEqual(weekEntriesOriginal,
                mRateLimiter.mPastRunsWeek.getEntriesCopy());
    }

    // TODO: b/333579817 - Add more rate limiter tests

    private void overrideRateLimiterDefaults(int systemHour, int processHour, int systemDay,
            int processDay, int systemWeek, int processWeek, int costHeapDump, int costHeapProfile,
            int costStackSampling, int costSystemTrace, int persistToDiskFrequency)
            throws Exception {
        executeShellCmd(OVERRIDE_DEVICE_CONFIG_INT, DeviceConfigHelper.NAMESPACE,
                DeviceConfigHelper.MAX_COST_SYSTEM_1_HOUR, systemHour);
        executeShellCmd(OVERRIDE_DEVICE_CONFIG_INT, DeviceConfigHelper.NAMESPACE,
                DeviceConfigHelper.MAX_COST_PROCESS_1_HOUR, processHour);
        executeShellCmd(OVERRIDE_DEVICE_CONFIG_INT, DeviceConfigHelper.NAMESPACE,
                DeviceConfigHelper.MAX_COST_SYSTEM_24_HOUR, systemDay);
        executeShellCmd(OVERRIDE_DEVICE_CONFIG_INT, DeviceConfigHelper.NAMESPACE,
                DeviceConfigHelper.MAX_COST_PROCESS_24_HOUR, processDay);
        executeShellCmd(OVERRIDE_DEVICE_CONFIG_INT, DeviceConfigHelper.NAMESPACE,
                DeviceConfigHelper.MAX_COST_SYSTEM_7_DAY, systemWeek);
        executeShellCmd(OVERRIDE_DEVICE_CONFIG_INT, DeviceConfigHelper.NAMESPACE,
                DeviceConfigHelper.MAX_COST_PROCESS_7_DAY, processWeek);
        executeShellCmd(OVERRIDE_DEVICE_CONFIG_INT, DeviceConfigHelper.NAMESPACE,
                DeviceConfigHelper.COST_JAVA_HEAP_DUMP, costHeapDump);
        executeShellCmd(OVERRIDE_DEVICE_CONFIG_INT, DeviceConfigHelper.NAMESPACE,
                DeviceConfigHelper.COST_HEAP_PROFILE, costHeapProfile);
        executeShellCmd(OVERRIDE_DEVICE_CONFIG_INT, DeviceConfigHelper.NAMESPACE,
                DeviceConfigHelper.COST_STACK_SAMPLING, costStackSampling);
        executeShellCmd(OVERRIDE_DEVICE_CONFIG_INT, DeviceConfigHelper.NAMESPACE,
                DeviceConfigHelper.COST_SYSTEM_TRACE, costSystemTrace);
        executeShellCmd(OVERRIDE_DEVICE_CONFIG_INT, DeviceConfigHelper.NAMESPACE,
                DeviceConfigHelper.PERSIST_TO_DISK_FREQUENCY_MS, persistToDiskFrequency);
    }

    @FormatMethod
    private String executeShellCmd(String cmdFormat, Object... args) throws Exception {
        String cmd = String.format(cmdFormat, args);
        return SystemUtil.runShellCommand(mInstrumentation, cmd);
    }

    private void confirmRateLimiterEntriesEqual(RateLimiter.CollectionEntry[] collectionOne,
            RateLimiter.CollectionEntry[] collectionTwo) {
        assertEquals(collectionOne.length, collectionTwo.length);
        for (int i = 0; i < collectionOne.length; i++) {
            assertEquals(collectionOne[i].mUid, collectionTwo[i].mUid);
            assertEquals(collectionOne[i].mCost, collectionTwo[i].mCost);
            assertEquals(collectionOne[i].mTimestamp, collectionTwo[i].mTimestamp);
        }
    }

    /** Confirm that all fields returned by callback match expectation. */
    private void confirmResultCallback(ProfilingResultCallback callback, String resultFile,
            long keyMostSigBits, long keyLeastSigBits, int status, String tag,
            boolean errorExpected) {
        assertEquals(resultFile, callback.mResultFile);
        assertEquals(keyMostSigBits, callback.mKeyMostSigBits);
        assertEquals(keyLeastSigBits, callback.mKeyLeastSigBits);
        assertEquals(status, callback.mStatus);
        assertEquals(tag, callback.mTag);
        if (errorExpected) {
            assertNotNull(callback.mError);
        } else {
            assertNull(callback.mError);
        }
    }

    public static class ProfilingResultCallback extends IProfilingResultCallback.Stub {
        boolean mResultSent = false;
        boolean mFileRequested = false;
        public String mResultFile;
        public long mKeyMostSigBits;
        public long mKeyLeastSigBits;
        public int mStatus;
        public String mTag;
        public String mError;
        @Override
        public void sendResult(String resultFile, long keyMostSigBits,
                long keyLeastSigBits, int status, String tag, String error) {
            mResultSent = true;
            mResultFile = resultFile;
            mKeyMostSigBits = keyMostSigBits;
            mKeyLeastSigBits = keyLeastSigBits;
            mStatus = status;
            mTag = tag;
            mError = error;
        }
        @Override
        public ParcelFileDescriptor generateFile(String filePathAbsolute, String fileName) {
            mFileRequested = true;
            return null;
        }
    }
}


