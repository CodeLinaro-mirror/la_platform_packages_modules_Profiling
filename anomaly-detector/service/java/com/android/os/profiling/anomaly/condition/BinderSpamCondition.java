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

package com.android.os.profiling.anomaly.condition;

import com.android.os.profiling.anomaly.core.BaseCondition;

import java.util.Objects;

/**
 * A condition that is met when binder calls exceed a certain count.
 *
 * @hide
 */
public record BinderSpamCondition(long callCountThreshold, String interfaceName, String methodName)
        implements BaseCondition {
    public BinderSpamCondition {
        if (callCountThreshold <= 0) {
            throw new IllegalArgumentException("Call count threshold must be positive");
        }
        Objects.requireNonNull(interfaceName);
        Objects.requireNonNull(methodName);
    }
}
