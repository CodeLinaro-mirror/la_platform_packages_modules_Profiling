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

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.os.Process;
import android.os.profiling.anomaly.flags.Flags;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;

import androidx.test.runner.AndroidJUnit4;

import com.android.os.profiling.anomaly.attribute.UidAttribute;
import com.android.os.profiling.anomaly.core.AnomalyReport;
import com.android.os.profiling.anomaly.core.BaseCondition;
import com.android.os.profiling.anomaly.internal.AnomalyReportImpl;
import com.android.os.profiling.anomaly.wrapper.SystemServiceFetcher;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.Collections;

/** Tests for {@link KillAnomalyHandler}. */
@RunWith(AndroidJUnit4.class)
@RequiresFlagsEnabled(Flags.FLAG_ANOMALY_DETECTOR_CORE)
public final class KillAnomalyHandlerTests {

    private static final int INVALID_UID = -1;
    private static final int TEST_APP_UID = Process.FIRST_APPLICATION_UID + 1;

    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    @Rule public MockitoRule mMockitoRule = MockitoJUnit.rule();

    @Mock private SystemServiceFetcher mMockSystemServiceFetcher;
    @Mock private ActivityManager mMockActivityManager;

    private KillAnomalyHandler mHandler;
    private com.android.os.profiling.anomaly.core.Rule<TestCondition> mTestRule;

    private static class TestCondition implements BaseCondition {}

    @Before
    public void setUp() {
        when(mMockSystemServiceFetcher.getActivityManager()).thenReturn(mMockActivityManager);
        mHandler = new KillAnomalyHandler(mMockSystemServiceFetcher);
        mTestRule =
                new com.android.os.profiling.anomaly.core.Rule<>(
                        new TestCondition(), Collections.emptySet());
    }

    @Test
    public void execute_withApplicationUid_killsProcess() {
        AnomalyReport report =
                new AnomalyReportImpl.Builder(mTestRule)
                        .addAttribute(new UidAttribute(TEST_APP_UID))
                        .build();

        mHandler.execute(report);

        verify(mMockActivityManager).killUid(TEST_APP_UID, "Anomaly detected");
    }

    @Test
    public void execute_withFirstApplicationUid_killsProcess() {
        AnomalyReport report =
                new AnomalyReportImpl.Builder(mTestRule)
                        .addAttribute(new UidAttribute(Process.FIRST_APPLICATION_UID))
                        .build();

        mHandler.execute(report);

        verify(mMockActivityManager).killUid(Process.FIRST_APPLICATION_UID, "Anomaly detected");
    }

    @Test
    public void execute_withSystemUid_doesNotKill() {
        AnomalyReport report =
                new AnomalyReportImpl.Builder(mTestRule)
                        .addAttribute(new UidAttribute(Process.SYSTEM_UID))
                        .build();

        mHandler.execute(report);

        verify(mMockActivityManager, never()).killUid(anyInt(), anyString());
    }

    @Test
    public void execute_withInvalidUid_doesNotKill() {
        AnomalyReport report =
                new AnomalyReportImpl.Builder(mTestRule)
                        .addAttribute(new UidAttribute(INVALID_UID))
                        .build();

        mHandler.execute(report);

        verify(mMockActivityManager, never()).killUid(anyInt(), anyString());
    }

    @Test
    public void execute_reportDoesNotProvideUid_doesNotKill() {
        // Create a plain AnomalyReport that does not implement ProvidesUid implicitly
        // by not setting a UID.
        AnomalyReport report = new AnomalyReportImpl.Builder(mTestRule).build();

        mHandler.execute(report);

        verify(mMockActivityManager, never()).killUid(anyInt(), anyString());
    }
}
