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

import static android.os.ProfilingResult.ERROR_NONE;

import static com.google.common.truth.Truth.assertThat;

import android.os.Bundle;
import android.os.ProfilingResult;
import android.os.profiling.DeviceConfigHelper;
import android.os.profiling.Flags;
import android.util.Log;

import com.android.compatibility.common.util.SystemUtil;

import com.google.common.truth.Expect;
import com.google.errorprone.annotations.FormatMethod;

import java.util.concurrent.Executor;

public final class ProfilingTestUtils {
    private static final String TAG = ProfilingTestUtils.class.getSimpleName();

    // Wait 2 seconds for profiling to get started and collect some data.
    // TODO: b/376440094 - change to query perfetto and confirm profiling is running.
    public static final int WAIT_TIME_FOR_PROFILING_START_MS = 2 * 1000;

    // LINT.IfChange(output_file_suffix)
    public static final String OUTPUT_FILE_JAVA_HEAP_DUMP_SUFFIX = ".perfetto-java-heap-dump";
    public static final String OUTPUT_FILE_HEAP_PROFILE_SUFFIX = ".perfetto-heap-profile";
    public static final String OUTPUT_FILE_STACK_SAMPLING_SUFFIX = ".perfetto-stack-sample";
    public static final String OUTPUT_FILE_TRACE_SUFFIX = ".perfetto-trace";
    // LINT.ThenChange(
    // /service/java/com/android/os/profiling/ProfilingService.java:output_file_suffix)

    private static final String KEY_DURATION_MS = "KEY_DURATION_MS";

    // Wait for rate limiter config to update for 250 milliseconds at a time for up to 12 increments
    // totalling 3 seconds.
    private static final int RATE_LIMITER_WAIT_TIME_INCREMENT_MS = 250;
    private static final int RATE_LIMITER_WAIT_TIME_INCREMENTS_COUNT = 12;

    static class ImmediateExecutor implements Executor {
        public void execute(Runnable r) {
            r.run();
        }
    }

    static Bundle getOneSecondDurationParamBundle() {
        Bundle params = new Bundle();
        params.putInt(KEY_DURATION_MS, 1000);
        return params;
    }

    /** Overrides a device config with a new integer value. */
    public static void overrideDeviceConfig(String namespace, String config, int newValue) {
        executeShellCmd("device_config put %s %s %d", namespace, config, newValue);
    }

    /** Overrides a device config with a new string value. */
    public static void overrideDeviceConfig(String namespace, String config, String newValue) {
        executeShellCmd("device_config put %s %s %s", namespace, config, newValue);
    }

    /** Overrides a device config with a new boolean value. */
    public static void overrideDeviceConfig(String namespace, String config, boolean newValue) {
        executeShellCmd("device_config put %s %s %b", namespace, config, newValue);
    }

    /** Gets the current value of a device config. */
    public static String getDeviceConfig(String namespace, String config) {
        return executeShellCmd("device_config get %s %s", namespace, config);
    }

    /** Deletes a device config override. */
    public static void deleteDeviceConfig(String namespace, String config) {
        executeShellCmd("device_config delete %s %s", namespace, config);
    }

    /**
     * Overrides the rate limiter to the provided value and wait long enough for the update to be
     * picked up.
     */
    public static void overrideRateLimiter(boolean disable) {
        overrideDeviceConfig(
                DeviceConfigHelper.NAMESPACE_TESTING,
                DeviceConfigHelper.RATE_LIMITER_DISABLE_PROPERTY,
                disable);
        for (int i = 0; i < RATE_LIMITER_WAIT_TIME_INCREMENTS_COUNT; i++) {
            sleep(RATE_LIMITER_WAIT_TIME_INCREMENT_MS);
            String output =
                    getDeviceConfig(
                            DeviceConfigHelper.NAMESPACE_TESTING,
                            DeviceConfigHelper.RATE_LIMITER_DISABLE_PROPERTY);
            if (Boolean.parseBoolean(output.trim()) == disable) {
                return;
            }
        }
    }

