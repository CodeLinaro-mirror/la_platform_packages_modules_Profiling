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
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.app.Instrumentation;
import android.content.Context;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ProfilingManager;
import android.os.ProfilingResult;
import android.os.profiling.DeviceConfigHelper;
import android.os.profiling.Flags;
import android.os.profiling.ProfilingService;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.filters.LargeTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.compatibility.common.util.SystemUtil;

import com.google.errorprone.annotations.FormatMethod;

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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 *
 * Tests defined in this class are expected to test the API implementation.  All tests below require
 * the android.os.profiling.telemetry_apis flag to be enabled, otherwise you will receive an
 * assumed failure for any tests has the @RequiresFlagsEnabled annotation.
 *
 */

@RunWith(AndroidJUnit4.class)
public final class ProfilingFrameworkTests {

    // Wait for callback for 5 seconds at a time for up to 60 increments totalling 5 minutes.
    private static final int CALLBACK_WAIT_TIME_INCREMENT_MS = 5 * 1000;
    private static final int CALLBACK_WAIT_TIME_INCREMENTS_COUNT = 60;

    // Smaller number of increments for cancel case - wait for callback for 5 seconds at a time for
    // up to 4 increments totalling 20 seconds.
    private static final int CALLBACK_CANCEL_WAIT_TIME_INCREMENTS_COUNT = 4;

    // Wait for rate limiter config to update for 250 milliseconds at a time for up to 12 increments
    // totalling 3 seconds.
    private static final int RATE_LIMITER_WAIT_TIME_INCREMENT_MS = 250;
    private static final int RATE_LIMITER_WAIT_TIME_INCREMENTS_COUNT = 12;

    // Wait 2 seconds for profiling to get started before attempting to cancel it.
    private static final int WAIT_TIME_FOR_PROFILING_START_MS = 2 * 1000;

    // Keep in sync with {@link ProfilingService} because we can't access it.
    private static final String OUTPUT_FILE_JAVA_HEAP_DUMP_SUFFIX = ".perfetto-java-heap-dump";
    private static final String OUTPUT_FILE_HEAP_PROFILE_SUFFIX = ".perfetto-heap-profile";
    private static final String OUTPUT_FILE_STACK_SAMPLING_SUFFIX = ".perfetto-stack-sample";
    private static final String OUTPUT_FILE_TRACE_SUFFIX = ".perfetto-trace";

    public static final Path DUMP_PATH = FileSystems.getDefault()
            .getPath("/sdcard/ProfilesCollected/");

    private static final String COMMAND_OVERRIDE_DEVICE_CONFIG_INT = "device_config put %s %s %d";
    private static final String COMMAND_OVERRIDE_DEVICE_CONFIG_BOOL = "device_config put %s %s %b";

    private static final int ONE_SECOND_MS = 1 * 1000;
    private static final int FIVE_SECONDS_MS = 5 * 1000;
    private static final int TEN_SECONDS_MS = 10 * 1000;
    private static final int ONE_MINUTE_MS = 60 * 1000;
    private static final int FIVE_MINUTES_MS = 5 * 60 * 1000;
    private static final int TEN_MINUTES_MS = 10 * 60 * 1000;

    private ProfilingManager mProfilingManager = null;
    private Context mContext = null;
    private Instrumentation mInstrumentation;

    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    @Rule
    public final TestName mTestName = new TestName();

    @Before
    public void setup() {
        mContext = ApplicationProvider.getApplicationContext();
        mProfilingManager = mContext.getSystemService(ProfilingManager.class);
        mInstrumentation = InstrumentationRegistry.getInstrumentation();

        // This permission is required for Headless (HSUM) tests, including Auto.
        mInstrumentation.getUiAutomation().adoptShellPermissionIdentity(
                android.Manifest.permission.INTERACT_ACROSS_USERS_FULL);

        // Disable the rate limiter, we're not testing that in any of these tests.
        disableRateLimiter();
    }

