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

package com.android.os.profiling.anomaly.detector;

import android.os.Bundle;
import android.os.OutcomeReceiver;
import android.os.profiling.anomaly.RuleInternal;
import android.util.ArrayMap;
import android.util.ArraySet;

import com.android.internal.annotations.GuardedBy;
import com.android.os.profiling.anomaly.attribute.BinderSpamDetailsAttribute;
import com.android.os.profiling.anomaly.attribute.SummaryAttribute;
import com.android.os.profiling.anomaly.attribute.UidAttribute;
import com.android.os.profiling.anomaly.collector.SignalCollector;
import com.android.os.profiling.anomaly.collector.SubscriptionId;
import com.android.os.profiling.anomaly.collector.binder.BinderSpamConfig;
import com.android.os.profiling.anomaly.collector.binder.BinderSpamConfigList;
import com.android.os.profiling.anomaly.collector.binder.BinderSpamData;
import com.android.os.profiling.anomaly.core.AnomalyDetector;
import com.android.os.profiling.anomaly.core.AnomalyReport;
import com.android.os.profiling.anomaly.core.SignalCollectorRegistry;
import com.android.os.profiling.anomaly.core.SignalTypeId;
import com.android.os.profiling.anomaly.internal.AnomalyReportImpl;
import com.android.os.profiling.anomaly.util.LogUtil;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Anomaly detector for binder spamming.
 *
 * @hide
 */
public final class BinderSpamAnomalyDetector extends AnomalyDetector {
    private static final String TAG = "BinderSpamAnomalyDetector";
    private static final LogUtil sLog = new LogUtil(TAG);

    private final SignalCollectorRegistry mRegistry;
    private final Object mLock = new Object();

    @GuardedBy("mLock")
    private Set<RuleInternal> mRules = new ArraySet<>();

    @GuardedBy("mLock")
    private SubscriptionId mSubscriptionId;

    @GuardedBy("mLock")
    private SignalCollector<BinderSpamConfigList, BinderSpamData> mCollector;

    /**
     * Constructs a new BinderSpamAnomalyDetector.
     *
     * @param registry The registry used to look up the binder spam signal collector.
     */
    public BinderSpamAnomalyDetector(SignalCollectorRegistry registry) {
        mRegistry = registry;
    }

    public static final AnomalyDetector.AnomalyDetectorFactory FACTORY =
            new AnomalyDetector.AnomalyDetectorFactory() {
                @Override
                public AnomalyDetector create(SignalCollectorRegistry registry) {
                    return new BinderSpamAnomalyDetector(registry);
                }

                @Override
                public Set<SignalTypeId> getRequiredSignalCollectorTypes() {
                    return Set.of(
                            new SignalTypeId(BinderSpamConfigList.class, BinderSpamData.class));
                }

                @Override
                public String getConditionType() {
                    return RuleInternal.CONDITION_TYPE_BINDER_SPAM;
                }
            };

    /** {@inheritDoc} */
    @Override
    public void setRules(Set<RuleInternal> rules) {
        synchronized (mLock) {
            if (mSubscriptionId != null && mCollector != null) {
                mCollector.unsubscribe(mSubscriptionId);
                mSubscriptionId = null;
                mCollector = null;
            }

            mRules = rules;

            if (mRules == null || mRules.isEmpty()) {
                return;
            }

            mCollector =
                    mRegistry.getSignalCollector(BinderSpamConfigList.class, BinderSpamData.class);

            if (mCollector != null) {
                List<BinderSpamConfig> configs = createConfigsFromRules(mRules);
                if (configs.isEmpty()) {
                    return;
                }
                BinderSpamConfigList configList = new BinderSpamConfigList(configs);

                mSubscriptionId =
                        mCollector.subscribe(
                                configList,
                                new OutcomeReceiver<>() {
                                    @Override
                                    public void onResult(BinderSpamData data) {
                                        onDataAvailable(data);
                                    }

                                    @Override
                                    public void onError(Throwable error) {
                                        sLog.e("Error receiving BinderSpamData", error);
                                    }
                                });
            } else {
                sLog.w("BinderSpam collector not available.");
            }
        }
    }

