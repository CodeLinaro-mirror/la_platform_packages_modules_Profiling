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

package com.android.os.profiling.anomaly.core;

import android.annotation.Nullable;

import com.android.os.profiling.anomaly.collector.SignalCollector;
import com.android.os.profiling.anomaly.collector.SignalCollectorConfig;
import com.android.os.profiling.anomaly.collector.SignalCollectorData;

import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * A thread-safe registry for {@link SignalCollector} instances.
 *
 * @hide
 */
public interface SignalCollectorRegistry {
    /**
     * Registers a new {@link SignalCollector} with the anomaly detector.
     *
     * @param <T> The specific type of {@link SignalCollectorConfig} that this collector handles.
     * @param <U> The specific type of {@link SignalCollectorData} that this collector produces.
     * @param configType The Class object representing the type of SignalCollectorConfig this
     *     collector handles. This will be used as the primary key.
     * @param dataType The Class object representing the type of SignalCollectorData this collector
     *     produces.
     * @param collector The instance of the SignalCollector to be registered.
     * @throws IllegalArgumentException if a collector handling the same config type is already
     *     registered.
     */
    <T extends SignalCollectorConfig, U extends SignalCollectorData> void registerSignalCollector(
            Class<T> configType, Class<U> dataType, SignalCollector<T, U> collector);

    /**
     * Unregisters a {@link SignalCollector} from the anomaly detector.
     *
     * @param <T> The specific type of {@link SignalCollectorConfig} that the collector handles.
     * @param <U> The specific type of {@link SignalCollectorData} that the collector produces.
     * @param configType The Class object representing the type of SignalCollectorConfig this
     *     collector handles.
     * @param dataType The Class object representing the type of SignalCollectorData this collector
     *     produces.
     */
    <T extends SignalCollectorConfig, U extends SignalCollectorData> void unregisterSignalCollector(
            Class<T> configType, Class<U> dataType);

    /**
     * Adds a callback to be invoked when a new {@link SignalCollector} is registered.
     *
     * @param executor The executor on which to invoke the callback.
     * @param callback The callback to be invoked.
     */
    void addCollectorRegisteredCallback(Executor executor, Consumer<SignalTypeId> callback);

    /**
     * Removes a callback that was previously added.
     *
     * @param callback The callback to be removed.
     */
    void removeCollectorRegisteredCallback(Consumer<SignalTypeId> callback);

    /**
     * Adds a callback to be invoked when a {@link SignalCollector} is unregistered.
     *
     * @param executor The executor on which to invoke the callback.
     * @param callback The callback to be invoked.
     */
    void addCollectorUnregisteredCallback(Executor executor, Consumer<SignalTypeId> callback);

    /**
     * Removes a callback that was previously added for unregistration events.
     *
     * @param callback The callback to be removed.
     */
    void removeCollectorUnregisteredCallback(Consumer<SignalTypeId> callback);

    /**
     * Retrieves a registered SignalCollector by its config type and verifies the expected data
     * type.
     *
     * @param configType The exact Class type of SignalCollectorConfig this collector handles.
     * @param dataType The exact Class type of SignalCollectorData this collector is expected to
     *     produce. This is used for verification.
     * @param <T> The expected type of SignalCollectorConfig.
     * @param <U> The expected type of SignalCollectorData.
     * @return The typed SignalCollector instance, or null if no collector is found for the
     *     specified config type, if the registered object is of an incompatible type, or if the
     *     registered data type does not match the expected dataType.
     */
    @Nullable
    <T extends SignalCollectorConfig, U extends SignalCollectorData>
            SignalCollector<T, U> getSignalCollector(Class<T> configType, Class<U> dataType);
}