    @SuppressWarnings("GuardedBy") // Suppress warning for mProfilingManager.mProfilingService lock.
    @After
    public void cleanup() {
        mProfilingManager.mProfilingService = null;
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
    public void testInvalidProfilingType() {
        if (mProfilingManager == null) throw new TestException("mProfilingManager can not be null");

        AppCallback callback = new AppCallback();

        // This call is passing an invalid profiling request type and should result in an error.
        mProfilingManager.requestProfiling(
                -1,
                null,
                null,
                null,
                new ProfilingTestUtils.ImmediateExecutor(),
                callback);

        // Wait until callback#onAccept is triggered so we can confirm the result.
        waitForCallback(callback);

        assertEquals(ProfilingResult.ERROR_FAILED_INVALID_REQUEST, callback.mResult.getErrorCode());
    }

    /** Test that request with invalid profiling params fails with correct error output. */
    @Test
    @RequiresFlagsEnabled(Flags.FLAG_TELEMETRY_APIS)
    public void testInvalidProfilingParams() {
        if (mProfilingManager == null) throw new TestException("mProfilingManager can not be null");

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
                new ProfilingTestUtils.ImmediateExecutor(),
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

        overrideJavaHeapDumpDeviceConfigValues(false, ONE_SECOND_MS, TEN_SECONDS_MS);

        AppCallback callback = new AppCallback();

        // Now kick off the request.
        mProfilingManager.requestProfiling(
                ProfilingManager.PROFILING_TYPE_JAVA_HEAP_DUMP,
                null,
                null,
                null,
                new ProfilingTestUtils.ImmediateExecutor(),
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

        overrideHeapProfileDeviceConfigValues(false, ONE_SECOND_MS, ONE_SECOND_MS, FIVE_SECONDS_MS);

        AppCallback callback = new AppCallback();

        // Add sampling interval param to test because it is currently the only long param.
        Bundle params = ProfilingTestUtils.getOneSecondDurationParamBundle();
        params.putLong(ProfilingManager.KEY_SAMPLING_INTERVAL_BYTES, 4096L);

        // Now kick off the request.
        mProfilingManager.requestProfiling(
                ProfilingManager.PROFILING_TYPE_HEAP_PROFILE,
                params,
                null,
                null,
                new ProfilingTestUtils.ImmediateExecutor(),
                callback);

        // Wait until callback#onAccept is triggered so we can confirm the result.
        waitForCallback(callback);

        // Assert that result matches assumptions for success.
        confirmCollectionSuccess(callback.mResult, OUTPUT_FILE_HEAP_PROFILE_SUFFIX);
        dumpTrace(callback.mResult);
    }

    /** Test that profiling request for stack sampling succeeds and returns a non-empty file. */
    @Test
    @RequiresFlagsEnabled(Flags.FLAG_TELEMETRY_APIS)
    public void testRequestStackSamplingSuccess() throws Exception {
        if (mProfilingManager == null) throw new TestException("mProfilingManager can not be null");

        overrideStackSamplingDeviceConfigValues(false, ONE_SECOND_MS, ONE_SECOND_MS,
                FIVE_SECONDS_MS);

        AppCallback callback = new AppCallback();

        // Now kick off the request.
        mProfilingManager.requestProfiling(
                ProfilingManager.PROFILING_TYPE_STACK_SAMPLING,
                ProfilingTestUtils.getOneSecondDurationParamBundle(),
                null,
                null,
                new ProfilingTestUtils.ImmediateExecutor(),
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
     * Test that profiling request for system trace fails as it's disabled until redaction
     * is in place.
     */
    @Test
    @RequiresFlagsEnabled({Flags.FLAG_TELEMETRY_APIS, Flags.FLAG_REDACTION_ENABLED})
    public void testRequestSystemTraceSuccess() throws Exception {
        if (mProfilingManager == null) throw new TestException("mProfilingManager can not be null");

        overrideSystemTraceDeviceConfigValues(false, ONE_SECOND_MS, ONE_SECOND_MS, FIVE_SECONDS_MS);

        AppCallback callback = new AppCallback();

        // Now kick off the request.
        mProfilingManager.requestProfiling(
                ProfilingManager.PROFILING_TYPE_SYSTEM_TRACE,
                ProfilingTestUtils.getOneSecondDurationParamBundle(),
                null,
                null,
                new ProfilingTestUtils.ImmediateExecutor(),
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

        // Set override duration and timeout to 10 minutes so we can ensure it finishes early when
        // canceled.
        overrideJavaHeapDumpDeviceConfigValues(false, TEN_MINUTES_MS, TEN_MINUTES_MS);

        AppCallback callback = new AppCallback();
        CancellationSignal cancellationSignal = new CancellationSignal();

        // Now kick off the request.
        mProfilingManager.requestProfiling(
                ProfilingManager.PROFILING_TYPE_JAVA_HEAP_DUMP,
                null,    // Use default parameters since we will cancel quickly
                null,
                cancellationSignal,
                new ProfilingTestUtils.ImmediateExecutor(),
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

        // Set override durations to 10 minutes so we can ensure it finishes early when canceled.
        overrideHeapProfileDeviceConfigValues(false, TEN_MINUTES_MS, TEN_MINUTES_MS,
                TEN_MINUTES_MS);

        AppCallback callback = new AppCallback();
        CancellationSignal cancellationSignal = new CancellationSignal();

        // Now kick off the request.
        mProfilingManager.requestProfiling(
                ProfilingManager.PROFILING_TYPE_HEAP_PROFILE,
                null,    // Use default parameters since we will cancel quickly
                null,
                cancellationSignal,
                new ProfilingTestUtils.ImmediateExecutor(),
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

        // Set override durations to 10 minutes so we can ensure it finishes early when canceled.
        overrideStackSamplingDeviceConfigValues(false, TEN_MINUTES_MS, TEN_MINUTES_MS,
                TEN_MINUTES_MS);

        AppCallback callback = new AppCallback();
        CancellationSignal cancellationSignal = new CancellationSignal();

        // Now kick off the request.
        mProfilingManager.requestProfiling(
                ProfilingManager.PROFILING_TYPE_STACK_SAMPLING,
                null,    // Use default parameters since we will cancel quickly
                null,
                cancellationSignal,
                new ProfilingTestUtils.ImmediateExecutor(),
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

        // Set override durations to 10 minutes so we can ensure it finishes early when canceled.
        overrideSystemTraceDeviceConfigValues(false, TEN_MINUTES_MS, TEN_MINUTES_MS,
                TEN_MINUTES_MS);

        AppCallback callback = new AppCallback();
        CancellationSignal cancellationSignal = new CancellationSignal();

        // Now kick off the request.
        mProfilingManager.requestProfiling(
                ProfilingManager.PROFILING_TYPE_SYSTEM_TRACE,
                null,    // Use default parameters since we will cancel quickly
                null,
                cancellationSignal,
                new ProfilingTestUtils.ImmediateExecutor(),
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

        overrideStackSamplingDeviceConfigValues(false, ONE_SECOND_MS, ONE_SECOND_MS,
                FIVE_SECONDS_MS);

        // Clear all existing callbacks.
        mProfilingManager.mCallbacks.clear();

        // Create 2 callbacks.
        AppCallback callbackSpecific = new AppCallback();
        AppCallback callbackGeneral = new AppCallback();

        // Register the general callback.
        mProfilingManager.registerForAllProfilingResults(
                new ProfilingTestUtils.ImmediateExecutor(), callbackGeneral);

        // Confirm callback is properly registered by checking for size of 1.
        assertTrue(mProfilingManager.mCallbacks.size() == 1);

        // Now unregister the general callback.
        mProfilingManager.unregisterForAllProfilingResults(callbackGeneral);

        // Now kick off the request.
        mProfilingManager.requestProfiling(
                ProfilingManager.PROFILING_TYPE_STACK_SAMPLING,
                ProfilingTestUtils.getOneSecondDurationParamBundle(),
                null,
                null,
                new ProfilingTestUtils.ImmediateExecutor(),
                callbackSpecific);

        // Wait until callback#onAccept is triggered so we can confirm the result.
        waitForCallback(callbackSpecific);

        // Assert that the unregistered callback was not triggered.
        assertNull(callbackGeneral.mResult);

    }

    /** Test that a globally registered listener is triggered along with the specific one. */
    @Test
    @RequiresFlagsEnabled(Flags.FLAG_TELEMETRY_APIS)
    public void testTriggerAllListeners() throws Exception {
        if (mProfilingManager == null) throw new TestException("mProfilingManager can not be null");

        overrideStackSamplingDeviceConfigValues(false, ONE_SECOND_MS, ONE_SECOND_MS,
                FIVE_SECONDS_MS);

        // Create 3 callbacks.
        AppCallback callbackSpecific = new AppCallback();
        AppCallback callbackGeneral1 = new AppCallback();
        AppCallback callbackGeneral2 = new AppCallback();

        // Register the first general callback before kicking off request.
        mProfilingManager.registerForAllProfilingResults(
                new ProfilingTestUtils.ImmediateExecutor(), callbackGeneral1);

        // Now kick off the request.
        mProfilingManager.requestProfiling(
                ProfilingManager.PROFILING_TYPE_STACK_SAMPLING,
                ProfilingTestUtils.getOneSecondDurationParamBundle(),
                null,
                null,
                new ProfilingTestUtils.ImmediateExecutor(),
                callbackSpecific);

        // Register the 2nd general callback after kicking off request, but before result is ready.
        mProfilingManager.registerForAllProfilingResults(
                new ProfilingTestUtils.ImmediateExecutor(), callbackGeneral2);

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

        overrideStackSamplingDeviceConfigValues(false, ONE_SECOND_MS, ONE_SECOND_MS,
                FIVE_SECONDS_MS);

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
        profilingManager.registerForAllProfilingResults(
                new ProfilingTestUtils.ImmediateExecutor(), callbackGeneral1);
        mProfilingManager.registerForAllProfilingResults(
                new ProfilingTestUtils.ImmediateExecutor(), callbackGeneral2);

        // Now kick off the request.
        mProfilingManager.requestProfiling(
                ProfilingManager.PROFILING_TYPE_STACK_SAMPLING,
                ProfilingTestUtils.getOneSecondDurationParamBundle(),
                null,
                null,
                new ProfilingTestUtils.ImmediateExecutor(),
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

        overrideStackSamplingDeviceConfigValues(false, ONE_SECOND_MS, ONE_SECOND_MS,
                FIVE_SECONDS_MS);

        AppCallback callback = new AppCallback();

        // Setup tag to use with invalid chars and length, and expected cleaned up version.
        String fullTag = "TestTag-_-_-12345678901234567890\\\"&:|<>";
        String tagForFilename = "testtag---1234567890";

        // Now kick off the request.
        mProfilingManager.requestProfiling(
                ProfilingManager.PROFILING_TYPE_STACK_SAMPLING,
                ProfilingTestUtils.getOneSecondDurationParamBundle(),
                fullTag,
                null,
                new ProfilingTestUtils.ImmediateExecutor(),
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

        overrideJavaHeapDumpDeviceConfigValues(true, ONE_SECOND_MS, TEN_SECONDS_MS);

        AppCallback callback = new AppCallback();

        // Now kick off the request.
        mProfilingManager.requestProfiling(
                ProfilingManager.PROFILING_TYPE_JAVA_HEAP_DUMP,
                null,
                null,
                null,
                new ProfilingTestUtils.ImmediateExecutor(),
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

        overrideHeapProfileDeviceConfigValues(true, ONE_SECOND_MS, FIVE_SECONDS_MS, TEN_SECONDS_MS);

        AppCallback callback = new AppCallback();

        // Now kick off the request.
        mProfilingManager.requestProfiling(
                ProfilingManager.PROFILING_TYPE_HEAP_PROFILE,
                null,
                null,
                null,
                new ProfilingTestUtils.ImmediateExecutor(),
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

        overrideStackSamplingDeviceConfigValues(true, ONE_SECOND_MS, FIVE_SECONDS_MS,
                TEN_SECONDS_MS);

        AppCallback callback = new AppCallback();

        // Now kick off the request.
        mProfilingManager.requestProfiling(
                ProfilingManager.PROFILING_TYPE_STACK_SAMPLING,
                null,
                null,
                null,
                new ProfilingTestUtils.ImmediateExecutor(),
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

        overrideSystemTraceDeviceConfigValues(true, ONE_SECOND_MS, FIVE_SECONDS_MS, TEN_SECONDS_MS);

        AppCallback callback = new AppCallback();

        // Now kick off the request.
        mProfilingManager.requestProfiling(
                ProfilingManager.PROFILING_TYPE_SYSTEM_TRACE,
                null,
                null,
                null,
                new ProfilingTestUtils.ImmediateExecutor(),
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
     * The flow should result in registerResultsCallback being triggered with isGeneralListener true
     * and generalListenerAdded not being triggered, but we cannot confirm this specifically here.
     */
    @SuppressWarnings("GuardedBy") // Suppress warning for mProfilingManager lock.
    @Test
    @RequiresFlagsEnabled({Flags.FLAG_TELEMETRY_APIS})
    public void testAddGeneralListenerNoCurrentListeners() throws Exception {
        if (mProfilingManager == null) throw new TestException("mProfilingManager can not be null");

        // Setup for no current listener - mProfilingService should be null and mCallbacks empty.
        mProfilingManager.mProfilingService = null;
        mProfilingManager.mCallbacks.clear();

        AppCallback callback = new AppCallback();

        // Register the general callback.
        mProfilingManager.registerForAllProfilingResults(new ProfilingTestUtils.ImmediateExecutor(),
                callback);

        // Confirm that mProfilingService has been initialized.
        assertNotNull(mProfilingManager.mProfilingService);
    }

    /**
     * Test that adding a new profiling instance specific listener when no listeners have been
     * added to that instance works correctly, that is: that mProfilingService has been initialized.
     *
     * The flow should result in registerResultsCallback being triggered with isGeneralListener
     * false and generalListenerAdded not being triggered, but we cannot confirm this specifically
     * here.
     */
    @SuppressWarnings("GuardedBy") // Suppress warning for mProfilingManager lock.
    @Test
    @RequiresFlagsEnabled({Flags.FLAG_TELEMETRY_APIS})
    public void testAddSpecificListenerNoCurrentListeners() throws Exception {
        if (mProfilingManager == null) throw new TestException("mProfilingManager can not be null");

        overrideStackSamplingDeviceConfigValues(false, ONE_SECOND_MS, ONE_SECOND_MS,
                FIVE_SECONDS_MS);

        // Setup for no current listener - mProfilingService should be null and mCallbacks empty.
        mProfilingManager.mProfilingService = null;
        mProfilingManager.mCallbacks.clear();

        AppCallback callback = new AppCallback();

        mProfilingManager.requestProfiling(
                ProfilingManager.PROFILING_TYPE_STACK_SAMPLING,
                null,
                null,
                null,
                new ProfilingTestUtils.ImmediateExecutor(),
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

        mProfilingManager.mProfilingService = spy(new ProfilingService(mContext));

        AppCallback callback = new AppCallback();

        // Register the general callback.
        mProfilingManager.registerForAllProfilingResults(new ProfilingTestUtils.ImmediateExecutor(),
                callback);

        // Confirm that generalListenerAdded was triggered and registerResultsCallback was not.
        verify(mProfilingManager.mProfilingService, times(0)).registerResultsCallback(anyBoolean(),
                any());
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

        overrideStackSamplingDeviceConfigValues(false, ONE_SECOND_MS, ONE_SECOND_MS,
                FIVE_SECONDS_MS);

        mProfilingManager.mProfilingService = spy(new ProfilingService(mContext));

        AppCallback callback = new AppCallback();

        mProfilingManager.requestProfiling(
                ProfilingManager.PROFILING_TYPE_STACK_SAMPLING,
                null,
                null,
                null,
                new ProfilingTestUtils.ImmediateExecutor(),
                callback);

        // Confirm that neither generalListenerAdded nor registerResultsCallback were triggered.
        verify(mProfilingManager.mProfilingService, times(0)).registerResultsCallback(anyBoolean(),
                any());
        verify(mProfilingManager.mProfilingService, times(0)).generalListenerAdded();
    }

    /** Disable the rate limiter and wait long enough for the update to be picked up. */
    private void disableRateLimiter() {
        SystemUtil.runShellCommand(
                "device_config put profiling_testing rate_limiter.disabled true");
        for (int i = 0; i < RATE_LIMITER_WAIT_TIME_INCREMENTS_COUNT; i++) {
            sleep(RATE_LIMITER_WAIT_TIME_INCREMENT_MS);
            String output = SystemUtil.runShellCommand(
                    "device_config get profiling_testing rate_limiter.disabled");
            if (Boolean.parseBoolean(output.trim())) {
                return;
            }

        }
    }

    /** Wait for callback to be triggered. Waits for up to 5 minutes, checking every 5 seconds. */
    private void waitForCallback(AppCallback callback) {
        waitForCallback(callback, CALLBACK_WAIT_TIME_INCREMENT_MS,
                CALLBACK_WAIT_TIME_INCREMENTS_COUNT);
    }

    /**
     * Wait for callback to be triggered after cancellation. Waits for up to 20 seconds, checking
     * every 5 seconds.
     */
    private void waitForCancelCallback(AppCallback callback) {
        waitForCallback(callback, CALLBACK_WAIT_TIME_INCREMENT_MS,
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
        assertNotNull(result);
        assertEquals(ProfilingResult.ERROR_NONE, result.getErrorCode());
        assertNotNull(result.getResultFilePath());
        assertTrue(result.getResultFilePath().contains(suffix));
        assertNull(result.getErrorMessage());

        // Confirm output file exists and is not empty.
        File file = new File(result.getResultFilePath());
        assertTrue(file.exists());
        assertFalse(file.length() == 0);
    }

    /** Copies the trace to an /sdcard directory that will be collected by the test runner. */
    private void dumpTrace(ProfilingResult result) {
        assertNotNull(result);
        assertEquals(ProfilingResult.ERROR_NONE, result.getErrorCode());
        assertNotNull(result.getResultFilePath());
        assertNull(result.getErrorMessage());

        // Copy to dump directory
        Path path = Paths.get(result.getResultFilePath());
        try {
            Files.createDirectories(DUMP_PATH);
            String filename = mTestName.getMethodName() + "_" + path.getFileName()
                    + ".perfetto-trace";
            Files.copy(path, Paths.get(DUMP_PATH.toString(), filename));
        } catch (IOException e) {
            throw new AssertionError("Failed to copy to DUMP_PATH", e);
        }
    }

    private void overrideJavaHeapDumpDeviceConfigValues(boolean killswitchEnabled, int durationMs,
            int dataSourceTimeoutMs) throws Exception {
        executeShellCmd(COMMAND_OVERRIDE_DEVICE_CONFIG_BOOL, DeviceConfigHelper.NAMESPACE,
                DeviceConfigHelper.KILLSWITCH_JAVA_HEAP_DUMP, killswitchEnabled);
        executeShellCmd(COMMAND_OVERRIDE_DEVICE_CONFIG_INT, DeviceConfigHelper.NAMESPACE,
                DeviceConfigHelper.JAVA_HEAP_DUMP_DURATION_MS_DEFAULT, durationMs);
        executeShellCmd(COMMAND_OVERRIDE_DEVICE_CONFIG_INT, DeviceConfigHelper.NAMESPACE,
                DeviceConfigHelper.JAVA_HEAP_DUMP_DATA_SOURCE_STOP_TIMEOUT_MS_DEFAULT,
                dataSourceTimeoutMs);
    }

    private void overrideHeapProfileDeviceConfigValues(boolean killswitchEnabled,
            int durationDefaultMs, int durationMinMs, int durationMaxMs) throws Exception {
        executeShellCmd(COMMAND_OVERRIDE_DEVICE_CONFIG_BOOL, DeviceConfigHelper.NAMESPACE,
                DeviceConfigHelper.KILLSWITCH_HEAP_PROFILE, killswitchEnabled);
        executeShellCmd(COMMAND_OVERRIDE_DEVICE_CONFIG_INT, DeviceConfigHelper.NAMESPACE,
                DeviceConfigHelper.HEAP_PROFILE_DURATION_MS_DEFAULT, durationDefaultMs);
        executeShellCmd(COMMAND_OVERRIDE_DEVICE_CONFIG_INT, DeviceConfigHelper.NAMESPACE,
                DeviceConfigHelper.HEAP_PROFILE_DURATION_MS_MIN, durationMinMs);
        executeShellCmd(COMMAND_OVERRIDE_DEVICE_CONFIG_INT, DeviceConfigHelper.NAMESPACE,
                DeviceConfigHelper.HEAP_PROFILE_DURATION_MS_MAX, durationMaxMs);
    }

    private void overrideStackSamplingDeviceConfigValues(boolean killswitchEnabled,
            int durationDefaultMs, int durationMinMs, int durationMaxMs) throws Exception {
        executeShellCmd(COMMAND_OVERRIDE_DEVICE_CONFIG_BOOL, DeviceConfigHelper.NAMESPACE,
                DeviceConfigHelper.KILLSWITCH_STACK_SAMPLING, killswitchEnabled);
        executeShellCmd(COMMAND_OVERRIDE_DEVICE_CONFIG_INT, DeviceConfigHelper.NAMESPACE,
                DeviceConfigHelper.STACK_SAMPLING_DURATION_MS_DEFAULT, durationDefaultMs);
        executeShellCmd(COMMAND_OVERRIDE_DEVICE_CONFIG_INT, DeviceConfigHelper.NAMESPACE,
                DeviceConfigHelper.STACK_SAMPLING_DURATION_MS_MIN, durationMinMs);
        executeShellCmd(COMMAND_OVERRIDE_DEVICE_CONFIG_INT, DeviceConfigHelper.NAMESPACE,
                DeviceConfigHelper.STACK_SAMPLING_DURATION_MS_MAX, durationMaxMs);
    }

    private void overrideSystemTraceDeviceConfigValues(boolean killswitchEnabled,
            int durationDefaultMs, int durationMinMs, int durationMaxMs) throws Exception {
        executeShellCmd(COMMAND_OVERRIDE_DEVICE_CONFIG_BOOL, DeviceConfigHelper.NAMESPACE,
                DeviceConfigHelper.KILLSWITCH_SYSTEM_TRACE, killswitchEnabled);
        executeShellCmd(COMMAND_OVERRIDE_DEVICE_CONFIG_INT, DeviceConfigHelper.NAMESPACE,
                DeviceConfigHelper.SYSTEM_TRACE_DURATION_MS_DEFAULT, durationDefaultMs);
        executeShellCmd(COMMAND_OVERRIDE_DEVICE_CONFIG_INT, DeviceConfigHelper.NAMESPACE,
                DeviceConfigHelper.SYSTEM_TRACE_DURATION_MS_MIN, durationMinMs);
        executeShellCmd(COMMAND_OVERRIDE_DEVICE_CONFIG_INT, DeviceConfigHelper.NAMESPACE,
                DeviceConfigHelper.SYSTEM_TRACE_DURATION_MS_MAX, durationMaxMs);
    }

    @FormatMethod
    private String executeShellCmd(String cmdFormat, Object... args) throws Exception {
        String cmd = String.format(cmdFormat, args);
        return SystemUtil.runShellCommand(mInstrumentation, cmd);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            // Do nothing.
        }
    }

    public static class AppCallback implements Consumer<ProfilingResult> {

        public ProfilingResult mResult;

        @Override
        public void accept(ProfilingResult result) {
            mResult = result;
        }
    }

    // Starts a thread that keeps a CPU busy.
    private static class BusyLoopThread {
        private Thread thread;
        private AtomicBoolean done = new AtomicBoolean(false);

        public BusyLoopThread() {
            done.set(false);
            thread = new Thread(() -> {
                while (!done.get()) {
                }
            });
            thread.start();
        }

        public void stop() {
            done.set(true);
            try {
                thread.join();
            } catch (InterruptedException e) {
                throw new AssertionError("InterruptedException", e);
            }
        }
    }
}
