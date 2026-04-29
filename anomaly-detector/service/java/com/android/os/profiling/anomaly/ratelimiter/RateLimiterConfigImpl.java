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

import android.provider.DeviceConfig;
import android.util.Base64;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.os.profiling.anomaly.config.AnomalyDetectorProperties;
import com.android.os.profiling.anomaly.ratelimiter.proto.SignatureCoolDownConfigProto;
import com.android.os.profiling.anomaly.ratelimiter.proto.SignatureCoolDownRule;
import com.android.os.profiling.anomaly.util.LogUtil;

import com.google.protobuf.InvalidProtocolBufferException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The default, production implementation of the {@link RateLimiterConfig} interface. This
 * implementation retrieves rate limiter settings from {@link DeviceConfig}, allowing for remote
 * configuration of thresholds and cool-down periods.
 *
 * @hide
 */
public final class RateLimiterConfigImpl implements RateLimiterConfig {
    private static final LogUtil sLog = new LogUtil("RateLimiterConfigImpl");

    private static final String KEY_PREFIX = "anomaly_ratelimiter.";

    @VisibleForTesting
    static final String KEY_DEVICE_FREQUENCY_WINDOW_MILLIS =
            KEY_PREFIX + "device_frequency_window_millis";

    @VisibleForTesting
    static final String KEY_DEVICE_FREQUENCY_MAX_COUNT = KEY_PREFIX + "device_frequency_max_count";

    @VisibleForTesting
    static final String KEY_UID_COOL_DOWN_MILLIS = KEY_PREFIX + "uid_cool_down_millis";

    @VisibleForTesting
    static final String KEY_SIGNATURE_COOL_DOWN_CONFIG_PROTO =
            KEY_PREFIX + "signature_cool_down_config_proto";

    @VisibleForTesting
    static final String KEY_PERSISTENCE_DELAY_MILLIS = KEY_PREFIX + "persistence_delay_millis";

    @VisibleForTesting
    static final String KEY_MAX_COOLDOWN_FOR_EVICTION_MILLIS =
            KEY_PREFIX + "max_cooldown_for_eviction_millis";

    @VisibleForTesting
    static final long DEFAULT_PERSISTENCE_DELAY_MILLIS = TimeUnit.SECONDS.toMillis(10);

    @VisibleForTesting
    static final long DEFAULT_DEVICE_FREQUENCY_WINDOW_MILLIS = TimeUnit.HOURS.toMillis(1);

    @VisibleForTesting static final int DEFAULT_DEVICE_FREQUENCY_MAX_COUNT = 4;
    @VisibleForTesting static final long DEFAULT_UID_COOL_DOWN_MILLIS = TimeUnit.HOURS.toMillis(12);

    @VisibleForTesting
    static final long DEFAULT_MAX_COOLDOWN_FOR_EVICTION_MILLIS = TimeUnit.DAYS.toMillis(7);

    private final Object mLock = new Object();

    @GuardedBy("mLock")
    private List<SignatureCoolDownRule> mSignatureCoolDownRules;

    private volatile long mDeviceFrequencyWindowMillis = DEFAULT_DEVICE_FREQUENCY_WINDOW_MILLIS;
    private volatile int mDeviceFrequencyMaxCount = DEFAULT_DEVICE_FREQUENCY_MAX_COUNT;
    private volatile long mUidCoolDownMillis = DEFAULT_UID_COOL_DOWN_MILLIS;
    private volatile long mPersistenceDelayMillis = DEFAULT_PERSISTENCE_DELAY_MILLIS;
    private volatile long mMaxCoolDownForEvictionMillis = DEFAULT_MAX_COOLDOWN_FOR_EVICTION_MILLIS;

    public RateLimiterConfigImpl(AnomalyDetectorProperties propertiesProvider) {
        updateAllProperties(propertiesProvider.getProperties());
        propertiesProvider.addOnPropertiesChangedListener(this::updateAllProperties);
    }

    private void updateAllProperties(DeviceConfig.Properties properties) {
        for (String key : properties.getKeyset()) {
            updateProperty(key, properties);
        }
    }

