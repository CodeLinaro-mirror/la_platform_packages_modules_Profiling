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

/**
 * A data object representing a single detected anomaly.
 *
 * <p>This object acts as a type-safe container for different attributes that hold the details of
 * the report.
 *
 * @hide
 */
public interface AnomalyReport {
    /** Returns the rule that was triggered to generate this report. */
    Rule<?> getRule();

    /**
     * Retrieves a report attribute of the specified type if it exists.
     *
     * @param attributeType The .class object of the attribute to retrieve.
     * @param <T> The type of the attribute.
     * @return The attribute instance, or {@code null} if an attribute of that type was not added to
     *     this report.
     */
    @Nullable
    <T> T get(Class<T> attributeType);
}
