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

import static android.profiling.cts.ProfilingTestUtils.ImmediateExecutor;
import static android.profiling.cts.ProfilingTestUtils.getOneSecondDurationParamBundle;
import static android.profiling.cts.ProfilingTestUtils.overrideDeviceConfig;
import static android.profiling.cts.ProfilingTestUtils.overrideRateLimiter;
import static android.profiling.cts.ProfilingTestUtils.resetAllConfigs;
import static android.profiling.cts.ProfilingTestUtils.sleep;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.app.ApplicationErrorReport;
import android.app.Instrumentation;
import android.content.Context;
import android.os.Binder;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Parcel;
import android.os.ProfilingManager;
import android.os.ProfilingResult;
import android.os.ProfilingServiceHelper;
import android.os.ProfilingTrigger;
import android.os.profiling.DeviceConfigHelper;
import android.os.profiling.Flags;
import android.os.profiling.ProfilingService;
import android.platform.test.annotations.RequiresFlagsDisabled;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.filters.LargeTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;
import org.testng.TestException;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Tests defined in this class are expected to test the API implementation. All tests below require
 * the android.os.profiling.telemetry_apis flag to be enabled, otherwise you will receive an assumed
 * failure for any tests has the @RequiresFlagsEnabled annotation.
 */
@RunWith(AndroidJUnit4.class)
public final class ProfilingFrameworkTests {

    // Wait for callback for 5 seconds at a time for up to 60 increments totalling 5 minutes.
    private static final int CALLBACK_WAIT_TIME_INCREMENT_MS = 5 * 1000;
    private static final int CALLBACK_WAIT_TIME_INCREMENTS_COUNT = 60;

    // Smaller number of increments for cancel case - wait for callback for 5 seconds at a time for
    // up to 4 increments totalling 20 seconds.
    private static final int CALLBACK_CANCEL_WAIT_TIME_INCREMENTS_COUNT = 4;

    // Wait 2 seconds for profiling to get started before attempting to cancel it.
    // TODO: b/376440094 - change to query perfetto and confirm profiling is running.
    private static final int WAIT_TIME_FOR_PROFILING_START_MS = 2 * 1000;

    // Wait 2 seconds for profiling to finish processing and transfer result to app.
    private static final int WAIT_TIME_FOR_PROFILING_POST_PROCESSING_MS = 2 * 1000;

    // Wait 10 seconds for profiling to potentially clone, process, and return result to confirm it
    // did not occur.
    private static final int WAIT_TIME_FOR_TRIGGERED_PROFILING_NO_RESULT = 10 * 1000;

    // LINT.IfChange(oom_device_configs)
    private static final int TIMEOUT_DEFAULT_JAVA_HEAP_DUMP_SECONDS = 5;
    private static final String CONFIG_TIMEOUT_OOM = "trigger_timeout_oom";
    // LINT.ThenChange(/framework/java/android/os/ProfilingServiceHelper.java:oom_device_configs)

    // Keep in sync with {@link ProfilingService} because we can't access it.
    private static final String OUTPUT_FILE_JAVA_HEAP_DUMP_SUFFIX = ".perfetto-java-heap-dump";
    private static final String OUTPUT_FILE_HEAP_PROFILE_SUFFIX = ".perfetto-heap-profile";
    private static final String OUTPUT_FILE_STACK_SAMPLING_SUFFIX = ".perfetto-stack-sample";
    private static final String OUTPUT_FILE_TRACE_SUFFIX = ".perfetto-trace";

    public static final Path DUMP_PATH =
            FileSystems.getDefault().getPath("/sdcard/ProfilesCollected/");

    private static final String REAL_PACKAGE_NAME = "com.android.profiling.tests";

    private static final int ONE_SECOND_MS = 1 * 1000;
    private static final int FIVE_SECONDS_MS = 5 * 1000;
    private static final int TEN_SECONDS_MS = 10 * 1000;
    private static final int TEN_MINUTES_MS = 10 * 60 * 1000;

    private ProfilingManager mProfilingManager = null;
    private Context mContext = null;
    private Instrumentation mInstrumentation;

    static {
        System.loadLibrary("cts_profiling_module_test_native");
    }

    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    @Rule public final TestName mTestName = new TestName();

    @Before
    public void setup() throws Exception {
        mContext = ApplicationProvider.getApplicationContext();
        mProfilingManager = mContext.getSystemService(ProfilingManager.class);
        mInstrumentation = InstrumentationRegistry.getInstrumentation();

        mProfilingManager.clearProfilingTriggers();

        // This permission is required for Headless (HSUM) tests, including Auto.
        mInstrumentation
                .getUiAutomation()
                .adoptShellPermissionIdentity(
                        android.Manifest.permission.INTERACT_ACROSS_USERS_FULL);

        mProfilingManager.clearProfilingTriggers();
    }

    @SuppressWarnings("GuardedBy") // Suppress warning for mProfilingManager.mProfilingService lock.
    @After
    public void cleanup() throws Exception {
        mProfilingManager.mProfilingService = null;
        resetAllConfigs();
    }

    /** Check and see if we can get a reference to the ProfilingManager service. */
    @Test
    @RequiresFlagsEnabled(Flags.FLAG_TELEMETRY_APIS)
    public void createServiceTest() {
        assertNotNull(mProfilingManager);
    }

    /** Test that request with invalid profiling type fails with correct error output. */
    @Test
    @RequiresFlagsEnabled(Flags.FLAG_TELEMETRY_APIS)
    public void testInvalidProfilingType() throws Exception {
        if (mProfilingManager == null) throw new TestException("mProfilingManager can not be null");

        disableRateLimiter();

        AppCallback callback = new AppCallback();

        // This call is passing an invalid profiling request type and should result in an error.
        mProfilingManager.requestProfiling(-1, null, null, null, new ImmediateExecutor(), callback);

        // Wait until callback#onAccept is triggered so we can confirm the result.
        waitForCallback(callback);

        assertEquals(ProfilingResult.ERROR_FAILED_INVALID_REQUEST, callback.mResult.getErrorCode());
    }

    /** Test that request with invalid profiling params fails with correct error output. */
    @Test
    @RequiresFlagsEnabled(Flags.FLAG_TELEMETRY_APIS)
    public void testInvalidProfilingParams() throws Exception {
        if (mProfilingManager == null) throw new TestException("mProfilingManager can not be null");

        disableRateLimiter();

        AppCallback callback = new AppCallback();

        Bundle params = new Bundle();
        params.putBoolean("bypass_rate_limiter", true);

        // This call is passing a parameters bundle with an invalid parameter and should result in
        // an error.
        mProfilingManager.requestProfiling(
                ProfilingManager.PROFILING_TYPE_STACK_SAMPLING,
                params,
                null,
                null,
                new ImmediateExecutor(),
                callback);

        // Wait until callback#onAccept is triggered so we can confirm the result.
        waitForCallback(callback);

        assertEquals(ProfilingResult.ERROR_FAILED_INVALID_REQUEST, callback.mResult.getErrorCode());
    }

    /** Test that profiling request for java heap dump succeeds and returns a non-empty file. */
    @Test
    @LargeTest
    @RequiresFlagsEnabled(Flags.FLAG_TELEMETRY_APIS)
    public void testRequestJavaHeapDumpSuccess() throws Exception {
        if (mProfilingManager == null) throw new TestException("mProfilingManager can not be null");

        disableRateLimiter();

        overrideJavaHeapDumpDeviceConfigValues(false, ONE_SECOND_MS, TEN_SECONDS_MS);

        AppCallback callback = new AppCallback();

        // Now kick off the request.
        mProfilingManager.requestProfiling(
                ProfilingManager.PROFILING_TYPE_JAVA_HEAP_DUMP,
                null,
                null,
                null,
                new ImmediateExecutor(),
                callback);

        // Wait until callback#onAccept is triggered so we can confirm the result.
        waitForCallback(callback);

        // Assert that result matches assumptions for success.
        confirmCollectionSuccess(callback.mResult, OUTPUT_FILE_JAVA_HEAP_DUMP_SUFFIX);
        dumpTrace(callback.mResult);
    }

    /** Test that profiling request for heap profile succeeds and returns a non-empty file. */
    @Test
    @RequiresFlagsEnabled(Flags.FLAG_TELEMETRY_APIS)
    public void testRequestHeapProfileSuccess() throws Exception {
        if (mProfilingManager == null) throw new TestException("mProfilingManager can not be null");

        disableRateLimiter();

        overrideHeapProfileDeviceConfigValues(false, ONE_SECOND_MS, ONE_SECOND_MS, FIVE_SECONDS_MS);

        AppCallback callback = new AppCallback();

        // Add sampling interval param to test because it is currently the only long param.
        Bundle params = getOneSecondDurationParamBundle();
        params.putLong(ProfilingManager.KEY_SAMPLING_INTERVAL_BYTES, 4096L);

        // Now kick off the request.
        mProfilingManager.requestProfiling(
                ProfilingManager.PROFILING_TYPE_HEAP_PROFILE,
                params,
                null,
                null,
                new ImmediateExecutor(),
                callback);

        MallocLoopThread mallocThread = new MallocLoopThread();

        // Wait until callback#onAccept is triggered so we can confirm the result.
        waitForCallback(callback);

        mallocThread.stop();

        // Assert that result matches assumptions for success.
        confirmCollectionSuccess(callback.mResult, OUTPUT_FILE_HEAP_PROFILE_SUFFIX);
        dumpTrace(callback.mResult);
    }

