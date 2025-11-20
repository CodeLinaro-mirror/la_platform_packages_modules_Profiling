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
import android.os.profiling.anomaly.Rule;
import android.os.profiling.anomaly.Rule.ConditionType;
import android.util.ArrayMap;
import android.util.Log;

import com.android.os.profiling.anomaly.core.AnomalyDetector;
import com.android.os.profiling.anomaly.core.AnomalyDetector.AnomalyDetectorFactory;
import com.android.os.profiling.anomaly.core.AnomalyDetectorRegistry;
import com.android.os.profiling.anomaly.core.SignalCollectorRegistry;

import java.util.Map;
import java.util.Set;

/**
 * The default implementation of the {@link AnomalyDetectorRegistry}.
 *
 * @hide
 */
public final class AnomalyDetectorRegistryImpl implements AnomalyDetectorRegistry {
    private static final String TAG = "AnomalyDetectorRegistry";

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
    public AnomalyDetector.AnomalyDetectorFactory getFactory(@ConditionType String conditionType) {
        return mFactories.get(conditionType);
    }

    /** {@inheritDoc} */
    @Override
    public AnomalyDetector createDetectorForRule(Rule rule, SignalCollectorRegistry registry) {
        @ConditionType String conditionType = rule.getConditionType();
        AnomalyDetector.AnomalyDetectorFactory factory = getFactory(conditionType);

        if (factory == null) {
            Log.w(TAG, "No AnomalyDetectorFactory found for condition: " + conditionType);
            return null;
        }

        AnomalyDetector detector = factory.create(registry);
        detector.setRule(rule);
        return detector;
    }
}
