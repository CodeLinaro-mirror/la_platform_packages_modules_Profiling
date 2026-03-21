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

package com.android.os.profiling.anomaly.ratelimiter;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.provider.DeviceConfig;
import android.util.Base64;

import com.android.os.profiling.anomaly.config.AnomalyDetectorProperties;
import com.android.os.profiling.anomaly.ratelimiter.proto.SignatureCoolDownConfigProto;
import com.android.os.profiling.anomaly.ratelimiter.proto.SignatureCoolDownRule;

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

import java.util.Collections;

@RunWith(JUnit4.class)
public class RateLimiterConfigImplTest {

    // Define test constants that are different from the defaults
    private static final long TEST_DEVICE_FREQUENCY_WINDOW_MILLIS =
            RateLimiterConfigImpl.DEFAULT_DEVICE_FREQUENCY_WINDOW_MILLIS + 1000;
    private static final int TEST_DEVICE_FREQUENCY_MAX_COUNT =
            RateLimiterConfigImpl.DEFAULT_DEVICE_FREQUENCY_MAX_COUNT + 1;
    private static final long TEST_UID_COOL_DOWN_MILLIS =
            RateLimiterConfigImpl.DEFAULT_UID_COOL_DOWN_MILLIS + 1000;
    private static final String ANY_CONDITION_TYPE = "any";
    private static final String TEST_CONDITION_TYPE = "test_type";
    private static final String TEST_RULE_NAME = "test_rule";
    private static final String OTHER_CONDITION_TYPE = "other_type";
    private static final long TEST_COOL_DOWN_MILLIS = 9999L;
    private static final String INVALID_RULE = "invalid_rule";

    @Rule public MockitoRule mMockitoRule = MockitoJUnit.rule();

    @Mock private AnomalyDetectorProperties mMockPropertiesProvider;
    @Captor private ArgumentCaptor<DeviceConfig.OnPropertiesChangedListener> mListenerCaptor;

    private RateLimiterConfigImpl mRateLimiterConfig;

    @Before
    public void setUp() {
        // Default behavior: return empty properties for initialization.
        when(mMockPropertiesProvider.getProperties())
                .thenReturn(
                        new DeviceConfig.Properties.Builder(AnomalyDetectorProperties.NAMESPACE)
                                .build());

        // Instantiate the SUT. This will call getProperties() and addOnPropertiesChangedListener.
        mRateLimiterConfig = new RateLimiterConfigImpl(mMockPropertiesProvider);

        // Capture the listener that was added.
        verify(mMockPropertiesProvider).addOnPropertiesChangedListener(mListenerCaptor.capture());
    }

    @Test
    public void constructor_noProperties_initializesWithDefaults() {
        assertThat(mRateLimiterConfig.getDeviceFrequencyWindowMillis())
                .isEqualTo(RateLimiterConfigImpl.DEFAULT_DEVICE_FREQUENCY_WINDOW_MILLIS);
        assertThat(mRateLimiterConfig.getDeviceFrequencyMaxCount())
                .isEqualTo(RateLimiterConfigImpl.DEFAULT_DEVICE_FREQUENCY_MAX_COUNT);
        assertThat(mRateLimiterConfig.getUidCoolDownMillis())
                .isEqualTo(RateLimiterConfigImpl.DEFAULT_UID_COOL_DOWN_MILLIS);
        assertThat(
                        mRateLimiterConfig.getSignatureCoolDownMillis(
                                ANY_CONDITION_TYPE, Collections.emptyMap()))
                .isEqualTo(0);
    }