    /** Test that profiling request for stack sampling succeeds and returns a non-empty file. */
    @Test
    @RequiresFlagsEnabled(Flags.FLAG_TELEMETRY_APIS)
    public void testRequestStackSamplingSuccess() throws Exception {
        if (mProfilingManager == null) throw new TestException("mProfilingManager can not be null");

        disableRateLimiter();

        overrideStackSamplingDeviceConfigValues(
                false, ONE_SECOND_MS, ONE_SECOND_MS, FIVE_SECONDS_MS);

        AppCallback callback = new AppCallback();

        // Now kick off the request.
        mProfilingManager.requestProfiling(
                ProfilingManager.PROFILING_TYPE_STACK_SAMPLING,
                getOneSecondDurationParamBundle(),
                null,
                null,
                new ImmediateExecutor(),
                callback);

        BusyLoopThread busy = new BusyLoopThread();

        // Wait until callback#onAccept is triggered so we can confirm the result.
        waitForCallback(callback);

        busy.stop();

        // Assert that result matches assumptions for success.
        confirmCollectionSuccess(callback.mResult, OUTPUT_FILE_STACK_SAMPLING_SUFFIX);
        dumpTrace(callback.mResult);
    }

    /**
     * Test that profiling request for system trace fails as it's disabled until redaction is in
     * place.
     */
    @Test
    @RequiresFlagsEnabled({Flags.FLAG_TELEMETRY_APIS, Flags.FLAG_REDACTION_ENABLED})
    public void testRequestSystemTraceSuccess() throws Exception {
        if (mProfilingManager == null) throw new TestException("mProfilingManager can not be null");

        disableRateLimiter();

        overrideSystemTraceDeviceConfigValues(false, ONE_SECOND_MS, ONE_SECOND_MS, FIVE_SECONDS_MS);

        AppCallback callback = new AppCallback();

        // Now kick off the request.
        mProfilingManager.requestProfiling(
                ProfilingManager.PROFILING_TYPE_SYSTEM_TRACE,
                getOneSecondDurationParamBundle(),
                null,
                null,
                new ImmediateExecutor(),
                callback);

        // Wait until callback#onAccept is triggered so we can confirm the result.
        waitForCallback(callback);

        // Assert trace has succeeded.
        confirmCollectionSuccess(callback.mResult, OUTPUT_FILE_TRACE_SUFFIX);
        dumpTrace(callback.mResult);
    }

    /** Test that cancelling java heap dump stops collection and still receives correct result. */
    @Test
    @RequiresFlagsEnabled(Flags.FLAG_TELEMETRY_APIS)
    public void testRequestJavaHeapDumpCancel() throws Exception {
        if (mProfilingManager == null) throw new TestException("mProfilingManager can not be null");

        disableRateLimiter();

        // Set override duration and timeout to 10 minutes so we can ensure it finishes early when
        // canceled.
        overrideJavaHeapDumpDeviceConfigValues(false, TEN_MINUTES_MS, TEN_MINUTES_MS);

        AppCallback callback = new AppCallback();
        CancellationSignal cancellationSignal = new CancellationSignal();

        // Now kick off the request.
        mProfilingManager.requestProfiling(
                ProfilingManager.PROFILING_TYPE_JAVA_HEAP_DUMP,
                null, // Use default parameters since we will cancel quickly
                null,
                cancellationSignal,
                new ImmediateExecutor(),
                callback);

        // Wait a bit for collection to get started.
        sleep(WAIT_TIME_FOR_PROFILING_START_MS);

        // Now request cancellation.
        cancellationSignal.cancel();

        // Wait until callback#onAccept is triggered so we can confirm the result.
        waitForCancelCallback(callback);

        // Assert that result matches assumptions for success.
        confirmCollectionSuccess(callback.mResult, OUTPUT_FILE_JAVA_HEAP_DUMP_SUFFIX);
    }

    /** Test that cancelling heap profile stops collection and still receives correct result. */
    @Test
    @RequiresFlagsEnabled(Flags.FLAG_TELEMETRY_APIS)
    public void testRequestHeapProfileCancel() throws Exception {
        if (mProfilingManager == null) throw new TestException("mProfilingManager can not be null");

        disableRateLimiter();

        // Set override durations to 10 minutes so we can ensure it finishes early when canceled.
        overrideHeapProfileDeviceConfigValues(
                false, TEN_MINUTES_MS, TEN_MINUTES_MS, TEN_MINUTES_MS);

        AppCallback callback = new AppCallback();
        CancellationSignal cancellationSignal = new CancellationSignal();

        // Now kick off the request.
        mProfilingManager.requestProfiling(
                ProfilingManager.PROFILING_TYPE_HEAP_PROFILE,
                null, // Use default parameters since we will cancel quickly
                null,
                cancellationSignal,
                new ImmediateExecutor(),
                callback);

        // Wait a bit for collection to get started.
        sleep(WAIT_TIME_FOR_PROFILING_START_MS);

        // Now request cancellation.
        cancellationSignal.cancel();

        // Wait until callback#onAccept is triggered so we can confirm the result.
        waitForCancelCallback(callback);

        // Assert that result matches assumptions for success.
        confirmCollectionSuccess(callback.mResult, OUTPUT_FILE_HEAP_PROFILE_SUFFIX);
    }

    /** Test that cancelling stack sampling stops collection and still receives correct result. */
    @Test
    @RequiresFlagsEnabled(Flags.FLAG_TELEMETRY_APIS)
    public void testRequestStackSamplingCancel() throws Exception {
        if (mProfilingManager == null) throw new TestException("mProfilingManager can not be null");

        disableRateLimiter();

        // Set override durations to 10 minutes so we can ensure it finishes early when canceled.
        overrideStackSamplingDeviceConfigValues(
                false, TEN_MINUTES_MS, TEN_MINUTES_MS, TEN_MINUTES_MS);

        AppCallback callback = new AppCallback();
        CancellationSignal cancellationSignal = new CancellationSignal();

        // Now kick off the request.
        mProfilingManager.requestProfiling(
                ProfilingManager.PROFILING_TYPE_STACK_SAMPLING,
                null, // Use default parameters since we will cancel quickly
                null,
                cancellationSignal,
                new ImmediateExecutor(),
                callback);

        // Wait a bit for collection to get started.
        sleep(WAIT_TIME_FOR_PROFILING_START_MS);

        // Now request cancellation.
        cancellationSignal.cancel();

        // Wait until callback#onAccept is triggered so we can confirm the result.
        waitForCancelCallback(callback);

        // Assert that result matches assumptions for success.
        confirmCollectionSuccess(callback.mResult, OUTPUT_FILE_STACK_SAMPLING_SUFFIX);
    }

    /** Test that cancelling stack sampling stops collection and still receives correct result. */
    @Test
    @RequiresFlagsEnabled({Flags.FLAG_TELEMETRY_APIS, Flags.FLAG_REDACTION_ENABLED})
    public void testRequestSystemTraceCancel() throws Exception {
        if (mProfilingManager == null) throw new TestException("mProfilingManager can not be null");

        disableRateLimiter();

        // Set override durations to 10 minutes so we can ensure it finishes early when canceled.
        overrideSystemTraceDeviceConfigValues(
                false, TEN_MINUTES_MS, TEN_MINUTES_MS, TEN_MINUTES_MS);

        AppCallback callback = new AppCallback();
        CancellationSignal cancellationSignal = new CancellationSignal();

        // Now kick off the request.
        mProfilingManager.requestProfiling(
                ProfilingManager.PROFILING_TYPE_SYSTEM_TRACE,
                null, // Use default parameters since we will cancel quickly
                null,
                cancellationSignal,
                new ImmediateExecutor(),
                callback);

        // Wait a bit for collection to get started.
        sleep(WAIT_TIME_FOR_PROFILING_START_MS);

        // Now request cancellation.
        cancellationSignal.cancel();

        // Wait until callback#onAccept is triggered so we can confirm the result.
        waitForCancelCallback(callback);

        // Assert that result matches assumptions for success.
        confirmCollectionSuccess(callback.mResult, OUTPUT_FILE_TRACE_SUFFIX);
    }

