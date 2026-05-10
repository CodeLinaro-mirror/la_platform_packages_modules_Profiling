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

import static android.content.Intent.FLAG_RECEIVER_FOREGROUND;
import static android.profiling.cts.ProfilingTestConstants.ACTION_INIT_AND_ADD_APP_FULLY_DRAWN_TRIGGER;
import static android.profiling.cts.ProfilingTestConstants.ACTION_INIT_AND_REQUEST_RUNNING_TRACE;
import static android.profiling.cts.ProfilingTestConstants.ACTION_KEY;
import static android.profiling.cts.ProfilingTestConstants.ACTION_REGISTER_AND_REPORT_FULLY_DRAWN;
import static android.profiling.cts.ProfilingTestConstants.ACTION_REGISTER_PROFILING_CALLBACK;
import static android.profiling.cts.ProfilingTestConstants.ACTION_SETUP_KILL_FORCE_STOP_TRIGGER;
import static android.profiling.cts.ProfilingTestConstants.ACTION_SETUP_KILL_RECENTS_TRIGGER;
import static android.profiling.cts.ProfilingTestConstants.ACTION_SETUP_KILL_TASK_MANAGER_TRIGGER;
import static android.profiling.cts.ProfilingTestConstants.ACTION_SETUP_PROFILING_TRIGGER_AND_TRIGGER_ANR;
import static android.profiling.cts.ProfilingTestConstants.FILE_VALIDATION_RESULT_NONE;
import static android.profiling.cts.ProfilingTestConstants.FILE_VALIDATION_RESULT_SUCCESS;
import static android.profiling.cts.ProfilingTestConstants.REPLY_ACTION_COMPLETE;
import static android.profiling.cts.ProfilingTestConstants.REPLY_EXTRA_FILE_VALIDATION_RESULT;
import static android.profiling.cts.ProfilingTestConstants.REPLY_EXTRA_PROFILING_RESULT;
import static android.profiling.cts.ProfilingTestUtils.OUTPUT_FILE_TRACE_SUFFIX;
import static android.profiling.cts.ProfilingTestUtils.assertProfilingResultSuccess;
import static android.profiling.cts.ProfilingTestUtils.executeShellCmd;
import static android.profiling.cts.ProfilingTestUtils.isProcessAlive;
import static android.profiling.cts.ProfilingTestUtils.resetAllConfigs;
import static android.profiling.cts.ProfilingTestUtils.sleep;
import static android.profiling.cts.ProfilingTestUtils.startSystemTriggeredTraceForTesting;
import static android.profiling.cts.ProfilingTestUtils.waitForCondition;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import android.app.Instrumentation;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Process;
import android.os.ProfilingResult;
import android.os.ProfilingTrigger;
import android.os.profiling.Flags;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.compatibility.common.util.AmUtils;
import com.android.modules.utils.build.SdkLevel;

import com.google.common.truth.Expect;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RunWith(AndroidJUnit4.class)
public class ProfilingAppTriggeredTests {
    private static final String TAG = ProfilingAppTriggeredTests.class.getSimpleName();
    private static final String STUB_PACKAGE_NAME = "android.profiling.cts.profilingapp";
    private static final String SIMPLE_ACTIVITY = ".ProfilingTriggerTestActivity";

    private static final int BROADCAST_RECEIVER_TIMEOUT_MS = 15_000;
    private static final int WAIT_FOR_CONDITION_INCREMENTS_MS = 500;
    private static final int ACTION_TIMEOUT_MS = 30_000;
    private static final int BROADCAST_FG_TIMEOUT_MS = 10_000;
    // ActivityManager will dump the ANR info and send the ANR trigger to ProfilingService in the
    // end. Adding delay to make sure the ANR handled by ActivityManager is finished and
    // send the trigger to ProfilingService.
    private static final int ANR_POST_PROCESS_DELAY_MS = 15_000;

    private Instrumentation mInstrumentation;
    private ResultReceiverFilter mResultReceiverFilter;
    private int mTestRunningUserId;

    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    @Rule public final Expect expect = Expect.create();

    @Before
    public void setup() {
        mInstrumentation = InstrumentationRegistry.getInstrumentation();
        mTestRunningUserId = Process.myUserHandle().getIdentifier();
    }

    @After
    public void cleanup() {
        resetAllConfigs();

        if (mResultReceiverFilter != null) {
            mResultReceiverFilter.unregister();
        }

        executeShellCmd("am force-stop --user %d %s", mTestRunningUserId, STUB_PACKAGE_NAME);
    }

