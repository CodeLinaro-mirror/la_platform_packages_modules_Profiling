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

import static android.os.profiling.ProfilingService.TracingState;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.app.Instrumentation;
import android.content.Context;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IProfilingResultCallback;
import android.os.ProfilingManager;
import android.os.ProfilingResult;
import android.os.profiling.DeviceConfigHelper;
import android.os.profiling.ProfilingService;
import android.os.profiling.ProfilingTrigger;
import android.os.profiling.RateLimiter;
import android.os.profiling.TracingSession;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.platform.test.flag.junit.SetFlagsRule;
import android.util.SparseArray;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.compatibility.common.util.SystemUtil;

import com.google.errorprone.annotations.FormatMethod;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Tests in this class are for testing the ProfilingService directly without the need to get a
 * reference to the service via the call to getSystemService().
 */

@RunWith(AndroidJUnit4.class)
public final class ProfilingServiceTests {

    private static final String APP_PACKAGE_NAME = "com.android.profiling.tests";
    private static final String REQUEST_TAG = "some unique string";

    private static final String OVERRIDE_DEVICE_CONFIG_INT = "device_config put %s %s %d";
    private static final String GET_DEVICE_CONFIG = "device_config get %s %s";
    private static final String DELETE_DEVICE_CONFIG = "device_config delete %s %s";

    private static final String PERSIST_TEST_DIR = "testdir";
    private static final String PERSIST_TEST_FILE = "testfile";

    // Key most and least significant bits are used to generate a unique key specific to each
    // request. Key is used to pair request back to caller and callbacks so test to keep consistent.
    private static final long KEY_MOST_SIG_BITS = 456l;
    private static final long KEY_LEAST_SIG_BITS = 123l;

    private static final int FAKE_UID = 12345;
    private static final int FAKE_UID_2 = 12346;

    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();
    @Rule public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

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
        mProfilingService.mRateLimiter = mRateLimiter;

        // Override the persist file/directory, for both queue and rate limiter, and instead point
        // to our own file/directory in app storage, since the test app context can't access
        // /data/system
        doReturn(true).when(mRateLimiter).setupPersistFiles();
        mRateLimiter.mPersistStoreDir = new File(mContext.getFilesDir(), PERSIST_TEST_DIR);
        mRateLimiter.mPersistStoreDir.mkdir();
        mRateLimiter.mPersistFile = new File(mRateLimiter.mPersistStoreDir, PERSIST_TEST_FILE);

        doReturn(true).when(mProfilingService).setupPersistQueueFiles();
        mProfilingService.mPersistStoreDir =
                new File(mContext.getFilesDir(), PERSIST_TEST_DIR);
        // Same dir for both, no need to create the 2nd time.
        mProfilingService.mPersistQueueFile =
                new File(mProfilingService.mPersistStoreDir, PERSIST_TEST_FILE);

        doReturn(true).when(mProfilingService).setupPersistAppTriggerFiles();
        mProfilingService.mPersistAppTriggersFile =
                new File(mProfilingService.mPersistStoreDir, PERSIST_TEST_FILE);

