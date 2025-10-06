/*
 * Copyright (C) 2025 The Android Open Source Project
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

import static android.profiling.cts.ProfilingTestUtils.ACTION_INIT_AND_ADD_APP_FULLY_DRAWN_TRIGGER;
import static android.profiling.cts.ProfilingTestUtils.ACTION_KEY;
import static android.profiling.cts.ProfilingTestUtils.ACTION_REGISTER_AND_REPORT_FULLY_DRAWN;
import static android.profiling.cts.ProfilingTestUtils.FILE_VALIDATION_RESULT_NONE;
import static android.profiling.cts.ProfilingTestUtils.FILE_VALIDATION_RESULT_SUCCESS;
import static android.profiling.cts.ProfilingTestUtils.REPLY_ACTION_COMPLETE;
import static android.profiling.cts.ProfilingTestUtils.REPLY_EXTRA_FILE_VALIDATION_RESULT;
import static android.profiling.cts.ProfilingTestUtils.REPLY_EXTRA_PROFILING_RESULT;
import static android.profiling.cts.ProfilingTestUtils.deleteDeviceConfig;
import static android.profiling.cts.ProfilingTestUtils.executeShellCmd;
import static android.profiling.cts.ProfilingTestUtils.overrideDeviceConfig;
import static android.profiling.cts.ProfilingTestUtils.resetNamespace;
import static android.profiling.cts.ProfilingTestUtils.sleep;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.fail;

import android.app.Instrumentation;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.ProfilingResult;
import android.os.ProfilingTrigger;
import android.os.profiling.DeviceConfigHelper;
import android.os.profiling.Flags;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.compatibility.common.util.AmUtils;

import com.google.common.truth.Expect;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;

@RunWith(AndroidJUnit4.class)
public class ProfilingAppTriggeredTests {
    private static final String TAG = ProfilingAppTriggeredTests.class.getSimpleName();
    private static final String STUB_PACKAGE_NAME = "android.profiling.cts.profilingapp";
    private static final String SIMPLE_ACTIVITY = ".ProfilingTriggerTestActivity";
    private static final String OUTPUT_FILE_TRACE_SUFFIX = ".perfetto-trace";
    private static final int WAIT_TIME_FOR_PROFILING_START_MS = 2 * 1000;
    private static final int WAIT_TIME_FOR_APP_START_MS = 1000;

    private Instrumentation mInstrumentation;
    private ResultReceiverFilter mResultReceiverFilter;

    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    @Rule public final Expect expect = Expect.create();

    @Before
    public void setup() {
        mInstrumentation = InstrumentationRegistry.getInstrumentation();
        resetNamespace(DeviceConfigHelper.NAMESPACE);
        resetNamespace(DeviceConfigHelper.NAMESPACE_TESTING);
    }

    @After
    public void cleanup() {
        deleteDeviceConfig(
                DeviceConfigHelper.NAMESPACE_TESTING,
                DeviceConfigHelper.SYSTEM_TRIGGERED_DEBUG_PACKAGE_NAME);

        if (mResultReceiverFilter != null) {
            mResultReceiverFilter.unregister();
        }

        executeShellCmd("am force-stop " + STUB_PACKAGE_NAME);
    }

    @Test
    @RequiresFlagsEnabled({Flags.FLAG_SYSTEM_TRIGGERED_PROFILING_NEW})
    public void testAppFullyDrawnTrigger() throws Exception {
        // Create a receiver to capture the broadcast intent sent by the test app.
        mResultReceiverFilter =
                new ResultReceiverFilter(
                        REPLY_ACTION_COMPLETE, /* resultsToWaitFor= */ 1, /* timeoutMs= */ 15_000);

        // Start the activity in the test app, which will clears all profiling triggers and adds an
        // app fully drawn profiling trigger.
        startActivityWithAction(ACTION_INIT_AND_ADD_APP_FULLY_DRAWN_TRIGGER);
        sleep(WAIT_TIME_FOR_APP_START_MS);

        // Stop the test app.
        executeShellCmd("am force-stop " + STUB_PACKAGE_NAME);

        // Set the device config to enable system-triggered debugging for the test app package.
        // This needs to be done after the test app registers a trigger so that an active trace
        // can start.
        // TODO(b/448723955): Move this to a common class.
        overrideDeviceConfig(
                DeviceConfigHelper.NAMESPACE_TESTING,
                DeviceConfigHelper.SYSTEM_TRIGGERED_DEBUG_PACKAGE_NAME,
                STUB_PACKAGE_NAME);
        // Wait a bit so the trace can get started and actually collect something.
        sleep(WAIT_TIME_FOR_PROFILING_START_MS);

        // Start the test app again to trigger reportFullyDrawn().
        startActivityWithAction(ACTION_REGISTER_AND_REPORT_FULLY_DRAWN);

        Log.d(TAG, "Waiting for broadcast receiver");
        if (!mResultReceiverFilter.waitForBroadcast()) {
            fail("Test timed out waiting for BroadcastReceiver");
        }

        // Assert that exactly one intent was received.
        assertThat(mResultReceiverFilter.getIntents().size()).isEqualTo(1);
        Bundle extras = mResultReceiverFilter.getIntents().getFirst().getExtras();
        assertThat(extras).isNotNull();

        // TODO(b/448727390): Move these to a common class.
        ProfilingResult result =
                extras.getParcelable(REPLY_EXTRA_PROFILING_RESULT, ProfilingResult.class);
        assertThat(result).isNotNull();

        String filePath = result.getResultFilePath();
        expect.that(result.getErrorCode()).isEqualTo(ProfilingResult.ERROR_NONE);
        expect.that(filePath).isNotNull();
        expect.that(filePath).contains(OUTPUT_FILE_TRACE_SUFFIX);
        expect.that(result.getTriggerType())
                .isEqualTo(ProfilingTrigger.TRIGGER_TYPE_APP_FULLY_DRAWN);

        int fileValidationResult =
                extras.getInt(REPLY_EXTRA_FILE_VALIDATION_RESULT, FILE_VALIDATION_RESULT_NONE);
        // Profiling caller test app receives the result file and validates it. Here we verify the
        // validation result code.
        assertThat(fileValidationResult).isEqualTo(FILE_VALIDATION_RESULT_SUCCESS);
    }

    /**
     * Starts the test app's activity with a specific action.
     *
     * <p>The test app will perform a different operation based on the provided action code.
     *
     * @param action The action to be passed to the activity.
     */
    private static void startActivityWithAction(int action) {
        executeShellCmd(
                "am start -W -n %s/%s --ei %s %d",
                STUB_PACKAGE_NAME, SIMPLE_ACTIVITY, ACTION_KEY, action);
    }

    // Helper class to receive and manage broadcast intents.
    private class ResultReceiverFilter extends BroadcastReceiver {
        private final String mAction;
        private final int mResultsToWaitFor;
        private final List<Intent> mIntents = new ArrayList<>();
        private final int mTimeoutMs;

        ResultReceiverFilter(String action, int resultsToWaitFor, int timeoutMs) {
            mAction = action;
            mResultsToWaitFor = resultsToWaitFor;
            mTimeoutMs = timeoutMs;
            IntentFilter filter = new IntentFilter();
            filter.addAction(mAction);
            mInstrumentation
                    .getTargetContext()
                    .registerReceiver(this, filter, Context.RECEIVER_EXPORTED);
        }

        @Override
        // Called when a broadcast is received.
        public void onReceive(Context context, Intent intent) {
            Log.d(TAG, "onReceive: " + intent.getAction());
            if (intent.getAction().equals(mAction)) {
                synchronized (this) {
                    mIntents.add(intent);
                    if (mIntents.size() >= mResultsToWaitFor) {
                        notifyAll();
                    }
                }
            }
        }

        // Waits for the expected number of broadcasts or until timeout.
        private boolean waitForBroadcast() throws InterruptedException {
            AmUtils.waitForBroadcastBarrier();
            synchronized (this) {
                // wait() must always be called in a loop. This pattern prevents spurious wakeups by
                // ensuring the condition is re-checked if the thread wakes up unexpectedly.
                long now = System.currentTimeMillis();
                long deadline = now + mTimeoutMs;
                while (mIntents.size() < mResultsToWaitFor && now < deadline) {
                    wait(deadline - now);
                    now = System.currentTimeMillis();
                }
                Log.d(TAG, "Received " + mIntents.size() + " intents");
                return mIntents.size() >= mResultsToWaitFor;
            }
        }

        // Returns the list of received intents.
        private List<Intent> getIntents() {
            return mIntents;
        }

        private void unregister() {
            synchronized (this) {
                mInstrumentation.getTargetContext().unregisterReceiver(this);
            }
        }
    }
}