    @Test
    @RequiresFlagsEnabled({Flags.FLAG_SYSTEM_TRIGGERED_PROFILING_NEW})
    public void testAppFullyDrawnTrigger() throws Exception {
        // AppFullyDrawn trigger was added in B.
        assumeTrue(SdkLevel.isAtLeastB());

        // Create a receiver to capture the broadcast intent sent by the test app.
        mResultReceiverFilter =
                new ResultReceiverFilter(
                        REPLY_ACTION_COMPLETE,
                        /* resultsToWaitFor= */ 1,
                        BROADCAST_RECEIVER_TIMEOUT_MS);

        // Start the activity in the test app, which will clears all profiling triggers and adds an
        // app fully drawn profiling trigger.
        startActivityWithAction(ACTION_INIT_AND_ADD_APP_FULLY_DRAWN_TRIGGER, STUB_PACKAGE_NAME);

        // Stop the test app.
        AmUtils.runStopApp(STUB_PACKAGE_NAME, true /* waitForStop */);

        startSystemTriggeredTraceForTesting(STUB_PACKAGE_NAME);

        // Start the test app again to trigger reportFullyDrawn().
        startActivityWithAction(ACTION_REGISTER_AND_REPORT_FULLY_DRAWN, STUB_PACKAGE_NAME);

        Log.d(TAG, "Waiting for broadcast receiver");
        if (!mResultReceiverFilter.waitForBroadcast()) {
            fail("Test timed out waiting for BroadcastReceiver");
        }

        assertProfilingResultAndFileValidation(
                mResultReceiverFilter, ProfilingTrigger.TRIGGER_TYPE_APP_FULLY_DRAWN);
    }

    @Test
    @LargeTest
    @RequiresFlagsEnabled({Flags.FLAG_SYSTEM_TRIGGERED_PROFILING_NEW})
    public void testAnrTrigger() throws Exception {
        // Anr trigger was added in B and a bug fix added in C.
        assumeTrue(SdkLevel.isAtLeastC());

        // Create a receiver to capture the broadcast intent sent by the test app.
        mResultReceiverFilter =
                new ResultReceiverFilter(
                        REPLY_ACTION_COMPLETE,
                        /* resultsToWaitFor= */ 1,
                        /* timeoutMs= */ BROADCAST_RECEIVER_TIMEOUT_MS);

        startSystemTriggeredTraceForTesting(STUB_PACKAGE_NAME);

        // Start the test app to register the ANR trigger, attach a listener, start broadcast
        // receiver and trigger ANR.
        startActivityWithAction(ACTION_SETUP_PROFILING_TRIGGER_AND_TRIGGER_ANR, STUB_PACKAGE_NAME);

        // Send a broadcast to test app. The test app handles this in an infinite loop, which will
        // cause ANR. The foreground priority flag will cause ANR in a shorter timeout.
        // This command can block until the ANR is complete, so we run it asynchronously to allow
        // the test to detect the ANR state and proceed.
        new Thread(
                        () ->
                                executeShellCmd(
                                        "am broadcast -a action_anr -p "
                                                + STUB_PACKAGE_NAME
                                                + " -f "
                                                + FLAG_RECEIVER_FOREGROUND))
                .start();

        // Get the hardware timeout multiplier to account for slower devices/emulators. Default to 1
        // because real devices and hardware-accelerated virtualized devices don't have this set.
        int timeoutMultiplier = 1;
        try {
            String output = executeShellCmd("getprop ro.hw_timeout_multiplier").trim();
            if (!output.isEmpty()) {
                timeoutMultiplier = Integer.parseInt(output);
            }
        } catch (NumberFormatException e) {
            // Ignore, use default
        }
        // Calculate the timeout for ANR. The default timeout for foreground broadcast is 10s *
        // timeoutMultiplier.
        // We add an extra 10s buffer to ensure ActivityManager and ProfilingService has finished
        // processing the ANR.
        int timeoutMs = BROADCAST_FG_TIMEOUT_MS * timeoutMultiplier + ANR_POST_PROCESS_DELAY_MS;
        Log.d(TAG, "Wait for ANR to complete: " + timeoutMs + " ms");
        sleep(timeoutMs);

        // Stop the test app.
        AmUtils.runStopApp(STUB_PACKAGE_NAME, /* waitForStop= */ true);

        // Restart the app and register the callback to receive the results of the ANR trace
        startActivityWithAction(ACTION_REGISTER_PROFILING_CALLBACK, STUB_PACKAGE_NAME);

        Log.d(TAG, "Waiting for broadcast receiver");
        if (!mResultReceiverFilter.waitForBroadcast()) {
            fail("Test timed out waiting for BroadcastReceiver");
        }

        assertProfilingResultAndFileValidation(
                mResultReceiverFilter, ProfilingTrigger.TRIGGER_TYPE_ANR);
    }

