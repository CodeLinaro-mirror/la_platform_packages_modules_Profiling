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
import android.os.profiling.anomaly.flags.Flags;
import android.text.TextUtils;

import com.android.os.profiling.anomaly.collector.SignalCollectorConfig;

/**
 * The configuration for collecting binder spam signals.
 * <p>
 * Each configuration contains information about a specific AIDL target to collect. The collector
 * will collect {@link BinderSpamData} based on this information.
 * @hide
 */
@SystemApi(client = SYSTEM_SERVER)
// TODO(b/419590607): Use a separate flag to guard this API
@FlaggedApi(Flags.FLAG_ANOMALY_DETECTOR_CORE)
public final class BinderSpamConfig implements SignalCollectorConfig {
    /**
     * The qualified name of the AIDL interface (e.g. android.app.IActivityManager).
     */
    private final String mInterfaceName;
    /**
     * The name of the AIDL method (e.g. startService).
     */
    private final String mMethodName;

    private BinderSpamConfig(Builder builder) {
        mInterfaceName = builder.mInterfaceName;
        mMethodName = builder.mMethodName;
    }


    /**
     * Get the AIDL interface this config cares about.
     * @return The qualified name of the AIDL interface (e.g. android.app.IActivityManager).
     */
    @NonNull
    public String getInterfaceName() {
        return mInterfaceName;
    }

    /**
     * Get the AIDL method this config cares about.
     * @return The name of the AIDL method (e.g. startService).
     */
    @NonNull
    public String getMethodName() {
        return mMethodName;
    }

    /** @hide */
    public static final class Builder {
        private String mInterfaceName;
        private String mMethodName;

        /**
         * Set the AIDL interface name of the configuration.
         * @param interfaceName The qualified name of the AIDL interface
         *                     (e.g. android.app.IActivityManager).
         * @return The builder itself.
         */
        @NonNull
        public Builder setInterfaceName(@NonNull String interfaceName) {
            mInterfaceName = interfaceName;
            return this;
        }

        /**
         * Set the AIDL method name of the configuration
         * @param methodName The name of the AIDL method (e.g. startService).
         * @return The builder itself.
         */
        @NonNull
        public Builder setMethodName(@NonNull String methodName) {
            mMethodName = methodName;
            return this;
        }

        /**
         * Build the configuration.
         * @return The built configuration.
         */
        @NonNull
        public BinderSpamConfig build() {
            if (TextUtils.isEmpty(mInterfaceName) || TextUtils.isEmpty(mMethodName)) {
                throw new IllegalArgumentException("Interface and method name must be set!");
            }
            return new BinderSpamConfig(this);
        }
    }
}
