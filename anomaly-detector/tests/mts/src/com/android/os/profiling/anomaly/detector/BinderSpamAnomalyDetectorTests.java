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

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.os.Bundle;
import android.os.OutcomeReceiver;
import android.os.profiling.anomaly.RuleInternal;

import androidx.test.runner.AndroidJUnit4;

import com.android.os.profiling.anomaly.attribute.BinderSpamDetailsAttribute;
import com.android.os.profiling.anomaly.attribute.SummaryAttribute;
import com.android.os.profiling.anomaly.attribute.UidAttribute;
import com.android.os.profiling.anomaly.collector.SignalCollector;
import com.android.os.profiling.anomaly.collector.SubscriptionId;
import com.android.os.profiling.anomaly.collector.binder.BinderSpamConfigList;
import com.android.os.profiling.anomaly.collector.binder.BinderSpamData;
import com.android.os.profiling.anomaly.core.AnomalyDetector;
import com.android.os.profiling.anomaly.core.AnomalyReport;
import com.android.os.profiling.anomaly.core.SignalCollectorRegistry;
import com.android.os.profiling.anomaly.core.SignalTypeId;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.time.Duration;

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
    private static final int TEST_CALL_LIMIT = 100;
    private static final long TEST_BINDER_CALL_INTERVAL_MILLIS = 1000L;

    @org.junit.Rule public MockitoRule mMockitoRule = MockitoJUnit.rule();

    @Mock private SignalCollectorRegistry mMockRegistry;
    @Mock private SignalCollector<BinderSpamConfigList, BinderSpamData> mMockCollector;
    @Mock private AnomalyDetector.OnAnomalyDetectedListener mMockListener;

    @Captor private ArgumentCaptor<AnomalyReport> mReportCaptor;

    private OutcomeReceiver<BinderSpamData, Throwable> mReceiver;

    @Before
    public void setUp() {
        when(mMockRegistry.getSignalCollector(BinderSpamConfigList.class, BinderSpamData.class))
                .thenReturn(mMockCollector);
        when(mMockCollector.subscribe(any(), any())).thenReturn(SubscriptionId.generateNew());

        BinderSpamAnomalyDetector detector = new BinderSpamAnomalyDetector(mMockRegistry);
        detector.setOnAnomalyDetectedListener(mMockListener);

        // Capture the receiver to simulate data arriving from the collector
        ArgumentCaptor<OutcomeReceiver<BinderSpamData, Throwable>> receiverCaptor =
                ArgumentCaptor.forClass(OutcomeReceiver.class);
        Bundle condition = new Bundle();
        condition.putString(
                RuleInternal.BUNDLE_KEY_CONDITION_BINDER_SPAM_INTERFACE_NAME, TEST_INTERFACE);
        condition.putString(RuleInternal.BUNDLE_KEY_CONDITION_BINDER_SPAM_METHOD_NAME, TEST_METHOD);
        condition.putInt(RuleInternal.BUNDLE_KEY_CONDITION_BINDER_SPAM_CALL_LIMIT, TEST_CALL_LIMIT);
        condition.putLong(
                RuleInternal.BUNDLE_KEY_CONDITION_BINDER_SPAM_BINDER_CALL_INTERVAL_MILLIS,
                TEST_BINDER_CALL_INTERVAL_MILLIS);
        RuleInternal rule =
                new RuleInternal.Builder()
                        .setName(TEST_RULE_NAME)
                        .setConditionType(RuleInternal.CONDITION_TYPE_BINDER_SPAM)
                        .setRuleCondition(condition)
                        .addAnomalyAction(RuleInternal.ACTION_TYPE_LOG)
                        .build();
        detector.setRule(rule);
        verify(mMockCollector).subscribe(any(), receiverCaptor.capture());
        mReceiver = receiverCaptor.getValue();
    }

    @Test
    public void onDataAvailable_rateExceedsThreshold_anomalyDetected() {
        BinderSpamData data =
                new BinderSpamData.Builder()
                        .setCallingUid(TEST_CALLER_UID)
                        .setServerUid(TEST_SERVER_UID)
                        .setCallerImportance(IMPORTANCE_FOREGROUND)
                        .setCallCount(101) // 101 calls/sec
                        .setInterfaceName(TEST_INTERFACE)
                        .setMethodName(TEST_METHOD)
                        .setTimespan(Duration.ofSeconds(1))
                        .build();
        mReceiver.onResult(data);

        verify(mMockListener).onAnomalyDetected(mReportCaptor.capture());
        verifyReport(mReportCaptor.getValue(), data);
    }

    @Test
    public void onDataAvailable_rateAtThreshold_noAnomaly() {
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
    public void onDataAvailable_tooShortTimeSpan_noAnomaly() {
        BinderSpamData data =
                new BinderSpamData.Builder()
                        .setCallingUid(TEST_CALLER_UID)
                        .setServerUid(TEST_SERVER_UID)
                        .setCallerImportance(IMPORTANCE_FOREGROUND)
                        .setCallCount(200) // > 200 calls/sec
                        .setInterfaceName(TEST_INTERFACE)
                        .setMethodName(TEST_METHOD)
                        .setTimespan(Duration.ofMillis(999))
                        .build();
        mReceiver.onResult(data);

        verify(mMockListener, never()).onAnomalyDetected(any());
    }

    private void verifyReport(AnomalyReport report, BinderSpamData data) {
        UidAttribute uidAttribute = report.get(UidAttribute.class);
        assertThat(uidAttribute).isNotNull();
        assertThat(uidAttribute.uid()).isEqualTo(data.getCallingUid());

        SummaryAttribute summaryAttribute = report.get(SummaryAttribute.class);
        assertThat(summaryAttribute).isNotNull();
        String summary = summaryAttribute.summary();
        assertThat(summary).isNotNull();
        assertThat(summary).contains("UID " + data.getCallingUid());
        assertThat(summary).contains(data.getCallCount() + " calls");
        assertThat(summary).contains(data.getInterfaceName());
        assertThat(summary).contains(data.getMethodName());
        assertThat(summary).contains(String.format("%ds", data.getTimespan().toSeconds()));

        BinderSpamDetailsAttribute binderSpamDetailsAttribute =
                report.get(BinderSpamDetailsAttribute.class);
        assertThat(binderSpamDetailsAttribute).isNotNull();
        assertThat(binderSpamDetailsAttribute.interfaceName()).isEqualTo(data.getInterfaceName());
        assertThat(binderSpamDetailsAttribute.methodName()).isEqualTo(data.getMethodName());
        assertThat(binderSpamDetailsAttribute.observedCallCount()).isEqualTo(data.getCallCount());
        assertThat(binderSpamDetailsAttribute.observedInterval())
                .isEqualTo(data.getTimespan());
        assertThat(binderSpamDetailsAttribute.thresholdCallCount()).isEqualTo(TEST_CALL_LIMIT);
        assertThat(binderSpamDetailsAttribute.thresholdInterval())
                .isEqualTo(Duration.ofMillis(TEST_BINDER_CALL_INTERVAL_MILLIS));
    }

    @Test
    public void onSignalCollectorUnregistered_unsubscribesFromCollector() {
        BinderSpamAnomalyDetector detector = new BinderSpamAnomalyDetector(mMockRegistry);
        SubscriptionId subscriptionId = SubscriptionId.generateNew();
        when(mMockCollector.subscribe(any(), any())).thenReturn(subscriptionId);

        Bundle condition = new Bundle();
        condition.putString(
                RuleInternal.BUNDLE_KEY_CONDITION_BINDER_SPAM_INTERFACE_NAME, TEST_INTERFACE);
        condition.putString(RuleInternal.BUNDLE_KEY_CONDITION_BINDER_SPAM_METHOD_NAME, TEST_METHOD);
        condition.putInt(RuleInternal.BUNDLE_KEY_CONDITION_BINDER_SPAM_CALL_LIMIT, TEST_CALL_LIMIT);
        condition.putLong(
                RuleInternal.BUNDLE_KEY_CONDITION_BINDER_SPAM_BINDER_CALL_INTERVAL_MILLIS,
                TEST_BINDER_CALL_INTERVAL_MILLIS);
        RuleInternal rule =
                new RuleInternal.Builder()
                        .setName("test_rule")
                        .setConditionType(RuleInternal.CONDITION_TYPE_BINDER_SPAM)
                        .setRuleCondition(condition)
                        .addAnomalyAction(RuleInternal.ACTION_TYPE_LOG)
                        .build();
        detector.setRule(rule);

        detector.onSignalCollectorUnregistered(
                new SignalTypeId(BinderSpamConfigList.class, BinderSpamData.class));

        verify(mMockCollector).unsubscribe(subscriptionId);
    }
}