    /**
     * Creates a list of {@link BinderSpamConfig} from a set of rules.
     *
     * @param rules The set of rules to process.
     * @return A list of {@link BinderSpamConfig} objects.
     */
    private List<BinderSpamConfig> createConfigsFromRules(Set<RuleInternal> rules) {
        List<BinderSpamConfig> configs = new ArrayList<>();
        for (RuleInternal rule : rules) {
            Bundle condition = rule.getRuleCondition();
            String interfaceName =
                    condition.getString(
                            RuleInternal.BUNDLE_KEY_CONDITION_BINDER_SPAM_INTERFACE_NAME);
            String methodName =
                    condition.getString(RuleInternal.BUNDLE_KEY_CONDITION_BINDER_SPAM_METHOD_NAME);
            int threshold =
                    condition.getInt(RuleInternal.BUNDLE_KEY_CONDITION_BINDER_SPAM_CALL_LIMIT);
            long windowMillis =
                    condition.getLong(
                            RuleInternal
                                    .BUNDLE_KEY_CONDITION_BINDER_SPAM_BINDER_CALL_INTERVAL_MILLIS);

            BinderSpamConfig config =
                    new BinderSpamConfig.Builder()
                            .setInterfaceName(interfaceName)
                            .setMethodName(methodName)
                            .setCallCountThreshold(threshold)
                            .setWindowSize(Duration.ofMillis(windowMillis))
                            .build();
            configs.add(config);
        }
        return configs;
    }

    /**
     * Called by the signal collector when new binder spam data is available.
     *
     * @param binderData The data collected for binder spam.
     */
    private void onDataAvailable(BinderSpamData binderData) {
        synchronized (mLock) {
            if (mRules == null || mRules.isEmpty()) {
                return;
            }

            for (RuleInternal rule : mRules) {
                checkRule(rule, binderData);
            }
        }
    }

    /**
     * Checks if the collected data violates the given rule.
     *
     * <p>This method verifies if the data corresponds to the rule's target interface and method. If
     * it does, it calculates the call rate from the data and compares it against the rule's
     * threshold. If the rate exceeds the threshold, an anomaly is reported.
     *
     * @param rule The rule to evaluate.
     * @param binderData The collected binder spam data.
     */
    @GuardedBy("mLock")
    private void checkRule(RuleInternal rule, BinderSpamData binderData) {
        Bundle condition = rule.getRuleCondition();
        String interfaceName =
                condition.getString(RuleInternal.BUNDLE_KEY_CONDITION_BINDER_SPAM_INTERFACE_NAME);
        String methodName =
                condition.getString(RuleInternal.BUNDLE_KEY_CONDITION_BINDER_SPAM_METHOD_NAME);

        // Check if this rule applies to the incoming data
        if (!binderData.getInterfaceName().equals(interfaceName)
                || !binderData.getMethodName().equals(methodName)) {
            return;
        }

        long callCount = binderData.getCallCount();
        Duration timespan = binderData.getTimespan();
        long threshold = condition.getInt(RuleInternal.BUNDLE_KEY_CONDITION_BINDER_SPAM_CALL_LIMIT);
        long intervalMillis =
                condition.getLong(
                        RuleInternal.BUNDLE_KEY_CONDITION_BINDER_SPAM_BINDER_CALL_INTERVAL_MILLIS);

        // Prevent low-confidence reports. If the observed call count doesn't meet the
        // rule's threshold (regardless of timespan), we shouldn't consider it an anomaly.
        if (callCount <= threshold) {
            return;
        }

        // Cross-multiplying to avoid floating point:
        // callCount / timespan > threshold / interval
        boolean isRateExceeded = callCount * intervalMillis > threshold * timespan.toMillis();
        if (isRateExceeded) {
            // For logging purposes, calculate the actual rate.
            double actualCallsPerSecond = (double) callCount / timespan.toSeconds();

            String summary =
                    String.format(
                            "UID %d made %d calls to %s#%s in %ds (Rate: %.2f calls/sec, "
                                    + "Threshold: %d calls/%dms)",
                            binderData.getCallingUid(),
                            callCount,
                            binderData.getInterfaceName(),
                            binderData.getMethodName(),
                            timespan.toSeconds(),
                            actualCallsPerSecond,
                            threshold,
                            intervalMillis);

            AnomalyReport report =
                    new AnomalyReportImpl.Builder(rule)
                            .addAttribute(new UidAttribute(binderData.getCallingUid()))
                            .addAttribute(new SummaryAttribute(summary))
                            .addAttribute(
                                    new BinderSpamDetailsAttribute(
                                            binderData.getInterfaceName(),
                                            binderData.getMethodName(),
                                            binderData.getCallCount(),
                                            timespan,
                                            (int) threshold,
                                            Duration.ofMillis(intervalMillis)))
                            .build();

            reportAnomaly(report);
        }
    }

    /** {@inheritDoc} */
    @Override
    public void onSignalCollectorUnregistered(SignalTypeId signalTypeId) {
        synchronized (mLock) {
            sLog.i("Signal collector unregistered: " + signalTypeId);
            // If the unregistered collector is the one we are using, clear the rule.
            if (mCollector != null
                    && FACTORY.getRequiredSignalCollectorTypes().contains(signalTypeId)) {
                setRules(Collections.emptySet());
            }
        }
    }
}
