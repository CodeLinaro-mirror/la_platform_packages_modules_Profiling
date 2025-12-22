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

package com.android.os.profiling.anomaly.collector.binder;

import static android.annotation.SystemApi.Client.SYSTEM_SERVER;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.app.ActivityManager;
import android.os.profiling.anomaly.flags.Flags;
import android.text.TextUtils;

import com.android.internal.annotations.VisibleForTesting;

import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;

/**
 * The configuration for collecting binder spam signals.
 *
 * <p>Each configuration contains information about a specific AIDL target to collect. The collector
 * will collect {@link BinderSpamData} based on this information.
 *
 * @hide
 */
@SystemApi(client = SYSTEM_SERVER)
@FlaggedApi(Flags.FLAG_ANOMALY_DETECTOR_CORE)
public final class BinderSpamConfig {
    /** The qualified name of the AIDL interface (e.g. android.app.IActivityManager). */
    private final String mInterfaceName;

    /** The name of the AIDL method (e.g. startService). */
    private final String mMethodName;

    /** The threshold of call count that should trigger a report if exceeded. */
    private final int mCallCountThreshold;

    /** The window size of calculating the total count over. */
    private final Duration mWindowSize;

    /** The list of UIDs to apply this config to. */
    private final int[] mUids;

    /**
     * The list of importance values to match with the caller process's importance. See {@link
     * ActivityManager.RunningAppProcessInfo#importance}.
     */
    private final int[] mCallerImportanceList;

    private BinderSpamConfig(Builder builder) {
        mInterfaceName = builder.mInterfaceName;
        mMethodName = builder.mMethodName;
        mCallCountThreshold = builder.mCallCountThreshold;
        mWindowSize = builder.mWindowSize;
        mUids = builder.mUids;
        mCallerImportanceList = builder.mCallerImportanceList;
    }

    /**
     * Get the AIDL interface this config cares about.
     *
     * @return The qualified name of the AIDL interface (e.g. android.app.IActivityManager).
     */
    @NonNull
    public String getInterfaceName() {
        return mInterfaceName;
    }

    /**
     * Get the AIDL method this config cares about.
     *
     * @return The name of the AIDL method (e.g. startService).
     */
    @NonNull
    public String getMethodName() {
        return mMethodName;
    }

    /**
     * Get the threshold of call count that should trigger a report if exceeded.
     *
     * @return The threshold of call count.
     */
    public int getCallCountThreshold() {
        return mCallCountThreshold;
    }

    /**
     * Get the window size of calculating the total count over.
     *
     * @return The window size.
     */
    @NonNull
    public Duration getWindowSize() {
        return mWindowSize;
    }

    /**
     * Get the list of UIDs to apply this config to.
     *
     * @return The list of UIDs.
     */
    @NonNull
    public int[] getUids() {
        return mUids;
    }

    /**
     * Get the list of importance values to match with the caller process's importance. See {@link
     * ActivityManager.RunningAppProcessInfo#importance}.
     *
     * @return The list of importance values. An empty list means no filtering is applied.
     */
    @NonNull
    public int[] getCallerImportanceList() {
        return mCallerImportanceList;
    }

    /** @hide */
    public static final class Builder {
        @VisibleForTesting static final Duration MINIMUM_WINDOW_SIZE = Duration.ofSeconds(1);
        private String mInterfaceName;
        private String mMethodName;
        private int mCallCountThreshold;
        private Duration mWindowSize;
        // Default to empty list, meaning no filtering.
        private int[] mUids = new int[0];
        // Default to empty list, meaning no filtering.
        private int[] mCallerImportanceList = new int[0];

        /**
         * Set the AIDL interface name of the configuration.
         *
         * @param interfaceName The qualified name of the AIDL interface (e.g.
         *     android.app.IActivityManager).
         * @return The builder itself.
         */
        @NonNull
        public Builder setInterfaceName(@NonNull String interfaceName) {
            if (TextUtils.isEmpty(interfaceName)) {
                throw new IllegalArgumentException("Interface name must not be empty!");
            }
            mInterfaceName = interfaceName;
            return this;
        }

        /**
         * Set the AIDL method name of the configuration
         *
         * @param methodName The name of the AIDL method (e.g. startService).
         * @return The builder itself.
         */
        @NonNull
        public Builder setMethodName(@NonNull String methodName) {
            if (TextUtils.isEmpty(methodName)) {
                throw new IllegalArgumentException("Method name must not be empty!");
            }
            mMethodName = methodName;
            return this;
        }

        /**
         * Set the threshold of call count that should trigger a report if exceeded.
         *
         * @param callCountThreshold The threshold of call count.
         * @return The builder itself.
         */
        @NonNull
        public Builder setCallCountThreshold(int callCountThreshold) {
            if (callCountThreshold <= 0) {
                throw new IllegalArgumentException("Call count threshold must be greater than 0!");
            }
            mCallCountThreshold = callCountThreshold;
            return this;
        }

        /**
         * Set the window size of calculating the total count over. The actual window size may be
         * lower-bounded due to implementation details at a lower level.
         *
         * @param windowSize The window size.
         * @return The builder itself.
         * @throws IllegalArgumentException When the input window size is less than one second.
         */
        @NonNull
        public Builder setWindowSize(@NonNull Duration windowSize) {
            if (Objects.requireNonNull(windowSize).compareTo(MINIMUM_WINDOW_SIZE) < 0) {
                throw new IllegalArgumentException(
                        "Window size must not be less than " + MINIMUM_WINDOW_SIZE);
            }
            mWindowSize = windowSize;
            return this;
        }

        /**
         * Set the list of UIDs to apply this config to.
         *
         * @param uids The list of UIDs. An empty list means applying to all UIDs.
         * @return The builder itself.
         */
        @NonNull
        public Builder setUids(@NonNull int[] uids) {
            mUids = Arrays.copyOf(Objects.requireNonNull(uids), uids.length);
            return this;
        }

        /**
         * Set the list of importance values to match with the caller process's importance.
         *
         * @param callerImportanceList The list of importance values. An empty list means no
         *     filtering is applied. See {@link ActivityManager.RunningAppProcessInfo#importance}.
         * @return The builder itself.
         */
        @NonNull
        public Builder setCallerImportanceList(@NonNull int[] callerImportanceList) {
            mCallerImportanceList =
                    Arrays.copyOf(
                            Objects.requireNonNull(callerImportanceList),
                            callerImportanceList.length);
            return this;
        }

        /**
         * Build the configuration.
         *
         * @return The built configuration.
         */
        @NonNull
        public BinderSpamConfig build() {
            Objects.requireNonNull(mInterfaceName);
            Objects.requireNonNull(mMethodName);
            Objects.requireNonNull(mWindowSize);
            if (mCallCountThreshold == 0) {
                throw new IllegalArgumentException("Call count threshold must be set!");
            }
            return new BinderSpamConfig(this);
        }
    }
}
