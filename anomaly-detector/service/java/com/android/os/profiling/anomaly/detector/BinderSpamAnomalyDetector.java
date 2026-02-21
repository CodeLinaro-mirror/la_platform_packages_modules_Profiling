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

import static android.os.ProfilingManager.KEY_SAMPLE_BINDER_ONLY;
import static android.os.ProfilingManager.PROFILING_TYPE_STACK_SAMPLING;

import android.annotation.Nullable;
import android.os.Bundle;
import android.os.OutcomeReceiver;
import android.os.SystemClock;
import android.os.profiling.anomaly.RuleInternal;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.SparseIntArray;
import android.util.SparseLongArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.os.profiling.anomaly.attribute.BinderSpamDetailsAttribute;
import com.android.os.profiling.anomaly.attribute.ProfilingParamsAttribute;
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
import java.util.Collections;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * Anomaly detector for binder spamming.
 *
 * @hide
 */
public final class BinderSpamAnomalyDetector extends AnomalyDetector {
    private static final String TAG = "BinderSpamAnomalyDetector";
    private static final LogUtil sLog = new LogUtil(TAG);

    private final LongSupplier mElapsedRealtime;
    private final SignalCollectorRegistry mRegistry;
    private final Object mLock = new Object();

    private record BinderSpamDataKey(String interfaceName, String methodName) {}

    @GuardedBy("mLock")
    private final ArrayMap<BinderSpamDataKey, Set<RuleEvaluator>> mRuleEvaluatorsMap =
            new ArrayMap<>();

    @GuardedBy("mLock")
    private SubscriptionId mSubscriptionId;

    @GuardedBy("mLock")
    private SignalCollector<BinderSpamConfigList, BinderSpamData> mCollector;

    // TODO: b/483173066 - Make this configurable.
    private static final int MAX_SESSION_DURATION_MS = 20000;

    /**
     * Constructs a new BinderSpamAnomalyDetector.
     *
     * @param registry The registry used to look up the binder spam signal collector.
     */
    public BinderSpamAnomalyDetector(SignalCollectorRegistry registry) {
        this(registry, SystemClock::elapsedRealtime);
    }

