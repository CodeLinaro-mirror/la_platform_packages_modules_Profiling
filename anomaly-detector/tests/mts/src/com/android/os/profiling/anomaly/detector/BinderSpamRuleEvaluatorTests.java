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

package com.android.os.profiling.anomaly.detector;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.when;

import android.os.profiling.anomaly.RuleInternal;

import androidx.test.runner.AndroidJUnit4;

import com.android.os.profiling.anomaly.attribute.BinderSpamDetailsAttribute;
import com.android.os.profiling.anomaly.attribute.SummaryAttribute;
import com.android.os.profiling.anomaly.attribute.UidAttribute;
import com.android.os.profiling.anomaly.collector.binder.BinderSpamData;
import com.android.os.profiling.anomaly.core.AnomalyReport;
import com.android.os.profiling.anomaly.detector.BinderSpamAnomalyDetector.RuleEvaluator;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.time.Duration;
import java.util.function.LongSupplier;

/** Tests for {@link RuleEvaluator}. */
@RunWith(AndroidJUnit4.class)
public final class BinderSpamRuleEvaluatorTests {
    private static final Duration TEST_WINDOW_SIZE = Duration.ofMinutes(1);
    private static final int TEST_CALL_COUNT_THRESHOLD = 100;
    private static final int TEST_CALLER_UID = 1;
    private static final int TEST_SERVER_UID = 1000;
    private static final String TEST_INTERFACE = "com.example.Interface";
    private static final String TEST_METHOD = "method";

    @org.junit.Rule public MockitoRule mMockitoRule = MockitoJUnit.rule();
    @Mock private RuleInternal mMockRule;
    @Mock private LongSupplier mMockElapsedRealtime;
    private int mCurrentTimeSeconds;

    @After
    public void tearDown() {
        resetTime();
    }

    @Test
    public void evaluate_singleCall_reportAnomaly() {
        RuleEvaluator evaluator =
                new RuleEvaluator(
                        mMockRule,
                        TEST_WINDOW_SIZE,
                        TEST_CALL_COUNT_THRESHOLD,
                        mMockElapsedRealtime);
        // Actual rate: 101 calls/min
        int actualCallCount = 101;
        Duration actualInterval = Duration.ofSeconds(60);

        advanceTimeInSeconds(60);
        AnomalyReport actualReport =
                evaluator.evaluate(
                        createBinderSpamData(actualCallCount, actualInterval, TEST_CALLER_UID));

        BinderSpamDetailsAttribute expectedDetails =
                createBinderSpamDetails(actualCallCount, actualInterval);

        assertThat(actualReport).isNotNull();
        assertThat(actualReport.get(UidAttribute.class))
                .isEqualTo(new UidAttribute(TEST_CALLER_UID));
        assertThat(actualReport.get(BinderSpamDetailsAttribute.class)).isEqualTo(expectedDetails);
        assertThat(actualReport.get(SummaryAttribute.class))
                .isEqualTo(createSummaryAttribute(expectedDetails));
    }

    @Test
    public void evaluate_singleCall_noAnomaly() {
        RuleEvaluator evaluator =
                new RuleEvaluator(
                        mMockRule,
                        TEST_WINDOW_SIZE,
                        TEST_CALL_COUNT_THRESHOLD,
                        mMockElapsedRealtime);
        // Actual rate: 99 calls/min
        advanceTimeInSeconds(60);
        AnomalyReport actualReport =
                evaluator.evaluate(
                        createBinderSpamData(99, Duration.ofSeconds(60), TEST_CALLER_UID));
        assertThat(actualReport).isNull();
    }

    @Test
    public void evaluate_multipleCallDifferentUids_reportAnomaly() {
        RuleEvaluator evaluator =
                new RuleEvaluator(
                        mMockRule,
                        TEST_WINDOW_SIZE,
                        TEST_CALL_COUNT_THRESHOLD,
                        mMockElapsedRealtime);

        // Actual rate: 200 calls/90s > 100 calls/min
        int actualCallCount = 200;
        Duration actualInterval = Duration.ofSeconds(90);

        // 100 calls in first 30 seconds.
        advanceTimeInSeconds(30);
        AnomalyReport report1 =
                evaluator.evaluate(
                        createBinderSpamData(100, Duration.ofSeconds(30), TEST_CALLER_UID));
        assertThat(report1).isNull();

        // 100 calls from different UID in the next 30 seconds.
        advanceTimeInSeconds(30);
        AnomalyReport report2 =
                evaluator.evaluate(
                        createBinderSpamData(100, Duration.ofSeconds(30), TEST_CALLER_UID + 1));
        assertThat(report2).isNull();

        // 100 calls from same UID in next 30 seconds.
        advanceTimeInSeconds(30);
        AnomalyReport report3 =
                evaluator.evaluate(
                        createBinderSpamData(100, Duration.ofSeconds(30), TEST_CALLER_UID));

        BinderSpamDetailsAttribute expectedDetails =
                createBinderSpamDetails(actualCallCount, actualInterval);

        assertThat(report3).isNotNull();
        assertThat(report3.get(UidAttribute.class)).isEqualTo(new UidAttribute(TEST_CALLER_UID));
        assertThat(report3.get(BinderSpamDetailsAttribute.class)).isEqualTo(expectedDetails);
        assertThat(report3.get(SummaryAttribute.class))
                .isEqualTo(createSummaryAttribute(expectedDetails));
    }