    private void updateProperty(String key, DeviceConfig.Properties properties) {
        switch (key) {
            case KEY_DEVICE_FREQUENCY_WINDOW_MILLIS ->
                    mDeviceFrequencyWindowMillis =
                            properties.getLong(
                                    KEY_DEVICE_FREQUENCY_WINDOW_MILLIS,
                                    DEFAULT_DEVICE_FREQUENCY_WINDOW_MILLIS);
            case KEY_DEVICE_FREQUENCY_MAX_COUNT ->
                    mDeviceFrequencyMaxCount =
                            properties.getInt(
                                    KEY_DEVICE_FREQUENCY_MAX_COUNT,
                                    DEFAULT_DEVICE_FREQUENCY_MAX_COUNT);
            case KEY_UID_COOL_DOWN_MILLIS ->
                    mUidCoolDownMillis =
                            properties.getLong(
                                    KEY_UID_COOL_DOWN_MILLIS, DEFAULT_UID_COOL_DOWN_MILLIS);
            case KEY_SIGNATURE_COOL_DOWN_CONFIG_PROTO ->
                    updateSignatureCoolDownRules(
                            properties.getString(KEY_SIGNATURE_COOL_DOWN_CONFIG_PROTO, null));
            case KEY_PERSISTENCE_DELAY_MILLIS ->
                    mPersistenceDelayMillis =
                            properties.getLong(
                                    KEY_PERSISTENCE_DELAY_MILLIS, DEFAULT_PERSISTENCE_DELAY_MILLIS);
            case KEY_MAX_COOLDOWN_FOR_EVICTION_MILLIS ->
                    mMaxCoolDownForEvictionMillis =
                            properties.getLong(
                                    KEY_MAX_COOLDOWN_FOR_EVICTION_MILLIS,
                                    DEFAULT_MAX_COOLDOWN_FOR_EVICTION_MILLIS);
        }
    }

    private void updateSignatureCoolDownRules(String protoAsBase64String) {
        synchronized (mLock) {
            if (protoAsBase64String == null || protoAsBase64String.isEmpty()) {
                mSignatureCoolDownRules = null;
                return;
            }
            try {
                byte[] protoBytes = Base64.decode(protoAsBase64String, Base64.NO_WRAP);
                SignatureCoolDownConfigProto configProto =
                        SignatureCoolDownConfigProto.parseFrom(protoBytes);
                List<SignatureCoolDownRule> validRules = new ArrayList<>();
                for (SignatureCoolDownRule rule : configProto.getRulesList()) {
                    if (rule.getConditionType().isEmpty()
                            && rule.getSignatureMatchersMap().isEmpty()) {
                        sLog.w(
                                "Ignoring invalid signature cool down rule with no condition_type"
                                        + " and no matchers: "
                                        + rule.getName());
                        continue;
                    }
                    validRules.add(rule);
                }
                // Sort by priority, highest first
                validRules.sort(
                        Comparator.comparingInt(SignatureCoolDownRule::getPriority).reversed());
                mSignatureCoolDownRules = validRules;
            } catch (IllegalArgumentException | InvalidProtocolBufferException e) {
                sLog.e("Failed to parse signature cool down config proto", e);
                mSignatureCoolDownRules = null;
            }
        }
    }

    @Override
    public long getDeviceFrequencyWindowMillis() {
        return mDeviceFrequencyWindowMillis;
    }

    @Override
    public int getDeviceFrequencyMaxCount() {
        return mDeviceFrequencyMaxCount;
    }

    @Override
    public long getUidCoolDownMillis() {
        return mUidCoolDownMillis;
    }

    @Override
    public long getPersistenceDelayMillis() {
        return mPersistenceDelayMillis;
    }

    @Override
    public long getMaxCoolDownForEvictionMillis() {
        long maxCoolDown = Math.max(mMaxCoolDownForEvictionMillis, mUidCoolDownMillis);
        synchronized (mLock) {
            if (mSignatureCoolDownRules != null) {
                for (SignatureCoolDownRule rule : mSignatureCoolDownRules) {
                    if (rule.getCoolDownMillis() > maxCoolDown) {
                        maxCoolDown = rule.getCoolDownMillis();
                    }
                }
            }
        }
        return maxCoolDown;
    }

    @Override
    public long getSignatureCoolDownMillis(String conditionType, Map<String, String> signature) {
        synchronized (mLock) {
            if (mSignatureCoolDownRules != null) {
                for (SignatureCoolDownRule rule : mSignatureCoolDownRules) {
                    if (ruleMatches(rule, conditionType, signature)) {
                        return rule.getCoolDownMillis();
                    }
                }
            }
        }
        return 0;
    }

    private boolean ruleMatches(
            SignatureCoolDownRule rule, String conditionType, Map<String, String> signature) {
        // Check condition type
        String ruleConditionType = rule.getConditionType();
        if (!ruleConditionType.isEmpty() && !ruleConditionType.equals(conditionType)) {
            return false;
        }

        // Check signature matchers
        Map<String, String> signatureMatchers = rule.getSignatureMatchersMap();
        if (!signatureMatchers.isEmpty()) {
            if (signature == null) {
                return false;
            }
            for (Map.Entry<String, String> entry : signatureMatchers.entrySet()) {
                if (!entry.getValue().equals(signature.get(entry.getKey()))) {
                    return false;
                }
            }
        }

        return true;
    }
}