    @Test
    @RequiresFlagsEnabled({
        Flags.FLAG_SYSTEM_TRIGGERED_PROFILING_NEW,
    })
    public void testAppRequestRunningTraceTrigger() throws Exception {
        // Create a receiver to capture the broadcast intent sent by the test app.
        mResultReceiverFilter =
                new ResultReceiverFilter(
                        REPLY_ACTION_COMPLETE,
                        /* resultsToWaitFor= */ 1,
                        BROADCAST_RECEIVER_TIMEOUT_MS);

        startSystemTriggeredTraceForTesting(STUB_PACKAGE_NAME);

        // Start the activity in the test app, which will clears all profiling triggers, adds a
        // request running trace trigger, registers for results, and requests a running trace.
        startActivityWithAction(ACTION_INIT_AND_REQUEST_RUNNING_TRACE, STUB_PACKAGE_NAME);

        Log.d(TAG, "Waiting for broadcast receiver");
        if (!mResultReceiverFilter.waitForBroadcast()) {
            fail("Test timed out waiting for BroadcastReceiver");
        }

        assertProfilingResultAndFileValidation(
                mResultReceiverFilter, ProfilingTrigger.TRIGGER_TYPE_APP_REQUEST_RUNNING_TRACE);
    }

    @Test
    @RequiresFlagsEnabled({Flags.FLAG_SYSTEM_TRIGGERED_PROFILING_NEW})
    public void testKillForceStopTrigger() throws Exception {
        // Kill force stop trigger was added in C.
        assumeTrue(SdkLevel.isAtLeastC());

        runKillTriggerTest(
                ACTION_SETUP_KILL_FORCE_STOP_TRIGGER,
                ProfilingTrigger.TRIGGER_TYPE_KILL_FORCE_STOP,
                // This command invokes ActivityManagerService.forceStopPackage(), which sends
                // ProfilingTrigger.TRIGGER_KILL_FORCE_STOP.
                () ->
                        executeShellCmd(
                                "am force-stop --user %d %s",
                                mTestRunningUserId, STUB_PACKAGE_NAME));
    }

    @Test
    @RequiresFlagsEnabled({Flags.FLAG_SYSTEM_TRIGGERED_PROFILING_NEW})
    public void testKillTaskManagerTrigger() throws Exception {
        // Kill task manager trigger was added in C.
        assumeTrue(SdkLevel.isAtLeastC());

        runKillTriggerTest(
                ACTION_SETUP_KILL_TASK_MANAGER_TRIGGER,
                ProfilingTrigger.TRIGGER_TYPE_KILL_TASK_MANAGER,
                // AmUtils.runStopApp uses "am stop-app" command", which sends
                // ProfilingTrigger.TRIGGER_KILL_TASK_MANAGER.
                () -> AmUtils.runStopApp(STUB_PACKAGE_NAME, /* waitForStop= */ true));
    }