    @Test
    public void constructor_allPropertiesFromServer_initializesWithServerValues() {
        DeviceConfig.Properties properties =
                new DeviceConfig.Properties.Builder(AnomalyDetectorProperties.NAMESPACE)
                        .setLong(
                                RateLimiterConfigImpl.KEY_DEVICE_FREQUENCY_WINDOW_MILLIS,
                                TEST_DEVICE_FREQUENCY_WINDOW_MILLIS)
                        .setInt(
                                RateLimiterConfigImpl.KEY_DEVICE_FREQUENCY_MAX_COUNT,
                                TEST_DEVICE_FREQUENCY_MAX_COUNT)
                        .setLong(
                                RateLimiterConfigImpl.KEY_UID_COOL_DOWN_MILLIS,
                                TEST_UID_COOL_DOWN_MILLIS)
                        .build();

        when(mMockPropertiesProvider.getProperties()).thenReturn(properties);

        // Re-initialize to test constructor loading
        mRateLimiterConfig = new RateLimiterConfigImpl(mMockPropertiesProvider);

        assertThat(mRateLimiterConfig.getDeviceFrequencyWindowMillis())
                .isEqualTo(TEST_DEVICE_FREQUENCY_WINDOW_MILLIS);
        assertThat(mRateLimiterConfig.getDeviceFrequencyMaxCount())
                .isEqualTo(TEST_DEVICE_FREQUENCY_MAX_COUNT);
        assertThat(mRateLimiterConfig.getUidCoolDownMillis()).isEqualTo(TEST_UID_COOL_DOWN_MILLIS);
        assertThat(
                        mRateLimiterConfig.getSignatureCoolDownMillis(
                                ANY_CONDITION_TYPE, Collections.emptyMap()))
                .isEqualTo(0);
    }

    @Test
    public void onPropertiesChanged_partialUpdate_updatesCorrectValue() {
        // Initial state is defaults.
        assertThat(mRateLimiterConfig.getDeviceFrequencyMaxCount())
                .isEqualTo(RateLimiterConfigImpl.DEFAULT_DEVICE_FREQUENCY_MAX_COUNT);
        assertThat(mRateLimiterConfig.getUidCoolDownMillis())
                .isEqualTo(RateLimiterConfigImpl.DEFAULT_UID_COOL_DOWN_MILLIS);

        // Simulate a change event with only one property.
        DeviceConfig.Properties newProperties =
                new DeviceConfig.Properties.Builder(AnomalyDetectorProperties.NAMESPACE)
                        .setInt(
                                RateLimiterConfigImpl.KEY_DEVICE_FREQUENCY_MAX_COUNT,
                                TEST_DEVICE_FREQUENCY_MAX_COUNT)
                        .build();

        mListenerCaptor.getValue().onPropertiesChanged(newProperties);

        // Verify the updated property has changed.
        assertThat(mRateLimiterConfig.getDeviceFrequencyMaxCount())
                .isEqualTo(TEST_DEVICE_FREQUENCY_MAX_COUNT);

        // Verify other properties remain unchanged (still default).
        assertThat(mRateLimiterConfig.getDeviceFrequencyWindowMillis())
                .isEqualTo(RateLimiterConfigImpl.DEFAULT_DEVICE_FREQUENCY_WINDOW_MILLIS);
        assertThat(mRateLimiterConfig.getUidCoolDownMillis())
                .isEqualTo(RateLimiterConfigImpl.DEFAULT_UID_COOL_DOWN_MILLIS);
    }

    @Test
    public void onPropertiesChanged_propertyDeleted_revertsToDefault() {
        // 1. Start with a non-default server value.
        DeviceConfig.Properties initialProperties =
                new DeviceConfig.Properties.Builder(AnomalyDetectorProperties.NAMESPACE)
                        .setInt(
                                RateLimiterConfigImpl.KEY_DEVICE_FREQUENCY_MAX_COUNT,
                                TEST_DEVICE_FREQUENCY_MAX_COUNT)
                        .build();
        mListenerCaptor.getValue().onPropertiesChanged(initialProperties);
        assertThat(mRateLimiterConfig.getDeviceFrequencyMaxCount())
                .isEqualTo(TEST_DEVICE_FREQUENCY_MAX_COUNT);

        // 2. Simulate a deletion event (key is present, value is null).
        DeviceConfig.Properties deletionProperties =
                new DeviceConfig.Properties.Builder(AnomalyDetectorProperties.NAMESPACE)
                        .setString(RateLimiterConfigImpl.KEY_DEVICE_FREQUENCY_MAX_COUNT, null)
                        .build();

        mListenerCaptor.getValue().onPropertiesChanged(deletionProperties);

        // 3. Verify the value has reverted to the hardcoded default.
        assertThat(mRateLimiterConfig.getDeviceFrequencyMaxCount())
                .isEqualTo(RateLimiterConfigImpl.DEFAULT_DEVICE_FREQUENCY_MAX_COUNT);
    }

