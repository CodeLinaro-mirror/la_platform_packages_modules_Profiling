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

import static android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import android.os.Bundle;
import android.os.OutcomeReceiver;
import android.profiling.utils.PerfettoMetadata;
import android.os.profiling.anomaly.RuleInternal;
import android.util.StatsEvent;
import android.util.StatsEventTestUtils;
import android.util.StatsLog;

import androidx.test.runner.AndroidJUnit4;

import com.android.modules.utils.testing.ExtendedMockitoRule;
import com.android.os.AtomsProto;
import com.android.os.profiling.anomaly.attribute.AnomalyDetailsAttribute;
import com.android.os.profiling.anomaly.attribute.ProfilingParamsAttribute;
import com.android.os.profiling.anomaly.attribute.RateLimitSignatureAttribute;
import com.android.os.profiling.anomaly.attribute.SummaryAttribute;
import com.android.os.profiling.anomaly.attribute.UidAttribute;
import com.android.os.profiling.anomaly.collector.SignalCollector;
import com.android.os.profiling.anomaly.collector.SubscriptionId;
import com.android.os.profiling.anomaly.collector.binder.BinderSpamConfig;
import com.android.os.profiling.anomaly.collector.binder.BinderSpamConfigList;
import com.android.os.profiling.anomaly.collector.binder.BinderSpamData;
import com.android.os.profiling.anomaly.core.AnomalyDetector;
import com.android.os.profiling.anomaly.core.AnomalyReport;
import com.android.os.profiling.anomaly.core.AnomalyStatsAtomsLog;
import com.android.os.profiling.anomaly.core.SignalCollectorRegistry;
import com.android.os.profiling.anomaly.core.SignalTypeId;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

/** Tests for {@link BinderSpamAnomalyDetector}. */
@RunWith(AndroidJUnit4.class)
public final class BinderSpamAnomalyDetectorTests {

    private static final String TEST_INTERFACE = "com.android.test.ITest";
    private static final String TEST_METHOD = "testMethod";
    private static final String TEST_WRONG_INTERFACE = "com.android.test.WRONG_INTERFACE";
    private static final String TEST_WRONG_METHOD = "wrong_method";
    private static final int TEST_CALLER_UID = 10001;
    private static final int TEST_SERVER_UID = 10002;
    private static final String TEST_RULE_NAME = "test_rule";
    private static final String TEST_RULE_NAME_2 = "test_rule_2";
    private static final int TEST_CALL_LIMIT = 100;
    private static final int TEST_CALL_LIMIT_2 = 200;
    private static final long TEST_CALL_INTERVAL_MILLIS = 1000L;
    private static final long TEST_PROFILING_SESSION_DURATION_MILLIS = 5000L;
    private static final String BINDER_SPAM_INTERFACE_KEY = "binder_spam.interface";
    private static final String BINDER_SPAM_METHOD_KEY = "binder_spam.method";

    // Ensure that the atom ID is consistent with the one in atoms.proto.
    private static final int ANOMALY_STATS_BINDER_SPAM_ATOM_ID = 1286;

    @org.junit.Rule
    public ExtendedMockitoRule mExtendedMockitoRule =
            new ExtendedMockitoRule.Builder(this).mockStatic(StatsLog.class).build();

    @Mock private SignalCollectorRegistry mMockRegistry;
    @Mock private SignalCollector<BinderSpamConfigList, BinderSpamData> mMockCollector;
    @Mock private AnomalyDetector.OnAnomalyDetectedListener mMockListener;
    @Mock private LongSupplier mMockElapsedRealtime;

    @Captor private ArgumentCaptor<AnomalyReport> mReportCaptor;
    @Captor private ArgumentCaptor<BinderSpamConfigList> mConfigListCaptor;
    @Captor private ArgumentCaptor<StatsEvent> mStatsEventCaptor;

    private OutcomeReceiver<BinderSpamData, Throwable> mReceiver;
    private BinderSpamAnomalyDetector mDetector;

    @Before
    public void setUp() {
        when(mMockRegistry.getSignalCollector(BinderSpamConfigList.class, BinderSpamData.class))
                .thenReturn(mMockCollector);
        when(mMockCollector.subscribe(any(), any())).thenReturn(SubscriptionId.generateNew());

        mDetector = new BinderSpamAnomalyDetector(mMockRegistry, mMockElapsedRealtime);
        mDetector.setOnAnomalyDetectedListener(mMockListener);
    }

