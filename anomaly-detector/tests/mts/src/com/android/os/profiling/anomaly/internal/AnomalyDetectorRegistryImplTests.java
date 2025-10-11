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

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.os.profiling.anomaly.flags.Flags;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;

import androidx.test.runner.AndroidJUnit4;

import com.android.os.profiling.anomaly.core.AnomalyDetector;
import com.android.os.profiling.anomaly.core.AnomalyDetector.AnomalyDetectorFactory;
import com.android.os.profiling.anomaly.core.AnomalyDetectorRegistry;
import com.android.os.profiling.anomaly.core.BaseCondition;
import com.android.os.profiling.anomaly.core.SignalCollectorRegistry;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** Tests for {@link AnomalyDetectorRegistryImpl}. */
@RunWith(AndroidJUnit4.class)
@RequiresFlagsEnabled(Flags.FLAG_ANOMALY_DETECTOR_CORE)
public final class AnomalyDetectorRegistryImplTests {

    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    @Rule public MockitoRule mMockitoRule = MockitoJUnit.rule();

    @Mock private AnomalyDetectorFactory<TestCondition> mMockFactory;
    @Mock private AnomalyDetector<TestCondition> mMockDetector;
    @Mock private SignalCollectorRegistry mMockSignalCollectorRegistry;

    private AnomalyDetectorRegistry mRegistry;

    private static final class TestCondition implements BaseCondition {}

    private static final class UnregisteredCondition implements BaseCondition {}

    @Before
    public void setUp() {
        when(mMockFactory.getConditionClass()).thenReturn(TestCondition.class);
        Set<AnomalyDetectorFactory<?>> factories = new HashSet<>();
        factories.add(mMockFactory);
        mRegistry = new AnomalyDetectorRegistryImpl(factories);
    }

    @Test
    public void getFactory_success() {
        AnomalyDetectorFactory<?> factory = mRegistry.getFactory(TestCondition.class);
        assertThat(factory).isEqualTo(mMockFactory);
    }

    @Test
    public void getFactory_unregistered_returnsNull() {
        AnomalyDetectorFactory<?> factory = mRegistry.getFactory(UnregisteredCondition.class);
        assertThat(factory).isNull();
    }

    @Test
    public void createDetectorForRule_success() {
        when(mMockFactory.create(any(SignalCollectorRegistry.class))).thenReturn(mMockDetector);

        com.android.os.profiling.anomaly.core.Rule<TestCondition> rule =
                new com.android.os.profiling.anomaly.core.Rule<>(
                        new TestCondition(), Collections.emptySet());
        AnomalyDetector<?> detector =
                mRegistry.createDetectorForRule(rule, mMockSignalCollectorRegistry);

        assertThat(detector).isEqualTo(mMockDetector);
        verify(mMockFactory).create(mMockSignalCollectorRegistry);
        verify(mMockDetector).setRule(rule);
    }

    @Test
    public void createDetectorForRule_unregistered_returnsNull() {
        com.android.os.profiling.anomaly.core.Rule<UnregisteredCondition> rule =
                new com.android.os.profiling.anomaly.core.Rule<>(
                        new UnregisteredCondition(), Collections.emptySet());
        AnomalyDetector<?> detector =
                mRegistry.createDetectorForRule(rule, mMockSignalCollectorRegistry);
        assertThat(detector).isNull();
    }
}