    @Test
    public void onPropertiesChanged_protoConfigChanged_updatesProtoRules() {
        // Initial state is default.
        assertThat(mRateLimiterConfig.getSignatureCoolDownMillis(TEST_CONDITION_TYPE, null))
                .isEqualTo(0);

        // Create a new proto config.
        SignatureCoolDownConfigProto proto =
                SignatureCoolDownConfigProto.newBuilder()
                        .addRules(
                                SignatureCoolDownRule.newBuilder()
                                        .setName(TEST_RULE_NAME)
                                        .setCoolDownMillis(TEST_COOL_DOWN_MILLIS)
                                        .setConditionType(TEST_CONDITION_TYPE)
                                        .build())
                        .build();
        String protoBase64 = Base64.encodeToString(proto.toByteArray(), Base64.NO_WRAP);

        DeviceConfig.Properties newProperties =
                new DeviceConfig.Properties.Builder(AnomalyDetectorProperties.NAMESPACE)
                        .setString(
                                RateLimiterConfigImpl.KEY_SIGNATURE_COOL_DOWN_CONFIG_PROTO,
                                protoBase64)
                        .build();

        mListenerCaptor.getValue().onPropertiesChanged(newProperties);

        // Verify the specific rule is now active.
        assertThat(mRateLimiterConfig.getSignatureCoolDownMillis(TEST_CONDITION_TYPE, null))
                .isEqualTo(TEST_COOL_DOWN_MILLIS);
        // Verify the default is still used for non-matching types.
        assertThat(mRateLimiterConfig.getSignatureCoolDownMillis(OTHER_CONDITION_TYPE, null))
                .isEqualTo(0);
    }

    @Test
    public void onPropertiesChanged_invalidRuleIsIgnored() {
        // Initial state is default.
        assertThat(mRateLimiterConfig.getSignatureCoolDownMillis(TEST_CONDITION_TYPE, null))
                .isEqualTo(0);
        assertThat(mRateLimiterConfig.getSignatureCoolDownMillis(ANY_CONDITION_TYPE, null))
                .isEqualTo(0);

        // Create a new proto config with one valid and one invalid rule.
        SignatureCoolDownConfigProto proto =
                SignatureCoolDownConfigProto.newBuilder()
                        // Valid rule
                        .addRules(
                                SignatureCoolDownRule.newBuilder()
                                        .setName(TEST_RULE_NAME)
                                        .setCoolDownMillis(TEST_COOL_DOWN_MILLIS)
                                        .setConditionType(TEST_CONDITION_TYPE)
                                        .build())
                        // Invalid rule (no condition type or matchers)
                        .addRules(
                                SignatureCoolDownRule.newBuilder()
                                        .setName(INVALID_RULE)
                                        .setCoolDownMillis(TEST_COOL_DOWN_MILLIS)
                                        .build())
                        .build();
        String protoBase64 = Base64.encodeToString(proto.toByteArray(), Base64.NO_WRAP);

        DeviceConfig.Properties newProperties =
                new DeviceConfig.Properties.Builder(AnomalyDetectorProperties.NAMESPACE)
                        .setString(
                                RateLimiterConfigImpl.KEY_SIGNATURE_COOL_DOWN_CONFIG_PROTO,
                                protoBase64)
                        .build();

        mListenerCaptor.getValue().onPropertiesChanged(newProperties);

        // Verify the specific (valid) rule is now active.
        assertThat(mRateLimiterConfig.getSignatureCoolDownMillis(TEST_CONDITION_TYPE, null))
                .isEqualTo(TEST_COOL_DOWN_MILLIS);
        // Verify the invalid rule was ignored and does not act as a default.
        assertThat(mRateLimiterConfig.getSignatureCoolDownMillis(ANY_CONDITION_TYPE, null))
                .isEqualTo(0);
        // Verify the default is still used for other non-matching types.
        assertThat(mRateLimiterConfig.getSignatureCoolDownMillis(OTHER_CONDITION_TYPE, null))
                .isEqualTo(0);
    }
}