    @VisibleForTesting
    BinderSpamAnomalyDetector(SignalCollectorRegistry registry, LongSupplier elapsedRealtime) {
        mRegistry = registry;
        mElapsedRealtime = elapsedRealtime;
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

    @Override
    public void setRules(Set<RuleInternal> rules) {
        synchronized (mLock) {
            if (mSubscriptionId != null && mCollector != null) {
                mCollector.unsubscribe(mSubscriptionId);
                mSubscriptionId = null;
                mCollector = null;
            }

            mRuleEvaluatorsMap.clear();

            if (rules == null || rules.isEmpty()) {
                return;
            }

            mCollector =
                    mRegistry.getSignalCollector(BinderSpamConfigList.class, BinderSpamData.class);

            if (mCollector == null) {
                sLog.w("BinderSpam collector not available.");
                return;
            }

            setupRuleEvaluators(rules);
            if (mRuleEvaluatorsMap.isEmpty()) {
                return;
            }
            BinderSpamConfigList configList =
                    new BinderSpamConfigList(
                            mRuleEvaluatorsMap.keySet().stream()
                                    .map(
                                            binderSpamDataKey ->
                                                    new BinderSpamConfig.Builder()
                                                            .setInterfaceName(
                                                                    binderSpamDataKey.interfaceName)
                                                            .setMethodName(
                                                                    binderSpamDataKey.methodName)
                                                            .build())
                                    .toList());

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
        }
    }

    /**
     * Set up the rule evaluators from a set of rules.
     *
     * @param rules The set of rules to process.
     */
    private void setupRuleEvaluators(Set<RuleInternal> rules) {
        for (RuleInternal rule : rules) {
            Bundle condition = rule.getRuleCondition();
            String interfaceName =
                    condition.getString(
                            RuleInternal.BUNDLE_KEY_CONDITION_BINDER_SPAM_INTERFACE_NAME);
            String methodName =
                    condition.getString(RuleInternal.BUNDLE_KEY_CONDITION_BINDER_SPAM_METHOD_NAME);

            if (interfaceName == null || methodName == null) {
                continue;
            }
            int threshold =
                    condition.getInt(RuleInternal.BUNDLE_KEY_CONDITION_BINDER_SPAM_CALL_LIMIT);
            long intervalMillis =
                    condition.getLong(
                            RuleInternal
                                    .BUNDLE_KEY_CONDITION_BINDER_SPAM_BINDER_CALL_INTERVAL_MILLIS);
            RuleEvaluator evaluator =
                    new RuleEvaluator(
                            rule, Duration.ofMillis(intervalMillis), threshold, mElapsedRealtime);
            mRuleEvaluatorsMap
                    .computeIfAbsent(
                            new BinderSpamDataKey(interfaceName, methodName), k -> new ArraySet<>())
                    .add(evaluator);
        }
    }

    /**
     * This class is an evaluator for incoming {@link BinderSpamData} against a rule assigned to the
     * evaluator at initialization. Each evaluator should only accept data that with a {@link
     * BinderSpamDataKey} matching the targeted aidl method by the associated rule.
     *
     * <p>Since there can be multiple rules for same aidl method, there will be multiple evaluators
     * for the same {@link BinderSpamDataKey}.
     */
    @VisibleForTesting
    static final class RuleEvaluator {
        private final LongSupplier mElapsedRealTime;
        private final RuleInternal mRule;
        private final Duration mWindowSize;
        private final int mCallCountThreshold;
        private final SparseLongArray mCurrentWindowEndMillis;
        private final SparseIntArray mCallCount;

        RuleEvaluator(
                RuleInternal rule,
                Duration windowSize,
                int callCountThreshold,
                LongSupplier elapsedRealTime) {
            mRule = rule;
            mWindowSize = windowSize;
            mCallCountThreshold = callCountThreshold;
            mCurrentWindowEndMillis = new SparseLongArray();
            mCallCount = new SparseIntArray();
            mElapsedRealTime = elapsedRealTime;
        }

        /**
         * Evaluate the collected data against rule.
         *
         * <p>This method accumulates collected data within a time window defined by the rule. If
         * the current data makes total count above the threshold, or the current window expires,
         * the rate will be calculated and compared with rule. An anomaly will be reported and
         * aggregation window will reset if the rate exceeds threshold.
         *
         * @param data The collected binder spam data.
         * @return An {@link AnomalyReport} if detected; null otherwise.
         */
        @Nullable
        AnomalyReport evaluate(BinderSpamData data) {
            final long currentTimeMillis = mElapsedRealTime.getAsLong();
            final int uid = data.getCallingUid();
            if (mCallCount.get(uid) == 0) {
                // When a new window starts for a UID, its end time is set relative to the current
                // time and the timespan of the data that triggered its creation. This ensures the
                // window aligns with the activity period.
                mCurrentWindowEndMillis.put(
                        uid, currentTimeMillis + mWindowSize.minus(data.getTimespan()).toMillis());
            }
            final int callCount = mCallCount.get(uid) + data.getCallCount();

            if (callCount <= mCallCountThreshold
                    && currentTimeMillis < mCurrentWindowEndMillis.get(uid)) {
                // Keep counting the calls.
                mCallCount.put(uid, callCount);
                return null;
            }

            // At this point, there may be three situations:
            //  1. The total count within current window has exceeded the threshold;
            //  2. The current window has expired;
            //  3. Both 1 and 2.
            // so let's compare the rate.
            // The timespan for rate calculation should be from the beginning of the window to the
            // current time.
            final Duration timespan =
                    Duration.ofMillis(
                            currentTimeMillis
                                    - mCurrentWindowEndMillis.get(uid)
                                    + mWindowSize.toMillis());
            // Clear the current window.
            mCurrentWindowEndMillis.delete(uid);
            mCallCount.delete(uid);

            // Cross-multiplying to avoid floating point:
            // callCount / timespan > threshold / interval
            boolean isRateExceeded =
                    callCount * mWindowSize.toMillis() > mCallCountThreshold * timespan.toMillis();
            if (!isRateExceeded) {
                return null;
            }

            // For logging purposes, calculate the actual rate.
            double actualCallsPerSecond = (double) callCount / timespan.toSeconds();
            String summary =
                    String.format(
                            "UID %d made %d calls to %s#%s in %ds (Rate: %.2f calls/sec, "
                                    + "Threshold: %d calls/%ds)",
                            data.getCallingUid(),
                            callCount,
                            data.getInterfaceName(),
                            data.getMethodName(),
                            timespan.toSeconds(),
                            actualCallsPerSecond,
                            mCallCountThreshold,
                            mWindowSize.toSeconds());

            Bundle sessionParams = new Bundle();
            sessionParams.putBoolean(KEY_SAMPLE_BINDER_ONLY, true);

            return new AnomalyReportImpl.Builder(mRule)
                    .addAttribute(new UidAttribute(data.getCallingUid()))
                    .addAttribute(new SummaryAttribute(summary))
                    .addAttribute(
                            new BinderSpamDetailsAttribute(
                                    data.getInterfaceName(),
                                    data.getMethodName(),
                                    callCount,
                                    timespan,
                                    mCallCountThreshold,
                                    mWindowSize))
                    .addAttribute(
                            new ProfilingParamsAttribute(
                                    MAX_SESSION_DURATION_MS,
                                    PROFILING_TYPE_STACK_SAMPLING,
                                    sessionParams))
                    .build();
        }
    }

    /**
     * Called by the signal collector when new binder spam data is available.
     *
     * @param binderData The data collected for binder spam.
     */
    private void onDataAvailable(BinderSpamData binderData) {
        BinderSpamDataKey key =
                new BinderSpamDataKey(binderData.getInterfaceName(), binderData.getMethodName());
        synchronized (mLock) {
            for (RuleEvaluator rule :
                    mRuleEvaluatorsMap.getOrDefault(key, Collections.emptySet())) {
                AnomalyReport report = rule.evaluate(binderData);
                if (report != null) {
                    reportAnomaly(report);
                }
            }
        }
    }

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