    /**
     * Reset all profiling device configs from both namespaces.
     *
     * <p>This should be called in the cleanup of any test which modified device config values.
     *
     * <p>Any config which is overridden in a test must be added here.
     */
    public static void resetAllConfigs() {
        // LINT.IfChange(reset_configs)
        // Testing namespace
        deleteDeviceConfig(
                DeviceConfigHelper.NAMESPACE_TESTING,
                DeviceConfigHelper.RATE_LIMITER_DISABLE_PROPERTY);
        deleteDeviceConfig(
                DeviceConfigHelper.NAMESPACE_TESTING,
                DeviceConfigHelper.DISABLE_DELETE_TEMPORARY_RESULTS);
        deleteDeviceConfig(
                DeviceConfigHelper.NAMESPACE_TESTING,
                DeviceConfigHelper.SYSTEM_TRIGGERED_DEBUG_PACKAGE_NAME);

        // Config namespace
        deleteDeviceConfig(
                DeviceConfigHelper.NAMESPACE, DeviceConfigHelper.KILLSWITCH_SYSTEM_TRACE);
        deleteDeviceConfig(DeviceConfigHelper.NAMESPACE, DeviceConfigHelper.COST_SYSTEM_TRACE);
        deleteDeviceConfig(
                DeviceConfigHelper.NAMESPACE, DeviceConfigHelper.SYSTEM_TRACE_DURATION_MS_DEFAULT);
        deleteDeviceConfig(
                DeviceConfigHelper.NAMESPACE, DeviceConfigHelper.SYSTEM_TRACE_DURATION_MS_MIN);
        deleteDeviceConfig(
                DeviceConfigHelper.NAMESPACE, DeviceConfigHelper.SYSTEM_TRACE_DURATION_MS_MAX);
        deleteDeviceConfig(
                DeviceConfigHelper.NAMESPACE, DeviceConfigHelper.SYSTEM_TRACE_SIZE_KB_DEFAULT);
        deleteDeviceConfig(
                DeviceConfigHelper.NAMESPACE, DeviceConfigHelper.SYSTEM_TRACE_SIZE_KB_MIN);
        deleteDeviceConfig(
                DeviceConfigHelper.NAMESPACE, DeviceConfigHelper.SYSTEM_TRACE_SIZE_KB_MAX);
        deleteDeviceConfig(
                DeviceConfigHelper.NAMESPACE, DeviceConfigHelper.KILLSWITCH_HEAP_PROFILE);
        deleteDeviceConfig(DeviceConfigHelper.NAMESPACE, DeviceConfigHelper.COST_HEAP_PROFILE);
        deleteDeviceConfig(
                DeviceConfigHelper.NAMESPACE,
                DeviceConfigHelper.HEAP_PROFILE_TRACK_JAVA_ALLOCATIONS_DEFAULT);
        deleteDeviceConfig(
                DeviceConfigHelper.NAMESPACE,
                DeviceConfigHelper.HEAP_PROFILE_FLUSH_TIMEOUT_MS_DEFAULT);
        deleteDeviceConfig(
                DeviceConfigHelper.NAMESPACE, DeviceConfigHelper.HEAP_PROFILE_DURATION_MS_DEFAULT);
        deleteDeviceConfig(
                DeviceConfigHelper.NAMESPACE, DeviceConfigHelper.HEAP_PROFILE_DURATION_MS_MIN);
        deleteDeviceConfig(
                DeviceConfigHelper.NAMESPACE, DeviceConfigHelper.HEAP_PROFILE_DURATION_MS_MAX);
        deleteDeviceConfig(
                DeviceConfigHelper.NAMESPACE, DeviceConfigHelper.HEAP_PROFILE_SIZE_KB_DEFAULT);
        deleteDeviceConfig(
                DeviceConfigHelper.NAMESPACE, DeviceConfigHelper.HEAP_PROFILE_SIZE_KB_MIN);
        deleteDeviceConfig(
                DeviceConfigHelper.NAMESPACE, DeviceConfigHelper.HEAP_PROFILE_SIZE_KB_MAX);
        deleteDeviceConfig(
                DeviceConfigHelper.NAMESPACE,
                DeviceConfigHelper.HEAP_PROFILE_SAMPLING_INTERVAL_BYTES_DEFAULT);
        deleteDeviceConfig(
                DeviceConfigHelper.NAMESPACE,
                DeviceConfigHelper.HEAP_PROFILE_SAMPLING_INTERVAL_BYTES_MIN);
        deleteDeviceConfig(
                DeviceConfigHelper.NAMESPACE,
                DeviceConfigHelper.HEAP_PROFILE_SAMPLING_INTERVAL_BYTES_MAX);
        deleteDeviceConfig(
                DeviceConfigHelper.NAMESPACE, DeviceConfigHelper.KILLSWITCH_JAVA_HEAP_DUMP);
        deleteDeviceConfig(DeviceConfigHelper.NAMESPACE, DeviceConfigHelper.COST_JAVA_HEAP_DUMP);
        deleteDeviceConfig(
                DeviceConfigHelper.NAMESPACE,
                DeviceConfigHelper.JAVA_HEAP_DUMP_DURATION_MS_DEFAULT);
        deleteDeviceConfig(
                DeviceConfigHelper.NAMESPACE,
                DeviceConfigHelper.JAVA_HEAP_DUMP_DATA_SOURCE_STOP_TIMEOUT_MS_DEFAULT);
        deleteDeviceConfig(
                DeviceConfigHelper.NAMESPACE, DeviceConfigHelper.JAVA_HEAP_DUMP_SIZE_KB_DEFAULT);
        deleteDeviceConfig(
                DeviceConfigHelper.NAMESPACE, DeviceConfigHelper.JAVA_HEAP_DUMP_SIZE_KB_MIN);
        deleteDeviceConfig(
                DeviceConfigHelper.NAMESPACE, DeviceConfigHelper.JAVA_HEAP_DUMP_SIZE_KB_MAX);
        deleteDeviceConfig(
                DeviceConfigHelper.NAMESPACE, DeviceConfigHelper.KILLSWITCH_STACK_SAMPLING);
        deleteDeviceConfig(DeviceConfigHelper.NAMESPACE, DeviceConfigHelper.COST_STACK_SAMPLING);
        deleteDeviceConfig(
                DeviceConfigHelper.NAMESPACE,
                DeviceConfigHelper.STACK_SAMPLING_FLUSH_TIMEOUT_MS_DEFAULT);
        deleteDeviceConfig(
                DeviceConfigHelper.NAMESPACE,
                DeviceConfigHelper.STACK_SAMPLING_DURATION_MS_DEFAULT);
        deleteDeviceConfig(
                DeviceConfigHelper.NAMESPACE, DeviceConfigHelper.STACK_SAMPLING_DURATION_MS_MIN);
        deleteDeviceConfig(
                DeviceConfigHelper.NAMESPACE, DeviceConfigHelper.STACK_SAMPLING_DURATION_MS_MAX);
        deleteDeviceConfig(
                DeviceConfigHelper.NAMESPACE,
                DeviceConfigHelper.STACK_SAMPLING_SAMPLING_SIZE_KB_DEFAULT);
        deleteDeviceConfig(
                DeviceConfigHelper.NAMESPACE,
                DeviceConfigHelper.STACK_SAMPLING_SAMPLING_SIZE_KB_MIN);
        deleteDeviceConfig(
                DeviceConfigHelper.NAMESPACE,
                DeviceConfigHelper.STACK_SAMPLING_SAMPLING_SIZE_KB_MAX);
        deleteDeviceConfig(
                DeviceConfigHelper.NAMESPACE, DeviceConfigHelper.STACK_SAMPLING_FREQUENCY_DEFAULT);
        deleteDeviceConfig(
                DeviceConfigHelper.NAMESPACE, DeviceConfigHelper.STACK_SAMPLING_FREQUENCY_MIN);
        deleteDeviceConfig(
                DeviceConfigHelper.NAMESPACE, DeviceConfigHelper.STACK_SAMPLING_FREQUENCY_MAX);
        deleteDeviceConfig(
                DeviceConfigHelper.NAMESPACE,
                DeviceConfigHelper.COST_SYSTEM_TRIGGERED_SYSTEM_TRACE);
        deleteDeviceConfig(
                DeviceConfigHelper.NAMESPACE,
                DeviceConfigHelper.SYSTEM_TRIGGERED_SYSTEM_TRACE_DURATION_MS);
        deleteDeviceConfig(
                DeviceConfigHelper.NAMESPACE,
                DeviceConfigHelper.SYSTEM_TRIGGERED_SYSTEM_TRACE_DISCARD_BUFFER_SIZE_KB);
        deleteDeviceConfig(
                DeviceConfigHelper.NAMESPACE,
                DeviceConfigHelper.SYSTEM_TRIGGERED_SYSTEM_TRACE_RING_BUFFER_SIZE_KB);
        deleteDeviceConfig(
                DeviceConfigHelper.NAMESPACE, DeviceConfigHelper.PERSIST_TO_DISK_FREQUENCY_MS);
        deleteDeviceConfig(DeviceConfigHelper.NAMESPACE, DeviceConfigHelper.MAX_COST_SYSTEM_1_HOUR);
        deleteDeviceConfig(
                DeviceConfigHelper.NAMESPACE, DeviceConfigHelper.MAX_COST_PROCESS_1_HOUR);
        deleteDeviceConfig(
                DeviceConfigHelper.NAMESPACE, DeviceConfigHelper.MAX_COST_SYSTEM_24_HOUR);
        deleteDeviceConfig(
                DeviceConfigHelper.NAMESPACE, DeviceConfigHelper.MAX_COST_PROCESS_24_HOUR);
        deleteDeviceConfig(DeviceConfigHelper.NAMESPACE, DeviceConfigHelper.MAX_COST_SYSTEM_7_DAY);
        deleteDeviceConfig(DeviceConfigHelper.NAMESPACE, DeviceConfigHelper.MAX_COST_PROCESS_7_DAY);
        deleteDeviceConfig(
                DeviceConfigHelper.NAMESPACE, DeviceConfigHelper.PERFETTO_DESTROY_TIMEOUT_MS);
        deleteDeviceConfig(
                DeviceConfigHelper.NAMESPACE, DeviceConfigHelper.MAX_RESULT_REDELIVERY_COUNT);
        deleteDeviceConfig(
                DeviceConfigHelper.NAMESPACE,
                DeviceConfigHelper.CLEAR_TEMPORARY_DIRECTORY_FREQUENCY_MS);
        deleteDeviceConfig(
                DeviceConfigHelper.NAMESPACE,
                DeviceConfigHelper.CLEAR_TEMPORARY_DIRECTORY_BOOT_DELAY_MS);
        deleteDeviceConfig(
                DeviceConfigHelper.NAMESPACE,
                DeviceConfigHelper.SYSTEM_TRIGGERED_TRACE_MIN_PERIOD_SECONDS);
        deleteDeviceConfig(
                DeviceConfigHelper.NAMESPACE,
                DeviceConfigHelper.SYSTEM_TRIGGERED_TRACE_MAX_PERIOD_SECONDS);
        deleteDeviceConfig(
                DeviceConfigHelper.NAMESPACE, DeviceConfigHelper.PROFILING_RECHECK_DELAY_MS);
        deleteDeviceConfig(
                DeviceConfigHelper.NAMESPACE, DeviceConfigHelper.REDACTION_CHECK_FREQUENCY_MS);
        deleteDeviceConfig(
                DeviceConfigHelper.NAMESPACE, DeviceConfigHelper.REDACTION_MAX_RUNTIME_ALLOTTED_MS);
        // LINT.ThenChange(/service/java/com/android/os/profiling/DeviceConfigHelper.java:configs)
    }

