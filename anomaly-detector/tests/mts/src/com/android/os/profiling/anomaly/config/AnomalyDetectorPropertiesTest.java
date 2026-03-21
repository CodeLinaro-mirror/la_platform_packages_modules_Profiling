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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.provider.DeviceConfig;

import com.android.dx.mockito.inline.extended.ExtendedMockito;
import com.android.modules.utils.testing.ExtendedMockitoRule;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;

import java.util.concurrent.Executor;

@RunWith(JUnit4.class)
public class AnomalyDetectorPropertiesTest {

    @Rule
    public final ExtendedMockitoRule mExtendedMockitoRule =
            new ExtendedMockitoRule.Builder(this).mockStatic(DeviceConfig.class).build();

    @Mock private DeviceConfig.OnPropertiesChangedListener mMockListener1;
    @Mock private DeviceConfig.OnPropertiesChangedListener mMockListener2;

    @Captor private ArgumentCaptor<DeviceConfig.OnPropertiesChangedListener> mSystemListenerCaptor;

    private AnomalyDetectorProperties mProperties;

    @Before
    public void setUp() {
        // This is necessary to avoid trying to start a real thread.
        ExtendedMockito.doAnswer(invocation -> null)
                .when(() -> DeviceConfig.addOnPropertiesChangedListener(any(), any(), any()));

        mProperties = new AnomalyDetectorProperties();

        // Verify that the static method was called during the constructor
        ExtendedMockito.verify(
                () ->
                        DeviceConfig.addOnPropertiesChangedListener(
                                eq(AnomalyDetectorProperties.NAMESPACE),
                                any(Executor.class),
                                mSystemListenerCaptor.capture()));
    }

    @Test
    public void getProperties_forwardsCallToDeviceConfig() {
        DeviceConfig.Properties expectedProperties =
                new DeviceConfig.Properties.Builder(AnomalyDetectorProperties.NAMESPACE).build();
        ExtendedMockito.when(DeviceConfig.getProperties(AnomalyDetectorProperties.NAMESPACE))
                .thenReturn(expectedProperties);

        DeviceConfig.Properties actualProperties = mProperties.getProperties();

        assertThat(actualProperties).isSameInstanceAs(expectedProperties);
    }

    @Test
    public void listenerNotification_singleListener_isNotified() {
        mProperties.addOnPropertiesChangedListener(mMockListener1);

        DeviceConfig.Properties newProperties =
                new DeviceConfig.Properties.Builder(AnomalyDetectorProperties.NAMESPACE)
                        .setInt("key", 1)
                        .build();
        mSystemListenerCaptor.getValue().onPropertiesChanged(newProperties);

        verify(mMockListener1).onPropertiesChanged(newProperties);
    }

    @Test
    public void listenerNotification_multipleListeners_areNotified() {
        mProperties.addOnPropertiesChangedListener(mMockListener1);
        mProperties.addOnPropertiesChangedListener(mMockListener2);

        DeviceConfig.Properties newProperties =
                new DeviceConfig.Properties.Builder(AnomalyDetectorProperties.NAMESPACE)
                        .setInt("key", 2)
                        .build();
        mSystemListenerCaptor.getValue().onPropertiesChanged(newProperties);

        verify(mMockListener1).onPropertiesChanged(newProperties);
        verify(mMockListener2).onPropertiesChanged(newProperties);
    }

    @Test
    public void removeListener_isNotNotified() {
        mProperties.addOnPropertiesChangedListener(mMockListener1);
        mProperties.addOnPropertiesChangedListener(mMockListener2);
        mProperties.removeOnPropertiesChangedListener(mMockListener1);

        DeviceConfig.Properties newProperties =
                new DeviceConfig.Properties.Builder(AnomalyDetectorProperties.NAMESPACE)
                        .setInt("key", 3)
                        .build();
        mSystemListenerCaptor.getValue().onPropertiesChanged(newProperties);

        verify(mMockListener1, never()).onPropertiesChanged(any());
        verify(mMockListener2, times(1)).onPropertiesChanged(newProperties);
    }
}
