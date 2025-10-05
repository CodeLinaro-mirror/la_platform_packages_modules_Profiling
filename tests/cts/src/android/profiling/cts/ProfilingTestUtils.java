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

import android.os.Bundle;

import com.android.compatibility.common.util.SystemUtil;

import com.google.errorprone.annotations.FormatMethod;

import java.util.concurrent.Executor;

public final class ProfilingTestUtils {

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

    /** Resets a device config namespace to its default values. */
    public static void resetNamespace(String namespace) {
        executeShellCmd("device_config reset trusted_defaults %s", namespace);
    }

    /**
     * Overrides the rate limiter to the provided value and wait long enough for the update to be
     * picked up.
     */
    public static void overrideRateLimiter(boolean disable) {
        overrideDeviceConfig("profiling_testing", "rate_limiter.disabled", disable);
        for (int i = 0; i < RATE_LIMITER_WAIT_TIME_INCREMENTS_COUNT; i++) {
            sleep(RATE_LIMITER_WAIT_TIME_INCREMENT_MS);
            String output = getDeviceConfig("profiling_testing", "rate_limiter.disabled");
            if (Boolean.parseBoolean(output.trim()) == disable) {
                return;
            }
        }
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
        return SystemUtil.runShellCommand(String.format(command, args));
    }
}
