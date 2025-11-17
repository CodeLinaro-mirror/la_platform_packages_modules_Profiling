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
import android.os.profiling.anomaly.Rule;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.os.profiling.anomaly.attribute.SummaryAttribute;
import com.android.os.profiling.anomaly.attribute.UidAttribute;
import com.android.os.profiling.anomaly.collector.SignalCollector;
import com.android.os.profiling.anomaly.collector.SubscriptionId;
import com.android.os.profiling.anomaly.collector.binder.BinderSpamConfig;
import com.android.os.profiling.anomaly.collector.binder.BinderSpamData;
import com.android.os.profiling.anomaly.core.AnomalyDetector;
import com.android.os.profiling.anomaly.core.AnomalyReport;
import com.android.os.profiling.anomaly.core.SignalCollectorRegistry;
import com.android.os.profiling.anomaly.core.SignalTypeId;
import com.android.os.profiling.anomaly.internal.AnomalyReportImpl;

import java.util.Set;

/**
 * Anomaly detector for binder spamming.
 *
 * @hide
 */
public final class BinderSpamAnomalyDetector extends AnomalyDetector {
    private static final String TAG = "BinderSpamAnomalyDetector";

    private static final long MILLIS_PER_SECOND = 1000L;

    private final SignalCollectorRegistry mRegistry;
    private final Object mLock = new Object();

    @GuardedBy("mLock")
    private Rule mRule;

    @GuardedBy("mLock")
    private SubscriptionId mSubscriptionId;

    @GuardedBy("mLock")
    private SignalCollector<BinderSpamConfig, BinderSpamData> mCollector;

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
                    return Set.of(new SignalTypeId(BinderSpamConfig.class, BinderSpamData.class));
                }

                @Override
                public String getConditionType() {
                    return Rule.CONDITION_TYPE_BINDER_SPAM;
                }
            };

    /** {@inheritDoc} */
    @Override
    public void setRule(Rule rule) {
        synchronized (mLock) {
            if (mSubscriptionId != null && mCollector != null) {
                mCollector.unsubscribe(mSubscriptionId);
                mSubscriptionId = null;
                mCollector = null;
            }

            mRule = rule;

            if (mRule == null) {
                return;
            }

            Bundle condition = mRule.getRuleCondition();

            mCollector = mRegistry.getSignalCollector(BinderSpamConfig.class, BinderSpamData.class);

            if (mCollector != null) {
                String interfaceName =
                        condition.getString(Rule.BUNDLE_KEY_CONDITION_BINDER_SPAM_INTERFACE_NAME);
                String methodName =
                        condition.getString(Rule.BUNDLE_KEY_CONDITION_BINDER_SPAM_METHOD_NAME);
                BinderSpamConfig config =
                        new BinderSpamConfig.Builder()
                                .setInterfaceName(interfaceName)
                                .setMethodName(methodName)
                                .build();

                mSubscriptionId =
                        mCollector.subscribe(
                                config,
                                new OutcomeReceiver<>() {
                                    @Override
                                    public void onResult(BinderSpamData data) {
                                        onDataAvailable(data);
                                    }

                                    @Override
                                    public void onError(Throwable error) {
                                        Slog.e(TAG, "Error receiving BinderSpamData", error);
                                    }
                                });
            } else {
                Slog.w(TAG, "BinderSpam collector not available.");
            }
        }
    }

    /**
     * Called by the signal collector when new binder spam data is available.
     *
     * @param binderData The data collected for binder spam.
     */
    private void onDataAvailable(BinderSpamData binderData) {
        synchronized (mLock) {
            if (mRule == null) {
                return;
            }

            Bundle condition = mRule.getRuleCondition();
            long callCount = binderData.getCallCount();
            long timespanMillis = binderData.getTimespanMillis();
            long threshold = condition.getInt(Rule.BUNDLE_KEY_CONDITION_BINDER_SPAM_CALL_LIMIT);
            long intervalMillis =
                    condition.getLong(
                            Rule.BUNDLE_KEY_CONDITION_BINDER_SPAM_BINDER_CALL_INTERVAL_MILLIS);

            // Timespan must not be less than 1000ms to calculate a rate, because short timespan may
            // cause an exaggerated call-rate, e,g, 2 calls over 10ms makes call-rate to be 200/s.
            if (timespanMillis < 1000) {
                Slog.w(TAG, "Timespan is too short, cannot calculate rate. Ignoring data.");
                return;
            }

            boolean isRateExceeded = callCount * intervalMillis > threshold * timespanMillis;

            if (isRateExceeded
                    && condition
                            .getString(Rule.BUNDLE_KEY_CONDITION_BINDER_SPAM_INTERFACE_NAME)
                            .equals(binderData.getInterfaceName())
                    && condition
                            .getString(Rule.BUNDLE_KEY_CONDITION_BINDER_SPAM_METHOD_NAME)
                            .equals(binderData.getMethodName())) {
                Slog.d(TAG, "Binder spam condition met. Creating a report.");

                // For logging purposes, calculate the actual rate.
                double actualCallsPerSecond =
                        (double) callCount * MILLIS_PER_SECOND / timespanMillis;

                String summary =
                        String.format(
                                "UID %d made %d calls to %s#%s in %dms (Rate: %.2f calls/sec, "
                                        + "Threshold: %d calls/sec)",
                                binderData.getCallingUid(),
                                callCount,
                                binderData.getInterfaceName(),
                                binderData.getMethodName(),
                                timespanMillis,
                                actualCallsPerSecond,
                                threshold);

                AnomalyReport report =
                        new AnomalyReportImpl.Builder(mRule)
                                .addAttribute(new UidAttribute(binderData.getCallingUid()))
                                .addAttribute(new SummaryAttribute(summary))
                                .build();

                reportAnomaly(report);
            }
        }
    }
}