    @Test
    @RequiresFlagsEnabled({Flags.FLAG_SYSTEM_TRIGGERED_PROFILING_NEW})
    public void testKillRecentsTrigger() throws Exception {
        // Kill recents trigger was added in C.
        assumeTrue(SdkLevel.isAtLeastC());

        runKillTriggerTest(
                ACTION_SETUP_KILL_RECENTS_TRIGGER,
                ProfilingTrigger.TRIGGER_TYPE_KILL_RECENTS,
                // In AMS, the command "am stack remove" triggers
                // ActivityManagerService.killProcessesForRemovedTask(), which is responsible for
                // cleaning up processes when their task is removed from the recent task list. This
                // method contains the logic that sends ProfilingTrigger.TRIGGER_TYPE_KILL_RECENTS.
                () -> {
                    // Move to home to put the app in background so 'am stack remove' kills it
                    // immediately.
                    executeShellCmd("input keyevent 3"); // KEYCODE_HOME
                    // Small sleep to ensure the process state update is processed by AMS.
                    sleep(1_000);
                    String output = executeShellCmd("am stack list");
                    String taskId = parseTaskId(output, STUB_PACKAGE_NAME);
                    executeShellCmd("am stack remove %s", taskId);
                });
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    /**
     * Common test logic for app kill-based profiling triggers.
     *
     * @param setupAction The action to initialize the trigger in the test app.
     * @param triggerType The expected {@link ProfilingTrigger} type.
     * @param stopAction The action that will cause the app to be killed or stopped.
     * @throws Exception if any error occurs during execution.
     */
    private void runKillTriggerTest(int setupAction, int triggerType, ThrowingRunnable stopAction)
            throws Exception {
        // Create a receiver to capture the broadcast intent sent by the test app.
        mResultReceiverFilter =
                new ResultReceiverFilter(
                        REPLY_ACTION_COMPLETE,
                        /* resultsToWaitFor= */ 1,
                        BROADCAST_RECEIVER_TIMEOUT_MS);

        startSystemTriggeredTraceForTesting(STUB_PACKAGE_NAME);

        // Start the activity in the test app to setup the trigger.
        startActivityWithAction(setupAction, STUB_PACKAGE_NAME);

        // Stop or kill the app based on how the trigger occurs.
        stopAction.run();
        // Check and wait until the app is killed.
        ensureProcessStopped(STUB_PACKAGE_NAME);

        // Start the test app again to register callback.
        startActivityWithAction(ACTION_REGISTER_PROFILING_CALLBACK, STUB_PACKAGE_NAME);

        Log.d(TAG, "Waiting for broadcast receiver");
        if (!mResultReceiverFilter.waitForBroadcast()) {
            fail("Test timed out waiting for BroadcastReceiver");
        }

        assertProfilingResultAndFileValidation(mResultReceiverFilter, triggerType);
    }

    private void ensureProcessStopped(String packageName) {
        Log.d(TAG, "Waiting for process to stop: " + packageName);
        waitForCondition(
                () -> !isProcessAlive(packageName),
                ACTION_TIMEOUT_MS,
                WAIT_FOR_CONDITION_INCREMENTS_MS,
                true);
    }

    /**
     * Starts the test app's activity with a specific action.
     *
     * <p>The test app will perform a different operation based on the provided action code.
     *
     * @param action The action to be passed to the activity.
     */
    private void startActivityWithAction(int action, String packageName) {
        executeShellCmd(
                "am start --user %d -W -n %s/%s --ei %s %d",
                mTestRunningUserId, packageName, SIMPLE_ACTIVITY, ACTION_KEY, action);

        Log.d(TAG, "Waiting for process to start: " + packageName);
        waitForCondition(
                () -> isProcessAlive(packageName),
                ACTION_TIMEOUT_MS,
                WAIT_FOR_CONDITION_INCREMENTS_MS,
                true);
    }

    /** Asserts that the profiling result and file validation are successful. */
    private void assertProfilingResultAndFileValidation(
            ResultReceiverFilter resultReceiverFilter, int triggerType) {
        // Assert that exactly one intent was received.
        assertThat(resultReceiverFilter.getIntents().size()).isEqualTo(1);
        Bundle extras = resultReceiverFilter.getIntents().getFirst().getExtras();
        assertThat(extras).isNotNull();

        ProfilingResult result =
                extras.getParcelable(REPLY_EXTRA_PROFILING_RESULT, ProfilingResult.class);
        assertProfilingResultSuccess(expect, result, OUTPUT_FILE_TRACE_SUFFIX, triggerType);

        int fileValidationResult =
                extras.getInt(REPLY_EXTRA_FILE_VALIDATION_RESULT, FILE_VALIDATION_RESULT_NONE);
        // Profiling caller test app receives the result file and validates it. Here we verify the
        // validation result code.
        assertThat(fileValidationResult).isEqualTo(FILE_VALIDATION_RESULT_SUCCESS);
    }

    /**
     * Parses the task ID for a given package from the "am stack list" output.
     *
     * @param output The output string from the shell command.
     * @param packageName The name of the package to search for.
     * @return The task ID associated with the package as a String.
     */
    private String parseTaskId(String output, String packageName) {
        // Example output format: "  taskId=123: android.profiling.cts.profilingapp/..."
        Pattern pattern = Pattern.compile("taskId=(\\d+):.*" + Pattern.quote(packageName));
        Matcher matcher = pattern.matcher(output);
        if (matcher.find()) {
            return matcher.group(1);
        }
        throw new RuntimeException(
                "Could not find taskId for package: " + packageName + "\nOutput: " + output);
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
