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

package com.android.os.profiling.anomaly;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.os.OutcomeReceiver;
import android.os.profiling.anomaly.flags.Flags;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.runner.AndroidJUnit4;

import com.android.os.profiling.anomaly.collector.SignalCollector;
import com.android.os.profiling.anomaly.collector.SignalCollectorConfig;
import com.android.os.profiling.anomaly.collector.SignalCollectorData;
import com.android.os.profiling.anomaly.collector.SubscriptionId;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/**
 * Tests for {@link AnomalyDetectorService}.
 * <p>
 * These tests verify the core functionality of the anomaly detector service, including
 * signal collector registration and methods for data subscription and retrieval.
 * This test class instantiates the service directly and requires platform-level visibility.
 */
@RunWith(AndroidJUnit4.class)
@RequiresFlagsEnabled(Flags.FLAG_ANOMALY_DETECTOR_CORE)
public class AnomalyDetectorServiceTests {

    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    @Rule
    public MockitoRule mMockitoRule = MockitoJUnit.rule();

    @Mock private SignalCollector<TestConfig, TestData> mTestCollector;
    @Mock private SignalCollector<TestConfig, TestData> mAnotherTestCollector;
    @Mock private OutcomeReceiver<TestData, Throwable> mTestListener;

    private Context mContext;
    private AnomalyDetectorService mService;
    private AnomalyDetectorManagerLocal mLocalManager;

    private TestConfig mTestConfig;
    private SubscriptionId mSubscriptionId;

    // Stub classes for strong typing in tests
    private static class TestConfig implements SignalCollectorConfig {}
    private static class TestData implements SignalCollectorData {}
    private static class AnotherData implements SignalCollectorData {}

    @Before
    public void setUp() {
        mContext = ApplicationProvider.getApplicationContext();
        mService = new AnomalyDetectorService(mContext);
        mLocalManager = mService.mLocalManager;
        mTestConfig = new TestConfig();
        mSubscriptionId = SubscriptionId.generateNew();
    }

    @Test
    public void registerSignalCollector_success() {
        registerCollector(TestConfig.class, TestData.class, mTestCollector);

        // Verify the collector is in the map.
        assertThat(mService.mRegisteredCollectors).containsKey(TestConfig.class);
        assertThat(mService.mRegisteredCollectors.get(TestConfig.class).getCollector())
                .isEqualTo(mTestCollector);
    }

    @Test
    public void registerSignalCollector_duplicate_throwsException() {
        registerCollector(TestConfig.class, TestData.class, mTestCollector);
        // Try to register another collector with the same config type, which should fail.
        assertThrows(IllegalArgumentException.class,
                () -> registerCollector(TestConfig.class, TestData.class, mAnotherTestCollector));
    }

    @Test
    public void registerSignalCollector_nullArgs_throwsException() {
        assertThrows(NullPointerException.class,
                () -> registerCollector(null, TestData.class, mTestCollector));
        assertThrows(NullPointerException.class,
                () -> registerCollector(TestConfig.class, null, mTestCollector));
        assertThrows(NullPointerException.class,
                () -> registerCollector(TestConfig.class, TestData.class, null));
    }

    @Test
    public void getSignalCollector_success() {
        registerCollector(TestConfig.class, TestData.class, mTestCollector);
        SignalCollector<TestConfig, TestData> collector =
                mService.getSignalCollector(TestConfig.class, TestData.class);

        assertThat(collector).isNotNull();
        assertThat(collector).isEqualTo(mTestCollector);
    }

    @Test
    public void getSignalCollector_notFound_returnsNull() {
        SignalCollector<TestConfig, TestData> collector =
                mService.getSignalCollector(TestConfig.class, TestData.class);

        assertThat(collector).isNull();
    }

    @Test
    public void getSignalCollector_dataTypeMismatch_returnsNull() {
        registerCollector(TestConfig.class, TestData.class, mTestCollector);
        // Request with a different data type
        SignalCollector<TestConfig, AnotherData> collector =
                mService.getSignalCollector(TestConfig.class, AnotherData.class);

        assertThat(collector).isNull();
    }

    @Test
    public void subscribeToData_success() {
        registerCollector(TestConfig.class, TestData.class, mTestCollector);
        when(mTestCollector.subscribe(mTestConfig, mTestListener)).thenReturn(mSubscriptionId);

        SubscriptionId resultId = mService.subscribeToData(mTestConfig, TestData.class,
                mTestListener);

        verify(mTestCollector).subscribe(mTestConfig, mTestListener);

        assertThat(resultId).isEqualTo(mSubscriptionId);
    }

    @Test
    public void subscribeToData_collectorNotFound_throwsException() {
        assertThrows(IllegalArgumentException.class,
                () -> mService.subscribeToData(mTestConfig, TestData.class, mTestListener));
    }

    @Test
    public void getCurrentData_success() {
        registerCollector(TestConfig.class, TestData.class, mTestCollector);
        TestData testData = new TestData();
        when(mTestCollector.getData(mSubscriptionId)).thenReturn(testData);

        TestData resultData = mService.getCurrentData(mSubscriptionId, TestConfig.class,
                TestData.class);

        verify(mTestCollector).getData(mSubscriptionId);

        assertThat(resultData).isEqualTo(testData);
    }

    @Test
    public void getCurrentData_collectorNotFound_throwsException() {
        assertThrows(IllegalArgumentException.class,
                () -> mService.getCurrentData(mSubscriptionId, TestConfig.class, TestData.class));
    }

    @Test
    public void requestSubscriptionUpdate_success() {
        registerCollector(TestConfig.class, TestData.class, mTestCollector);

        mService.requestSubscriptionUpdate(mSubscriptionId, TestConfig.class, TestData.class);

        verify(mTestCollector).requestUpdate(mSubscriptionId);
    }

    @Test
    public void requestSubscriptionUpdate_collectorNotFound_throwsException() {
        assertThrows(IllegalArgumentException.class,
                () -> mService.requestSubscriptionUpdate(mSubscriptionId, TestConfig.class,
                        TestData.class));
    }

    @Test
    public void unsubscribeFromData_success() {
        registerCollector(TestConfig.class, TestData.class, mTestCollector);

        mService.unsubscribeFromData(mSubscriptionId, TestConfig.class, TestData.class);

        verify(mTestCollector).unsubscribe(mSubscriptionId);
    }

    @Test
    public void unsubscribeFromData_collectorNotFound_throwsException() {
        assertThrows(IllegalArgumentException.class,
                () -> mService.unsubscribeFromData(mSubscriptionId, TestConfig.class,
                        TestData.class));
    }

    /**
     * Helper method to register a collector using the local manager interface,
     * which is exposed for testing. This mimics how other system services would
     * register collectors.
     */
    private <T extends SignalCollectorConfig, U extends SignalCollectorData> void registerCollector(
            Class<T> configType, Class<U> dataType,
            SignalCollector<T, U> collector) {
        mLocalManager.registerSignalCollector(configType, dataType, collector);
    }
}