    @Test
    public void evaluate_afterAnomalyReported_noRepeatAnomaly() {
        RuleEvaluator evaluator =
                new RuleEvaluator(
                        mMockRule,
                        TEST_WINDOW_SIZE,
                        TEST_CALL_COUNT_THRESHOLD,
                        mMockElapsedRealtime);

        // 101 calls in first 30 seconds. Should report anomaly.
        advanceTimeInSeconds(30);
        AnomalyReport report1 =
                evaluator.evaluate(
                        createBinderSpamData(101, Duration.ofSeconds(30), TEST_CALLER_UID));
        assertThat(report1).isNotNull();

        // Received more data for the same UID. 99 calls in 60s.
        advanceTimeInSeconds(30);
        AnomalyReport report2 =
                evaluator.evaluate(
                        createBinderSpamData(99, Duration.ofSeconds(60), TEST_CALLER_UID));
        assertThat(report2).isNull();
    }

    @Test
    public void evaluate_windowExpires_noAnomaly() {
        RuleEvaluator evaluator =
                new RuleEvaluator(
                        mMockRule,
                        TEST_WINDOW_SIZE,
                        TEST_CALL_COUNT_THRESHOLD,
                        mMockElapsedRealtime);

        // 100 call in first 60 seconds.
        advanceTimeInSeconds(60);
        AnomalyReport report1 =
                evaluator.evaluate(
                        createBinderSpamData(100, Duration.ofSeconds(60), TEST_CALLER_UID));
        assertThat(report1).isNull();

        // Another 100 call in 1 second. If window does not reset, anomaly should report.
        advanceTimeInSeconds(1);
        AnomalyReport report2 =
                evaluator.evaluate(
                        createBinderSpamData(100, Duration.ofSeconds(1), TEST_CALLER_UID));
        assertThat(report2).isNull();
    }

    private static BinderSpamData createBinderSpamData(
            int totalCalls, Duration timespan, int callerUid) {
        return new BinderSpamData.Builder()
                .setCallingUid(callerUid)
                .setServerUid(TEST_SERVER_UID)
                .setInterfaceName(TEST_INTERFACE)
                .setMethodName(TEST_METHOD)
                .setCallCount(totalCalls)
                .setTimespan(timespan)
                .build();
    }

    private static BinderSpamDetailsAttribute createBinderSpamDetails(
            int observedCallCount, Duration observedInterval) {
        return new BinderSpamDetailsAttribute(
                TEST_INTERFACE,
                TEST_METHOD,
                observedCallCount,
                observedInterval,
                BinderSpamRuleEvaluatorTests.TEST_CALL_COUNT_THRESHOLD,
                BinderSpamRuleEvaluatorTests.TEST_WINDOW_SIZE);
    }

    private static SummaryAttribute createSummaryAttribute(
            BinderSpamDetailsAttribute detailsAttribute) {
        double actualCallsPerSecond =
                (double) detailsAttribute.observedCallCount()
                        / detailsAttribute.observedInterval().toSeconds();
        return new SummaryAttribute(
                String.format(
                        "UID %d made %d calls to %s#%s in %ds (Rate: %.2f calls/sec, "
                                + "Threshold: %d calls/%ds)",
                        TEST_CALLER_UID,
                        detailsAttribute.observedCallCount(),
                        TEST_INTERFACE,
                        TEST_METHOD,
                        detailsAttribute.observedInterval().toSeconds(),
                        actualCallsPerSecond,
                        detailsAttribute.thresholdCallCount(),
                        detailsAttribute.thresholdInterval().toSeconds()));
    }

    private void advanceTimeInSeconds(int seconds) {
        mCurrentTimeSeconds += seconds;
        when(mMockElapsedRealtime.getAsLong()).thenReturn(mCurrentTimeSeconds * 1000L);
    }

    private void resetTime() {
        mCurrentTimeSeconds = 0;
        when(mMockElapsedRealtime.getAsLong()).thenReturn(0L);
    }
}