    @Test
    public void setRules_unsubscribeBeforeSubscribe() {
        RuleInternal rule1 = createRuleWithPerSecondRate(TEST_RULE_NAME, TEST_CALL_LIMIT); // 100
        RuleInternal rule2 =
                createRuleWithPerSecondRate(TEST_RULE_NAME_2, TEST_CALL_LIMIT_2); // 200
        SubscriptionId id1 = SubscriptionId.generateNew();
        when(mMockCollector.subscribe(any(), any())).thenReturn(id1);
        mDetector.setRules(Collections.singleton(rule1));
        verify(mMockCollector).subscribe(any(), any());

        when(mMockCollector.subscribe(any(), any())).thenReturn(SubscriptionId.generateNew());
        mDetector.setRules(Collections.singleton(rule2));
        verify(mMockCollector).unsubscribe(id1);
    }

    @Test
    public void setRules_createsOneConfigForSameTarget() {
        RuleInternal rule1 = createRuleWithPerSecondRate(TEST_RULE_NAME, TEST_CALL_LIMIT); // 100
        RuleInternal rule2 =
                createRuleWithPerSecondRate(TEST_RULE_NAME_2, TEST_CALL_LIMIT_2); // 200

        mDetector.setRules(Set.of(rule1, rule2));

        verify(mMockCollector).subscribe(mConfigListCaptor.capture(), any());
        BinderSpamConfigList configList = mConfigListCaptor.getValue();

        assertThat(configList.getConfigs()).hasSize(1);
        BinderSpamConfig config = configList.getConfigs().getFirst();
        assertThat(config.getInterfaceName()).isEqualTo(TEST_INTERFACE);
        assertThat(config.getMethodName()).isEqualTo(TEST_METHOD);
    }

    @Test
    public void setRules_differentTargetMultipleConfigs() {
        RuleInternal rule1 =
                createRule(TEST_RULE_NAME, TEST_CALL_LIMIT, 1000L, TEST_INTERFACE, TEST_METHOD);
        RuleInternal rule2 =
                createRule(
                        TEST_RULE_NAME_2,
                        TEST_CALL_LIMIT,
                        1000L,
                        TEST_WRONG_INTERFACE,
                        TEST_WRONG_METHOD);

        mDetector.setRules(Set.of(rule1, rule2));

        verify(mMockCollector).subscribe(mConfigListCaptor.capture(), any());
        List<BinderSpamConfig> configs = mConfigListCaptor.getValue().getConfigs();
        assertThat(configs).hasSize(2);

        // Verify that we have two configs, one for each rule.
        // Since order is not guaranteed, we check for existence.
        boolean hasConfig1 =
                configs.stream()
                        .anyMatch(
                                c ->
                                        c.getInterfaceName().equals(TEST_INTERFACE)
                                                && c.getMethodName().equals(TEST_METHOD));
        boolean hasConfig2 =
                configs.stream()
                        .anyMatch(
                                c ->
                                        c.getInterfaceName().equals(TEST_INTERFACE)
                                                && c.getMethodName().equals(TEST_METHOD));
        assertThat(hasConfig1).isTrue();
        assertThat(hasConfig2).isTrue();
    }

    @Test
    public void onDataAvailable_rateExceedsThreshold_anomalyDetected() throws Exception {
        // Limit is 100/sec
        setupRule(createRuleWithPerSecondRate(TEST_RULE_NAME, TEST_CALL_LIMIT));

        // Actual call rate: 101 calls/sec
        int totalCalls = 101;
        Duration timespan = Duration.ofSeconds(1);
        BinderSpamData data = createBinderSpamData(totalCalls, timespan);
        mReceiver.onResult(data);

        verify(mMockListener).onAnomalyDetected(mReportCaptor.capture());
        verifyReport(
                mReportCaptor.getValue(),
                totalCalls,
                timespan,
                TEST_CALL_LIMIT,
                Duration.ofSeconds(1),
                TEST_INTERFACE,
                TEST_METHOD);
        verifyLogAnomalyStatsBinderSpam(data);
    }