        // Since we use mock files we can't rely on the setup call that would typically come from
        // initialization of rate limiter, so trigger setup manually.
        mRateLimiter.setupFromPersistedData();
    }

    @After
    public void cleanup() throws Exception {
        // Delete any local persist files.
        if (mRateLimiter.mPersistFile != null) {
            mRateLimiter.mPersistFile.delete();
        }
        if (mProfilingService.mPersistQueueFile != null) {
            // This doesn't really do anything as the 2 file objects point to the same actual file
            // on disk, but just in case that changes try the delete here too.
            mProfilingService.mPersistQueueFile.delete();
        }

        // Remove any overrides set for period.
        executeShellCmd(DELETE_DEVICE_CONFIG, DeviceConfigHelper.NAMESPACE,
                DeviceConfigHelper.SYSTEM_TRIGGERED_TRACE_MIN_PERIOD_SECONDS);
        executeShellCmd(DELETE_DEVICE_CONFIG, DeviceConfigHelper.NAMESPACE,
                DeviceConfigHelper.SYSTEM_TRIGGERED_TRACE_MAX_PERIOD_SECONDS);
    }

    /** Test that registering binder callbacks works as expected. */
    @Test
    public void testRegisterResultCallback() {
        ProfilingResultCallback callback = new ProfilingResultCallback();

        // Register callback.
        mProfilingService.registerResultsCallback(true, callback);

        // Confirm callback is registered.
        assertEquals(callback,
                mProfilingService.mResultCallbacks.get(Binder.getCallingUid()).get(0));
    }

    /** Test that only the callback belonging to the requesting uid is triggered. */
    @Test
    public void testRequestProfiling_OnlyRequestingProcessCallbackTriggered() {
        // Mock traces running check to simulate collection running so it fails early.
        doReturn(true).when(mProfilingService).areAnyTracesRunning();

        ProfilingResultCallback callback = new ProfilingResultCallback();
        ProfilingResultCallback mockProcessCallback = new ProfilingResultCallback();

        // Register callback.
        mProfilingService.registerResultsCallback(false, callback);

        // Add other process callback manually to mock uid.
        List<IProfilingResultCallback> callbacks = Arrays.asList(mockProcessCallback);
        mProfilingService.mResultCallbacks.put(FAKE_UID, callbacks);

        // Confirm both callbacks are registered.
        assertEquals(callback,
                mProfilingService.mResultCallbacks.get(Binder.getCallingUid()).get(0));
        assertEquals(mockProcessCallback,
                mProfilingService.mResultCallbacks.get(FAKE_UID).get(0));

        // Kick off request.
        mProfilingService.requestProfiling(ProfilingManager.PROFILING_TYPE_JAVA_HEAP_DUMP, null,
                REQUEST_TAG, KEY_MOST_SIG_BITS, KEY_LEAST_SIG_BITS, APP_PACKAGE_NAME);

        // Confirm callbacks was triggered for callback registered to this process.
        assertTrue(callback.mResultSent);

        // Confirm callbacks was not triggered for callback registered to other process.
        assertFalse(mockProcessCallback.mResultSent);
    }

    /** Test that multiple callbacks belonging to the requesting uid are all triggered. */
    @Test
    public void testRequestProfiling_MultipleCallbackTriggered() {
        // Mock traces running check to simulate collection running so it fails early.
        doReturn(true).when(mProfilingService).areAnyTracesRunning();

        ProfilingResultCallback callbackOne = new ProfilingResultCallback();
        ProfilingResultCallback callbackTwo = new ProfilingResultCallback();

        // Register callbacks.
        mProfilingService.registerResultsCallback(true, callbackOne);
        mProfilingService.registerResultsCallback(true, callbackTwo);

        // Confirm both callbacks are registered.
        assertEquals(callbackOne,
                mProfilingService.mResultCallbacks.get(Binder.getCallingUid()).get(0));
        assertEquals(callbackTwo,
                mProfilingService.mResultCallbacks.get(Binder.getCallingUid()).get(1));

        // Kick off request.
        mProfilingService.requestProfiling(ProfilingManager.PROFILING_TYPE_JAVA_HEAP_DUMP, null,
                REQUEST_TAG, KEY_MOST_SIG_BITS, KEY_LEAST_SIG_BITS, APP_PACKAGE_NAME);

        // Confirm callbacks was triggered for callback registered to this process.
        assertTrue(callbackOne.mResultSent);
        assertTrue(callbackTwo.mResultSent);
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
        mProfilingService.registerResultsCallback(false, callback);

        // Kick off request.
        mProfilingService.requestProfiling(ProfilingManager.PROFILING_TYPE_JAVA_HEAP_DUMP, null,
                REQUEST_TAG, KEY_MOST_SIG_BITS, KEY_LEAST_SIG_BITS, APP_PACKAGE_NAME);

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
        mProfilingService.registerResultsCallback(false, callback);

        // Kick off request.
        mProfilingService.requestProfiling(-1, null, REQUEST_TAG, KEY_MOST_SIG_BITS,
                KEY_LEAST_SIG_BITS, APP_PACKAGE_NAME);

        // Confirm result matches failure expectation.
        confirmResultCallback(callback, null, KEY_MOST_SIG_BITS, KEY_LEAST_SIG_BITS,
                ProfilingResult.ERROR_FAILED_INVALID_REQUEST, REQUEST_TAG, true);
    }

    /** Test that requesting where we cannot access the package name fails. */
    @Test
    public void testRequestProfiling_PackageNameNotFound_Fails() {
        // Register callback.
        ProfilingResultCallback callback = new ProfilingResultCallback();
        mProfilingService.registerResultsCallback(false, callback);

        // Kick off request.
        mProfilingService.requestProfiling(ProfilingManager.PROFILING_TYPE_JAVA_HEAP_DUMP, null,
                REQUEST_TAG, KEY_MOST_SIG_BITS, KEY_LEAST_SIG_BITS, null);

        // Confirm result matches failure expectation.
        confirmResultCallback(callback, null, KEY_MOST_SIG_BITS, KEY_LEAST_SIG_BITS,
                ProfilingResult.ERROR_UNKNOWN, REQUEST_TAG, true);
    }

    /** Test that requesting with a package name not associated with the calling uid fails. */
    @Test
    public void testRequestProfiling_PackageNameNotAssociatedWithCaller_Fails() {
        // Register callback.
        ProfilingResultCallback callback = new ProfilingResultCallback();
        mProfilingService.registerResultsCallback(false, callback);

        // Kick off request.
        mProfilingService.requestProfiling(ProfilingManager.PROFILING_TYPE_JAVA_HEAP_DUMP, null,
                REQUEST_TAG, KEY_MOST_SIG_BITS, KEY_LEAST_SIG_BITS, "not.my.application");

        // Confirm result matches failure expectation.
        confirmResultCallback(callback, null, KEY_MOST_SIG_BITS, KEY_LEAST_SIG_BITS,
                ProfilingResult.ERROR_FAILED_INVALID_REQUEST, REQUEST_TAG, true);
    }

    /** Test that failing rate limiting blocks trace from running. */
    @Test
    public void testRequestProfiling_RateLimitBlocked_Fails() {
        // Bypass traces running check, we're not testing that here.
        doReturn(false).when(mProfilingService).areAnyTracesRunning();

        // Mock rate limiter result to simulate failure case.
        doReturn(RateLimiter.RATE_LIMIT_RESULT_BLOCKED_PROCESS).when(mRateLimiter)
              .isProfilingRequestAllowed(anyInt(), anyInt(), eq(false), any());

        // Register callback.
        ProfilingResultCallback callback = new ProfilingResultCallback();
        mProfilingService.registerResultsCallback(false, callback);

        // Kick off request.
        mProfilingService.requestProfiling(ProfilingManager.PROFILING_TYPE_JAVA_HEAP_DUMP, null,
                REQUEST_TAG, KEY_MOST_SIG_BITS, KEY_LEAST_SIG_BITS, APP_PACKAGE_NAME);

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
        mProfilingService.registerResultsCallback(false, callback);

        // Kick off request.
        mProfilingService.requestProfiling(ProfilingManager.PROFILING_TYPE_JAVA_HEAP_DUMP, null,
                REQUEST_TAG, KEY_MOST_SIG_BITS, KEY_LEAST_SIG_BITS, APP_PACKAGE_NAME);

        // Perfetto cannot be run from this context, ensure it was attempted and failed permissions.
        confirmResultCallback(callback, null, KEY_MOST_SIG_BITS, KEY_LEAST_SIG_BITS,
                ProfilingResult.ERROR_UNKNOWN, REQUEST_TAG, true);
        assertEquals("Error communicating with perfetto", callback.mError);
    }

    /** Test that checking if any traces are running works when trace is running. */
    @Test
    public void testAreAnyTracesRunning_True() {
        // Ensure no active tracing sessions tracked.
        mProfilingService.mActiveTracingSessions.clear();
        assertFalse(mProfilingService.areAnyTracesRunning());

        // Create a tracing session.
        TracingSession tracingSession = new TracingSession(
                ProfilingManager.PROFILING_TYPE_JAVA_HEAP_DUMP, null, 123, APP_PACKAGE_NAME,
                REQUEST_TAG, KEY_MOST_SIG_BITS, KEY_LEAST_SIG_BITS);

        // Mock tracing session to be running.
        doReturn(true).when(mActiveTrace).isAlive();

        // Add trace to session and session to ProfilingService tracked sessions.
        tracingSession.setActiveTrace(mActiveTrace);
        mProfilingService.mActiveTracingSessions.put(
                (new UUID(KEY_MOST_SIG_BITS, KEY_LEAST_SIG_BITS)).toString(), tracingSession);

        // Confirm check returns that a trace is running.
        assertTrue(mProfilingService.areAnyTracesRunning());
    }

    /** Test that checking if any traces are running works when trace is not running. */
    @Test
    public void testAreAnyTracesRunning_False() {
        mProfilingService.mActiveTracingSessions.clear();
        assertEquals(0, mProfilingService.mActiveTracingSessions.size());
        assertFalse(mProfilingService.areAnyTracesRunning());

        TracingSession tracingSession = new TracingSession(
                ProfilingManager.PROFILING_TYPE_JAVA_HEAP_DUMP, null, 123, APP_PACKAGE_NAME,
                REQUEST_TAG, KEY_MOST_SIG_BITS, KEY_LEAST_SIG_BITS);
        mProfilingService.mActiveTracingSessions.put(
                (new UUID(KEY_MOST_SIG_BITS, KEY_LEAST_SIG_BITS)).toString(), tracingSession);

        // Confirm no traces are running because the 1 we added is not in a running state.
        assertFalse(mProfilingService.areAnyTracesRunning());
    }

    /** Test that cleaning up active traces list works correctly. */
    @Test
    public void testActiveTracesCleanup() {
        mProfilingService.mActiveTracingSessions.clear();
        assertEquals(0, mProfilingService.mActiveTracingSessions.size());
        assertFalse(mProfilingService.areAnyTracesRunning());

        TracingSession tracingSession = new TracingSession(
                ProfilingManager.PROFILING_TYPE_JAVA_HEAP_DUMP, null, 123, APP_PACKAGE_NAME,
                REQUEST_TAG, KEY_MOST_SIG_BITS, KEY_LEAST_SIG_BITS);
        mProfilingService.mActiveTracingSessions.put(
                (new UUID(KEY_MOST_SIG_BITS, KEY_LEAST_SIG_BITS)).toString(), tracingSession);

        // Confirm the session was added.
        assertEquals(1, mProfilingService.mActiveTracingSessions.size());

        // Confirm no traces are running because the 1 we added is not in a running state.
        assertFalse(mProfilingService.areAnyTracesRunning());

        // Now run a cleanup of the non running session.
        mProfilingService.cleanupActiveTracingSessions();

        // Confirm the non running session was cleaned up.
        assertEquals(0, mProfilingService.mActiveTracingSessions.size());
    }

    /** Test that request cancel trace does nothing if no trace is running. */
    @Test
    public void testRequestCancel_NotRunning() {
        // Ensure no active tracing sessions tracked.
        mProfilingService.mActiveTracingSessions.clear();
        assertFalse(mProfilingService.areAnyTracesRunning());

        // Register callback.
        ProfilingResultCallback callback = new ProfilingResultCallback();
        mProfilingService.registerResultsCallback(false, callback);

        // Request cancellation.
        mProfilingService.requestCancel(KEY_MOST_SIG_BITS, KEY_LEAST_SIG_BITS);

        // Confirm callback was not triggerd with a result because there was no trace to stop.
        assertFalse(callback.mResultSent);
    }

    /** Test that rate limiter correctly persists and restores data. */
    @Test
    public void testRateLimiter_PersistAndRestore() throws Exception {
        overrideRateLimiterDefaults();

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

        // Now load the persisted records from disk using the overridden files we set up earlier.
        mRateLimiter.setupFromPersistedData();

        // Finally, verify the records.
        confirmRateLimiterEntriesEqual(hourEntriesOriginal,
                mRateLimiter.mPastRunsHour.getEntriesCopy());
        confirmRateLimiterEntriesEqual(dayEntriesOriginal,
                mRateLimiter.mPastRunsDay.getEntriesCopy());
        confirmRateLimiterEntriesEqual(weekEntriesOriginal,
                mRateLimiter.mPastRunsWeek.getEntriesCopy());
    }

    /**
     * Test that rate limiter handles no persist file correctly.
     *
     * - Test setup ensures records are empty and that no file exists.
     * - Rate limiter is expected to handle no file as a "profiling has never been used" state,
     *       resulting in the records remaining empty and data load being marked complete.
     */
    @Test
    public void testRateLimiter_NoPersistFile() throws Exception {
        overrideRateLimiterDefaults();

        // Ensure file doesn't exist
        mRateLimiter.mPersistFile.delete();

        // Remove all records
        long currentTimeMillis = System.currentTimeMillis();
        mRateLimiter.mPastRunsHour.removeOlderThan(currentTimeMillis);
        mRateLimiter.mPastRunsDay.removeOlderThan(currentTimeMillis);
        mRateLimiter.mPastRunsWeek.removeOlderThan(currentTimeMillis);

        // Confirm records have been removed
        assertEquals(0, mRateLimiter.mPastRunsHour.getEntriesCopy().length);
        assertEquals(0, mRateLimiter.mPastRunsDay.getEntriesCopy().length);
        assertEquals(0, mRateLimiter.mPastRunsWeek.getEntriesCopy().length);

        // Now load the persisted records from disk using the overridden files we set up earlier.
        mRateLimiter.setupFromPersistedData();

        // Confirm load is marked complete
        assertTrue(mRateLimiter.mDataLoaded.get());

        // Confirm records are still empty
        assertEquals(0, mRateLimiter.mPastRunsHour.getEntriesCopy().length);
        assertEquals(0, mRateLimiter.mPastRunsDay.getEntriesCopy().length);
        assertEquals(0, mRateLimiter.mPastRunsWeek.getEntriesCopy().length);
    }

    /**
     * Test that rate limiter handles an empty persist file correctly.
     *
     * - Test setup ensures records are empty and that an empty file exists.
     * - Rate limiter is expected to handle the empty file as a "profiling has never been used"
     *       state, resulting in the records remaining empty and data load being marked complete.
     */
    @Test
    public void testRateLimiter_EmptyPersistFile() throws Exception {
        overrideRateLimiterDefaults();

        // Ensure file exists and is empty
        mRateLimiter.mPersistFile.delete();
        mRateLimiter.mPersistFile.createNewFile();
        assertTrue(mRateLimiter.mPersistFile.exists());

        // Remove all records
        long currentTimeMillis = System.currentTimeMillis();
        mRateLimiter.mPastRunsHour.removeOlderThan(currentTimeMillis);
        mRateLimiter.mPastRunsDay.removeOlderThan(currentTimeMillis);
        mRateLimiter.mPastRunsWeek.removeOlderThan(currentTimeMillis);

        // Confirm records have been removed
        assertEquals(0, mRateLimiter.mPastRunsHour.getEntriesCopy().length);
        assertEquals(0, mRateLimiter.mPastRunsDay.getEntriesCopy().length);
        assertEquals(0, mRateLimiter.mPastRunsWeek.getEntriesCopy().length);

        // Now load the persisted records from disk using the overridden files we set up earlier.
        mRateLimiter.setupFromPersistedData();

        // Confirm load is marked complete
        assertTrue(mRateLimiter.mDataLoaded.get());

        // Confirm records are still empty
        assertEquals(0, mRateLimiter.mPastRunsHour.getEntriesCopy().length);
        assertEquals(0, mRateLimiter.mPastRunsDay.getEntriesCopy().length);
        assertEquals(0, mRateLimiter.mPastRunsWeek.getEntriesCopy().length);
    }

    /**
     * Test that rate limiter handles a invalid persist file with remediation success correctly.
     *
     * - Test setup ensures records are empty, that a file with contents not of expected proto
     *       type exists, and that remediation succeeds.
     * - Rate limiter is expected to handle the invalid file contents by attempting remediation and
     *       succeeding, resulting in stub records being added and data load being marked complete.
     */
    @Test
    public void testRateLimiter_BadFile_RemediateSuccess() throws Exception {
        overrideRateLimiterDefaults();

        // Ensure file exists and is written with data not matching proto expectation
        mRateLimiter.mPersistFile.delete();
        mRateLimiter.mPersistFile.createNewFile();
        FileOutputStream fileOutputStream = new FileOutputStream(mRateLimiter.mPersistFile);
        fileOutputStream.write("some text that is definitely not a proto".getBytes());
        fileOutputStream.close();

        // Remove all records
        long currentTimeMillis = System.currentTimeMillis();
        mRateLimiter.mPastRunsHour.removeOlderThan(currentTimeMillis);
        mRateLimiter.mPastRunsDay.removeOlderThan(currentTimeMillis);
        mRateLimiter.mPastRunsWeek.removeOlderThan(currentTimeMillis);

        // Confirm records have been removed
        assertEquals(0, mRateLimiter.mPastRunsHour.getEntriesCopy().length);
        assertEquals(0, mRateLimiter.mPastRunsDay.getEntriesCopy().length);
        assertEquals(0, mRateLimiter.mPastRunsWeek.getEntriesCopy().length);

        // Now load the persisted records from disk using the overridden files we set up earlier.
        mRateLimiter.setupFromPersistedData();

        // Confirm load is marked complete
        assertTrue(mRateLimiter.mDataLoaded.get());

        // Confirm fake records have been added
        assertEquals(1, mRateLimiter.mPastRunsHour.getEntriesCopy().length);
        assertEquals(Integer.MAX_VALUE, mRateLimiter.mPastRunsHour.getEntriesCopy()[0].mCost);
        assertEquals(1, mRateLimiter.mPastRunsDay.getEntriesCopy().length);
        assertEquals(Integer.MAX_VALUE, mRateLimiter.mPastRunsDay.getEntriesCopy()[0].mCost);
        assertEquals(1, mRateLimiter.mPastRunsWeek.getEntriesCopy().length);
        assertEquals(Integer.MAX_VALUE, mRateLimiter.mPastRunsWeek.getEntriesCopy()[0].mCost);
    }

    /**
     * Test that rate limiter handles a invalid persist file with remediation failure correctly.
     *
     * - Test setup ensures records are empty, that a file with contents not of expected proto
     *       type exists, and that remediation fails.
     * - Rate limiter is expected to handle the invalid file contents by attempting remediation and
     *       failing, resulting in records remaining empty and data load being marked incomplete.
     */
    @Test
    public void testRateLimiter_BadFile_RemediateFailure() throws Exception {
        overrideRateLimiterDefaults();

        // Mock failure of handleBadFile.
        doReturn(false).when(mRateLimiter).handleBadFile();

        // Ensure file exists and is written with data not matching proto expectation
        mRateLimiter.mPersistFile.delete();
        mRateLimiter.mPersistFile.createNewFile();
        FileOutputStream fileOutputStream = new FileOutputStream(mRateLimiter.mPersistFile);
        fileOutputStream.write("some text that is definitely not a proto".getBytes());
        fileOutputStream.close();

        // Remove all records
        long currentTimeMillis = System.currentTimeMillis();
        mRateLimiter.mPastRunsHour.removeOlderThan(currentTimeMillis);
        mRateLimiter.mPastRunsDay.removeOlderThan(currentTimeMillis);
        mRateLimiter.mPastRunsWeek.removeOlderThan(currentTimeMillis);

        // Confirm records have been removed
        assertEquals(0, mRateLimiter.mPastRunsHour.getEntriesCopy().length);
        assertEquals(0, mRateLimiter.mPastRunsDay.getEntriesCopy().length);
        assertEquals(0, mRateLimiter.mPastRunsWeek.getEntriesCopy().length);

        // Now load the persisted records from disk using the overridden files we set up earlier.
        mRateLimiter.setupFromPersistedData();

        // Confirm load is marked incomplete
        assertFalse(mRateLimiter.mDataLoaded.get());

        // Confirm records are still empty
        assertEquals(0, mRateLimiter.mPastRunsHour.getEntriesCopy().length);
        assertEquals(0, mRateLimiter.mPastRunsDay.getEntriesCopy().length);
        assertEquals(0, mRateLimiter.mPastRunsWeek.getEntriesCopy().length);
    }

    // TODO: b/333579817 - Add more rate limiter tests

    /** Test that advancing state in forward direction works as expected. */
    @Test
    public void testSessionState_AdvanceForwardSucceeds() {
        // Create a session with some state.
        TracingSession session = new TracingSession(
                ProfilingManager.PROFILING_TYPE_HEAP_PROFILE,
                new Bundle(),
                FAKE_UID,
                APP_PACKAGE_NAME,
                REQUEST_TAG,
                KEY_LEAST_SIG_BITS,
                KEY_MOST_SIG_BITS);
        session.setState(TracingState.PROFILING_FINISHED);

        // Trigger an advance to a subsequent state.
        mProfilingService.advanceTracingSession(session, TracingState.ERROR_OCCURRED);

        // Ensure it does try to advance.
        verify(mProfilingService, times(1)).processTracingSessionResultCallback(any(), eq(true));
    }

    /** Test that advancing state in backwards direction does not work. */
    @Test
    public void testSessionState_AdvanceBackwardsFails() {
        // Override cleanupTracingSession to do nothing or it will call through to
        // advanceTracingSession after cleanup and we need to confirm that advanceTracingSession is
        // not immediately called.
        doNothing().when(mProfilingService).cleanupTracingSession(any());

        // Create a session with some state.
        TracingSession session = new TracingSession(
                ProfilingManager.PROFILING_TYPE_HEAP_PROFILE,
                new Bundle(),
                FAKE_UID,
                APP_PACKAGE_NAME,
                REQUEST_TAG,
                KEY_LEAST_SIG_BITS,
                KEY_MOST_SIG_BITS);
        session.setState(TracingState.APPROVED);

        // Attempt to advance to earlier state.
        mProfilingService.advanceTracingSession(session, TracingState.REQUESTED);

        // Ensure service determines something is broken, does not try to advance, and triggers a
        // cleanup.
        verify(mProfilingService, times(1)).cleanupTracingSession(any());
    }

    /**
     * Test that advancing state with a null new state and no retries (i.e. not from queue retry)
     * does not work. */
    @Test
    public void testSessionState_AdvanceNullFails() {
        // Override cleanupTracingSession to do nothing or it will call through to
        // advanceTracingSession after cleanup and we need to confirm that advanceTracingSession is
        // not immediately called.
        doNothing().when(mProfilingService).cleanupTracingSession(any());

        // Create a session with some state.
        TracingSession session = new TracingSession(
                ProfilingManager.PROFILING_TYPE_HEAP_PROFILE,
                new Bundle(),
                FAKE_UID,
                APP_PACKAGE_NAME,
                REQUEST_TAG,
                KEY_LEAST_SIG_BITS,
                KEY_MOST_SIG_BITS);
        session.setState(TracingState.REQUESTED);

        // Make sure retry count is 0 (default value).
        assertEquals(0, session.getRetryCount());

        // Attempt to advance with null new state.
        mProfilingService.advanceTracingSession(session, null);

        // Ensure service determines something is broken, does not try to advance, and triggers a
        // cleanup.
        verify(mProfilingService, times(1)).cleanupTracingSession(any());
    }

    /**
     * Test that persisting the queue and then reloading it from disk works correctly, loading the
     * previous queue and all persistable fields.
     */
    @Test
    @EnableFlags(android.os.profiling.Flags.FLAG_PERSIST_QUEUE)
    public void testQueuePersist_PersistAndRestore() {
        // Clear the queue.
        mProfilingService.mQueuedTracingResults.clear();

        // A 2nd fake uid so we can have records belonging to multiple uids.
        int fakeUid2 = FAKE_UID + 1;

        // Create 3 fake sessions with various fields set on each.
        TracingSession session1 = new TracingSession(
                ProfilingManager.PROFILING_TYPE_HEAP_PROFILE,
                new Bundle(),
                FAKE_UID,
                APP_PACKAGE_NAME,
                REQUEST_TAG,
                KEY_LEAST_SIG_BITS,
                KEY_MOST_SIG_BITS);
        session1.setProfilingStartTimeMs(System.currentTimeMillis());
        session1.setState(TracingState.PROFILING_FINISHED);

        TracingSession session2 = new TracingSession(
                ProfilingManager.PROFILING_TYPE_JAVA_HEAP_DUMP,
                new Bundle(),
                FAKE_UID,
                APP_PACKAGE_NAME,
                REQUEST_TAG,
                KEY_LEAST_SIG_BITS,
                KEY_MOST_SIG_BITS);
        session2.setProfilingStartTimeMs(System.currentTimeMillis());
        session2.setState(TracingState.ERROR_OCCURRED);
        session2.setError(ProfilingResult.ERROR_FAILED_POST_PROCESSING, "some error message");

        TracingSession session3 = new TracingSession(
                ProfilingManager.PROFILING_TYPE_SYSTEM_TRACE,
                new Bundle(),
                fakeUid2,
                APP_PACKAGE_NAME,
                REQUEST_TAG,
                KEY_LEAST_SIG_BITS,
                KEY_MOST_SIG_BITS);
        session3.setProfilingStartTimeMs(System.currentTimeMillis());
        session3.setState(TracingState.REDACTED);

        // Create 2 session lists.
        List<TracingSession> sessionListUid1 = new ArrayList<TracingSession>();
        List<TracingSession> sessionListUid2 = new ArrayList<TracingSession>();

        // Add 2 sessions to the first list and 1 to the second list.
        sessionListUid1.add(session1);
        sessionListUid1.add(session2);
        sessionListUid2.add(session3);

        // Add each of the lists to the queue.
        mProfilingService.mQueuedTracingResults.put(FAKE_UID, sessionListUid1);
        mProfilingService.mQueuedTracingResults.put(fakeUid2, sessionListUid2);

        // Trigger a persist.
        mProfilingService.persistQueueToDisk();

        // Confirm file was written to
        confirmNonEmptyFileExists(mProfilingService.mPersistQueueFile);

        // Clear the queue so we can ensure it is reloaded properly.
        mProfilingService.mQueuedTracingResults.clear();
        assertEquals(0, mProfilingService.mQueuedTracingResults.size());

        // Load the queue from disk.
        mProfilingService.loadQueueFromPersistedData();

        // Finally, verify the loaded contents match the ones that were persisted.
        // First check that the queue contains 2 lists, as added above.
        assertEquals(2, mProfilingService.mQueuedTracingResults.size());

        // Now, confirm that there are 2 queued results belonging to the first uid, and 1 belonging
        // to the 2nd uid, as defined above.
        assertEquals(2, mProfilingService.mQueuedTracingResults.get(FAKE_UID).size());
        assertEquals(1, mProfilingService.mQueuedTracingResults.get(fakeUid2).size());

        // Lastly, check that each loaded session is equal its persisted counterpart.
        confirmTracingSessionsEqual(session1,
                mProfilingService.mQueuedTracingResults.get(FAKE_UID).get(0));
        confirmTracingSessionsEqual(session2,
                mProfilingService.mQueuedTracingResults.get(FAKE_UID).get(1));
        confirmTracingSessionsEqual(session3,
                mProfilingService.mQueuedTracingResults.get(fakeUid2).get(0));
    }

    /**
     * Test that loading queue with no persist file works as intended with no records added and
     * correct methods called.
     */
    @Test
    @EnableFlags(android.os.profiling.Flags.FLAG_PERSIST_QUEUE)
    public void testQueuePersist_NoPersistFile() {
        // Clear the queue.
        mProfilingService.mQueuedTracingResults.clear();

        // Ensure the file doesn't exist.
        mProfilingService.mPersistQueueFile.delete();
        assertFalse(mProfilingService.mPersistQueueFile.exists());

        // Load the queue from disk.
        mProfilingService.loadQueueFromPersistedData();

        // Ensure queue still empty.
        assertEquals(0, mProfilingService.mQueuedTracingResults.size());
        verify(mProfilingService, times(0)).deletePersistQueueFile();
    }

    /**
     * Test that loading queue with an empty persist file works as intended with no records added
     * and correct methods called.
     */
    @Test
    @EnableFlags(android.os.profiling.Flags.FLAG_PERSIST_QUEUE)
    public void testQueuePersist_EmptyPersistFile() throws Exception {
        // Clear the queue.
        mProfilingService.mQueuedTracingResults.clear();

        // Ensure the file exists and is empty.
        mProfilingService.mPersistQueueFile.delete();
        assertFalse(mProfilingService.mPersistQueueFile.exists());
        mProfilingService.mPersistQueueFile.createNewFile();
        assertTrue(mProfilingService.mPersistQueueFile.exists());
        assertEquals(0L, mProfilingService.mPersistQueueFile.length());

        // Load the queue from disk.
        mProfilingService.loadQueueFromPersistedData();

        // Ensure that the queue is still empty and that a delete was attempted as expected for the
        // bad file state.
        assertEquals(0, mProfilingService.mQueuedTracingResults.size());
        verify(mProfilingService, times(1)).deletePersistQueueFile();
    }

    /**
     * Test that loading queue with a invalid persist file works as intended with no records added
     * and correct methods called.
     */
    @Test
    @EnableFlags(android.os.profiling.Flags.FLAG_PERSIST_QUEUE)
    public void testQueuePersist_BadPersistFile() throws Exception {
        // Clear the queue.
        mProfilingService.mQueuedTracingResults.clear();

        // Ensure the file exists and is empty.
        mProfilingService.mPersistQueueFile.delete();
        mProfilingService.mPersistQueueFile.createNewFile();
        FileOutputStream fileOutputStream = new FileOutputStream(
                mProfilingService.mPersistQueueFile);
        fileOutputStream.write("some text that is definitely not a proto".getBytes());
        fileOutputStream.close();
        confirmNonEmptyFileExists(mProfilingService.mPersistQueueFile);

        // Load the queue from disk.
        mProfilingService.loadQueueFromPersistedData();

        // Ensure that the queue is still empty and that a delete was attempted as expected for the
        // bad file state.
        assertEquals(0, mProfilingService.mQueuedTracingResults.size());
        verify(mProfilingService, times(1)).deletePersistQueueFile();
    }

    /**
     * Test that persisting queue respects the frequency defined, allowing the persist on the first
     * instance but rejecting the subsequent persist.
     *
     * While this test focuses on queue persist, the logic for respect frequency is shared with
     * triggers so this test covers both.
     */
    @Test
    @EnableFlags(android.os.profiling.Flags.FLAG_PERSIST_QUEUE)
    public void testQueuePersist_RespectFrequency() throws Exception {
        // Override persist frequency to something large.
        updateDeviceConfigAndWaitForChange(DeviceConfigHelper.NAMESPACE,
                DeviceConfigHelper.PERSIST_TO_DISK_FREQUENCY_MS, 60 * 60 * 1000);

        // Clear the queue.
        mProfilingService.mQueuedTracingResults.clear();

        // Populate the queue.
        List<TracingSession> sessionList = new ArrayList<TracingSession>();
        TracingSession session1 = new TracingSession(
                ProfilingManager.PROFILING_TYPE_HEAP_PROFILE,
                new Bundle(),
                FAKE_UID,
                APP_PACKAGE_NAME,
                REQUEST_TAG,
                KEY_LEAST_SIG_BITS,
                KEY_MOST_SIG_BITS);
        session1.setProfilingStartTimeMs(System.currentTimeMillis());
        session1.setState(TracingState.PROFILING_FINISHED);
        TracingSession session2 = new TracingSession(
                ProfilingManager.PROFILING_TYPE_JAVA_HEAP_DUMP,
                new Bundle(),
                FAKE_UID,
                APP_PACKAGE_NAME,
                REQUEST_TAG,
                KEY_LEAST_SIG_BITS,
                KEY_MOST_SIG_BITS);
        session2.setProfilingStartTimeMs(System.currentTimeMillis());
        session2.setState(TracingState.ERROR_OCCURRED);
        session2.setError(ProfilingResult.ERROR_FAILED_POST_PROCESSING, "some error message");

        sessionList.add(session1);
        sessionList.add(session2);
        mProfilingService.mQueuedTracingResults.put(FAKE_UID, sessionList);

        // Trigger a persist.
        mProfilingService.maybePersistToDisk();

        // Confirm that it actually persisted.
        verify(mProfilingService, times(1)).persistQueueToDisk();
        assertTrue(mProfilingService.mPersistQueueFile.exists());

        // Delete the file so we can confirm the next call does nothing.
        assertTrue(mProfilingService.mPersistQueueFile.delete());

        // Finally, trigger another persist.
        mProfilingService.maybePersistToDisk();

        // And confirm the persist did not immediately run.
        assertFalse(mProfilingService.mPersistQueueFile.exists());
        // Verify with same value as earlier so we know it didn't get triggered again.
        verify(mProfilingService, times(1)).persistQueueToDisk();
    }

    /**
     * Test that persists that are scheduled for the future due to a persist having recently
     * occurred, occur at a future time as expected.
     *
     * While this test focuses on queue persist, the logic for scheduling is shared with triggers so
     * this test covers both.
     */
    @Test
    @EnableFlags(android.os.profiling.Flags.FLAG_PERSIST_QUEUE)
    public void testQueuePersist_Scheduling() throws Exception {
        // Override persist frequency to 5 seconds that way we can confirm both that the persist did
        // not happen immediately and that it did eventually happen. This is the time from the first
        // call to maybePersistToDisk until the next call to the same method for the scheduling
        // of the next persist to occur as expected, rather than immediately persisting.
        updateDeviceConfigAndWaitForChange(DeviceConfigHelper.NAMESPACE,
                DeviceConfigHelper.PERSIST_TO_DISK_FREQUENCY_MS, 5 * 1000);

        // Clear the queue.
        mProfilingService.mQueuedTracingResults.clear();

        // Populate the queue.
        TracingSession session = new TracingSession(
                ProfilingManager.PROFILING_TYPE_HEAP_PROFILE,
                new Bundle(),
                FAKE_UID,
                APP_PACKAGE_NAME,
                REQUEST_TAG,
                KEY_LEAST_SIG_BITS,
                KEY_MOST_SIG_BITS);
        session.setProfilingStartTimeMs(System.currentTimeMillis());
        session.setState(TracingState.PROFILING_FINISHED);

        List<TracingSession> sessionList = new ArrayList<TracingSession>();
        sessionList.add(session);
        mProfilingService.mQueuedTracingResults.put(FAKE_UID, sessionList);

        // Trigger a persist.
        mProfilingService.maybePersistToDisk();

        // Confirm that it actually persisted.
        assertTrue(mProfilingService.mPersistQueueFile.exists());

        // Delete the file so that we can later use its existence to confirm whether the next
        // persist occurred.
        assertTrue(mProfilingService.mPersistQueueFile.delete());

        // Trigger another persist.
        mProfilingService.maybePersistToDisk();

        // And confirm the persist did not immediately run.
        assertFalse(mProfilingService.mPersistQueueFile.exists());

        // Wait 1 second longer than the configured delay to be sure the persist had time to finish.
        sleep(6 * 1000);

        // Finally, confirm that the file now exists.
        assertTrue(mProfilingService.mPersistQueueFile.exists());
    }

    /**
     * Test that persisting app triggers and then reloading them from disk works correctly, loading
     * all previous triggers.
     */
    @Test
    @EnableFlags(android.os.profiling.Flags.FLAG_SYSTEM_TRIGGERED_PROFILING_NEW)
    public void testAppTriggersPersist_PersistAndRestore() {
        // First, clear the data structure.
        mProfilingService.mAppTriggers.getMap().clear();

        // Create 3 triggers belonging to 2 uids. Add a last triggered time to one of them.
        ProfilingTrigger trigger1 = new ProfilingTrigger(FAKE_UID, APP_PACKAGE_NAME, 1, 0);

        ProfilingTrigger trigger2 = new ProfilingTrigger(FAKE_UID, APP_PACKAGE_NAME, 2, 1);
        trigger2.setLastTriggeredTimeMs(123L);

        ProfilingTrigger trigger3 = new ProfilingTrigger(FAKE_UID_2, APP_PACKAGE_NAME, 1, 2);

        // Group into sparse arrays by uid.
        SparseArray<ProfilingTrigger> triggerArray1 = new SparseArray<ProfilingTrigger>();
        triggerArray1.put(1, trigger1);
        triggerArray1.put(2, trigger2);

        SparseArray<ProfilingTrigger> triggerArray2 = new SparseArray<ProfilingTrigger>();
        triggerArray2.put(1, trigger3);

        mProfilingService.mAppTriggers.put(APP_PACKAGE_NAME, FAKE_UID, triggerArray1);
        mProfilingService.mAppTriggers.put(APP_PACKAGE_NAME, FAKE_UID_2, triggerArray2);

        // Trigger a persist.
        mProfilingService.persistAppTriggersToDisk();

        // Confirm file was written to
        confirmNonEmptyFileExists(mProfilingService.mPersistAppTriggersFile);

        // Clear app triggers so we can ensure it is reloaded properly.
        mProfilingService.mAppTriggers.getMap().clear();
        assertEquals(0, mProfilingService.mAppTriggers.getMap().size());

        // Load app triggers from disk.
        mProfilingService.loadAppTriggersFromPersistedData();

        // Finally, verify the loaded contents match the ones that were persisted.
        confirmProfilingTriggerEquals(trigger1,
                mProfilingService.mAppTriggers.get(APP_PACKAGE_NAME, FAKE_UID).get(1));
        confirmProfilingTriggerEquals(trigger2,
                mProfilingService.mAppTriggers.get(APP_PACKAGE_NAME, FAKE_UID).get(2));
        confirmProfilingTriggerEquals(trigger3,
                mProfilingService.mAppTriggers.get(APP_PACKAGE_NAME, FAKE_UID_2).get(1));
    }

    /**
     * Test that loading app triggers with no persist file works as intended with no triggers added,
     * loaded set to true, and correct methods called.
     */
    @Test
    @EnableFlags(android.os.profiling.Flags.FLAG_SYSTEM_TRIGGERED_PROFILING_NEW)
    public void testAppTriggersPersist_NoPersistFile() {
        // First, clear the data structure.
        mProfilingService.mAppTriggers.getMap().clear();

        // Ensure the file doesn't exist.
        mProfilingService.mPersistAppTriggersFile.delete();
        assertFalse(mProfilingService.mPersistAppTriggersFile.exists());

        // Load app triggers from disk.
        mProfilingService.loadAppTriggersFromPersistedData();

        // Ensure that the triggers are still empty, that loaded was set to true, and that a delete
        // was not attempted as there was no file to delete.
        assertEquals(0, mProfilingService.mAppTriggers.getMap().size());
        assertTrue(mProfilingService.mAppTriggersLoaded);
        verify(mProfilingService, times(0)).deletePersistAppTriggersFile();
    }

    /**
     * Test that loading app triggers with an empty persist file works as intended with no triggers
     * added, loaded set to true, and correct methods called.
     */
    @Test
    @EnableFlags(android.os.profiling.Flags.FLAG_PERSIST_QUEUE)
    public void testAppTriggersPersist_EmptyPersistFile() throws Exception {
        // First, clear the data structure.
        mProfilingService.mAppTriggers.getMap().clear();

        // Ensure the file exists and is empty.
        mProfilingService.mPersistAppTriggersFile.delete();
        assertFalse(mProfilingService.mPersistAppTriggersFile.exists());
        mProfilingService.mPersistAppTriggersFile.createNewFile();
        assertTrue(mProfilingService.mPersistAppTriggersFile.exists());
        assertEquals(0L, mProfilingService.mPersistAppTriggersFile.length());

        // Load app triggers from disk.
        mProfilingService.loadAppTriggersFromPersistedData();

        // Ensure that the triggers are still empty, that loaded was set to true, and that a delete
        // was attempted as expected for the bad file state.
        assertEquals(0, mProfilingService.mAppTriggers.getMap().size());
        assertTrue(mProfilingService.mAppTriggersLoaded);
        verify(mProfilingService, times(1)).deletePersistAppTriggersFile();
    }

    /**
     * Test that loading app triggers with an invalid persist file works as intended with no
     * triggers added, loaded set to true, and correct methods called.
     */
    @Test
    @EnableFlags(android.os.profiling.Flags.FLAG_PERSIST_QUEUE)
    public void testAppTriggersPersist_BadPersistFile() throws Exception {
        // First, clear the data structure.
        mProfilingService.mAppTriggers.getMap().clear();

        // Ensure the file exists and contains some non proto contents.
        mProfilingService.mPersistAppTriggersFile.delete();
        mProfilingService.mPersistAppTriggersFile.createNewFile();
        FileOutputStream fileOutputStream = new FileOutputStream(
                mProfilingService.mPersistAppTriggersFile);
        fileOutputStream.write("some text that is definitely not a proto".getBytes());
        fileOutputStream.close();
        confirmNonEmptyFileExists(mProfilingService.mPersistAppTriggersFile);

        // Load app triggers from disk.
        mProfilingService.loadAppTriggersFromPersistedData();

        // Ensure that the triggers are still empty, that loaded was set to true, and that a delete
        // was attempted as expected for the bad file state.
        assertEquals(0, mProfilingService.mAppTriggers.getMap().size());
        assertTrue(mProfilingService.mAppTriggersLoaded);
        verify(mProfilingService, times(1)).deletePersistAppTriggersFile();
    }

    /** Test that adding a specific listener does not trigger handling queued results. */
    @Test
    public void testQueuedResult_RequestSpecificListener() {
        // Add a request specific callback.
        ProfilingResultCallback callback = new ProfilingResultCallback();
        mProfilingService.registerResultsCallback(false, callback);

        // Confirm handling queued results was not attempted.
        verify(mProfilingService, times(0)).handleQueuedResults(anyInt());
    }

    /** Test that adding a general listener does trigger handling queued results. */
    @Test
    public void testQueuedResult_GeneralListenerCallbackRegistered() {
        // Add a general callback.
        ProfilingResultCallback callback = new ProfilingResultCallback();
        mProfilingService.registerResultsCallback(true, callback);

        // Confirm handling queued results was attempted.
        verify(mProfilingService, times(1)).handleQueuedResults(anyInt());
    }

    /**
     * Test that notifying of a general listener added to existing callback does trigger handling
     * queued results.
     */
    @Test
    public void testQueuedResult_GeneralListenerAdded() {
        // Notify service that a general listener was added to existing callback.
        mProfilingService.generalListenerAdded();

        // Confirm handling queued results was attempted.
        verify(mProfilingService, times(1)).handleQueuedResults(anyInt());
    }

    /** Test that a queued result with an invalid state is discarded with no callback. */
    @Test
    public void testQueuedResult_InvalidState() {
        // Clear all existing queued results.
        mProfilingService.mQueuedTracingResults.clear();

        // Add a in progress session to queue with invalid state. PROFILING_STARTED is an invalid
        // state because mQueuedTracingResults should only contain sessions that have completed with
        // a result.
        List<TracingSession> queue = new ArrayList<TracingSession>();
        TracingSession session = new TracingSession(
                ProfilingManager.PROFILING_TYPE_HEAP_PROFILE,
                new Bundle(),
                FAKE_UID,
                APP_PACKAGE_NAME,
                REQUEST_TAG,
                KEY_LEAST_SIG_BITS,
                KEY_MOST_SIG_BITS);
        session.setState(TracingState.PROFILING_STARTED);
        queue.add(session);
        mProfilingService.mQueuedTracingResults.put(FAKE_UID, queue);

        // Add a callback directly with fake uid
        ProfilingResultCallback callback = new ProfilingResultCallback();
        mProfilingService.mResultCallbacks.put(FAKE_UID, Arrays.asList(callback));

        // Trigger handle queued results
        mProfilingService.handleQueuedResults(FAKE_UID);

        // Confirm that the in progress result was deleted without triggering the callback
        assertFalse(mProfilingService.mQueuedTracingResults.contains(FAKE_UID));
        assertFalse(callback.mResultSent);
    }

    /**
     * Test that a queued result which is over the max retry limit is discarded with no callback.
     */
    @Test
    public void testQueuedResult_OverMaxRetries() throws Exception {
        // Clear all existing queued results.
        mProfilingService.mQueuedTracingResults.clear();

        // Override the retry count
        executeShellCmd(OVERRIDE_DEVICE_CONFIG_INT, DeviceConfigHelper.NAMESPACE,
                DeviceConfigHelper.MAX_RESULT_REDELIVERY_COUNT, 3);

        // Add a in progress session to queue with too many retries
        List<TracingSession> queue = new ArrayList<TracingSession>();
        TracingSession session = new TracingSession(
                ProfilingManager.PROFILING_TYPE_HEAP_PROFILE,
                new Bundle(),
                FAKE_UID,
                APP_PACKAGE_NAME,
                REQUEST_TAG,
                KEY_LEAST_SIG_BITS,
                KEY_MOST_SIG_BITS);
        session.setState(TracingState.PROFILING_FINISHED);
        session.setRetryCount(3);
        queue.add(session);
        mProfilingService.mQueuedTracingResults.put(FAKE_UID, queue);

        // Add a callback directly with fake uid
        ProfilingResultCallback callback = new ProfilingResultCallback();
        mProfilingService.mResultCallbacks.put(FAKE_UID, Arrays.asList(callback));

        // Trigger handle queued results
        mProfilingService.handleQueuedResults(FAKE_UID);

        // Confirm that the in progress result was deleted without triggering the callback
        assertFalse(mProfilingService.mQueuedTracingResults.contains(FAKE_UID));
        assertFalse(callback.mResultSent);
    }

    /**
     * Test that a queued result for a finished non-trace profiling follows the path to be redacted.
     */
    @Test
    public void testQueuedResult_ProfilingFinished() {
        // Clear all existing queued results.
        mProfilingService.mQueuedTracingResults.clear();

        int uid = Binder.getCallingUid();

        // Add a in progress session to queue with too many retries
        List<TracingSession> queue = new ArrayList<TracingSession>();
        TracingSession session = new TracingSession(
                ProfilingManager.PROFILING_TYPE_STACK_SAMPLING,
                new Bundle(),
                uid,
                APP_PACKAGE_NAME,
                REQUEST_TAG,
                KEY_LEAST_SIG_BITS,
                KEY_MOST_SIG_BITS);
        session.setState(TracingState.PROFILING_FINISHED);
        queue.add(session);
        mProfilingService.mQueuedTracingResults.put(uid, queue);

        // Add a callback directly with fake uid
        ProfilingResultCallback callback = new ProfilingResultCallback();
        mProfilingService.mResultCallbacks.put(uid, Arrays.asList(callback));

        // Trigger handle queued results
        mProfilingService.handleQueuedResults(uid);

        // Confirm that the correct path was called. Callback will be for failed post processing
        // because we cannot copy from this context.
        verify(mProfilingService, times(1)).beginMoveFileToAppStorage(any());
        verify(mProfilingService, times(1)).processTracingSessionResultCallback(any(), eq(false));
        assertTrue(callback.mFileRequested);
        assertTrue(callback.mResultSent);
        assertEquals(ProfilingResult.ERROR_FAILED_POST_PROCESSING, callback.mStatus);
    }

    /**
     * Test that a queued result for an unredacted trace follows the path to be redacted. It will
     * not actually be redacted because there was no trace run to redact. */
    @Test
    public void testQueuedResult_TraceUnredacted() {
        // Clear all existing queued results.
        mProfilingService.mQueuedTracingResults.clear();

        // Add a in progress session to queue with too many retries
        List<TracingSession> queue = new ArrayList<TracingSession>();
        TracingSession session = new TracingSession(
                ProfilingManager.PROFILING_TYPE_SYSTEM_TRACE,
                new Bundle(),
                FAKE_UID,
                APP_PACKAGE_NAME,
                REQUEST_TAG,
                KEY_LEAST_SIG_BITS,
                KEY_MOST_SIG_BITS);
        session.setState(TracingState.PROFILING_FINISHED);
        queue.add(session);
        mProfilingService.mQueuedTracingResults.put(FAKE_UID, queue);

        // Add a callback directly with fake uid
        ProfilingResultCallback callback = new ProfilingResultCallback();
        mProfilingService.mResultCallbacks.put(FAKE_UID, Arrays.asList(callback));

        // Trigger handle queued results
        mProfilingService.handleQueuedResults(FAKE_UID);

        // Confirm that the correct path was called. Callback will be for failed post processing
        // because we cannot copy from this context.
        verify(mProfilingService, times(1)).handleRedactionRequiredResult(any());
        assertTrue(callback.mResultSent);
        assertEquals(ProfilingResult.ERROR_FAILED_POST_PROCESSING, callback.mStatus);
    }


    /** Test that a queued result for an unredacted trace follows the path to be redacted. */
    @Test
    public void testQueuedResult_TraceRedacted() {
        // Clear all existing queued results.
        mProfilingService.mQueuedTracingResults.clear();

        int uid = Binder.getCallingUid();

        // Add a in progress session to queue with state redacted
        List<TracingSession> queue = new ArrayList<TracingSession>();
        TracingSession session = new TracingSession(
                ProfilingManager.PROFILING_TYPE_SYSTEM_TRACE,
                new Bundle(),
                uid,
                APP_PACKAGE_NAME,
                REQUEST_TAG,
                KEY_LEAST_SIG_BITS,
                KEY_MOST_SIG_BITS);
        session.setState(TracingState.REDACTED);
        session.setProfilingStartTimeMs(System.currentTimeMillis());
        queue.add(session);
        mProfilingService.mQueuedTracingResults.put(uid, queue);

        // Add a callback directly
        ProfilingResultCallback callback = new ProfilingResultCallback();
        mProfilingService.mResultCallbacks.put(uid, Arrays.asList(callback));

        // Trigger handle queued results
        mProfilingService.handleQueuedResults(uid);

        // Confirm that the correct path was called.
        verify(mProfilingService, times(1)).beginMoveFileToAppStorage(any());
        verify(mProfilingService, times(1)).processTracingSessionResultCallback(any(), eq(false));
        assertTrue(callback.mFileRequested);
        assertTrue(callback.mResultSent);
        assertEquals(ProfilingResult.ERROR_FAILED_POST_PROCESSING, callback.mStatus);
    }

    /**
     * Test that a queued result for an already redacted and copied trace successfully triggers a
     * callback.
     *
     * Success callback will be received in this case because there is no attempt to re-copy files
     * which would have failed from this context.
     */
    @Test
    public void testQueuedResult_AlreadyCopied() {
        // Clear all existing queued results.
        mProfilingService.mQueuedTracingResults.clear();

        // Add a in progress session to queue with too many retries
        List<TracingSession> queue = new ArrayList<TracingSession>();
        TracingSession session = new TracingSession(
                ProfilingManager.PROFILING_TYPE_SYSTEM_TRACE,
                new Bundle(),
                FAKE_UID,
                APP_PACKAGE_NAME,
                REQUEST_TAG,
                KEY_LEAST_SIG_BITS,
                KEY_MOST_SIG_BITS);
        session.setState(TracingState.COPIED_FILE);
        session.setError(ProfilingResult.ERROR_NONE);
        queue.add(session);
        mProfilingService.mQueuedTracingResults.put(FAKE_UID, queue);

        // Add a callback directly with fake uid
        ProfilingResultCallback callback = new ProfilingResultCallback();
        mProfilingService.mResultCallbacks.put(FAKE_UID, Arrays.asList(callback));

        // Trigger handle queued results
        mProfilingService.handleQueuedResults(FAKE_UID);

        // Confirm that the correct path was called that a success callback was received.
        verify(mProfilingService, times(1)).processTracingSessionResultCallback(any(), eq(true));
        assertFalse(mProfilingService.mQueuedTracingResults.contains(FAKE_UID));
    }

    /**
     * Test that a queued result for a session with state of error occurred correctly progresses
     * to next step of triggering callback.
     */
    @Test
    public void testQueuedResult_ErrorOccurred() {
        // Clear all existing queued results.
        mProfilingService.mQueuedTracingResults.clear();

        // Add a in progress session to queue with state error occurred
        List<TracingSession> queue = new ArrayList<TracingSession>();
        TracingSession session = new TracingSession(
                ProfilingManager.PROFILING_TYPE_SYSTEM_TRACE,
                new Bundle(),
                FAKE_UID,
                APP_PACKAGE_NAME,
                REQUEST_TAG,
                KEY_LEAST_SIG_BITS,
                KEY_MOST_SIG_BITS);
        session.setState(TracingState.ERROR_OCCURRED);
        session.setError(ProfilingResult.ERROR_UNKNOWN);
        queue.add(session);
        mProfilingService.mQueuedTracingResults.put(FAKE_UID, queue);

        // Add a callback directly with fake uid
        ProfilingResultCallback callback = new ProfilingResultCallback();
        mProfilingService.mResultCallbacks.put(FAKE_UID, Arrays.asList(callback));

        // Trigger handle queued results
        mProfilingService.handleQueuedResults(FAKE_UID);

        // Confirm that the correct path was called that an error callback was received.
        verify(mProfilingService, times(1)).processTracingSessionResultCallback(any(), eq(true));
        assertTrue(callback.mResultSent);
        assertEquals(ProfilingResult.ERROR_UNKNOWN, callback.mStatus);
    }

    /**
     * Test that a queued result for a session with state of notified requester correctly progresses
     * to next step of cleaning up and does not trigger any further callbacks.
     */
    @Test
    public void testQueuedResult_NotifiedRequester() {
        // Clear all existing queued results.
        mProfilingService.mQueuedTracingResults.clear();

        // Add a in progress session to queue with state notified requester
        List<TracingSession> queue = new ArrayList<TracingSession>();
        TracingSession session = new TracingSession(
                ProfilingManager.PROFILING_TYPE_SYSTEM_TRACE,
                new Bundle(),
                FAKE_UID,
                APP_PACKAGE_NAME,
                REQUEST_TAG,
                KEY_LEAST_SIG_BITS,
                KEY_MOST_SIG_BITS);
        session.setState(TracingState.NOTIFIED_REQUESTER);
        session.setError(ProfilingResult.ERROR_NONE);
        queue.add(session);
        mProfilingService.mQueuedTracingResults.put(FAKE_UID, queue);

        // Add a callback directly with fake uid
        ProfilingResultCallback callback = new ProfilingResultCallback();
        mProfilingService.mResultCallbacks.put(FAKE_UID, Arrays.asList(callback));

        // Trigger handle queued results
        mProfilingService.handleQueuedResults(FAKE_UID);

        // Confirm that the correct path was called.
        verify(mProfilingService, times(1)).cleanupTracingSession(any());
        assertFalse(mProfilingService.mQueuedTracingResults.contains(FAKE_UID));
        assertFalse(callback.mResultSent);
    }

    /**
     * Test that a queued result that was started longer than max queue time ago is successfully
     * cleaned up when the queue is triggered for a different uid.
     */
    @Test
    public void testQueuedResult_Cleanup() {
        // Clear all existing queued results.
        mProfilingService.mQueuedTracingResults.clear();

        // Add a in progress session to queue that was started more than max duration ago.
        List<TracingSession> queue = new ArrayList<TracingSession>();
        TracingSession session = new TracingSession(
                ProfilingManager.PROFILING_TYPE_SYSTEM_TRACE,
                new Bundle(),
                FAKE_UID,
                APP_PACKAGE_NAME,
                REQUEST_TAG,
                KEY_LEAST_SIG_BITS,
                KEY_MOST_SIG_BITS);
        session.setState(TracingState.COPIED_FILE);
        session.setProfilingStartTimeMs(System.currentTimeMillis() - 1000
                - ProfilingService.QUEUED_RESULT_MAX_RETAINED_DURATION_MS);
        queue.add(session);
        mProfilingService.mQueuedTracingResults.put(FAKE_UID, queue);

        int fakeUid2 = FAKE_UID + 1;

        // Add a callback directly with a different fake uid
        ProfilingResultCallback callback = new ProfilingResultCallback();
        mProfilingService.mResultCallbacks.put(fakeUid2, Arrays.asList(callback));

        // Trigger handle queued results
        mProfilingService.handleQueuedResults(fakeUid2);

        // Confirm the old result was cleaned up.
        assertEquals(0, mProfilingService.mQueuedTracingResults.size());
    }

    @SuppressWarnings("GuardedBy") // Suppress warning for mProfilingService lock.
    @Test
    public void testTemporaryDirectoryCleanup_inActiveSession() throws Exception {
        // Setup the temporary directory in app storage so we can access it from this context. Make
        // sure it exists and is empty.
        File directory = new File(mContext.getFilesDir().getPath());
        directory.delete();
        directory.mkdirs();
        assertTrue(directory.isDirectory());

        // Create 3 files, 1 to be tracked and 2 not to be tracked
        File trackedFile = createAndConfirmFileExists(directory, "tracked_active_file");
        File untrackedFile1 = createAndConfirmFileExists(directory, "untracked_file_1");
        File untrackedFile2 = createAndConfirmFileExists(directory, "untracked_file_2");

        // Add the tracked file to active sessions
        TracingSession session = new TracingSession(
                ProfilingManager.PROFILING_TYPE_SYSTEM_TRACE,
                new Bundle(),
                FAKE_UID,
                APP_PACKAGE_NAME,
                REQUEST_TAG,
                KEY_LEAST_SIG_BITS,
                KEY_MOST_SIG_BITS);
        session.setFileName(trackedFile.getName());
        mProfilingService.mActiveTracingSessions.put(session.getKey(), session);
        assertEquals(1, mProfilingService.mActiveTracingSessions.size());

        // Now trigger the cleanup
        mProfilingService.cleanupTemporaryDirectoryLocked(directory.getPath());

        // Finally, confirm that the 1 tracked file is still present and that the 2 non-tracked
        // files were deleted.
        confirmNonEmptyFileExists(trackedFile);
        assertFalse(untrackedFile1.exists());
        assertFalse(untrackedFile2.exists());
    }

    @SuppressWarnings("GuardedBy") // Suppress warning for mProfilingService lock.
    @Test
    public void testTemporaryDirectoryCleanup_inQueuedSession() throws Exception {
        // Setup the temporary directory in app storage so we can access it from this context. Make
        // sure it exists and is empty.
        File directory = new File(mContext.getFilesDir().getPath());
        directory.delete();
        directory.mkdirs();
        assertTrue(directory.isDirectory());

        // Create 5 files, 3 to be tracked and 2 not to be tracked
        File trackedFile1 = createAndConfirmFileExists(directory, "tracked_queued_file_1");
        File trackedFile2 = createAndConfirmFileExists(directory, "tracked_queued_file_2");
        File trackedFile3 = createAndConfirmFileExists(directory, "tracked_queued_file_3");
        File untrackedFile1 = createAndConfirmFileExists(directory, "untracked_file_1");
        File untrackedFile2 = createAndConfirmFileExists(directory, "untracked_file_2");

        // Add the 3 tracked files to active sessions, 2 under 1 uid and 1 under another.
        int fakeUid2 = FAKE_UID + 1;
        // Create the fake sessions and set their filenames.
        TracingSession session1 = new TracingSession(
                ProfilingManager.PROFILING_TYPE_SYSTEM_TRACE,
                new Bundle(),
                FAKE_UID,
                APP_PACKAGE_NAME,
                REQUEST_TAG,
                KEY_LEAST_SIG_BITS,
                KEY_MOST_SIG_BITS);
        session1.setRedactedFileName(trackedFile1.getName());
        TracingSession session2 = new TracingSession(
                ProfilingManager.PROFILING_TYPE_JAVA_HEAP_DUMP,
                new Bundle(),
                fakeUid2,
                APP_PACKAGE_NAME,
                REQUEST_TAG,
                KEY_LEAST_SIG_BITS,
                KEY_MOST_SIG_BITS);
        session2.setFileName(trackedFile2.getName());
        TracingSession session3 = new TracingSession(
                ProfilingManager.PROFILING_TYPE_JAVA_HEAP_DUMP,
                new Bundle(),
                fakeUid2,
                APP_PACKAGE_NAME,
                REQUEST_TAG,
                KEY_LEAST_SIG_BITS,
                KEY_MOST_SIG_BITS);
        session3.setFileName(trackedFile3.getName());
        // Put 1 session in one list.
        List<TracingSession> sessionList1 = new ArrayList<TracingSession>(Arrays.asList(session1));
        mProfilingService.mQueuedTracingResults.put(FAKE_UID, sessionList1);
        // Put 2 sessions in the other list.
        List<TracingSession> sessionList2 = new ArrayList<TracingSession>(
                Arrays.asList(session2, session3));
        mProfilingService.mQueuedTracingResults.put(fakeUid2, sessionList2);
        // Add an empty list just for fun.
        mProfilingService.mQueuedTracingResults.put(fakeUid2 + 1, new ArrayList<TracingSession>());
        // Make sure all lists have been added.
        assertEquals(3, mProfilingService.mQueuedTracingResults.size());

        // Now trigger the cleanup
        mProfilingService.cleanupTemporaryDirectoryLocked(directory.getPath());

        // Finally, confirm that the 3 tracked files are still present and that the 2 non-tracked
        // files were deleted.
        confirmNonEmptyFileExists(trackedFile1);
        confirmNonEmptyFileExists(trackedFile2);
        confirmNonEmptyFileExists(trackedFile3);
        assertFalse(untrackedFile1.exists());
        assertFalse(untrackedFile2.exists());
    }

    /** Test that result callbacks are correctly cleaned up when new callbacks are added. */
    @Test
    public void testResultCallbacksCleanup() throws Exception {
        mProfilingService.mResultCallbacks.clear();

        // Create 4 callbacks and mock binder dead in 2 of them.
        ProfilingResultCallback callbackAlive1 = new ProfilingResultCallback();
        ProfilingResultCallback callbackAlive2 = new ProfilingResultCallback();
        ProfilingResultCallback callbackDead1 = spy(new ProfilingResultCallback());
        doReturn(false).when(callbackDead1).isBinderAlive();
        ProfilingResultCallback callbackDead2 = spy(new ProfilingResultCallback());
        doReturn(false).when(callbackDead2).isBinderAlive();

        // Register alive callback and confirm it's retained.
        mProfilingService.registerResultsCallback(true, callbackAlive1);
        assertEquals(1, mProfilingService.mResultCallbacks.get(Binder.getCallingUid()).size());

        // Register dead callback. Cleanup is not performed on just added callback so expect 2
        // callbacks to be present.
        mProfilingService.registerResultsCallback(true, callbackDead1);
        assertEquals(2, mProfilingService.mResultCallbacks.get(Binder.getCallingUid()).size());

        // Register another dead callback. Cleanup is expected to remove the first dead callback and
        // leave the new one so size should still be 2.
        mProfilingService.registerResultsCallback(true, callbackDead2);
        assertEquals(2, mProfilingService.mResultCallbacks.get(Binder.getCallingUid()).size());

        // Register another alive callback. Cleanup should now remove the 2nd dead callback leaving
        // the 2 alive callbacks in place.
        mProfilingService.registerResultsCallback(true, callbackAlive2);
        assertEquals(2, mProfilingService.mResultCallbacks.get(Binder.getCallingUid()).size());
    }

    /**
     * Test that adding triggers adds to the correct process and overwrites with new results when
     * the same trigger, uid, and process name are used.
     */
    @Test
    @EnableFlags(android.os.profiling.Flags.FLAG_SYSTEM_TRIGGERED_PROFILING_NEW)
    public void testAddTriggers() throws Exception {
        // First, clear the data structure.
        mProfilingService.mAppTriggers.getMap().clear();

        // Now add several triggers:
        // First add 2 different triggers to the same uid/package
        // TODO: b/373461116 - update hardcoded triggers to api value
        mProfilingService.addTrigger(FAKE_UID, APP_PACKAGE_NAME, 1, 0);
        mProfilingService.addTrigger(FAKE_UID, APP_PACKAGE_NAME, 2, 0);
        // And add one to another uid with the same package name.
        mProfilingService.addTrigger(FAKE_UID_2, APP_PACKAGE_NAME, 2, 0);

        // Grab the per process arrays.
        SparseArray<ProfilingTrigger> uid1Triggers =
                mProfilingService.mAppTriggers.get(APP_PACKAGE_NAME, FAKE_UID);
        SparseArray<ProfilingTrigger> uid2Triggers =
                mProfilingService.mAppTriggers.get(APP_PACKAGE_NAME, FAKE_UID_2);

        // Confirm they are represented correctly.
        assertEquals(2, uid1Triggers.size());
        assertEquals(1, uid2Triggers.size());
        confirmProfilingTriggerEquals(uid1Triggers.get(1), FAKE_UID, APP_PACKAGE_NAME, 1, 0);
        confirmProfilingTriggerEquals(uid1Triggers.get(2), FAKE_UID, APP_PACKAGE_NAME, 2, 0);
        confirmProfilingTriggerEquals(uid2Triggers.get(2), FAKE_UID_2, APP_PACKAGE_NAME, 2, 0);

        // Now add a repeated trigger with 1 field changed.
        mProfilingService.addTrigger(FAKE_UID, APP_PACKAGE_NAME, 2, 100);

        // Confirm the new value is set.
        assertEquals(100, mProfilingService.mAppTriggers.get(APP_PACKAGE_NAME, FAKE_UID).get(2)
                .getRateLimitingPeriodHours());
    }

    /** Test that app level rate limiting works correctly in the allow case. */
    @Test
    @EnableFlags(android.os.profiling.Flags.FLAG_SYSTEM_TRIGGERED_PROFILING_NEW)
    public void testProcessTrigger_appLevelRateLimit_allow() throws Exception {
        // First, clear the data structure.
        mProfilingService.mAppTriggers.getMap().clear();

        // Override the system rate limiter to always pass, we're not testing that here.
        doReturn(RateLimiter.RATE_LIMIT_RESULT_ALLOWED).when(mRateLimiter)
                .isProfilingRequestAllowed(anyInt(), anyInt(), eq(true), any());

        // And setup some mocks.
        mProfilingService.mSystemTriggeredTraceUniqueSessionName = "something_non_null";
        doReturn(true).when(mActiveTrace).isAlive();
        mProfilingService.mSystemTriggeredTraceProcess = mActiveTrace;

        // TODO: b/373461116 - update hardcoded trigger to api value
        int fakeTrigger = 1;

        // Setup some rate limiting values. Since this is an allow test, set the last run to be 1
        // hour more than the rate limiting period.
        int rateLimitingPeriodHours = 10;
        long fakeLastTriggerTimeMs = System.currentTimeMillis()
                - ((rateLimitingPeriodHours + 1) * 60L * 60L * 1000L);

        // Add the trigger we'll use.
        mProfilingService.addTrigger(FAKE_UID, APP_PACKAGE_NAME, fakeTrigger,
                rateLimitingPeriodHours);

        // Set the last run time.
        mProfilingService.mAppTriggers.get(APP_PACKAGE_NAME, FAKE_UID).get(fakeTrigger)
                .setLastTriggeredTimeMs(fakeLastTriggerTimeMs);

        // Now process the trigger.
        mProfilingService.processTriggerInternal(FAKE_UID, APP_PACKAGE_NAME, fakeTrigger);

        // Get the new trigger time and make sure it's later than the fake one, indicating it ran.
        long newTriggerTime = mProfilingService.mAppTriggers.get(APP_PACKAGE_NAME, FAKE_UID)
                .get(fakeTrigger).getLastTriggeredTimeMs();
        assertTrue(newTriggerTime > fakeLastTriggerTimeMs);
    }

    /** Test that app level rate limiting works correctly in the deny case. */
    @Test
    @EnableFlags(android.os.profiling.Flags.FLAG_SYSTEM_TRIGGERED_PROFILING_NEW)
    public void testProcessTrigger_appLevelRateLimit_deny() throws Exception {
        // First, clear the data structure.
        mProfilingService.mAppTriggers.getMap().clear();

        // Override the system rate limiter to always pass, we're not testing that here.
        doReturn(RateLimiter.RATE_LIMIT_RESULT_ALLOWED).when(mRateLimiter)
                .isProfilingRequestAllowed(anyInt(), anyInt(), eq(true), any());

        // And setup some mocks.
        mProfilingService.mSystemTriggeredTraceUniqueSessionName = "something_non_null";
        doReturn(true).when(mActiveTrace).isAlive();
        mProfilingService.mSystemTriggeredTraceProcess = mActiveTrace;

        // TODO: b/373461116 - update hardcoded trigger to api value
        int fakeTrigger = 1;

        // Setup some rate limiting values. Since this is a deny test, set the last run to be 1 hour
        // less than the rate limiting period.
        int rateLimitingPeriodHours = 10;
        long fakeLastTriggerTimeMs = System.currentTimeMillis()
                - ((rateLimitingPeriodHours - 1) * 60L * 60L * 1000L);

        // Add the trigger we'll use,
        mProfilingService.addTrigger(FAKE_UID, APP_PACKAGE_NAME, fakeTrigger,
                rateLimitingPeriodHours);

        // Set the last run time.
        mProfilingService.mAppTriggers.get(APP_PACKAGE_NAME, FAKE_UID).get(fakeTrigger)
                .setLastTriggeredTimeMs(fakeLastTriggerTimeMs);

        // Now process the trigger.
        mProfilingService.processTriggerInternal(FAKE_UID, APP_PACKAGE_NAME, fakeTrigger);

        // Get the new trigger time and make sure it's equal to the fake one, indicating it did not
        // run.
        long newTriggerTime = mProfilingService.mAppTriggers.get(APP_PACKAGE_NAME, FAKE_UID)
                .get(fakeTrigger).getLastTriggeredTimeMs();
        assertEquals(fakeLastTriggerTimeMs, newTriggerTime);
    }

    /** Test that system level rate limiting works correctly in the allow case. */
    @Test
    @EnableFlags(android.os.profiling.Flags.FLAG_SYSTEM_TRIGGERED_PROFILING_NEW)
    public void testProcessTrigger_systemLevelRateLimit_allow() throws Exception {
        overrideRateLimiterDefaults();

        // First, clear the data structure.
        mProfilingService.mAppTriggers.getMap().clear();

        // And setup some mocks.
        mProfilingService.mSystemTriggeredTraceUniqueSessionName = "something_non_null";
        doReturn(true).when(mActiveTrace).isAlive();
        mProfilingService.mSystemTriggeredTraceProcess = mActiveTrace;

        // TODO: b/373461116 - update hardcoded trigger to api value
        int fakeTrigger = 1;

        // Set app level rate limiting to 0, we're not testing that here.
        int rateLimitingPeriodHours = 0;

        // Add the trigger we'll use.
        mProfilingService.addTrigger(FAKE_UID, APP_PACKAGE_NAME, fakeTrigger,
                rateLimitingPeriodHours);

        // Now process the trigger.
        mProfilingService.processTriggerInternal(FAKE_UID, APP_PACKAGE_NAME, fakeTrigger);

        // Get the new trigger time and make sure it's later than 0, indicating it ran.
        long newTriggerTime = mProfilingService.mAppTriggers.get(APP_PACKAGE_NAME, FAKE_UID)
                .get(fakeTrigger).getLastTriggeredTimeMs();
        assertTrue(newTriggerTime > 0);
    }

    /** Test that system level rate limiting works correctly in the deny case. */
    @Test
    @EnableFlags(android.os.profiling.Flags.FLAG_SYSTEM_TRIGGERED_PROFILING_NEW)
    public void testProcessTrigger_systemLevelRateLimit_deny() throws Exception {
        overrideRateLimiterDefaults();

        // First, clear the data structure.
        mProfilingService.mAppTriggers.getMap().clear();

        // And setup some mocks.
        mProfilingService.mSystemTriggeredTraceUniqueSessionName = "something_non_null";
        doReturn(true).when(mActiveTrace).isAlive();
        mProfilingService.mSystemTriggeredTraceProcess = mActiveTrace;

        // Add record with high cost to rate limiter so that it won't allow future runs.
        mRateLimiter.mPastRunsHour.add(FAKE_UID, 1000, System.currentTimeMillis());

        // Wait 1 ms to ensure time has ticked and avoid potential flake.
        sleep(1);

        // TODO: b/373461116 - update hardcoded trigger to api value
        int fakeTrigger = 1;

        // Set app level rate limiting to 0, we're not testing that here.
        int rateLimitingPeriodHours = 0;

        // Add the trigger we'll use,
        mProfilingService.addTrigger(FAKE_UID, APP_PACKAGE_NAME, fakeTrigger,
                rateLimitingPeriodHours);

        // Now process the trigger.
        mProfilingService.processTriggerInternal(FAKE_UID, APP_PACKAGE_NAME, fakeTrigger);

        // Get the new trigger time and make sure it's equal to 0, indicating it did not run.
        long newTriggerTime = mProfilingService.mAppTriggers.get(APP_PACKAGE_NAME, FAKE_UID)
                .get(fakeTrigger).getLastTriggeredTimeMs();
        assertEquals(0, newTriggerTime);
    }

    /**
     * Test that scheduling for system triggered profiling trace start works correctly, configuring
     * run delay for correct amount of time.
     */
    @Test
    @EnableFlags(android.os.profiling.Flags.FLAG_SYSTEM_TRIGGERED_PROFILING_NEW)
    public void testSystemTriggeredProfiling_Scheduling() throws Exception {
        // Override system triggered trace start values so that the trace will be attempted to be
        // started within the test duration. If these values are changed, make sure to update the
        // additional delay below as well.
        executeShellCmd(OVERRIDE_DEVICE_CONFIG_INT, DeviceConfigHelper.NAMESPACE,
                DeviceConfigHelper.SYSTEM_TRIGGERED_TRACE_MIN_PERIOD_SECONDS, 3);
        updateDeviceConfigAndWaitForChange(DeviceConfigHelper.NAMESPACE,
                DeviceConfigHelper.SYSTEM_TRIGGERED_TRACE_MAX_PERIOD_SECONDS, 4);

        // Cancel the already scheduled future and set to null, if applicable.
        if (mProfilingService.mStartSystemTriggeredTraceScheduledFuture != null) {
            mProfilingService.mStartSystemTriggeredTraceScheduledFuture.cancel(true);
            mProfilingService.mStartSystemTriggeredTraceScheduledFuture = null;
        }

        // Schedule a start of system triggered trace.
        mProfilingService.scheduleNextSystemTriggeredTraceStart();

        // Confirm the future is scheduled and that an attempt to start the trace has not occurred
        // yet.
        assertNotNull(mProfilingService.mStartSystemTriggeredTraceScheduledFuture);
        assertFalse(mProfilingService.mStartSystemTriggeredTraceScheduledFuture.isDone());
        verify(mProfilingService, times(0)).startSystemTriggeredTrace();

        // Wait for 2 seconds longer than the scheduled future delay so that the future can execute
        // once, but not twice. 2 seconds is selected as the extra delay because it is less than 3
        // which is set as min for period above, but also the highest value possible to give time to
        // execute.
        long delay = mProfilingService.mStartSystemTriggeredTraceScheduledFuture.getDelay(
                TimeUnit.SECONDS);
        sleep((delay + 2L) * 1000L);

        // Finally, confirm that the future ran by confirming that an attempt to start the trace was
        // made. We don't confirm that it actually started as we can't actually start the trace from
        // this context.
        verify(mProfilingService, times(1)).startSystemTriggeredTrace();
    }

    private File createAndConfirmFileExists(File directory, String fileName) throws Exception {
        File file = new File(directory, fileName);
        file.createNewFile();
        FileOutputStream fileOutputStream = new FileOutputStream(file);
        fileOutputStream.write("some stub text".getBytes());
        fileOutputStream.close();
        confirmNonEmptyFileExists(file);
        return file;
    }

    private void confirmNonEmptyFileExists(File file) {
        assertTrue(file.exists());
        assertTrue(file.length() > 0L);
    }

    private void overrideRateLimiterDefaults() throws Exception {
        // Update DeviceConfig defaults to general high enough limits, cost of 1, and persist
        // frequency 0.
        overrideRateLimiterDefaults(5, 10, 20, 50, 50, 100, 1, 1, 1, 1, 1, 0);
    }

    private void overrideRateLimiterDefaults(int systemHour, int processHour, int systemDay,
            int processDay, int systemWeek, int processWeek, int costHeapDump, int costHeapProfile,
            int costStackSampling, int costSystemTrace, int costSystemTriggeredSystemProfiling,
            int persistToDiskFrequency)
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
                DeviceConfigHelper.COST_SYSTEM_TRIGGERED_SYSTEM_TRACE,
                costSystemTriggeredSystemProfiling);
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

    // LINT.IfChange(equals)
    private void confirmTracingSessionsEqual(TracingSession s1, TracingSession s2) {
        assertEquals(s1.getProfilingType(), s2.getProfilingType());
        assertEquals(s1.getUid(), s2.getUid());
        assertEquals(s1.getPackageName(), s2.getPackageName());
        assertEquals(s1.getTag(), s2.getTag());
        assertEquals(s1.getKeyMostSigBits(), s2.getKeyMostSigBits());
        assertEquals(s1.getKeyLeastSigBits(), s2.getKeyLeastSigBits());
        assertEquals(s1.getFileName(), s2.getFileName());
        assertEquals(s1.getRedactedFileName(), s2.getRedactedFileName());
        assertEquals(s1.getState().getValue(), s2.getState().getValue());
        assertEquals(s1.getRetryCount(), s2.getRetryCount());
        assertEquals(s1.getErrorMessage(), s2.getErrorMessage());
        assertEquals(s1.getErrorStatus(), s2.getErrorStatus());
        assertEquals(s1.getTriggerType(), s2.getTriggerType());
    }
    // LINT.ThenChange(/service/proto/android/os/queue.proto:proto)

    // LINT.IfChange(trigger_equals)
    private void confirmProfilingTriggerEquals(ProfilingTrigger t1, int uid, String packageName,
            int triggerType, int rateLimitingPeriodHours) {
        confirmProfilingTriggerEquals(t1,
                new ProfilingTrigger(uid, packageName, triggerType, rateLimitingPeriodHours));
    }

    private void confirmProfilingTriggerEquals(ProfilingTrigger t1, ProfilingTrigger t2) {
        assertEquals(t1.getUid(), t2.getUid());
        assertEquals(t1.getPackageName(), t2.getPackageName());
        assertEquals(t1.getTriggerType(), t2.getTriggerType());
        assertEquals(t1.getRateLimitingPeriodHours(), t2.getRateLimitingPeriodHours());
        assertEquals(t1.getLastTriggeredTimeMs(), t2.getLastTriggeredTimeMs());
    }
    // LINT.ThenChange(/service/proto/android/os/trigger.proto:proto)

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

    /**
     * Update the provided device config value and wait for up to 2 seconds, checking every 100ms,
     * for the value change to take effect.
     */
    private void updateDeviceConfigAndWaitForChange(String namespace, String config, int newValue)
            throws Exception {
        executeShellCmd(OVERRIDE_DEVICE_CONFIG_INT, namespace, config, newValue);
        for (int i = 0; i < 20; i++) {
            sleep(100);
            String s = executeShellCmd(GET_DEVICE_CONFIG, namespace, config);
            try {
                int val = Integer.parseInt(s.trim());
                if (val == newValue) {
                    return;
                }
            } catch (NumberFormatException e) {
                // Ignore and continue.
            }
        }
        fail("DeviceConfig value never updated to match expected value.");
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            // Do nothing.
        }
    }

    public class ProfilingResultCallback extends IProfilingResultCallback.Stub {
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
        public void generateFile(String filePathAbsolute, String fileName, long keyMostSigBits,
                long keyLeastSigBits) {
            mFileRequested = true;

            // To properly mock the flow for copy, pass null back to service via
            // receiveFileDescriptor. This will then fail at the copy step and the test will receive
            // a callback. In actual use and end-to-end testing via {@link ProfilingFrameworkTests},
            // this would be done by creating a real file in {@link ProfilingManager}. If we simply
            // ignored this call then the test flow would not progress past the beginning of copy
            // attempt.
            mProfilingService.receiveFileDescriptor(null, keyMostSigBits, keyLeastSigBits);
        }

        @Override
        public void deleteFile(String filePathAndName) {
            // Ignore
        }
    }
}


