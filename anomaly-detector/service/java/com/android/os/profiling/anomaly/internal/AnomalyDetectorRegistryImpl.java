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

package com.android.os.profiling.anomaly.internal;

import android.annotation.Nullable;
import android.os.profiling.anomaly.RuleInternal.ConditionTypeInternal;
import android.util.ArrayMap;

import com.android.os.profiling.anomaly.core.AnomalyDetector;
import com.android.os.profiling.anomaly.core.AnomalyDetector.AnomalyDetectorFactory;
import com.android.os.profiling.anomaly.core.AnomalyDetectorRegistry;
import com.android.os.profiling.anomaly.core.SignalCollectorRegistry;
import com.android.os.profiling.anomaly.util.LogUtil;

import java.util.Map;
import java.util.Set;

/**
 * The default implementation of the {@link AnomalyDetectorRegistry}.
 *
 * @hide
 */
public final class AnomalyDetectorRegistryImpl implements AnomalyDetectorRegistry {
    private static final String TAG = "AnomalyDetectorRegistry";
    private static final LogUtil sLog = new LogUtil(TAG);

    private final Map<String, AnomalyDetectorFactory> mFactories;

    /**
     * Constructs a new AnomalyDetectorRegistryImpl.
     *
     * @param factories The set of all available detector factories to be included in the registry.
     */
    public AnomalyDetectorRegistryImpl(Set<AnomalyDetector.AnomalyDetectorFactory> factories) {
        mFactories = new ArrayMap<>(factories.size());
        for (AnomalyDetector.AnomalyDetectorFactory factory : factories) {
            mFactories.put(factory.getConditionType(), factory);
        }
    }

    /** {@inheritDoc} */
    @Override
    @Nullable
    public AnomalyDetector.AnomalyDetectorFactory getFactory(
            @ConditionTypeInternal String conditionType) {
        return mFactories.get(conditionType);
    }

    /** {@inheritDoc} */
    @Override
    public AnomalyDetector createDetectorForCondition(
            @ConditionTypeInternal String conditionType, SignalCollectorRegistry registry) {
        AnomalyDetector.AnomalyDetectorFactory factory = getFactory(conditionType);

        if (factory == null) {
            sLog.w("No AnomalyDetectorFactory found for condition: " + conditionType);
            return null;
        }

        return factory.create(registry);
    }
}
