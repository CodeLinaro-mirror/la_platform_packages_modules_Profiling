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
import android.os.profiling.anomaly.RuleInternal;

import androidx.test.runner.AndroidJUnit4;

import com.android.os.profiling.anomaly.collector.SignalCollector;
import com.android.os.profiling.anomaly.collector.SignalCollectorConfig;
import com.android.os.profiling.anomaly.collector.SignalCollectorData;
import com.android.os.profiling.anomaly.core.AnomalyDetector;
import com.android.os.profiling.anomaly.core.AnomalyDetectorRegistry;
import com.android.os.profiling.anomaly.core.AnomalyHandlerRegistry;
import com.android.os.profiling.anomaly.core.RuleStorage;
import com.android.os.profiling.anomaly.core.SignalCollectorRegistry;
import com.android.os.profiling.anomaly.core.SignalTypeId;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.Mockito;
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

    @Captor private ArgumentCaptor<Consumer<SignalTypeId>> mCallbackCaptor;

    private AnomalyDetectorControllerImpl mController;
    private RuleInternal mTestConditionRule;
    private RuleInternal mUnregisteredConditionRule;

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
                new RuleInternal.Builder()
                        .setName("test_rule")
                        .setConditionType(TEST_CONDITION_TYPE)
                        .setRuleCondition(new Bundle())
                        .addAnomalyAction(RuleInternal.ACTION_TYPE_LOG)
                        .build();
        mUnregisteredConditionRule =
                new RuleInternal.Builder()
                        .setName("unregistered_test_rule")
                        .setConditionType(UNREGISTERED_CONDITION_TYPE)
                        .setRuleCondition(new Bundle())
                        .addAnomalyAction(RuleInternal.ACTION_TYPE_LOG)
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
        ArgumentCaptor<Consumer<SignalTypeId>> callbackCaptor =
                ArgumentCaptor.forClass(Consumer.class);
        verify(mMockSignalCollectorRegistry)
                .addCollectorRegisteredCallback(any(), callbackCaptor.capture());
        Consumer<SignalTypeId> callback = callbackCaptor.getValue();

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
        callback.accept(new SignalTypeId(TestConfig.class, TestData.class));

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

    @Test
    public void onSignalCollectorUnregistered_removesAffectedDetector() {
        // Capture the unregistration callback.
        verify(mMockSignalCollectorRegistry)
                .addCollectorUnregisteredCallback(any(), mCallbackCaptor.capture());
        Consumer<SignalTypeId> callback = mCallbackCaptor.getValue();

        // Set up a rule and detector.
        when(mMockAnomalyDetectorRegistry.getFactory(any(String.class)))
                .thenAnswer(i -> mMockFactory);
        when(mMockAnomalyDetectorRegistry.createDetectorForRule(any(), any()))
                .thenAnswer(i -> mMockDetector);
        SignalTypeId signalTypeId = new SignalTypeId(TestConfig.class, TestData.class);
        when(mMockFactory.getRequiredSignalCollectorTypes())
                .thenReturn(Collections.singleton(signalTypeId));

        mController.setRules(Collections.singleton(mTestConditionRule));
        verify(mMockDetector).setOnAnomalyDetectedListener(mController);

        // Reset the mock to ensure we are only verifying behavior from this point forward.
        Mockito.reset(mMockAnomalyDetectorRegistry);
        when(mMockAnomalyDetectorRegistry.getFactory(any(String.class)))
                .thenAnswer(i -> mMockFactory);
        when(mMockFactory.getRequiredSignalCollectorTypes())
                .thenReturn(Collections.singleton(signalTypeId));

        // Simulate the collector being unregistered.
        callback.accept(signalTypeId);

        // Verify the detector was notified.
        verify(mMockDetector).onSignalCollectorUnregistered(signalTypeId);
        verify(mMockAnomalyDetectorRegistry, never()).createDetectorForRule(any(), any());
    }

    @Test
    public void onSignalCollectorUnregistered_noActiveRule_doesNothing() {
        // Capture the unregistration callback.
        verify(mMockSignalCollectorRegistry)
                .addCollectorUnregisteredCallback(any(), mCallbackCaptor.capture());
        Consumer<SignalTypeId> callback = mCallbackCaptor.getValue();

        // No rules are set, so no detectors are active.

        // Simulate a collector being unregistered.
        callback.accept(new SignalTypeId(TestConfig.class, TestData.class));

        // Verify that no detectors were told about it (because there are none).
        verify(mMockDetector, never()).onSignalCollectorUnregistered(any());
    }
}
