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
import android.util.ArrayMap;
import android.util.Log;

import com.android.os.profiling.anomaly.core.AnomalyDetector;
import com.android.os.profiling.anomaly.core.AnomalyDetector.AnomalyDetectorFactory;
import com.android.os.profiling.anomaly.core.AnomalyDetectorRegistry;
import com.android.os.profiling.anomaly.core.BaseCondition;
import com.android.os.profiling.anomaly.core.Rule;
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

    // TODO: This will need to be changed once the new API providing ConditionType instead of
    // BaseCondition is available.
    private final Map<Class<? extends BaseCondition>, AnomalyDetectorFactory<?>> mFactories;

    /**
     * Constructs a new AnomalyDetectorRegistryImpl.
     *
     * @param factories The set of all available detector factories to be included in the registry.
     */
    public AnomalyDetectorRegistryImpl(Set<AnomalyDetector.AnomalyDetectorFactory<?>> factories) {
        mFactories = new ArrayMap<>(factories.size());
        for (AnomalyDetector.AnomalyDetectorFactory<?> factory : factories) {
            mFactories.put(factory.getConditionClass(), factory);
        }
    }

    /** {@inheritDoc} */
    @Override
    @Nullable
    public AnomalyDetector.AnomalyDetectorFactory<?> getFactory(
            Class<? extends BaseCondition> conditionClass) {
        return mFactories.get(conditionClass);
    }

    /** {@inheritDoc} */
    @Override
    public AnomalyDetector<? extends BaseCondition> createDetectorForRule(
            Rule<? extends BaseCondition> rule, SignalCollectorRegistry registry) {
        BaseCondition condition = rule.baseCondition();
        AnomalyDetector.AnomalyDetectorFactory<?> factory = getFactory(condition.getClass());

        if (factory == null) {
            Log.w(
                    TAG,
                    "No AnomalyDetectorFactory found for condition: "
                            + condition.getClass().getSimpleName());
            return null;
        }

        // Use a helper to resolve generics and ensure type safety.
        return createAndSetRuleHelper(factory, rule, registry);
    }

    /** A type-safe helper to create the detector and set its rule. */
    @SuppressWarnings("unchecked")
    private <T extends BaseCondition> AnomalyDetector<T> createAndSetRuleHelper(
            AnomalyDetectorFactory<T> factory, Rule<?> rule, SignalCollectorRegistry registry) {
        AnomalyDetector<T> detector = factory.create(registry);
        detector.setRule((Rule<T>) rule);
        return detector;
    }
}