    @Test
    public void onDataAvailable_totalCountExceedsThreshold() throws Exception {
        // Limit is 100/minute
        setupRule(createRuleWithPerMinuteRate(TEST_RULE_NAME, TEST_CALL_LIMIT));

        // Actual call rate:
        int totalCalls = 101;
        Duration timespan = Duration.ofSeconds(50);

        // First 51 calls made within 30 seconds.
        mReceiver.onResult(createBinderSpamData(51, Duration.ofSeconds(30)));
        verify(mMockListener, never()).onAnomalyDetected(any());

        // Next 50 calls made within next 20 seconds
        when(mMockElapsedRealtime.getAsLong()).thenReturn(20L * 1000);
        mReceiver.onResult(createBinderSpamData(50, Duration.ofSeconds(20)));

        verify(mMockListener).onAnomalyDetected(mReportCaptor.capture());
        verifyReport(
                mReportCaptor.getValue(),
                totalCalls,
                timespan,
                TEST_CALL_LIMIT,
                Duration.ofMinutes(1),
                TEST_INTERFACE,
                TEST_METHOD);
        verifyLogAnomalyStatsBinderSpam(createBinderSpamData(totalCalls, timespan));
    }

    @Test
    public void onDataAvailable_windowResets() throws Exception {
        // Limit is 100/minute
        setupRule(createRuleWithPerMinuteRate(TEST_RULE_NAME, TEST_CALL_LIMIT));

        // Actual call rate of anomaly:
        int totalCalls = 101;
        Duration timespan = Duration.ofSeconds(50);
        // First 100 calls made within in 60 seconds.
        mReceiver.onResult(createBinderSpamData(100, Duration.ofSeconds(60)));
        verify(mMockListener, never()).onAnomalyDetected(any());

        // Window should reset here. Next 51 calls made within next 30 seconds.
        when(mMockElapsedRealtime.getAsLong()).thenReturn(30L * 1000);
        mReceiver.onResult(createBinderSpamData(51, Duration.ofSeconds(30)));
        verify(mMockListener, never()).onAnomalyDetected(any());

        // Next 50 calls made within next 20 seconds
        when(mMockElapsedRealtime.getAsLong()).thenReturn(50L * 1000);
        mReceiver.onResult(createBinderSpamData(50, Duration.ofSeconds(20)));

        verify(mMockListener).onAnomalyDetected(mReportCaptor.capture());
        verifyReport(
                mReportCaptor.getValue(),
                totalCalls,
                timespan,
                TEST_CALL_LIMIT,
                Duration.ofMinutes(1),
                TEST_INTERFACE,
                TEST_METHOD);
        verifyLogAnomalyStatsBinderSpam(createBinderSpamData(totalCalls, timespan));
    }

    @Test
    public void onDataAvailable_differentUid_doNotExceedThreshold() {
        // Limit is 100/minute
        setupRule(createRuleWithPerMinuteRate(TEST_RULE_NAME, TEST_CALL_LIMIT));

        // First 51 calls made within 30 seconds.
        mReceiver.onResult(createBinderSpamData(51, Duration.ofSeconds(30)));
        verify(mMockListener, never()).onAnomalyDetected(any());

        // Next 50 calls made within next 20 seconds by different UID
        when(mMockElapsedRealtime.getAsLong()).thenReturn(20L * 1000);
        mReceiver.onResult(
                new BinderSpamData.Builder()
                        .setCallingUid(1) // Different UID than TEST_CALLER_UID (10001).
                        .setServerUid(TEST_SERVER_UID)
                        .setCallCount(50)
                        .setInterfaceName(TEST_INTERFACE)
                        .setMethodName(TEST_METHOD)
                        .setTimespan(Duration.ofSeconds(20))
                        .build());

        verify(mMockListener, never()).onAnomalyDetected(mReportCaptor.capture());
    }

    @Test
    public void onDataAvailable_totalRateExceedsThreshold() throws Exception {
        // Limit is 100/minute
        setupRule(createRuleWithPerMinuteRate(TEST_RULE_NAME, TEST_CALL_LIMIT));

        // Actual call rate:
        int totalCalls = 201;
        Duration timespan = Duration.ofMinutes(2);

        // First 100 calls made within 30 seconds.
        mReceiver.onResult(createBinderSpamData(100, Duration.ofSeconds(30)));
        verify(mMockListener, never()).onAnomalyDetected(any());

        // Next 101 calls made within next 90 seconds.
        when(mMockElapsedRealtime.getAsLong()).thenReturn(90L * 1000);
        mReceiver.onResult(createBinderSpamData(101, Duration.ofSeconds(90)));

        verify(mMockListener).onAnomalyDetected(mReportCaptor.capture());
        verifyReport(
                mReportCaptor.getValue(),
                totalCalls,
                timespan,
                TEST_CALL_LIMIT,
                Duration.ofMinutes(1),
                TEST_INTERFACE,
                TEST_METHOD);
        verifyLogAnomalyStatsBinderSpam(createBinderSpamData(totalCalls, timespan));
    }

