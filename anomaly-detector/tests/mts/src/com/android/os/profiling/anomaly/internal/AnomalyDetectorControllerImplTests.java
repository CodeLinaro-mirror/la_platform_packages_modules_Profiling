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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.os.Bundle;
import android.os.profiling.anomaly.Rule;

import androidx.test.runner.AndroidJUnit4;

import com.android.os.profiling.anomaly.collector.SignalCollector;
import com.android.os.profiling.anomaly.collector.SignalCollectorConfig;
import com.android.os.profiling.anomaly.collector.SignalCollectorData;
import com.android.os.profiling.anomaly.core.AnomalyDetector;
import com.android.os.profiling.anomaly.core.AnomalyDetectorRegistry;
import com.android.os.profiling.anomaly.core.AnomalyHandlerRegistry;
import com.android.os.profiling.anomaly.core.RuleStorage;
import com.android.os.profiling.anomaly.core.SignalCollectorRegistry;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.Collections;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** Tests for {@link AnomalyDetectorControllerImpl}. */
@RunWith(AndroidJUnit4.class)
public final class AnomalyDetectorControllerImplTests {
    @org.junit.Rule public MockitoRule mMockitoRule = MockitoJUnit.rule();

    @Mock private RuleStorage mMockRuleStorage;
    @Mock private SignalCollectorRegistry mMockSignalCollectorRegistry;
    @Mock private AnomalyHandlerRegistry mMockAnomalyHandlerRegistry;
    @Mock private AnomalyDetectorRegistry mMockAnomalyDetectorRegistry;
    @Mock private AnomalyDetector.AnomalyDetectorFactory mMockFactory;
    @Mock private AnomalyDetector mMockDetector;
    @Mock private SignalCollector<TestConfig, TestData> mMockCollector;

    @Captor private ArgumentCaptor<Consumer<SignalCollector<?, ?>>> mCallbackCaptor;

    private AnomalyDetectorControllerImpl mController;
    private Rule mTestConditionRule;
    private Rule mUnregisteredConditionRule;

    private static final String TEST_CONDITION_TYPE = "test_condition";
    private static final String UNREGISTERED_CONDITION_TYPE = "unregistered_condition";

    private static class TestConfig implements SignalCollectorConfig {}

    private static class TestData implements SignalCollectorData {}

    @Before
    public void setUp() {
        Executor executor = Runnable::run; // Direct executor
        mController =
                new AnomalyDetectorControllerImpl(
                        mMockRuleStorage,
                        mMockSignalCollectorRegistry,
                        mMockAnomalyHandlerRegistry,
                        mMockAnomalyDetectorRegistry,
                        executor);
        mTestConditionRule =
                new Rule.Builder()
                        .setConditionType(TEST_CONDITION_TYPE)
                        .setRuleCondition(new Bundle())
                        .addAnomalyAction(Rule.ACTION_TYPE_LOG)
                        .build();
        mUnregisteredConditionRule =
                new Rule.Builder()
                        .setConditionType(UNREGISTERED_CONDITION_TYPE)
                        .setRuleCondition(new Bundle())
                        .addAnomalyAction(Rule.ACTION_TYPE_LOG)
                        .build();
    }

    @Test
    public void setRules_detectorCreated() {
        when(mMockAnomalyDetectorRegistry.getFactory(any(String.class)))
                .thenAnswer(i -> mMockFactory);
        when(mMockAnomalyDetectorRegistry.createDetectorForRule(any(), any()))
                .thenAnswer(i -> mMockDetector);

        mController.setRules(Collections.singleton(mTestConditionRule));

        verify(mMockAnomalyDetectorRegistry).getFactory(TEST_CONDITION_TYPE);
        verify(mMockAnomalyDetectorRegistry)
                .createDetectorForRule(mTestConditionRule, mMockSignalCollectorRegistry);
        verify(mMockDetector).setOnAnomalyDetectedListener(mController);
    }

    @Test
    public void setRules_flushesOldDetectors() {
        // Activate a first rule.
        when(mMockAnomalyDetectorRegistry.getFactory(any(String.class)))
                .thenAnswer(i -> mMockFactory);
        when(mMockAnomalyDetectorRegistry.createDetectorForRule(any(), any()))
                .thenAnswer(i -> mMockDetector);
        mController.setRules(Collections.singleton(mTestConditionRule));

        // Now, set a new, empty set of rules.
        mController.setRules(Collections.emptySet());

        // Verify the old detector was flushed by having setRule(null) called on it.
        verify(mMockDetector).setRule(null);
    }

    @Test
    public void onSignalCollectorRegistered_activatesPendingRule() {
        // Capture the callback that the controller registers.
        verify(mMockSignalCollectorRegistry)
                .addCollectorRegisteredCallback(any(), mCallbackCaptor.capture());
        Consumer<SignalCollector<?, ?>> callback = mCallbackCaptor.getValue();

        // Set up a rule for which the detector cannot be created initially.
        when(mMockAnomalyDetectorRegistry.getFactory(any(String.class)))
                .thenAnswer(i -> mMockFactory);
        when(mMockAnomalyDetectorRegistry.createDetectorForRule(
                        mTestConditionRule, mMockSignalCollectorRegistry))
                .thenReturn(null); // Simulate dependency not met

        // Try to set the rule. The detector should not be activated.
        mController.setRules(Collections.singleton(mTestConditionRule));
        verify(mMockDetector, never()).setOnAnomalyDetectedListener(any());

        // Now, change the mock so the detector *can* be created.
        when(mMockAnomalyDetectorRegistry.createDetectorForRule(
                        mTestConditionRule, mMockSignalCollectorRegistry))
                .thenAnswer(i -> mMockDetector);

        // Trigger the callback, simulating a new collector being registered.
        callback.accept(mMockCollector);

        // Verify the controller re-evaluated the rule and activated the detector this time.
        verify(mMockAnomalyDetectorRegistry, times(2))
                .createDetectorForRule(mTestConditionRule, mMockSignalCollectorRegistry);
        verify(mMockDetector).setOnAnomalyDetectedListener(mController);
    }

    @Test
    public void setRules_detectorNotCreatedWhenFactoryMissing() {
        when(mMockAnomalyDetectorRegistry.getFactory(UNREGISTERED_CONDITION_TYPE)).thenReturn(null);

        mController.setRules(Collections.singleton(mUnregisteredConditionRule));

        verify(mMockAnomalyDetectorRegistry).getFactory(UNREGISTERED_CONDITION_TYPE);
        verify(mMockAnomalyDetectorRegistry, never()).createDetectorForRule(any(), any());
    }
}
