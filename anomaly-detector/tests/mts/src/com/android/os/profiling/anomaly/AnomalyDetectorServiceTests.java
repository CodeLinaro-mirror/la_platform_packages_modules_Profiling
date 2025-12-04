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

import static org.junit.Assert.assertThrows;

import android.content.Context;
import android.os.profiling.anomaly.flags.Flags;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.runner.AndroidJUnit4;

import com.android.os.profiling.anomaly.collector.SignalCollector;
import com.android.os.profiling.anomaly.collector.SignalCollectorConfig;
import com.android.os.profiling.anomaly.collector.SignalCollectorData;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/**
 * Tests for {@link AnomalyDetectorService}.
 *
 * <p>Verifies the behavior of the signal collector registration within the {@link
 * AnomalyDetectorService}, including the handling of invalid arguments and duplicate registrations.
 * This test class instantiates the service directly and requires platform-level visibility to
 * access its local manager.
 */
@RunWith(AndroidJUnit4.class)
@RequiresFlagsEnabled(Flags.FLAG_ANOMALY_DETECTOR_CORE)
public final class AnomalyDetectorServiceTests {

    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    @Rule public MockitoRule mMockitoRule = MockitoJUnit.rule();

    @Mock private SignalCollector<TestConfig, TestData> mTestCollector;
    @Mock private SignalCollector<TestConfig, TestData> mAnotherTestCollector;

    private Context mContext;
    private AnomalyDetectorService mService;
    private AnomalyDetectorManagerLocal mLocalManager;

    // Stub classes for strong typing in tests
    private static class TestConfig implements SignalCollectorConfig {}

    private static class TestData implements SignalCollectorData {}

    private static class AnotherData implements SignalCollectorData {}

    @Before
    public void setUp() {
        mContext = ApplicationProvider.getApplicationContext();
        mService = new AnomalyDetectorService(mContext);
        mLocalManager = mService.mLocalManager;
    }

    @Test
    public void registerSignalCollector_duplicate_throwsException() {
        registerCollector(TestConfig.class, TestData.class, mTestCollector);
        // Try to register another collector with the same config type, which should fail.
        assertThrows(
                IllegalArgumentException.class,
                () -> registerCollector(TestConfig.class, TestData.class, mAnotherTestCollector));
    }

    @Test
    public void registerSignalCollector_nullArgs_throwsException() {
        assertThrows(
                NullPointerException.class,
                () -> registerCollector(null, TestData.class, mTestCollector));
        assertThrows(
                NullPointerException.class,
                () -> registerCollector(TestConfig.class, null, mTestCollector));
        assertThrows(
                NullPointerException.class,
                () -> registerCollector(TestConfig.class, TestData.class, null));
    }

    /**
     * Helper method to register a collector using the local manager interface, which is exposed for
     * testing. This mimics how other system services would register collectors.
     */
    private <T extends SignalCollectorConfig, U extends SignalCollectorData> void registerCollector(
            Class<T> configType, Class<U> dataType, SignalCollector<T, U> collector) {
        mLocalManager.registerSignalCollector(configType, dataType, collector);
    }
}
