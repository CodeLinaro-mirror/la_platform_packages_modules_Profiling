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

import com.android.os.profiling.anomaly.collector.SignalCollectorConfig;
import com.android.os.profiling.anomaly.collector.SignalCollectorData;

import java.util.Objects;

/**
 * Represents a unique type of signal, defined by a pair of {@link SignalCollectorConfig} and {@link
 * SignalCollectorData} classes.
 *
 * @hide
 */
public record SignalTypeId(
        Class<? extends SignalCollectorConfig> configClass,
        Class<? extends SignalCollectorData> dataClass) {
    public SignalTypeId {
        Objects.requireNonNull(configClass);
        Objects.requireNonNull(dataClass);
    }

    @Override
    public String toString() {
        return "SignalTypeId{"
                + "configClass="
                + configClass.getSimpleName()
                + ", dataClass="
                + dataClass.getSimpleName()
                + '}';
    }
}