    @Test
    public void onDataAvailable_multipleRules_lowerThresholdTriggered() throws Exception {
        // Limit is 100/sec
        RuleInternal rule1 = createRuleWithPerSecondRate(TEST_RULE_NAME, TEST_CALL_LIMIT);
        // Limit is 200/sec
        RuleInternal rule2 =
                createRuleWithPerSecondRate(TEST_RULE_NAME_2, TEST_CALL_LIMIT_2);
        setupRules(Set.of(rule1, rule2));

        int totalCalls = 150;
        Duration timespan = Duration.ofSeconds(1);
        BinderSpamData data =
                new BinderSpamData.Builder()
                        .setCallingUid(TEST_CALLER_UID)
                        .setServerUid(TEST_SERVER_UID)
                        .setCallerImportance(IMPORTANCE_FOREGROUND)
                        .setCallCount(totalCalls) // 150 calls/sec -> >100, <200
                        .setInterfaceName(TEST_INTERFACE)
                        .setMethodName(TEST_METHOD)
                        .setTimespan(Duration.ofSeconds(1))
                        .build();
        mReceiver.onResult(data);

        verify(mMockListener, times(1)).onAnomalyDetected(mReportCaptor.capture());
        AnomalyReport report = mReportCaptor.getValue();
        // Should only trigger rule 1
        assertThat(report.getRule()).isEqualTo(rule1);
        verifyReport(
                report,
                totalCalls,
                timespan,
                TEST_CALL_LIMIT,
                Duration.ofSeconds(1),
                TEST_INTERFACE,
                TEST_METHOD);
        verifyLogAnomalyStatsBinderSpam(data);
    }

    @Test
    public void onDataAvailable_multipleRules_bothThresholdsTriggered() throws Exception {
        RuleInternal rule1 = createRuleWithPerSecondRate(TEST_RULE_NAME, TEST_CALL_LIMIT); // 100
        RuleInternal rule2 =
                createRuleWithPerSecondRate(TEST_RULE_NAME_2, TEST_CALL_LIMIT_2); // 200
        setupRules(Set.of(rule1, rule2));

        BinderSpamData data =
                new BinderSpamData.Builder()
                        .setCallingUid(TEST_CALLER_UID)
                        .setServerUid(TEST_SERVER_UID)
                        .setCallerImportance(IMPORTANCE_FOREGROUND)
                        .setCallCount(250) // 250 calls/sec -> >100, >200
                        .setInterfaceName(TEST_INTERFACE)
                        .setMethodName(TEST_METHOD)
                        .setTimespan(Duration.ofSeconds(1))
                        .build();
        mReceiver.onResult(data);

        verify(mMockListener, times(2)).onAnomalyDetected(mReportCaptor.capture());
        List<AnomalyReport> reports = mReportCaptor.getAllValues();

        // Verify both rules triggered
        boolean triggeredRule1 = reports.stream().anyMatch(r -> r.getRule().equals(rule1));
        boolean triggeredRule2 = reports.stream().anyMatch(r -> r.getRule().equals(rule2));

        assertThat(triggeredRule1).isTrue();
        assertThat(triggeredRule2).isTrue();
        verifyLogAnomalyStatsBinderSpam(data, 2);
    }

    @Test
    public void onDataAvailable_rateAtThreshold_noAnomaly() {
        setupRule(createRuleWithPerSecondRate(TEST_RULE_NAME, TEST_CALL_LIMIT));

        BinderSpamData data =
                new BinderSpamData.Builder()
                        .setCallingUid(TEST_CALLER_UID)
                        .setServerUid(TEST_SERVER_UID)
                        .setCallerImportance(IMPORTANCE_FOREGROUND)
                        .setCallCount(100) // 100 calls/sec
                        .setInterfaceName(TEST_INTERFACE)
                        .setMethodName(TEST_METHOD)
                        .setTimespan(Duration.ofSeconds(1))
                        .build();
        mReceiver.onResult(data);

        verify(mMockListener, never()).onAnomalyDetected(any());
    }

