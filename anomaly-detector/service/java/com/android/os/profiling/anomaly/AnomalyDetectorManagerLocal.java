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

package com.android.os.profiling.anomaly;

import static android.annotation.SystemApi.Client.SYSTEM_SERVER;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.os.profiling.anomaly.flags.Flags;

import com.android.os.profiling.anomaly.collector.SignalCollector;
import com.android.os.profiling.anomaly.collector.SignalCollectorConfig;
import com.android.os.profiling.anomaly.collector.SignalCollectorData;

/**
 * Defines a local interface for the AnomalyDetector service, primarily used by other system server
 * components to register {@link SignalCollector} instances.
 *
 * @hide
 */
@SystemApi(client = SYSTEM_SERVER)
@FlaggedApi(Flags.FLAG_ANOMALY_DETECTOR_CORE)
public interface AnomalyDetectorManagerLocal {
    /**
     * Registers a new {@link SignalCollector} with the AnomalyDetector service. This method allows
     * other platform components to provide their data collection capabilities to the anomaly
     * detector, enabling the anomaly detector to subscribe to various types of data for analysis.
     *
     * @param <T> The specific type of {@link SignalCollectorConfig} that this collector handles.
     * @param <U> The specific type of {@link SignalCollectorData} that this collector produces.
     * @param configType The {@link Class} of the configuration object (extends {@link
     *     SignalCollectorConfig}) used by the collector. This, along with {@code dataType}, forms a
     *     unique key for registration.
     * @param dataType The {@link Class} of the data object (extends {@link SignalCollectorData})
     *     produced by the collector. This, along with {@code configType}, forms a unique key for
     *     registration.
     * @param collector The instance of the {@link SignalCollector} to be registered.
     */
    <T extends SignalCollectorConfig, U extends SignalCollectorData> void registerSignalCollector(
            @NonNull Class<T> configType,
            @NonNull Class<U> dataType,
            @NonNull SignalCollector<T, U> collector);
}
