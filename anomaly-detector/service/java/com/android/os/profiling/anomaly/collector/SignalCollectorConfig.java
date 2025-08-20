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

package com.android.os.profiling.anomaly.collector;

import static android.annotation.SystemApi.Client.SYSTEM_SERVER;

import android.annotation.FlaggedApi;
import android.annotation.SystemApi;
import android.os.profiling.anomaly.flags.Flags;

/**
 * Base interface for data collection configurations.
 * <p>
 * This interface serves as a common type for different configurations
 * that define how data collection should be performed by a {@code SignalCollector}.
 * Specific parameters and settings are defined in concrete classes that
 * implement this interface.
 *
 * @hide
 */
@SystemApi(client = SYSTEM_SERVER)
@FlaggedApi(Flags.FLAG_ANOMALY_DETECTOR_CORE)
public interface SignalCollectorConfig {
    // No methods defined here as it serves as a base type.
    // Specific configurations will be defined in implementing classes.
}