    @Test
    public void onDataAvailable_rateBelowThreshold_noAnomaly() {
        setupRule(createRuleWithPerSecondRate(TEST_RULE_NAME, TEST_CALL_LIMIT));

        BinderSpamData data =
                new BinderSpamData.Builder()
                        .setCallingUid(TEST_CALLER_UID)
                        .setServerUid(TEST_SERVER_UID)
                        .setCallerImportance(IMPORTANCE_FOREGROUND)
                        .setCallCount(150) // 75 calls/sec
                        .setInterfaceName(TEST_INTERFACE)
                        .setMethodName(TEST_METHOD)
                        .setTimespan(Duration.ofSeconds(2))
                        .build();
        mReceiver.onResult(data);

        verify(mMockListener, never()).onAnomalyDetected(any());
    }

    @Test
    public void onDataAvailable_wrongInterface_noAnomaly() {
        setupRule(createRuleWithPerSecondRate(TEST_RULE_NAME, TEST_CALL_LIMIT));

        BinderSpamData data =
                new BinderSpamData.Builder()
                        .setCallingUid(TEST_CALLER_UID)
                        .setServerUid(TEST_SERVER_UID)
                        .setCallerImportance(IMPORTANCE_FOREGROUND)
                        .setCallCount(200) // 200 calls/sec
                        .setInterfaceName(TEST_WRONG_INTERFACE)
                        .setMethodName(TEST_METHOD)
                        .setTimespan(Duration.ofSeconds(1))
                        .build();
        mReceiver.onResult(data);

        verify(mMockListener, never()).onAnomalyDetected(any());
    }

    @Test
    public void onDataAvailable_wrongMethod_noAnomaly() {
        setupRule(createRuleWithPerSecondRate(TEST_RULE_NAME, TEST_CALL_LIMIT));

        BinderSpamData data =
                new BinderSpamData.Builder()
                        .setCallingUid(TEST_CALLER_UID)
                        .setServerUid(TEST_SERVER_UID)
                        .setCallerImportance(IMPORTANCE_FOREGROUND)
                        .setCallCount(200) // 200 calls/sec
                        .setInterfaceName(TEST_INTERFACE)
                        .setMethodName(TEST_WRONG_METHOD)
                        .setTimespan(Duration.ofSeconds(1))
                        .build();
        mReceiver.onResult(data);

        verify(mMockListener, never()).onAnomalyDetected(any());
    }

    @Test
    public void onDataAvailable_withCustomProfilingSessionDuration_reportHasCorrectDuration() {
        Bundle condition = new Bundle();
        condition.putString(
                RuleInternal.BUNDLE_KEY_CONDITION_BINDER_SPAM_INTERFACE_NAME, TEST_INTERFACE);
        condition.putString(RuleInternal.BUNDLE_KEY_CONDITION_BINDER_SPAM_METHOD_NAME, TEST_METHOD);
        condition.putInt(RuleInternal.BUNDLE_KEY_CONDITION_BINDER_SPAM_CALL_LIMIT, TEST_CALL_LIMIT);
        condition.putLong(
                RuleInternal.BUNDLE_KEY_CONDITION_BINDER_SPAM_BINDER_CALL_INTERVAL_MILLIS,
                TEST_CALL_INTERVAL_MILLIS);
        condition.putLong(
                RuleInternal.BUNDLE_KEY_PROFILING_SESSION_DURATION_MILLIS,
                TEST_PROFILING_SESSION_DURATION_MILLIS);

        RuleInternal rule =
                new RuleInternal.Builder()
                        .setName(TEST_RULE_NAME)
                        .setConditionType(RuleInternal.CONDITION_TYPE_BINDER_SPAM)
                        .setRuleCondition(condition)
                        .addAnomalyAction(RuleInternal.ACTION_TYPE_COLLECT_PROFILE)
                        .build();

        setupRule(rule);

        mReceiver.onResult(createBinderSpamData(TEST_CALL_LIMIT + 1, Duration.ofSeconds(1)));

        verify(mMockListener).onAnomalyDetected(mReportCaptor.capture());
        AnomalyReport report = mReportCaptor.getValue();
        ProfilingParamsAttribute profilingParams = report.get(ProfilingParamsAttribute.class);
        assertThat(profilingParams).isNotNull();
        assertThat(profilingParams.maxSessionDurationMs())
                .isEqualTo(TEST_PROFILING_SESSION_DURATION_MILLIS);
    }

