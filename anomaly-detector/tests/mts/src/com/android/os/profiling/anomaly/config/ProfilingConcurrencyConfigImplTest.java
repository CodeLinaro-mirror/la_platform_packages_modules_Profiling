/*
 * Copyright (C) 2026 The Android Open Source Project
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

package com.android.os.profiling.anomaly.config;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.provider.DeviceConfig;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@RunWith(JUnit4.class)
public class ProfilingConcurrencyConfigImplTest {

    private static final int TEST_DEVICE_MAX_CONCURRENT_SESSIONS =
            ProfilingConcurrencyConfigImpl.DEFAULT_DEVICE_MAX_CONCURRENT_SESSIONS + 1;

    @Rule public MockitoRule mMockitoRule = MockitoJUnit.rule();

    @Mock private AnomalyDetectorProperties mMockPropertiesProvider;
    @Captor private ArgumentCaptor<DeviceConfig.OnPropertiesChangedListener> mListenerCaptor;

    private ProfilingConcurrencyConfigImpl mConcurrencyConfig;

    @Before
    public void setUp() {
        when(mMockPropertiesProvider.getProperties())
                .thenReturn(
                        new DeviceConfig.Properties.Builder(AnomalyDetectorProperties.NAMESPACE)
                                .build());

        mConcurrencyConfig = new ProfilingConcurrencyConfigImpl(mMockPropertiesProvider);
        verify(mMockPropertiesProvider).addOnPropertiesChangedListener(mListenerCaptor.capture());
    }

    @Test
    public void constructor_noProperties_initializesWithDefaults() {
        assertThat(mConcurrencyConfig.getDeviceMaxConcurrentSessions())
                .isEqualTo(ProfilingConcurrencyConfigImpl.DEFAULT_DEVICE_MAX_CONCURRENT_SESSIONS);
    }

    @Test
    public void constructor_allPropertiesFromServer_initializesWithServerValues() {
        DeviceConfig.Properties properties =
                new DeviceConfig.Properties.Builder(AnomalyDetectorProperties.NAMESPACE)
                        .setInt(
                                ProfilingConcurrencyConfigImpl.KEY_DEVICE_MAX_CONCURRENT_SESSIONS,
                                TEST_DEVICE_MAX_CONCURRENT_SESSIONS)
                        .build();

        when(mMockPropertiesProvider.getProperties()).thenReturn(properties);
        mConcurrencyConfig = new ProfilingConcurrencyConfigImpl(mMockPropertiesProvider);

        assertThat(mConcurrencyConfig.getDeviceMaxConcurrentSessions())
                .isEqualTo(TEST_DEVICE_MAX_CONCURRENT_SESSIONS);
    }

    @Test
    public void onPropertiesChanged_partialUpdate_updatesCorrectValue() {
        DeviceConfig.Properties newProperties =
                new DeviceConfig.Properties.Builder(AnomalyDetectorProperties.NAMESPACE)
                        .setInt(
                                ProfilingConcurrencyConfigImpl.KEY_DEVICE_MAX_CONCURRENT_SESSIONS,
                                TEST_DEVICE_MAX_CONCURRENT_SESSIONS)
                        .build();

        mListenerCaptor.getValue().onPropertiesChanged(newProperties);

        assertThat(mConcurrencyConfig.getDeviceMaxConcurrentSessions())
                .isEqualTo(TEST_DEVICE_MAX_CONCURRENT_SESSIONS);
    }

    @Test
    public void onPropertiesChanged_propertyDeleted_revertsToDefault() {
        DeviceConfig.Properties initialProperties =
                new DeviceConfig.Properties.Builder(AnomalyDetectorProperties.NAMESPACE)
                        .setInt(
                                ProfilingConcurrencyConfigImpl.KEY_DEVICE_MAX_CONCURRENT_SESSIONS,
                                TEST_DEVICE_MAX_CONCURRENT_SESSIONS)
                        .build();
        mListenerCaptor.getValue().onPropertiesChanged(initialProperties);
        assertThat(mConcurrencyConfig.getDeviceMaxConcurrentSessions())
                .isEqualTo(TEST_DEVICE_MAX_CONCURRENT_SESSIONS);

        DeviceConfig.Properties deletionProperties =
                new DeviceConfig.Properties.Builder(AnomalyDetectorProperties.NAMESPACE)
                        .setString(
                                ProfilingConcurrencyConfigImpl.KEY_DEVICE_MAX_CONCURRENT_SESSIONS,
                                null)
                        .build();

        mListenerCaptor.getValue().onPropertiesChanged(deletionProperties);

        assertThat(mConcurrencyConfig.getDeviceMaxConcurrentSessions())
                .isEqualTo(ProfilingConcurrencyConfigImpl.DEFAULT_DEVICE_MAX_CONCURRENT_SESSIONS);
    }
}