    /** Test that unregistering a global listener works and that listener does not get called. */
    @Test
    @SuppressWarnings("GuardedBy") // Suppress warning for mProfilingManager.mCallbacks lock.
    @RequiresFlagsEnabled(Flags.FLAG_TELEMETRY_APIS)
    public void testUnregisterGeneralListener() throws Exception {
        if (mProfilingManager == null) throw new TestException("mProfilingManager can not be null");

        disableRateLimiter();

        overrideStackSamplingDeviceConfigValues(
                false, ONE_SECOND_MS, ONE_SECOND_MS, FIVE_SECONDS_MS);

        // Clear all existing callbacks.
        mProfilingManager.mCallbacks.clear();

        // Create 2 callbacks.
        AppCallback callbackSpecific = new AppCallback();
        AppCallback callbackGeneral = new AppCallback();

        // Register the general callback.
        mProfilingManager.registerForAllProfilingResults(new ImmediateExecutor(), callbackGeneral);

        // Confirm callback is properly registered by checking for size of 1.
        assertTrue(mProfilingManager.mCallbacks.size() == 1);

        // Now unregister the general callback.
        mProfilingManager.unregisterForAllProfilingResults(callbackGeneral);

        // Now kick off the request.
        mProfilingManager.requestProfiling(
                ProfilingManager.PROFILING_TYPE_STACK_SAMPLING,
                getOneSecondDurationParamBundle(),
                null,
                null,
                new ImmediateExecutor(),
                callbackSpecific);

        // Wait until callback#onAccept is triggered so we can confirm the result.
        waitForCallback(callbackSpecific);

        // Assert that the unregistered callback was not triggered.
        assertNull(callbackGeneral.mResult);
    }

    /** Test that unregistering all global listeners works and that listeners do not get called. */
    @Test
    @SuppressWarnings("GuardedBy") // Suppress warning for mProfilingManager.mCallbacks lock.
    @RequiresFlagsEnabled(Flags.FLAG_TELEMETRY_APIS)
    public void testUnregisterAllGeneralListeners() throws Exception {
        if (mProfilingManager == null) throw new TestException("mProfilingManager can not be null");

        disableRateLimiter();

        overrideStackSamplingDeviceConfigValues(
                false, ONE_SECOND_MS, ONE_SECOND_MS, FIVE_SECONDS_MS);

        // Clear all existing callbacks.
        mProfilingManager.mCallbacks.clear();

        // Create 3 callbacks, 2 general and 1 specific.
        AppCallback callbackSpecific = new AppCallback();
        AppCallback callbackGeneral1 = new AppCallback();
        AppCallback callbackGeneral2 = new AppCallback();

        // Register both general callbacks.
        mProfilingManager.registerForAllProfilingResults(new ImmediateExecutor(), callbackGeneral1);
        mProfilingManager.registerForAllProfilingResults(new ImmediateExecutor(), callbackGeneral2);

        // Confirm callbacks are properly registered by checking for size of 2.
        assertTrue(mProfilingManager.mCallbacks.size() == 2);

        // Now unregister the general callbacks.
        mProfilingManager.unregisterForAllProfilingResults(null);

        // Now kick off the request.
        mProfilingManager.requestProfiling(
                ProfilingManager.PROFILING_TYPE_STACK_SAMPLING,
                getOneSecondDurationParamBundle(),
                null,
                null,
                new ImmediateExecutor(),
                callbackSpecific);

        // Wait until callback#onAccept is triggered so we can confirm the result.
        waitForCallback(callbackSpecific);

        // Assert that the unregistered callbacks were not triggered.
        assertNull(callbackGeneral1.mResult);
        assertNull(callbackGeneral2.mResult);
    }

    /** Test that a globally registered listener is triggered along with the specific one. */
    @Test
    @RequiresFlagsEnabled(Flags.FLAG_TELEMETRY_APIS)
    public void testTriggerAllListeners() throws Exception {
        if (mProfilingManager == null) throw new TestException("mProfilingManager can not be null");

        disableRateLimiter();

        overrideStackSamplingDeviceConfigValues(
                false, ONE_SECOND_MS, ONE_SECOND_MS, FIVE_SECONDS_MS);

        // Create 3 callbacks.
        AppCallback callbackSpecific = new AppCallback();
        AppCallback callbackGeneral1 = new AppCallback();
        AppCallback callbackGeneral2 = new AppCallback();

        // Register the first general callback before kicking off request.
        mProfilingManager.registerForAllProfilingResults(new ImmediateExecutor(), callbackGeneral1);

        // Now kick off the request.
        mProfilingManager.requestProfiling(
                ProfilingManager.PROFILING_TYPE_STACK_SAMPLING,
                getOneSecondDurationParamBundle(),
                null,
                null,
                new ImmediateExecutor(),
                callbackSpecific);

        // Register the 2nd general callback after kicking off request, but before result is ready.
        mProfilingManager.registerForAllProfilingResults(new ImmediateExecutor(), callbackGeneral2);

        // Wait until callback#onAccept is triggered so we can confirm the result.
        waitForCallback(callbackSpecific);

        // Assert that result matches assumptions for success in all callbacks.
        confirmCollectionSuccess(callbackSpecific.mResult, OUTPUT_FILE_STACK_SAMPLING_SUFFIX);
        confirmCollectionSuccess(callbackGeneral1.mResult, OUTPUT_FILE_STACK_SAMPLING_SUFFIX);
        confirmCollectionSuccess(callbackGeneral2.mResult, OUTPUT_FILE_STACK_SAMPLING_SUFFIX);
    }

    /** Test that listeners registered to the same UID from different contexts are all triggered. */
    @Test
    @RequiresFlagsEnabled(Flags.FLAG_TELEMETRY_APIS)
    public void testTriggerAllListenersDifferentContexts() throws Exception {
        if (mProfilingManager == null) throw new TestException("mProfilingManager can not be null");

        disableRateLimiter();

        overrideStackSamplingDeviceConfigValues(
                false, ONE_SECOND_MS, ONE_SECOND_MS, FIVE_SECONDS_MS);

        // Obtain another ProfilingManager instance from a different context.
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        // Confirm the 2 contexts are of a different class. This check is broader than is a strictly
        // required, but guarantees these contexts cannot be the same.
        assertFalse(mContext.getClass().equals(context.getClass()));
        ProfilingManager profilingManager = context.getSystemService(ProfilingManager.class);

        // Create 3 callbacks.
        AppCallback callbackSpecific = new AppCallback();
        AppCallback callbackGeneral1 = new AppCallback();
        AppCallback callbackGeneral2 = new AppCallback();

        // Register the general callbacks, one to each context.
        profilingManager.registerForAllProfilingResults(new ImmediateExecutor(), callbackGeneral1);
        mProfilingManager.registerForAllProfilingResults(new ImmediateExecutor(), callbackGeneral2);

        // Now kick off the request.
        mProfilingManager.requestProfiling(
                ProfilingManager.PROFILING_TYPE_STACK_SAMPLING,
                getOneSecondDurationParamBundle(),
                null,
                null,
                new ImmediateExecutor(),
                callbackSpecific);

        // Wait until callback#onAccept is triggered so we can confirm the result.
        waitForCallback(callbackSpecific);

        // Assert that result matches assumptions for success in all callbacks.
        confirmCollectionSuccess(callbackSpecific.mResult, OUTPUT_FILE_STACK_SAMPLING_SUFFIX);
        confirmCollectionSuccess(callbackGeneral1.mResult, OUTPUT_FILE_STACK_SAMPLING_SUFFIX);
        confirmCollectionSuccess(callbackGeneral2.mResult, OUTPUT_FILE_STACK_SAMPLING_SUFFIX);
    }

    /** Test that profiling request result file name contains the correct tag. */
    @Test
    @RequiresFlagsEnabled(Flags.FLAG_TELEMETRY_APIS)
    public void testRequestTagInFilename() throws Exception {
        if (mProfilingManager == null) throw new TestException("mProfilingManager can not be null");

        disableRateLimiter();

        overrideStackSamplingDeviceConfigValues(
                false, ONE_SECOND_MS, ONE_SECOND_MS, FIVE_SECONDS_MS);

        AppCallback callback = new AppCallback();

        // Setup tag to use with invalid chars and length, and expected cleaned up version.
        String fullTag = "TestTag-_-_-12345678901234567890\\\"&:|<>";
        String tagForFilename = "testtag---1234567890";

        // Now kick off the request.
        mProfilingManager.requestProfiling(
                ProfilingManager.PROFILING_TYPE_STACK_SAMPLING,
                getOneSecondDurationParamBundle(),
                fullTag,
                null,
                new ImmediateExecutor(),
                callback);

        // Wait until callback#onAccept is triggered so we can confirm the result.
        waitForCallback(callback);

        // Assert that used tag matches returned tag.
        assertTrue(fullTag.equals(callback.mResult.getTag()));

        // Split the path to obtain the filename.
        String[] pathArray = callback.mResult.getResultFilePath().split("/");
        // Then split the filename to obtain the tag section.
        String[] nameArray = pathArray[pathArray.length - 1].split("_");

        // Assert that the file name section containing the tag matches the expected filename tag.
        assertTrue(nameArray[1].equals(tagForFilename));
    }

