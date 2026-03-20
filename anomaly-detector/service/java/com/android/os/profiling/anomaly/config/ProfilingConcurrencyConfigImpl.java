/*
 * Copyright (C) 2026 The Android Open Source Project
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

package com.android.os.profiling.anomaly.config;

import android.provider.DeviceConfig;

import com.android.internal.annotations.VisibleForTesting;

/**
 * The default, production implementation of the {@link ProfilingConcurrencyConfig} interface. This
 * implementation retrieves settings from {@link DeviceConfig}.
 *
 * @hide
 */
public final class ProfilingConcurrencyConfigImpl implements ProfilingConcurrencyConfig {
    private static final String KEY_PREFIX = "anomaly_ratelimiter.";

    @VisibleForTesting
    static final String KEY_DEVICE_MAX_CONCURRENT_SESSIONS =
            KEY_PREFIX + "device_max_concurrent_sessions";

    @VisibleForTesting static final int DEFAULT_DEVICE_MAX_CONCURRENT_SESSIONS = 1;

    private volatile int mDeviceMaxConcurrentSessions = DEFAULT_DEVICE_MAX_CONCURRENT_SESSIONS;

    public ProfilingConcurrencyConfigImpl(AnomalyDetectorProperties propertiesProvider) {
        updateAllProperties(propertiesProvider.getProperties());
        propertiesProvider.addOnPropertiesChangedListener(this::updateAllProperties);
    }

    private void updateAllProperties(DeviceConfig.Properties properties) {
        for (String key : properties.getKeyset()) {
            updateProperty(key, properties);
        }
    }

    private void updateProperty(String key, DeviceConfig.Properties properties) {
        if (KEY_DEVICE_MAX_CONCURRENT_SESSIONS.equals(key)) {
            mDeviceMaxConcurrentSessions =
                    properties.getInt(
                            KEY_DEVICE_MAX_CONCURRENT_SESSIONS,
                            DEFAULT_DEVICE_MAX_CONCURRENT_SESSIONS);
        }
    }

    @Override
    public int getDeviceMaxConcurrentSessions() {
        return mDeviceMaxConcurrentSessions;
    }
}
