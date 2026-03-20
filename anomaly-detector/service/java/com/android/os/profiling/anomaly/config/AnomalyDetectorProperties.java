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
import android.provider.DeviceConfig.OnPropertiesChangedListener;
import android.provider.DeviceConfig.Properties;

import com.android.internal.annotations.VisibleForTesting;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * A central provider for anomaly detector properties from DeviceConfig.
 *
 * <p>This class listens for changes in a single namespace and broadcasts the updated properties to
 * its own listeners.
 *
 * @hide
 */
public final class AnomalyDetectorProperties {
    @VisibleForTesting public static final String NAMESPACE = "com_android_profiling";

    // Using CopyOnWriteArrayList to allow for safe concurrent modification and iteration.
    private final List<OnPropertiesChangedListener> mListeners = new CopyOnWriteArrayList<>();
    private final Executor mExecutor;

    public AnomalyDetectorProperties() {
        mExecutor = Executors.newSingleThreadExecutor();
        // The listener that receives updates from the system's DeviceConfig.
        OnPropertiesChangedListener systemListener =
                (properties) -> {
                    // When an update is received, notify all registered internal listeners.
                    for (OnPropertiesChangedListener listener : mListeners) {
                        listener.onPropertiesChanged(properties);
                    }
                };
        DeviceConfig.addOnPropertiesChangedListener(NAMESPACE, mExecutor, systemListener);
    }

    /**
     * Gets all properties for the anomaly_detector namespace at once.
     *
     * @return The current set of properties.
     */
    public Properties getProperties() {
        return DeviceConfig.getProperties(NAMESPACE);
    }

    /**
     * Adds a listener to be notified of property changes.
     *
     * @param listener The listener to add.
     */
    public void addOnPropertiesChangedListener(OnPropertiesChangedListener listener) {
        mListeners.add(listener);
    }

    /**
     * Removes a previously added listener.
     *
     * @param listener The listener to remove.
     */
    public void removeOnPropertiesChangedListener(OnPropertiesChangedListener listener) {
        mListeners.remove(listener);
    }
}