    @Test
    public void onDataAvailable_withoutCustomProfilingSessionDuration_usesDefaultDuration() {
        RuleInternal rule =
                new RuleInternal.Builder()
                        .setName(TEST_RULE_NAME)
                        .setConditionType(RuleInternal.CONDITION_TYPE_BINDER_SPAM)
                        .setRuleCondition(
                                createRuleWithPerSecondRate(TEST_RULE_NAME, TEST_CALL_LIMIT)
                                        .getRuleCondition())
                        .addAnomalyAction(RuleInternal.ACTION_TYPE_COLLECT_PROFILE)
                        .build();

        setupRule(rule);

        mReceiver.onResult(createBinderSpamData(TEST_CALL_LIMIT + 1, Duration.ofSeconds(1)));

        verify(mMockListener).onAnomalyDetected(mReportCaptor.capture());
        AnomalyReport report = mReportCaptor.getValue();
        ProfilingParamsAttribute profilingParams = report.get(ProfilingParamsAttribute.class);
        assertThat(profilingParams).isNotNull();
        assertThat(profilingParams.maxSessionDurationMs())
                .isEqualTo(BinderSpamAnomalyDetector.DEFAULT_PROFILING_SESSION_DURATION_MILLIS);
    }

    @Test
    public void onSignalCollectorUnregistered_unsubscribesFromCollector() {
        SubscriptionId subscriptionId = SubscriptionId.generateNew();
        when(mMockCollector.subscribe(any(), any())).thenReturn(subscriptionId);

        setupRule(createRuleWithPerSecondRate(TEST_RULE_NAME, TEST_CALL_LIMIT));

        mDetector.onSignalCollectorUnregistered(
                new SignalTypeId(BinderSpamConfigList.class, BinderSpamData.class));

        verify(mMockCollector).unsubscribe(subscriptionId);
    }

    private void verifyReport(
            AnomalyReport report,
            int totalCalls,
            Duration actualTimespan,
            int thresholdCallCount,
            Duration thresholdInterval,
            String interfaceName,
            String methodName) {
        UidAttribute uidAttribute = report.get(UidAttribute.class);
        assertThat(uidAttribute).isNotNull();
        assertThat(uidAttribute.uid()).isEqualTo(TEST_CALLER_UID);

        SummaryAttribute summaryAttribute = report.get(SummaryAttribute.class);
        assertThat(summaryAttribute).isNotNull();
        String summary = summaryAttribute.summary();
        assertThat(summary).isNotNull();
        assertThat(summary).contains("UID " + TEST_CALLER_UID);

        AnomalyDetailsAttribute actualAnomalyDetailsAttribute =
                report.get(AnomalyDetailsAttribute.class);
        assertThat(actualAnomalyDetailsAttribute).isNotNull();
        assertThat(actualAnomalyDetailsAttribute.durationMillis())
                .isEqualTo(actualTimespan.toMillis());
        String actualAnomalyDetailsString =
                actualAnomalyDetailsAttribute.anomalyDetails().toString();
        assertThat(actualAnomalyDetailsString).contains(interfaceName);
        assertThat(actualAnomalyDetailsString).contains(methodName);
        assertThat(actualAnomalyDetailsString)
                .contains(String.valueOf(
                        (int) (thresholdCallCount / (thresholdInterval.toMillis() / 1000.0))));
        assertThat(actualAnomalyDetailsString)
                .contains(String.valueOf(
                        (int) (totalCalls / (actualTimespan.toMillis() / 1000.0))));
        RateLimitSignatureAttribute signatureAttribute =
                report.get(RateLimitSignatureAttribute.class);
        assertThat(signatureAttribute).isNotNull();
        Map<String, String> signature = signatureAttribute.signature();
        assertThat(signature).isNotNull();
        assertThat(signature).hasSize(2);
        assertThat(signature).containsEntry(BINDER_SPAM_INTERFACE_KEY, interfaceName);
        assertThat(signature).containsEntry(BINDER_SPAM_METHOD_KEY, methodName);
    }