    /** Sleeps for the given number of milliseconds. */
    public static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            // Do nothing.
        }
    }

    /**
     * Helper to execute shell commands.
     *
     * @param command The command to execute.
     * @param args The arguments to the command.
     * @return The output of the command.
     */
    @FormatMethod
    public static String executeShellCmd(String command, Object... args) {
        Log.d(TAG, "Executing shell command: " + String.format(command, args));
        return SystemUtil.runShellCommand(String.format(command, args));
    }

    /**
     * Starts a system-triggered trace by setting the DeviceConfig for the test app package.
     *
     * @param packageName The package name for which to trigger the trace.
     * @param waitTraceStart Whether to wait for the trace to start.
     */
    public static void startSystemTriggeredTraceForTesting(
            String packageName, boolean waitTraceStart) {
        overrideDeviceConfig(
                DeviceConfigHelper.NAMESPACE_TESTING,
                DeviceConfigHelper.SYSTEM_TRIGGERED_DEBUG_PACKAGE_NAME,
                packageName);

        if (waitTraceStart) {
            // Wait a bit so the trace can get started and actually collect something.
            sleep(WAIT_TIME_FOR_PROFILING_START_MS);
        }
    }

    /**
     * Asserts that the given {@link ProfilingResult} is valid and matches the expected values.
     *
     * @param expect The {@link Expect} instance to use for assertions.
     * @param result The {@link ProfilingResult} to assert.
     * @param expectedFileSuffix The expected file suffix of the result file path.
     * @param expectedTriggerType The expected trigger type of the profiling result.
     */
    public static void assertProfilingResultSuccess(
            Expect expect,
            ProfilingResult result,
            String expectedFileSuffix,
            int expectedTriggerType) {
        assertThat(result).isNotNull();
        expect.that(result.getErrorCode()).isEqualTo(ERROR_NONE);
        expect.that(result.getResultFilePath()).isNotNull();
        expect.that(result.getResultFilePath()).contains(expectedFileSuffix);
        expect.that(result.getTriggerType()).isEqualTo(expectedTriggerType);

        if (Flags.addRateLimiterDisabledToResult()) {
            expect.that(result.getErrorMessage()).isNotNull();
        } else {
            expect.that(result.getErrorMessage()).isNull();
        }
    }
}
