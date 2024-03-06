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
import android.os.CancellationSignal;
import android.os.ProfilingManager;
import android.os.ProfilingResult;
import android.os.profiling.Flags;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;

import androidx.test.core.app.ApplicationProvider;
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

    // Wait for callback for 30 seconds at a time for up to 20 increments totalling 10 minutes.
    private static final int CALLBACK_WAIT_TIME_INCREMENT_MS = 30 * 1000;
    private static final int CALLBACK_WAIT_TIME_INCREMENTS_COUNT = 20;

    // Wait for rate limiter config to update for 250 milliseconds at a time for up to 12 increments
    // totalling 3 seconds.
    private static final int RATE_LIMITER_WAIT_TIME_INCREMENT_MS = 250;
    private static final int RATE_LIMITER_WAIT_TIME_INCREMENTS_COUNT = 12;

    private static ProfilingManager mProfilingManager = null;
    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    @Before
    public void setup() {
        Context context = ApplicationProvider.getApplicationContext();
        mProfilingManager = context.getSystemService(ProfilingManager.class);
    }

    /** Check and see if we can get a reference to the ProfilingManager service. */
    @Test
    @RequiresFlagsEnabled(Flags.FLAG_TELEMETRY_APIS)
    public void createServiceTest() {
        assertNotNull(mProfilingManager);
    }

    /** Test that request with invalid input fails with correct error output. */
    @Test
    @RequiresFlagsEnabled(Flags.FLAG_TELEMETRY_APIS)
    public void testInvalidProfilingRequest() {
        if (mProfilingManager == null) throw new TestException("mProfilingManager can not be null");

        AppCallback callback = new AppCallback();

        // This call is passing an invalid profiling request config and should result in a parsing
        // error and a ERROR_FAILED_INVALID_REQUEST as the error delivered to resultConsumer.
        mProfilingManager.requestProfiling(
                new byte[16],
                null,
                null,
                new ProfilingTestUtils.ImmediateExecutor(),
                callback);

        // Wait until callback#onAccept is triggered so we can confirm the result.
        waitForCallback(callback);

        assertEquals(ProfilingResult.ERROR_FAILED_INVALID_REQUEST, callback.mResult.getErrorCode());
    }

    /** Test that profiling request for java heap dump succeeds and returns a file. */
    @Test
    @RequiresFlagsEnabled(Flags.FLAG_TELEMETRY_APIS)
    public void testRequestJavaHeapDumpSuccess() {
        if (mProfilingManager == null) throw new TestException("mProfilingManager can not be null");

        // Disable the rate limiter, we're not testing that.
        disableRateLimiter();

        AppCallback callback = new AppCallback();

        // Now kick off the request.
        mProfilingManager.requestProfiling(
                ProfilingTestUtils.getJavaHeapDumpProfilingRequest(),
                null,
                null,
                new ProfilingTestUtils.ImmediateExecutor(),
                callback);

        // Wait until callback#onAccept is triggered so we can confirm the result.
        waitForCallback(callback);

        // Assert that result matches assumptions for success.
        confirmCollectionSuccess(callback.mResult);
    }

    /** Test that profiling request for heap profile succeeds and returns a file. */
    @Test
    @RequiresFlagsEnabled(Flags.FLAG_TELEMETRY_APIS)
    public void testRequestHeapProfileSuccess() {
        if (mProfilingManager == null) throw new TestException("mProfilingManager can not be null");

        // Disable the rate limiter, we're not testing that.
        disableRateLimiter();

        AppCallback callback = new AppCallback();

        // Now kick off the request.
        mProfilingManager.requestProfiling(
                ProfilingTestUtils.getHeapProfileProfilingRequest(),
                null,
                null,
                new ProfilingTestUtils.ImmediateExecutor(),
                callback);

        // Wait until callback#onAccept is triggered so we can confirm the result.
        waitForCallback(callback);

        // Assert that result matches assumptions for success.
        confirmCollectionSuccess(callback.mResult);
    }

    /** Test that profiling request for stack sampling succeeds and returns a file. */
    @Test
    @RequiresFlagsEnabled(Flags.FLAG_TELEMETRY_APIS)
    public void testRequestStackSamplingSuccess() {
        if (mProfilingManager == null) throw new TestException("mProfilingManager can not be null");

        // Disable the rate limiter, we're not testing that.
        disableRateLimiter();

        AppCallback callback = new AppCallback();

        // Now kick off the request.
        mProfilingManager.requestProfiling(
                ProfilingTestUtils.getStackSamplingProfilingRequest(),
                null,
                null,
                new ProfilingTestUtils.ImmediateExecutor(),
                callback);

        // Wait until callback#onAccept is triggered so we can confirm the result.
        waitForCallback(callback);

        // Assert that result matches assumptions for success.
        confirmCollectionSuccess(callback.mResult);
    }

    /**
     * Test that profiling request for system trace fails as it's disabled until redaction
     * is in place.
     */
    @Test
    @RequiresFlagsEnabled(Flags.FLAG_TELEMETRY_APIS)
    public void testRequestSystemTraceSuccess() {
        if (mProfilingManager == null) throw new TestException("mProfilingManager can not be null");

        // Disable the rate limiter, we're not testing that.
        disableRateLimiter();

        AppCallback callback = new AppCallback();

        // Now kick off the request.
        mProfilingManager.requestProfiling(
                ProfilingTestUtils.getSystemTraceProfilingRequest(),
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

        // Disable the rate limiter, we're not testing that.
        disableRateLimiter();

        AppCallback callback = new AppCallback();
        CancellationSignal cancellationSignal = new CancellationSignal();

        // Now kick off the request.
        mProfilingManager.requestProfiling(
                ProfilingTestUtils.getJavaHeapDumpProfilingRequest(), // TODO: b/327423523 use trace
                null,
                cancellationSignal,
                new ProfilingTestUtils.ImmediateExecutor(),
                callback);

        // Wait a bit for collection to get started.
        sleep(1000);

        // Now request cancellation.
        cancellationSignal.cancel();

        // Wait until callback#onAccept is triggered so we can confirm the result.
        waitForCallback(callback);

        // Assert that result matches assumptions for success.
        confirmCollectionSuccess(callback.mResult);
    }

    /** Test that unregistering a global listener works and that listener does not get called. */
    @Test
    @SuppressWarnings("GuardedBy") // Suppress warning for mProfilingManager.mCallbacks lock.
    @RequiresFlagsEnabled(Flags.FLAG_TELEMETRY_APIS)
    public void testUnregisterGeneralListener() {
        if (mProfilingManager == null) throw new TestException("mProfilingManager can not be null");

        // Clear all existing callbacks.
        mProfilingManager.mCallbacks.clear();

        // Disable the rate limiter, we're not testing that.
        disableRateLimiter();

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
                ProfilingTestUtils.getJavaHeapDumpProfilingRequest(),
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

        // Disable the rate limiter, we're not testing that.
        disableRateLimiter();

        // Create 3 callbacks.
        AppCallback callbackSpecific = new AppCallback();
        AppCallback callbackGeneral1 = new AppCallback();
        AppCallback callbackGeneral2 = new AppCallback();

        // Register the first general callback before kicking off request.
        mProfilingManager.registerForAllProfilingResults(
                new ProfilingTestUtils.ImmediateExecutor(), callbackGeneral1);

        // Now kick off the request.
        mProfilingManager.requestProfiling(
                ProfilingTestUtils.getJavaHeapDumpProfilingRequest(),
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
        confirmCollectionSuccess(callbackSpecific.mResult);
        confirmCollectionSuccess(callbackGeneral1.mResult);
        confirmCollectionSuccess(callbackGeneral2.mResult);
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
    private void confirmCollectionSuccess(ProfilingResult result) {
        assertNotNull(result);
        assertEquals(ProfilingResult.ERROR_NONE, result.getErrorCode());
        assertNotNull(result.getResultFilePath());
        assertNull(result.getErrorMessage());
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

