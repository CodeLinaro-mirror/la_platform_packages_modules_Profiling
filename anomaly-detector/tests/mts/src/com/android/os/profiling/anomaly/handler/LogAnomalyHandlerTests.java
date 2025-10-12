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

package com.android.os.profiling.anomaly.handler;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.os.profiling.anomaly.flags.Flags;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;

import androidx.test.runner.AndroidJUnit4;

import com.android.os.profiling.anomaly.attribute.SummaryAttribute;
import com.android.os.profiling.anomaly.core.AnomalyReport;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/** Tests for {@link LogAnomalyHandler}. */
@RunWith(AndroidJUnit4.class)
@RequiresFlagsEnabled(Flags.FLAG_ANOMALY_DETECTOR_CORE)
public final class LogAnomalyHandlerTests {

    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    @Rule public MockitoRule mMockitoRule = MockitoJUnit.rule();

    @Mock private AnomalyReport mMockReport;

    private LogAnomalyHandler mHandler;

    @Before
    public void setUp() {
        mHandler = new LogAnomalyHandler();
    }

    @Test
    public void execute_withSummary_logsSummary() {
        SummaryAttribute summaryAttribute = new SummaryAttribute("Test Summary");
        when(mMockReport.get(SummaryAttribute.class)).thenReturn(summaryAttribute);

        mHandler.execute(mMockReport);

        // Verify that the handler interacts with the report correctly.
        // We don't verify the static Slog.w call itself, but we verify that the
        // handler retrieved the data it would need to make that call.
        verify(mMockReport).get(SummaryAttribute.class);
    }

    @Test
    public void execute_withoutSummary_logsWarning() {
        when(mMockReport.get(SummaryAttribute.class)).thenReturn(null);

        mHandler.execute(mMockReport);

        // Verify that the handler interacts with the report correctly.
        verify(mMockReport).get(SummaryAttribute.class);
    }
}
