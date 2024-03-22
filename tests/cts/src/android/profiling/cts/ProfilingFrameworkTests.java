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

import static org.junit.Assert.*;

import android.content.Context;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ProfilingManager;
import android.os.ProfilingResult;
import android.os.profiling.Flags;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.filters.LargeTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.compatibility.common.util.SystemUtil;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runner.RunWith;
import org.junit.runners.model.Statement;
import org.testng.TestException;

import java.io.File;
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

    private static ProfilingManager mProfilingManager = null;
    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    @Before
    public void setup() {
        Context context = ApplicationProvider.getApplicationContext();
        mProfilingManager = context.getSystemService(ProfilingManager.class);

        // This permission is required for Headless (HSUM) tests, including Auto.
        InstrumentationRegistry.getInstrumentation().getUiAutomation().adoptShellPermissionIdentity(
                android.Manifest.permission.INTERACT_ACROSS_USERS_FULL);

        // Disable the rate limiter, we're not testing that in any of these tests.
        disableRateLimiter();
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
    public void testRequestJavaHeapDumpSuccess() {
        if (mProfilingManager == null) throw new TestException("mProfilingManager can not be null");

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
    }

    /** Test that profiling request for heap profile succeeds and returns a non-empty file. */
    @Test
    @RequiresFlagsEnabled(Flags.FLAG_TELEMETRY_APIS)
    public void testRequestHeapProfileSuccess() {
        if (mProfilingManager == null) throw new TestException("mProfilingManager can not be null");

        AppCallback callback = new AppCallback();

        // Now kick off the request.
        mProfilingManager.requestProfiling(
                ProfilingManager.PROFILING_TYPE_HEAP_PROFILE,
                ProfilingTestUtils.getOneSecondDurationParamBundle(),
                null,
                null,
                new ProfilingTestUtils.ImmediateExecutor(),
                callback);

        // Wait until callback#onAccept is triggered so we can confirm the result.
        waitForCallback(callback);

        // Assert that result matches assumptions for success.
        confirmCollectionSuccess(callback.mResult, OUTPUT_FILE_HEAP_PROFILE_SUFFIX);
    }

    /** Test that profiling request for stack sampling succeeds and returns a non-empty file. */
    @Test
    @RequiresFlagsEnabled(Flags.FLAG_TELEMETRY_APIS)
    public void testRequestStackSamplingSuccess() {
        if (mProfilingManager == null) throw new TestException("mProfilingManager can not be null");

        AppCallback callback = new AppCallback();

        // Now kick off the request.
        mProfilingManager.requestProfiling(
                ProfilingManager.PROFILING_TYPE_STACK_SAMPLING,
                ProfilingTestUtils.getOneSecondDurationParamBundle(),
                null,
                null,
                new ProfilingTestUtils.ImmediateExecutor(),
                callback);

        // Wait until callback#onAccept is triggered so we can confirm the result.
        waitForCallback(callback);

        // Assert that result matches assumptions for success.
        confirmCollectionSuccess(callback.mResult, OUTPUT_FILE_STACK_SAMPLING_SUFFIX);
    }

    /**
     * Test that profiling request for system trace fails as it's disabled until redaction
     * is in place.
     */
    @Test
    @RequiresFlagsEnabled(Flags.FLAG_TELEMETRY_APIS)
    public void testRequestSystemTraceSuccess() {
        if (mProfilingManager == null) throw new TestException("mProfilingManager can not be null");

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

        // Assert trace failed as it's not yet supported.
        // TODO: b/327423523 update when redaction is in place.
        assertEquals(ProfilingResult.ERROR_FAILED_INVALID_REQUEST, callback.mResult.getErrorCode());
    }

    /** Test that cancelling stops collection and still receives correct result. */
    @Test
    @RequiresFlagsEnabled(Flags.FLAG_TELEMETRY_APIS)
    public void testRequestProfilingCancel() {
        if (mProfilingManager == null) throw new TestException("mProfilingManager can not be null");

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
        waitForCallback(callback);

        // Assert that result matches assumptions for success.
        confirmCollectionSuccess(callback.mResult, OUTPUT_FILE_STACK_SAMPLING_SUFFIX);
    }

    /** Test that unregistering a global listener works and that listener does not get called. */
    @Test
    @SuppressWarnings("GuardedBy") // Suppress warning for mProfilingManager.mCallbacks lock.
    @RequiresFlagsEnabled(Flags.FLAG_TELEMETRY_APIS)
    public void testUnregisterGeneralListener() {
        if (mProfilingManager == null) throw new TestException("mProfilingManager can not be null");

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
    public void testTriggerAllListeners() {
        if (mProfilingManager == null) throw new TestException("mProfilingManager can not be null");

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

    /** Test that profiling request result file name contains the correct tag. */
    @Test
    @RequiresFlagsEnabled(Flags.FLAG_TELEMETRY_APIS)
    public void testRequestTagInFilename() {
        if (mProfilingManager == null) throw new TestException("mProfilingManager can not be null");

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

    /** Disable the rate limiter and wait long enough for the update to be picked up. */
    private void disableRateLimiter() {
        SystemUtil.runShellCommand("device_config put profiling rate_limiter.disabled true");
        for (int i = 0; i < RATE_LIMITER_WAIT_TIME_INCREMENTS_COUNT; i++) {
            sleep(RATE_LIMITER_WAIT_TIME_INCREMENT_MS);
            String output = SystemUtil.runShellCommand(
                    "device_config get profiling rate_limiter.disabled");
            if (Boolean.parseBoolean(output.trim())) {
                return;
            }

        }
    }

    /** Wait for callback to be triggered. Waits for up to 10 minutes, checking every 30 seconds. */
    private void waitForCallback(AppCallback callback) {
        for (int i = 0; i < CALLBACK_WAIT_TIME_INCREMENTS_COUNT; i++) {
            sleep(CALLBACK_WAIT_TIME_INCREMENT_MS);
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

    private void sleep(long ms) {
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
}