    /** Test that java heap dump killswitch disables collection. */
    @Test
    @RequiresFlagsEnabled(Flags.FLAG_TELEMETRY_APIS)
    public void testJavaHeapDumpKillswitchEnabled() throws Exception {
        if (mProfilingManager == null) throw new TestException("mProfilingManager can not be null");

        disableRateLimiter();

        overrideJavaHeapDumpDeviceConfigValues(true, ONE_SECOND_MS, TEN_SECONDS_MS);

        AppCallback callback = new AppCallback();

        // Now kick off the request.
        mProfilingManager.requestProfiling(
                ProfilingManager.PROFILING_TYPE_JAVA_HEAP_DUMP,
                null,
                null,
                null,
                new ImmediateExecutor(),
                callback);

        // Wait until callback#onAccept is triggered so we can confirm the result.
        waitForCallback(callback);

        // Assert that request failed with correct error code.
        assertEquals(ProfilingResult.ERROR_FAILED_INVALID_REQUEST, callback.mResult.getErrorCode());
    }

    /** Test that heap profile killswitch disables collection. */
    @Test
    @RequiresFlagsEnabled(Flags.FLAG_TELEMETRY_APIS)
    public void testHeapProfileKillswitchEnabled() throws Exception {
        if (mProfilingManager == null) throw new TestException("mProfilingManager can not be null");

        disableRateLimiter();

        overrideHeapProfileDeviceConfigValues(true, ONE_SECOND_MS, FIVE_SECONDS_MS, TEN_SECONDS_MS);

        AppCallback callback = new AppCallback();

        // Now kick off the request.
        mProfilingManager.requestProfiling(
                ProfilingManager.PROFILING_TYPE_HEAP_PROFILE,
                null,
                null,
                null,
                new ImmediateExecutor(),
                callback);

        // Wait until callback#onAccept is triggered so we can confirm the result.
        waitForCallback(callback);

        // Assert that request failed with correct error code.
        assertEquals(ProfilingResult.ERROR_FAILED_INVALID_REQUEST, callback.mResult.getErrorCode());
    }

    /** Test that stack sampling killswitch disables collection. */
    @Test
    @RequiresFlagsEnabled(Flags.FLAG_TELEMETRY_APIS)
    public void testStackSamplingKillswitchEnabled() throws Exception {
        if (mProfilingManager == null) throw new TestException("mProfilingManager can not be null");

        disableRateLimiter();

        overrideStackSamplingDeviceConfigValues(
                true, ONE_SECOND_MS, FIVE_SECONDS_MS, TEN_SECONDS_MS);

        AppCallback callback = new AppCallback();

        // Now kick off the request.
        mProfilingManager.requestProfiling(
                ProfilingManager.PROFILING_TYPE_STACK_SAMPLING,
                null,
                null,
                null,
                new ImmediateExecutor(),
                callback);

        // Wait until callback#onAccept is triggered so we can confirm the result.
        waitForCallback(callback);

        // Assert that request failed with correct error code.
        assertEquals(ProfilingResult.ERROR_FAILED_INVALID_REQUEST, callback.mResult.getErrorCode());
    }

    /** Test that system trace killswitch disables collection. */
    @Test
    @RequiresFlagsEnabled({Flags.FLAG_TELEMETRY_APIS, Flags.FLAG_REDACTION_ENABLED})
    public void testSystemTraceKillswitchEnabled() throws Exception {
        if (mProfilingManager == null) throw new TestException("mProfilingManager can not be null");

        disableRateLimiter();

        overrideSystemTraceDeviceConfigValues(true, ONE_SECOND_MS, FIVE_SECONDS_MS, TEN_SECONDS_MS);

        AppCallback callback = new AppCallback();

        // Now kick off the request.
        mProfilingManager.requestProfiling(
                ProfilingManager.PROFILING_TYPE_SYSTEM_TRACE,
                null,
                null,
                null,
                new ImmediateExecutor(),
                callback);

        // Wait until callback#onAccept is triggered so we can confirm the result.
        waitForCallback(callback);

        // Assert that request failed with correct error code.
        assertEquals(ProfilingResult.ERROR_FAILED_INVALID_REQUEST, callback.mResult.getErrorCode());
    }

    /**
     * Test that adding a new general listener when no listeners have been added to that instance
     * works correctly, that is: that mProfilingService has been initialized.
     *
     * <p>The flow should result in registerResultsCallback being triggered with isGeneralListener
     * true and generalListenerAdded not being triggered, but we cannot confirm this specifically
     * here.
     */
    @SuppressWarnings("GuardedBy") // Suppress warning for mProfilingManager lock.
    @Test
    @RequiresFlagsEnabled({Flags.FLAG_TELEMETRY_APIS})
    public void testAddGeneralListenerNoCurrentListeners() throws Exception {
        if (mProfilingManager == null) throw new TestException("mProfilingManager can not be null");

        disableRateLimiter();

        // Setup for no current listener - mProfilingService should be null and mCallbacks empty.
        mProfilingManager.mProfilingService = null;
        mProfilingManager.mCallbacks.clear();

        AppCallback callback = new AppCallback();

        // Register the general callback.
        mProfilingManager.registerForAllProfilingResults(new ImmediateExecutor(), callback);

        // Confirm that mProfilingService has been initialized.
        assertNotNull(mProfilingManager.mProfilingService);
    }

    /**
     * Test that adding a new profiling instance specific listener when no listeners have been added
     * to that instance works correctly, that is: that mProfilingService has been initialized.
     *
     * <p>The flow should result in registerResultsCallback being triggered with isGeneralListener
     * false and generalListenerAdded not being triggered, but we cannot confirm this specifically
     * here.
     */
    @SuppressWarnings("GuardedBy") // Suppress warning for mProfilingManager lock.
    @Test
    @RequiresFlagsEnabled({Flags.FLAG_TELEMETRY_APIS})
    public void testAddSpecificListenerNoCurrentListeners() throws Exception {
        if (mProfilingManager == null) throw new TestException("mProfilingManager can not be null");

        disableRateLimiter();

        overrideStackSamplingDeviceConfigValues(
                false, ONE_SECOND_MS, ONE_SECOND_MS, FIVE_SECONDS_MS);

        // Setup for no current listener - mProfilingService should be null and mCallbacks empty.
        mProfilingManager.mProfilingService = null;
        mProfilingManager.mCallbacks.clear();

        AppCallback callback = new AppCallback();

        mProfilingManager.requestProfiling(
                ProfilingManager.PROFILING_TYPE_STACK_SAMPLING,
                null,
                null,
                null,
                new ImmediateExecutor(),
                callback);

        // Confirm that mProfilingService has been initialized.
        assertNotNull(mProfilingManager.mProfilingService);
    }

    /**
     * Test that adding a new general listener when a listener has already been added to that
     * instance works correctly, that is: generalListenerAdded is triggered, but
     * registerResultsCallback is not.
     */
    @SuppressWarnings("GuardedBy") // Suppress warning for mProfilingManager lock.
    @Test
    @RequiresFlagsEnabled({Flags.FLAG_TELEMETRY_APIS})
    public void testAddGeneralListenerWithCurrentListener() throws Exception {
        if (mProfilingManager == null) throw new TestException("mProfilingManager can not be null");

        disableRateLimiter();

        mProfilingManager.mProfilingService = spy(new ProfilingService(mContext));

        AppCallback callback = new AppCallback();

        // Register the general callback.
        mProfilingManager.registerForAllProfilingResults(new ImmediateExecutor(), callback);

        // Confirm that generalListenerAdded was triggered and registerResultsCallback was not.
        verify(mProfilingManager.mProfilingService, times(0))
                .registerResultsCallback(anyBoolean(), any());
        verify(mProfilingManager.mProfilingService, times(1)).generalListenerAdded();
    }

    /**
     * Test that adding a new profiling instance specific listener when a listener has already been
     * added to that instance works correctly, that is: neither registerResultsCallback nor
     * generalListenerAdded are triggered.
     */
    @SuppressWarnings("GuardedBy") // Suppress warning for mProfilingManager lock.
    @Test
    @RequiresFlagsEnabled({Flags.FLAG_TELEMETRY_APIS})
    public void testAddSpecificListenerWithCurrentListener() throws Exception {
        if (mProfilingManager == null) throw new TestException("mProfilingManager can not be null");

        disableRateLimiter();

        overrideStackSamplingDeviceConfigValues(
                false, ONE_SECOND_MS, ONE_SECOND_MS, FIVE_SECONDS_MS);

        mProfilingManager.mProfilingService = spy(new ProfilingService(mContext));

        AppCallback callback = new AppCallback();

        mProfilingManager.requestProfiling(
                ProfilingManager.PROFILING_TYPE_STACK_SAMPLING,
                null,
                null,
                null,
                new ImmediateExecutor(),
                callback);

        // Confirm that neither generalListenerAdded nor registerResultsCallback were triggered.
        verify(mProfilingManager.mProfilingService, times(0))
                .registerResultsCallback(anyBoolean(), any());
        verify(mProfilingManager.mProfilingService, times(0)).generalListenerAdded();
    }

