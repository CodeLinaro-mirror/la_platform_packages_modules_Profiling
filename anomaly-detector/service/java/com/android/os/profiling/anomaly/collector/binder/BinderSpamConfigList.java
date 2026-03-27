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

import com.android.os.profiling.anomaly.collector.SignalCollectorConfig;

import java.util.List;
import java.util.Objects;

/**
 * A data class that represents a list of {@link BinderSpamConfig}.
 *
 * @hide
 */
@SystemApi(client = SYSTEM_SERVER)
@FlaggedApi(Flags.FLAG_ANOMALY_DETECTOR_CORE_C)
public final class BinderSpamConfigList implements SignalCollectorConfig {
    private final List<BinderSpamConfig> mConfigs;

    public BinderSpamConfigList(@NonNull List<BinderSpamConfig> configs) {
        if (Objects.requireNonNull(configs).isEmpty()) {
            throw new IllegalArgumentException("Must provide at least one config.");
        }
        mConfigs = List.copyOf(configs);
    }

    @NonNull
    public List<BinderSpamConfig> getConfigs() {
        return mConfigs;
    }
}
