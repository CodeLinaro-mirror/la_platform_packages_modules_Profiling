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

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.os.OutcomeReceiver;
import android.os.profiling.anomaly.flags.Flags;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;

import androidx.test.runner.AndroidJUnit4;

import com.android.os.profiling.anomaly.attribute.SummaryAttribute;
import com.android.os.profiling.anomaly.attribute.UidAttribute;
import com.android.os.profiling.anomaly.collector.SignalCollector;
import com.android.os.profiling.anomaly.collector.SubscriptionId;
import com.android.os.profiling.anomaly.collector.binder.BinderSpamConfig;
import com.android.os.profiling.anomaly.collector.binder.BinderSpamData;
import com.android.os.profiling.anomaly.condition.BinderSpamCondition;
import com.android.os.profiling.anomaly.core.AnomalyDetector;
import com.android.os.profiling.anomaly.core.AnomalyReport;
import com.android.os.profiling.anomaly.core.SignalCollectorRegistry;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.Collections;

/** Tests for {@link BinderSpamAnomalyDetector}. */
@RunWith(AndroidJUnit4.class)
@RequiresFlagsEnabled(Flags.FLAG_ANOMALY_DETECTOR_CORE)
public final class BinderSpamAnomalyDetectorTests {

    private static final String TEST_INTERFACE = "com.android.test.ITest";
    private static final String TEST_METHOD = "testMethod";
    private static final int TEST_UID = 10001;

    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    @Rule public MockitoRule mMockitoRule = MockitoJUnit.rule();

    @Mock private SignalCollectorRegistry mMockRegistry;
    @Mock private SignalCollector<BinderSpamConfig, BinderSpamData> mMockCollector;
    @Mock private AnomalyDetector.OnAnomalyDetectedListener mMockListener;

    @Captor private ArgumentCaptor<AnomalyReport> mReportCaptor;

    private BinderSpamAnomalyDetector mDetector;
    private OutcomeReceiver<BinderSpamData, Throwable> mReceiver;

    @Before
    public void setUp() {
        when(mMockRegistry.getSignalCollector(BinderSpamConfig.class, BinderSpamData.class))
                .thenReturn(mMockCollector);
        when(mMockCollector.subscribe(any(), any())).thenReturn(SubscriptionId.generateNew());

        mDetector = new BinderSpamAnomalyDetector(mMockRegistry);
        mDetector.setOnAnomalyDetectedListener(mMockListener);

        // Capture the receiver to simulate data arriving from the collector
        ArgumentCaptor<OutcomeReceiver<BinderSpamData, Throwable>> receiverCaptor =
                ArgumentCaptor.forClass(OutcomeReceiver.class);
        BinderSpamCondition condition = new BinderSpamCondition(100, TEST_INTERFACE, TEST_METHOD);
        com.android.os.profiling.anomaly.core.Rule<BinderSpamCondition> rule =
                new com.android.os.profiling.anomaly.core.Rule<>(condition, Collections.emptySet());
        mDetector.setRule(rule);
        verify(mMockCollector).subscribe(any(), receiverCaptor.capture());
        mReceiver = receiverCaptor.getValue();
    }

    @Test
    public void onDataAvailable_rateExceedsThreshold_anomalyDetected() {
        BinderSpamData data =
                new BinderSpamData.Builder()
                        .setCallingUid(TEST_UID)
                        .setCallCount(101) // 101 calls/sec
                        .setInterfaceName(TEST_INTERFACE)
                        .setMethodName(TEST_METHOD)
                        .setTimespanMillis(1000)
                        .build();
        mReceiver.onResult(data);

        verify(mMockListener).onAnomalyDetected(mReportCaptor.capture());
        AnomalyReport report = mReportCaptor.getValue();

        UidAttribute uidAttribute = report.get(UidAttribute.class);
        assertThat(uidAttribute).isNotNull();
        assertThat(uidAttribute.uid()).isEqualTo(TEST_UID);

        SummaryAttribute summaryAttribute = report.get(SummaryAttribute.class);
        assertThat(summaryAttribute).isNotNull();
        assertThat(summaryAttribute.summary()).isNotNull();
        assertThat(summaryAttribute.summary()).contains("UID " + TEST_UID);
        assertThat(summaryAttribute.summary()).contains("101 calls");
        assertThat(summaryAttribute.summary()).contains(TEST_INTERFACE);
        assertThat(summaryAttribute.summary()).contains(TEST_METHOD);
    }

    @Test
    public void onDataAvailable_rateAtThreshold_noAnomaly() {
        BinderSpamData data =
                new BinderSpamData.Builder()
                        .setCallingUid(TEST_UID)
                        .setCallCount(100) // 100 calls/sec
                        .setInterfaceName(TEST_INTERFACE)
                        .setMethodName(TEST_METHOD)
                        .setTimespanMillis(1000)
                        .build();
        mReceiver.onResult(data);

        verify(mMockListener, never()).onAnomalyDetected(any());
    }

    @Test
    public void onDataAvailable_rateBelowThreshold_noAnomaly() {
        BinderSpamData data =
                new BinderSpamData.Builder()
                        .setCallingUid(TEST_UID)
                        .setCallCount(150) // 75 calls/sec
                        .setInterfaceName(TEST_INTERFACE)
                        .setMethodName(TEST_METHOD)
                        .setTimespanMillis(2000)
                        .build();
        mReceiver.onResult(data);

        verify(mMockListener, never()).onAnomalyDetected(any());
    }

    @Test
    public void onDataAvailable_wrongInterface_noAnomaly() {
        BinderSpamData data =
                new BinderSpamData.Builder()
                        .setCallingUid(TEST_UID)
                        .setCallCount(200) // 200 calls/sec
                        .setInterfaceName("com.android.test.WRONG_INTERFACE")
                        .setMethodName(TEST_METHOD)
                        .setTimespanMillis(1000)
                        .build();
        mReceiver.onResult(data);

        verify(mMockListener, never()).onAnomalyDetected(any());
    }

    @Test
    public void onDataAvailable_timespanTooShort_noAnomaly() {
        BinderSpamData data =
                new BinderSpamData.Builder()
                        .setCallingUid(TEST_UID)
                        .setCallCount(2) // 200 calls/sec
                        .setInterfaceName(TEST_INTERFACE)
                        .setMethodName(TEST_METHOD)
                        .setTimespanMillis(10) // Less than 1000ms.
                        .build();
        mReceiver.onResult(data);

        verify(mMockListener, never()).onAnomalyDetected(any());
    }
}