    /**
     * Test adding a profiling trigger and receiving a result works correctly.
     *
     * <p>This is done by: adding the trigger through the public api, force starting a system
     * triggered trace, sending a fake trigger as if from the system, and then confirming the result
     * is received.
     */
    @SuppressWarnings("GuardedBy") // Suppress warning for mProfilingManager lock.
    @Test
    @RequiresFlagsEnabled(android.os.profiling.Flags.FLAG_SYSTEM_TRIGGERED_PROFILING_NEW)
    public void testSystemTriggeredProfiling() throws Exception {
        if (mProfilingManager == null) throw new TestException("mProfilingManager can not be null");

        disableRateLimiter();

        // First add a trigger
        ProfilingTrigger trigger =
                new ProfilingTrigger.Builder(ProfilingTrigger.TRIGGER_TYPE_ANR)
                        .setRateLimitingPeriodHours(1)
                        .build();
        mProfilingManager.addProfilingTriggers(List.of(trigger));

        // Verify the trigger and rate limiting period.
        assertEquals(ProfilingTrigger.TRIGGER_TYPE_ANR, trigger.getTriggerType());
        assertEquals(1, trigger.getRateLimitingPeriodHours());

        // And add a global listener
        AppCallback callbackGeneral = new AppCallback();
        mProfilingManager.registerForAllProfilingResults(new ImmediateExecutor(), callbackGeneral);

        // Then start the system triggered trace for testing.
        overrideDeviceConfig(
                DeviceConfigHelper.NAMESPACE_TESTING,
                DeviceConfigHelper.SYSTEM_TRIGGERED_DEBUG_PACKAGE_NAME,
                REAL_PACKAGE_NAME);

        // Wait a bit so the trace can get started and actually collect something.
        sleep(WAIT_TIME_FOR_PROFILING_START_MS);

        // Now fake a system trigger.
        ProfilingServiceHelper.getInstance()
                .onProfilingTriggerOccurred(
                        Binder.getCallingUid(),
                        REAL_PACKAGE_NAME,
                        ProfilingTrigger.TRIGGER_TYPE_ANR);

        // Wait for the trace to process.
        waitForCallback(callbackGeneral);

        // Finally, confirm that a result was received.
        confirmCollectionSuccess(
                callbackGeneral.mResult,
                OUTPUT_FILE_TRACE_SUFFIX,
                ProfilingTrigger.TRIGGER_TYPE_ANR);
    }

    /**
     * Test add all profiling triggers and receiving a result works correctly.
     *
     * <p>This is done by:
     *
     * <ul>
     *   <li>adding all triggers through the public api,
     *   <li>force starting a system triggered trace,
     *   <li>sending a fake trigger as if from the system, and then
     *   <li>confirming the result is received.
     * </ul>
     */
    @SuppressWarnings("GuardedBy") // Suppress warning for mProfilingManager lock.
    @Test
    @RequiresFlagsEnabled(android.os.profiling.Flags.FLAG_PROFILING_25Q4)
    public void testSystemTriggeredProfilingAddAll() throws Exception {
        if (mProfilingManager == null) throw new TestException("mProfilingManager can not be null");

        disableRateLimiter();

        mProfilingManager.addAllProfilingTriggers();

        // And add a global listener
        AppCallback callbackGeneral = new AppCallback();
        mProfilingManager.registerForAllProfilingResults(new ImmediateExecutor(), callbackGeneral);

        // Then start the system triggered trace for testing.
        overrideDeviceConfig(
                DeviceConfigHelper.NAMESPACE_TESTING,
                DeviceConfigHelper.SYSTEM_TRIGGERED_DEBUG_PACKAGE_NAME,
                REAL_PACKAGE_NAME);

        // Wait a bit so the trace can get started and actually collect something.
        sleep(WAIT_TIME_FOR_PROFILING_START_MS);

        // Now fake a system trigger.
        ProfilingServiceHelper.getInstance()
                .onProfilingTriggerOccurred(
                        Binder.getCallingUid(),
                        REAL_PACKAGE_NAME,
                        ProfilingTrigger.TRIGGER_TYPE_KILL_FORCE_STOP);

        // Wait for the trace to process.
        waitForCallback(callbackGeneral);

        // Finally, confirm that a result was received.
        confirmCollectionSuccess(
                callbackGeneral.mResult,
                OUTPUT_FILE_TRACE_SUFFIX,
                ProfilingTrigger.TRIGGER_TYPE_KILL_FORCE_STOP);
    }

    /**
     * Test request running trace trigger.
     *
     * <p>This is done by: adding the app request trigger through the public api, force starting a
     * system triggered trace, calling the app requested trace api, and then confirming the result
     * is received.
     */
    @SuppressWarnings("GuardedBy") // Suppress warning for mProfilingManager lock.
    @Test
    @RequiresFlagsEnabled(android.os.profiling.Flags.FLAG_PROFILING_25Q4)
    public void testSystemTriggeredProfilingRequestRunningTrace() throws Exception {
        if (mProfilingManager == null) throw new TestException("mProfilingManager can not be null");

        disableRateLimiter();

        // First add a trigger
        ProfilingTrigger trigger =
                new ProfilingTrigger.Builder(
                                ProfilingTrigger.TRIGGER_TYPE_APP_REQUEST_RUNNING_TRACE)
                        .build();
        mProfilingManager.addProfilingTriggers(List.of(trigger));

        // And add a global listener
        AppCallback callbackGeneral = new AppCallback();
        mProfilingManager.registerForAllProfilingResults(new ImmediateExecutor(), callbackGeneral);

        // Then start the system triggered trace for testing.
        overrideDeviceConfig(
                DeviceConfigHelper.NAMESPACE_TESTING,
                DeviceConfigHelper.SYSTEM_TRIGGERED_DEBUG_PACKAGE_NAME,
                REAL_PACKAGE_NAME);

        // Wait a bit so the trace can get started and actually collect something.
        sleep(WAIT_TIME_FOR_PROFILING_START_MS);

        String tag = "some_tag";

        // Now request the running trace.
        mProfilingManager.requestRunningSystemTrace(tag);

        // Wait for the trace to process.
        waitForCallback(callbackGeneral);

        // Finally, confirm that a result was received.
        confirmCollectionSuccess(
                callbackGeneral.mResult,
                OUTPUT_FILE_TRACE_SUFFIX,
                ProfilingTrigger.TRIGGER_TYPE_APP_REQUEST_RUNNING_TRACE);
        assertTrue(tag.equals(callbackGeneral.mResult.getTag()));
    }

    /**
     * Test removing profiling trigger.
     *
     * <p>There is no way to check the data structure from this context and that specifically is
     * tested in {@link ProfilingServiceTests}, so this test just ensures that a result is not
     * received.
     */
    @SuppressWarnings("GuardedBy") // Suppress warning for mProfilingManager lock.
    @Test
    @RequiresFlagsEnabled(android.os.profiling.Flags.FLAG_SYSTEM_TRIGGERED_PROFILING_NEW)
    public void testSystemTriggeredProfilingRemove() throws Exception {
        if (mProfilingManager == null) throw new TestException("mProfilingManager can not be null");

        disableRateLimiter();

        // First add a trigger
        ProfilingTrigger trigger =
                new ProfilingTrigger.Builder(ProfilingTrigger.TRIGGER_TYPE_ANR)
                        .setRateLimitingPeriodHours(1)
                        .build();
        mProfilingManager.addProfilingTriggers(List.of(trigger));

        // And add a global listener
        AppCallback callbackGeneral = new AppCallback();
        mProfilingManager.registerForAllProfilingResults(new ImmediateExecutor(), callbackGeneral);

        // Then start the system triggered trace for testing.
        overrideDeviceConfig(
                DeviceConfigHelper.NAMESPACE_TESTING,
                DeviceConfigHelper.SYSTEM_TRIGGERED_DEBUG_PACKAGE_NAME,
                REAL_PACKAGE_NAME);

        // Wait a bit so the trace can get started and actually collect something.
        sleep(WAIT_TIME_FOR_PROFILING_START_MS);

        // Remove the trigger.
        mProfilingManager.removeProfilingTriggersByType(
                new int[] {ProfilingTrigger.TRIGGER_TYPE_ANR});

        // Now fake a system trigger.
        ProfilingServiceHelper.getInstance()
                .onProfilingTriggerOccurred(
                        Binder.getCallingUid(),
                        REAL_PACKAGE_NAME,
                        ProfilingTrigger.TRIGGER_TYPE_ANR);

        // We can't wait for nothing to happen, so wait 10 seconds which should be long enough.
        sleep(WAIT_TIME_FOR_TRIGGERED_PROFILING_NO_RESULT);

        // Finally, confirm that no callback was received.
        assertNull(callbackGeneral.mResult);
    }

