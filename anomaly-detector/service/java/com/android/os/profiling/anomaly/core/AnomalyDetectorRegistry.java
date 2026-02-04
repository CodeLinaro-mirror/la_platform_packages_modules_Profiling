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
import android.os.profiling.anomaly.RuleInternal;
import android.os.profiling.anomaly.RuleInternal.ConditionTypeInternal;

import com.android.os.profiling.anomaly.core.AnomalyDetector.AnomalyDetectorFactory;

/**
 * A central registry for anomaly detector factories.
 *
 * <p>This class maps condition types to the factories that can create the appropriate anomaly
 * detectors.
 *
 * @hide
 */
public interface AnomalyDetectorRegistry {
    /**
     * Returns the factory for a given condition type.
     *
     * @param conditionType The type of the condition.
     * @return The factory that creates detectors for this condition, or {@code null} if not found.
     */
    @Nullable
    AnomalyDetectorFactory getFactory(@ConditionTypeInternal String conditionType);

    /**
     * Creates and configures an AnomalyDetector for the given condition type.
     *
     * @param conditionType The condition type for which to create a detector.
     * @param registry The registry used to look up signal collectors for the detector.
     * @return A configured AnomalyDetector, or {@code null} if no factory is registered or if the
     *     detector could not be created.
     */
    AnomalyDetector createDetectorForCondition(
            @ConditionTypeInternal String conditionType, SignalCollectorRegistry registry);
}