    private void verifyLogAnomalyStatsBinderSpam(BinderSpamData binderData) throws Exception {
        verifyLogAnomalyStatsBinderSpam(binderData, 1);
    }

    private void verifyLogAnomalyStatsBinderSpam(BinderSpamData binderData, int times)
            throws Exception {
        verify(() -> StatsLog.write(mStatsEventCaptor.capture()), times(times));
        List<StatsEvent> actualEvents = mStatsEventCaptor.getAllValues();
        assertEquals(times, actualEvents.size());

        StatsEvent expectedEvent =
                buildAnomalyStatsBinderSpamEvent(
                        binderData.getCallingUid(),
                        binderData.getInterfaceName(),
                        binderData.getMethodName(),
                        binderData.getCallCount(),
                        binderData.getTimespan().toMillis());
        AtomsProto.Atom expectedAtom = StatsEventTestUtils.convertToAtom(expectedEvent);

        for (StatsEvent actualEvent : actualEvents) {
            AtomsProto.Atom actualAtom = StatsEventTestUtils.convertToAtom(actualEvent);
            assertEquals(expectedAtom, actualAtom);
        }

        assertEquals(
                ANOMALY_STATS_BINDER_SPAM_ATOM_ID, AnomalyStatsAtomsLog.ANOMALY_STATS_BINDER_SPAM);
    }

    private RuleInternal createRuleWithPerSecondRate(String name, int limit) {
        return createRule(name, limit, /* intervalMillis = 1s */ 1000, TEST_INTERFACE, TEST_METHOD);
    }

    private RuleInternal createRuleWithPerMinuteRate(String name, int limit) {
        return createRule(
                name, limit, /* intervalMillis = 60s */ 60 * 1000, TEST_INTERFACE, TEST_METHOD);
    }

    private RuleInternal createRule(
            String name, int limit, long intervalMillis, String interfaceName, String methodName) {
        Bundle condition = new Bundle();
        condition.putString(
                RuleInternal.BUNDLE_KEY_CONDITION_BINDER_SPAM_INTERFACE_NAME, interfaceName);
        condition.putString(RuleInternal.BUNDLE_KEY_CONDITION_BINDER_SPAM_METHOD_NAME, methodName);
        condition.putInt(RuleInternal.BUNDLE_KEY_CONDITION_BINDER_SPAM_CALL_LIMIT, limit);
        condition.putLong(
                RuleInternal.BUNDLE_KEY_CONDITION_BINDER_SPAM_BINDER_CALL_INTERVAL_MILLIS,
                intervalMillis);
        return new RuleInternal.Builder()
                .setName(name)
                .setConditionType(RuleInternal.CONDITION_TYPE_BINDER_SPAM)
                .setRuleCondition(condition)
                .addAnomalyAction(RuleInternal.ACTION_TYPE_LOG)
                .build();
    }

    private void setupRule(RuleInternal rule) {
        setupRules(Set.of(rule));
    }

    private void setupRules(Set<RuleInternal> rules) {
        ArgumentCaptor<OutcomeReceiver<BinderSpamData, Throwable>> receiverCaptor =
                ArgumentCaptor.forClass(OutcomeReceiver.class);
        mDetector.setRules(rules);
        verify(mMockCollector).subscribe(any(), receiverCaptor.capture());
        mReceiver = receiverCaptor.getValue();
    }

    private static BinderSpamData createBinderSpamData(int totalCalls, Duration timespan) {
        return new BinderSpamData.Builder()
                .setCallingUid(TEST_CALLER_UID)
                .setServerUid(TEST_SERVER_UID)
                .setCallCount(totalCalls)
                .setInterfaceName(TEST_INTERFACE)
                .setMethodName(TEST_METHOD)
                .setTimespan(timespan)
                .build();
    }

    private static StatsEvent buildAnomalyStatsBinderSpamEvent(
            int uid, String binderInterface, String binderMethod, int callCount, long timespanMs) {
        return StatsEvent.newBuilder()
                .setAtomId(ANOMALY_STATS_BINDER_SPAM_ATOM_ID)
                .writeInt(uid)
                .writeString(binderInterface)
                .writeString(binderMethod)
                .writeInt(callCount)
                .writeLong(timespanMs)
                .build();
    }
}