    /**
     * Test clearing all profiling triggers.
     *
     * <p>There is no way to check the data structure from this context and that specifically is
     * tested in {@link ProfilingServiceTests}, so this test just ensures that a result is not
     * received.
     */
    @SuppressWarnings("GuardedBy") // Suppress warning for mProfilingManager lock.
    @Test
    @RequiresFlagsEnabled(android.os.profiling.Flags.FLAG_SYSTEM_TRIGGERED_PROFILING_NEW)
    public void testSystemTriggeredProfilingClear() throws Exception {
        if (mProfilingManager == null) throw new TestException("mProfilingManager can not be null");

        disableRateLimiter();

        // First add a trigger
        ProfilingTrigger trigger =
                new ProfilingTrigger.Builder(ProfilingTrigger.TRIGGER_TYPE_ANR)
                        .setRateLimitingPeriodHours(1)
                        .build();
        mProfilingManager.addProfilingTriggers(List.of(trigger));

        // And add a global listener
        AppCallback callbackGeneral = new AppCallback();
        mProfilingManager.registerForAllProfilingResults(new ImmediateExecutor(), callbackGeneral);

        // Then start the system triggered trace for testing.
        overrideDeviceConfig(
                DeviceConfigHelper.NAMESPACE_TESTING,
                DeviceConfigHelper.SYSTEM_TRIGGERED_DEBUG_PACKAGE_NAME,
                REAL_PACKAGE_NAME);

        // Wait a bit so the trace can get started and actually collect something.
        sleep(WAIT_TIME_FOR_PROFILING_START_MS);

        // Clear all triggers for this process.
        mProfilingManager.clearProfilingTriggers();

        // Now fake a system trigger.
        ProfilingServiceHelper.getInstance()
                .onProfilingTriggerOccurred(
                        Binder.getCallingUid(),
                        REAL_PACKAGE_NAME,
                        ProfilingTrigger.TRIGGER_TYPE_ANR);

        // We can't wait for nothing to happen, so wait 10 seconds which should be long enough.
        sleep(WAIT_TIME_FOR_TRIGGERED_PROFILING_NO_RESULT);

        // Finally, confirm that no callback was received.
        assertNull(callbackGeneral.mResult);
    }

    /**
     * Test system triggered profiling for application crash case in which the crash is eligible for
     * profiling and the app is registered for the trigger.
     *
     * <p>Test confirms both that the correct result is received, and that the latch is correctly
     * counted down allowing callers to block on the latch.
     */
    @Test
    @RequiresFlagsEnabled(Flags.FLAG_PROFILING_TRIGGER_OOM)
    public void testSystemTriggeredApplicationCrashSuccess() throws Exception {
        if (mProfilingManager == null) throw new TestException("mProfilingManager can not be null");

        // Start the system triggered trace for testing as this covers rate limiting override for
        // triggers.
        overrideDeviceConfig(
                DeviceConfigHelper.NAMESPACE_TESTING,
                DeviceConfigHelper.SYSTEM_TRIGGERED_DEBUG_PACKAGE_NAME,
                REAL_PACKAGE_NAME);

        // Register for OOM trigger
        ProfilingTrigger trigger =
                new ProfilingTrigger.Builder(ProfilingTrigger.TRIGGER_TYPE_OOM).build();
        mProfilingManager.addProfilingTriggers(List.of(trigger));

        // And add a global listener
        AppCallback callbackGeneral = new AppCallback();
        mProfilingManager.registerForAllProfilingResults(
                new ProfilingTestUtils.ImmediateExecutor(), callbackGeneral);

        CountDownLatch latch = new CountDownLatch(1);

        // Fake a system trigger.
        ProfilingServiceHelper.getInstance()
                .profileApplicationCrash(
                        Binder.getCallingUid(),
                        REAL_PACKAGE_NAME,
                        new ApplicationErrorReport.CrashInfo(new OutOfMemoryError()),
                        latch);

        // Await up to 10 seconds, Java Heap Dump should take less than 5 seconds, so assert true
        // to ensure exit was due to latch counting down rather than timeout.
        assertTrue(latch.await(10, TimeUnit.SECONDS));

        // The latch counts down when collection is complete, but before a callback is necessarily
        // received, so wait for a bit.
        sleep(WAIT_TIME_FOR_PROFILING_POST_PROCESSING_MS);

        // Confirm that a result was received.
        confirmCollectionSuccess(
                callbackGeneral.mResult,
                OUTPUT_FILE_JAVA_HEAP_DUMP_SUFFIX,
                ProfilingTrigger.TRIGGER_TYPE_OOM);
    }

    /**
     * Test system triggered profiling for application crash case in which the crash is eligible for
     * profiling but the app is not registered for the trigger.
     *
     * <p>Test confirms both that no result is received, and that the latch is promptly counted down
     * allowing callers to block on the latch.
     */
    @Test
    @RequiresFlagsEnabled(Flags.FLAG_PROFILING_TRIGGER_OOM)
    @RequiresFlagsDisabled(Flags.FLAG_OOM_TRIGGER_EXPERIMENT_DO_NOT_RELEASE)
    public void testSystemTriggeredApplicationCrashNotRegistered() throws Exception {
        if (mProfilingManager == null) throw new TestException("mProfilingManager can not be null");

        // Start the system triggered trace for testing as this covers rate limiting override for
        // triggers.
        overrideDeviceConfig(
                DeviceConfigHelper.NAMESPACE_TESTING,
                DeviceConfigHelper.SYSTEM_TRIGGERED_DEBUG_PACKAGE_NAME,
                REAL_PACKAGE_NAME);

        // And add a global listener
        AppCallback callbackGeneral = new AppCallback();
        mProfilingManager.registerForAllProfilingResults(
                new ProfilingTestUtils.ImmediateExecutor(), callbackGeneral);

        CountDownLatch latch = new CountDownLatch(1);

        // Fake a system trigger.
        ProfilingServiceHelper.getInstance()
                .profileApplicationCrash(
                        Binder.getCallingUid(),
                        REAL_PACKAGE_NAME,
                        new ApplicationErrorReport.CrashInfo(new OutOfMemoryError()),
                        latch);

        // Await up to 1 second, since the trigger is not registered the latch should be counted
        // down in less than that time so assert true to ensure exit was not due to timeout.
        assertTrue(latch.await(1, TimeUnit.SECONDS));

        // Set wait time to timeout plus post processing wait time
        int waitTimeMs =
                DeviceConfigHelper.getInt(
                                        CONFIG_TIMEOUT_OOM, TIMEOUT_DEFAULT_JAVA_HEAP_DUMP_SECONDS)
                                * 1000
                        + WAIT_TIME_FOR_PROFILING_POST_PROCESSING_MS;
        sleep(waitTimeMs);

        // Confirm that no callback was received.
        assertNull(callbackGeneral.mResult);
    }

    /**
     * Test system triggered profiling for application crash case in which the crash is not eligible
     * for profiling.
     *
     * <p>Test confirms both that no result is received, and that the latch is promptly counted down
     * allowing callers to block on the latch.
     */
    @Test
    @RequiresFlagsEnabled(Flags.FLAG_PROFILING_TRIGGER_OOM)
    public void testSystemTriggeredApplicationCrashNotProfilingEligible() throws Exception {
        if (mProfilingManager == null) throw new TestException("mProfilingManager can not be null");

        // Start the system triggered trace for testing as this covers rate limiting override for
        // triggers.
        overrideDeviceConfig(
                DeviceConfigHelper.NAMESPACE_TESTING,
                DeviceConfigHelper.SYSTEM_TRIGGERED_DEBUG_PACKAGE_NAME,
                REAL_PACKAGE_NAME);

        mProfilingManager.addAllProfilingTriggers();

        // And add a global listener
        AppCallback callbackGeneral = new AppCallback();
        mProfilingManager.registerForAllProfilingResults(
                new ProfilingTestUtils.ImmediateExecutor(), callbackGeneral);

        CountDownLatch latch = new CountDownLatch(1);

        // Fake a system trigger for a NPE, which is not a type that is eligible for profiling.
        ProfilingServiceHelper.getInstance()
                .profileApplicationCrash(
                        Binder.getCallingUid(),
                        REAL_PACKAGE_NAME,
                        new ApplicationErrorReport.CrashInfo(new NullPointerException()),
                        latch);

        // Await up to 1 second, since the trigger is not registered the latch should be counted
        // down in less than that time so assert true to ensure exit was not due to timeout.
        assertTrue(latch.await(1, TimeUnit.SECONDS));

        // Set wait time to timeout plus post processing wait time
        int waitTimeMs =
                DeviceConfigHelper.getInt(
                                        CONFIG_TIMEOUT_OOM, TIMEOUT_DEFAULT_JAVA_HEAP_DUMP_SECONDS)
                                * 1000
                        + WAIT_TIME_FOR_PROFILING_POST_PROCESSING_MS;
        sleep(waitTimeMs);

        // Confirm that no callback was received.
        assertNull(callbackGeneral.mResult);
    }

    /**
     * Test {@link ProfilingResult} parcel read and write implementations match, correctly loading
     * result with the same values and leaving no data unread.
     */
    @Test
    @RequiresFlagsEnabled(Flags.FLAG_TELEMETRY_APIS)
    public void testProfilingResultParcelReadWriteMatch() throws Exception {
        // Create a fake ProfilingResult with all fields set.
        ProfilingResult result =
                new ProfilingResult(
                        ProfilingResult.ERROR_FAILED_RATE_LIMIT_SYSTEM,
                        "/path/to/file.type",
                        "some_tag",
                        "This is an error message.",
                        ProfilingTrigger.TRIGGER_TYPE_APP_FULLY_DRAWN);

        // Write to parcel.
        Parcel parcel = Parcel.obtain();
        result.writeToParcel(parcel, 0 /* flags */);

        // Set the data position back to 0 so it's ready to be read.
        parcel.setDataPosition(0);

        // Now load from the parcel.
        ProfilingResult resultFromParcel = new ProfilingResult(parcel);

        // Make sure there is no unread data remaining in the parcel, and confirm that the loaded
        // object is equal to the one it was written from. Check dataAvail first as if that check
        // fails then the next check will fail too, but knowing the status of this check will tell
        // us that we're missing a read or write. Check the objects are equals second as  if the
        // avail check passes and equals fails, then we know we're reading all the data just not to
        // the correct fields.
        assertEquals(0, parcel.dataAvail());
        assertTrue(result.equals(resultFromParcel));
    }

    /**
     * Test that profiling request fails system rate limiter when cost exceeds max.
     *
     * <p>This test in particular will fail the hour bucket just to verify end to end a system deny.
     * Testing for each time bucket is covered in service side tests.
     */
    @Test
    @RequiresFlagsEnabled({Flags.FLAG_TELEMETRY_APIS, Flags.FLAG_REDACTION_ENABLED})
    public void testRateLimiterDenySystem() throws Exception {
        if (mProfilingManager == null) throw new TestException("mProfilingManager can not be null");

        enableRateLimiter();

        // Override rate limiter values such that the system trace cost is more than the system
        // limits but less than the process limits.
        overrideSystemTraceDeviceConfigValues(false, ONE_SECOND_MS, ONE_SECOND_MS, FIVE_SECONDS_MS);
        overrideDeviceConfig(
                DeviceConfigHelper.NAMESPACE, DeviceConfigHelper.MAX_COST_SYSTEM_1_HOUR, 10);
        overrideDeviceConfig(
                DeviceConfigHelper.NAMESPACE, DeviceConfigHelper.MAX_COST_SYSTEM_24_HOUR, 10);
        overrideDeviceConfig(
                DeviceConfigHelper.NAMESPACE, DeviceConfigHelper.MAX_COST_SYSTEM_7_DAY, 10);
        overrideDeviceConfig(
                DeviceConfigHelper.NAMESPACE, DeviceConfigHelper.MAX_COST_PROCESS_1_HOUR, 1000);
        overrideDeviceConfig(
                DeviceConfigHelper.NAMESPACE, DeviceConfigHelper.MAX_COST_PROCESS_24_HOUR, 1000);
        overrideDeviceConfig(
                DeviceConfigHelper.NAMESPACE, DeviceConfigHelper.MAX_COST_PROCESS_7_DAY, 1000);
        overrideDeviceConfig(
                DeviceConfigHelper.NAMESPACE, DeviceConfigHelper.COST_SYSTEM_TRACE, 100);

        AppCallback callback = new AppCallback();

        // Now kick off the request.
        mProfilingManager.requestProfiling(
                ProfilingManager.PROFILING_TYPE_SYSTEM_TRACE,
                getOneSecondDurationParamBundle(),
                null,
                null,
                new ImmediateExecutor(),
                callback);

        // Wait until callback#onAccept is triggered so we can confirm the result.
        waitForCallback(callback);

        // Assert request failed with system rate limiting error.
        assertEquals(
                ProfilingResult.ERROR_FAILED_RATE_LIMIT_SYSTEM, callback.mResult.getErrorCode());
    }

    /**
     * Test that profiling request fails process rate limiter when cost exceeds max.
     *
     * <p>This test in particular will fail the hour bucket just to verify end to end a process
     * deny. Testing for each time bucket is covered in service side tests.
     */
    @Test
    @RequiresFlagsEnabled({Flags.FLAG_TELEMETRY_APIS, Flags.FLAG_REDACTION_ENABLED})
    public void testRateLimiterDenyProcess() throws Exception {
        if (mProfilingManager == null) throw new TestException("mProfilingManager can not be null");

        enableRateLimiter();

        // Override rate limiter values such that the system trace cost is more than the process
        // limits but less than the system limits.
        overrideSystemTraceDeviceConfigValues(false, ONE_SECOND_MS, ONE_SECOND_MS, FIVE_SECONDS_MS);
        overrideDeviceConfig(
                DeviceConfigHelper.NAMESPACE, DeviceConfigHelper.MAX_COST_SYSTEM_1_HOUR, 1000);
        overrideDeviceConfig(
                DeviceConfigHelper.NAMESPACE, DeviceConfigHelper.MAX_COST_SYSTEM_24_HOUR, 1000);
        overrideDeviceConfig(
                DeviceConfigHelper.NAMESPACE, DeviceConfigHelper.MAX_COST_SYSTEM_7_DAY, 1000);
        overrideDeviceConfig(
                DeviceConfigHelper.NAMESPACE, DeviceConfigHelper.MAX_COST_PROCESS_1_HOUR, 10);
        overrideDeviceConfig(
                DeviceConfigHelper.NAMESPACE, DeviceConfigHelper.MAX_COST_PROCESS_24_HOUR, 10);
        overrideDeviceConfig(
                DeviceConfigHelper.NAMESPACE, DeviceConfigHelper.MAX_COST_PROCESS_7_DAY, 10);
        overrideDeviceConfig(
                DeviceConfigHelper.NAMESPACE, DeviceConfigHelper.COST_SYSTEM_TRACE, 100);

        AppCallback callback = new AppCallback();

        // Now kick off the request.
        mProfilingManager.requestProfiling(
                ProfilingManager.PROFILING_TYPE_SYSTEM_TRACE,
                getOneSecondDurationParamBundle(),
                null,
                null,
                new ImmediateExecutor(),
                callback);

        // Wait until callback#onAccept is triggered so we can confirm the result.
        waitForCallback(callback);

        // Assert request failed with process rate limiting error.
        assertEquals(
                ProfilingResult.ERROR_FAILED_RATE_LIMIT_PROCESS, callback.mResult.getErrorCode());
    }

    /** Test that profiling request passes system rate limiter. */
    @Test
    @RequiresFlagsEnabled({Flags.FLAG_TELEMETRY_APIS, Flags.FLAG_REDACTION_ENABLED})
    public void testRateLimiterAllow() throws Exception {
        if (mProfilingManager == null) throw new TestException("mProfilingManager can not be null");

        enableRateLimiter();

        // Override rate limiter values such that the system trace cost is less than both the system
        // and process limits.
        overrideSystemTraceDeviceConfigValues(false, ONE_SECOND_MS, ONE_SECOND_MS, FIVE_SECONDS_MS);
        overrideDeviceConfig(
                DeviceConfigHelper.NAMESPACE, DeviceConfigHelper.MAX_COST_SYSTEM_1_HOUR, 1000);
        overrideDeviceConfig(
                DeviceConfigHelper.NAMESPACE, DeviceConfigHelper.MAX_COST_SYSTEM_24_HOUR, 1000);
        overrideDeviceConfig(
                DeviceConfigHelper.NAMESPACE, DeviceConfigHelper.MAX_COST_SYSTEM_7_DAY, 1000);
        overrideDeviceConfig(
                DeviceConfigHelper.NAMESPACE, DeviceConfigHelper.MAX_COST_PROCESS_1_HOUR, 1000);
        overrideDeviceConfig(
                DeviceConfigHelper.NAMESPACE, DeviceConfigHelper.MAX_COST_PROCESS_24_HOUR, 1000);
        overrideDeviceConfig(
                DeviceConfigHelper.NAMESPACE, DeviceConfigHelper.MAX_COST_PROCESS_7_DAY, 1000);
        overrideDeviceConfig(
                DeviceConfigHelper.NAMESPACE, DeviceConfigHelper.COST_SYSTEM_TRACE, 100);

        AppCallback callback = new AppCallback();

        // Now kick off the request.
        mProfilingManager.requestProfiling(
                ProfilingManager.PROFILING_TYPE_SYSTEM_TRACE,
                getOneSecondDurationParamBundle(),
                null,
                null,
                new ImmediateExecutor(),
                callback);

        // Wait until callback#onAccept is triggered so we can confirm the result.
        waitForCallback(callback);

        // Assert request returned with no error indicating that the rate limiter allowed the run.
        assertEquals(ProfilingResult.ERROR_NONE, callback.mResult.getErrorCode());
    }

    /**
     * Test that registering a trigger with an invalid trigger type fails with the correct
     * exception. The invalid type used here is value 1000 which is noted in docs to be reserved,
     * thus this test covers both that invalid triggers fail and that the reserved value isn't used.
     */
    @Test
    public void testInvalidTriggerType() throws Exception {
        if (mProfilingManager == null) throw new TestException("mProfilingManager can not be null");

        try {
            ProfilingTrigger trigger =
                    new ProfilingTrigger.Builder(-1 /* Invalid value reserved for all */).build();

            // This is not expected to happen as trigger type -1 should throw an exception.
            fail("Invalid trigger type did not throw Exception");
        } catch (IllegalArgumentException e) {
            // Do nothing, this is what we want.
        } catch (Exception e) {
            // Wrong exception type thrown, fail.
            fail("Invalid trigger type did not throw correct Exception");
        }
    }

    /** Disable the rate limiter and wait long enough for the update to be picked up. */
    private void disableRateLimiter() throws Exception {
        overrideRateLimiter(true);
    }

    /** Enable the rate limiter and wait long enough for the update to be picked up. */
    private void enableRateLimiter() throws Exception {
        overrideRateLimiter(false);
    }

    /** Wait for callback to be triggered. Waits for up to 5 minutes, checking every 5 seconds. */
    private void waitForCallback(AppCallback callback) {
        waitForCallback(
                callback, CALLBACK_WAIT_TIME_INCREMENT_MS, CALLBACK_WAIT_TIME_INCREMENTS_COUNT);
    }

    /**
     * Wait for callback to be triggered after cancellation. Waits for up to 20 seconds, checking
     * every 5 seconds.
     */
    private void waitForCancelCallback(AppCallback callback) {
        waitForCallback(
                callback,
                CALLBACK_WAIT_TIME_INCREMENT_MS,
                CALLBACK_CANCEL_WAIT_TIME_INCREMENTS_COUNT);
    }

    /**
     * Wait for callback to be triggered. Waits up to incrementMs * count, checking every
     * incrementMs milliseconds.
     */
    private void waitForCallback(AppCallback callback, int incrementMs, int count) {
        for (int i = 0; i < count; i++) {
            sleep(incrementMs);
            if (callback.mResult != null) {
                return;
            }
        }
        fail("Test timed out waiting for callback");
    }

    /** Assert that result matches a success case, specifically: contains a path and no errors. */
    private void confirmCollectionSuccess(ProfilingResult result, String suffix) {
        confirmCollectionSuccess(result, suffix, 0);
    }

    /** Assert that result matches a success case, specifically: contains a path and no errors. */
    private void confirmCollectionSuccess(ProfilingResult result, String suffix, int triggerType) {
        assertNotNull(result);
        assertEquals(ProfilingResult.ERROR_NONE, result.getErrorCode());
        assertNotNull(result.getResultFilePath());
        assertTrue(result.getResultFilePath().contains(suffix));
        if (Flags.addRateLimiterDisabledToResult()) {
            assertNotNull(result.getErrorMessage());
        } else {
            assertNull(result.getErrorMessage());
        }
        assertEquals(triggerType, result.getTriggerType());

        // Confirm output file exists and is not empty.
        File file = new File(result.getResultFilePath());
        assertTrue(file.exists());
        assertFalse(file.length() == 0);
    }

    /**
     * Copies the trace to an /sdcard directory that will be collected by the test runner.
     *
     * <p>Expected to be called only after validating that the profiling was successful.
     */
    private void dumpTrace(ProfilingResult result) {
        assertNotNull(result);
        assertNotNull(result.getResultFilePath());

        // Copy to dump directory
        Path path = Paths.get(result.getResultFilePath());
        try {
            Files.createDirectories(DUMP_PATH);
            String filename =
                    mTestName.getMethodName() + "_" + path.getFileName() + ".perfetto-trace";
            Files.copy(path, Paths.get(DUMP_PATH.toString(), filename));
        } catch (IOException e) {
            throw new AssertionError("Failed to copy to DUMP_PATH", e);
        }
    }

    private void overrideJavaHeapDumpDeviceConfigValues(
            boolean killswitchEnabled, int durationMs, int dataSourceTimeoutMs) throws Exception {
        overrideDeviceConfig(
                DeviceConfigHelper.NAMESPACE,
                DeviceConfigHelper.KILLSWITCH_JAVA_HEAP_DUMP,
                killswitchEnabled);
        overrideDeviceConfig(
                DeviceConfigHelper.NAMESPACE,
                DeviceConfigHelper.JAVA_HEAP_DUMP_DURATION_MS_DEFAULT,
                durationMs);
        overrideDeviceConfig(
                DeviceConfigHelper.NAMESPACE,
                DeviceConfigHelper.JAVA_HEAP_DUMP_DATA_SOURCE_STOP_TIMEOUT_MS_DEFAULT,
                dataSourceTimeoutMs);
    }

    private void overrideHeapProfileDeviceConfigValues(
            boolean killswitchEnabled, int durationDefaultMs, int durationMinMs, int durationMaxMs)
            throws Exception {
        overrideDeviceConfig(
                DeviceConfigHelper.NAMESPACE,
                DeviceConfigHelper.KILLSWITCH_HEAP_PROFILE,
                killswitchEnabled);
        overrideDeviceConfig(
                DeviceConfigHelper.NAMESPACE,
                DeviceConfigHelper.HEAP_PROFILE_DURATION_MS_DEFAULT,
                durationDefaultMs);
        overrideDeviceConfig(
                DeviceConfigHelper.NAMESPACE,
                DeviceConfigHelper.HEAP_PROFILE_DURATION_MS_MIN,
                durationMinMs);
        overrideDeviceConfig(
                DeviceConfigHelper.NAMESPACE,
                DeviceConfigHelper.HEAP_PROFILE_DURATION_MS_MAX,
                durationMaxMs);
    }

    private void overrideStackSamplingDeviceConfigValues(
            boolean killswitchEnabled, int durationDefaultMs, int durationMinMs, int durationMaxMs)
            throws Exception {
        overrideDeviceConfig(
                DeviceConfigHelper.NAMESPACE,
                DeviceConfigHelper.KILLSWITCH_STACK_SAMPLING,
                killswitchEnabled);
        overrideDeviceConfig(
                DeviceConfigHelper.NAMESPACE,
                DeviceConfigHelper.STACK_SAMPLING_DURATION_MS_DEFAULT,
                durationDefaultMs);
        overrideDeviceConfig(
                DeviceConfigHelper.NAMESPACE,
                DeviceConfigHelper.STACK_SAMPLING_DURATION_MS_MIN,
                durationMinMs);
        overrideDeviceConfig(
                DeviceConfigHelper.NAMESPACE,
                DeviceConfigHelper.STACK_SAMPLING_DURATION_MS_MAX,
                durationMaxMs);
    }

    private void overrideSystemTraceDeviceConfigValues(
            boolean killswitchEnabled, int durationDefaultMs, int durationMinMs, int durationMaxMs)
            throws Exception {
        overrideDeviceConfig(
                DeviceConfigHelper.NAMESPACE,
                DeviceConfigHelper.KILLSWITCH_SYSTEM_TRACE,
                killswitchEnabled);
        overrideDeviceConfig(
                DeviceConfigHelper.NAMESPACE,
                DeviceConfigHelper.SYSTEM_TRACE_DURATION_MS_DEFAULT,
                durationDefaultMs);
        overrideDeviceConfig(
                DeviceConfigHelper.NAMESPACE,
                DeviceConfigHelper.SYSTEM_TRACE_DURATION_MS_MIN,
                durationMinMs);
        overrideDeviceConfig(
                DeviceConfigHelper.NAMESPACE,
                DeviceConfigHelper.SYSTEM_TRACE_DURATION_MS_MAX,
                durationMaxMs);
    }

    private static native void doMallocAndFree();

    public static class AppCallback implements Consumer<ProfilingResult> {

        public ProfilingResult mResult;

        @Override
        public void accept(ProfilingResult result) {
            mResult = result;
        }
    }

    // Starts a thread that keeps a CPU busy.
    private static class BusyLoopThread {
        private Thread mThread;
        private AtomicBoolean mDone = new AtomicBoolean(false);

        BusyLoopThread() {
            mDone.set(false);
            mThread =
                    new Thread(
                            () -> {
                                while (!mDone.get()) {
                                    // Keep spinning!
                                }
                            });
            mThread.start();
        }

        public void stop() {
            mDone.set(true);
            try {
                mThread.join();
            } catch (InterruptedException e) {
                throw new AssertionError("InterruptedException", e);
            }
        }
    }

    // Starts a thread that repeatedly issues malloc() and free().
    private static class MallocLoopThread {
        private Thread mThread;
        private AtomicBoolean mDone = new AtomicBoolean(false);

        MallocLoopThread() {
            mDone.set(false);
            mThread =
                    new Thread(
                            () -> {
                                while (!mDone.get()) {
                                    doMallocAndFree();
                                    sleep(10);
                                }
                            });
            mThread.start();
        }

        public void stop() {
            mDone.set(true);
            try {
                mThread.join();
            } catch (InterruptedException e) {
                throw new AssertionError("InterruptedException", e);
            }
        }
    }
}
