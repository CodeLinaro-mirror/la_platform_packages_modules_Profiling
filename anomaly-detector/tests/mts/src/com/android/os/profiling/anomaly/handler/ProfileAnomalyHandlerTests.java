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

package com.android.os.profiling.anomaly.handler;

import static android.os.ProfilingManager.KEY_SAMPLE_BINDER_ONLY;
import static android.os.ProfilingManager.PROFILING_TYPE_STACK_SAMPLING;
import static android.os.ProfilingTrigger.TRIGGER_TYPE_ANOMALY;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.pm.PackageManager;
import android.os.AnomalyProfilingClient;
import android.os.Bundle;
import android.os.profiling.anomaly.RuleInternal;

import androidx.test.runner.AndroidJUnit4;

import com.android.os.profiling.anomaly.attribute.ProfilingParamsAttribute;
import com.android.os.profiling.anomaly.attribute.UidAttribute;
import com.android.os.profiling.anomaly.core.AnomalyReport;
import com.android.os.profiling.anomaly.wrapper.SystemServiceFetcher;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/** Tests for {@link ProfileAnomalyHandler} */
@RunWith(AndroidJUnit4.class)
public class ProfileAnomalyHandlerTests {
    private static final int UID = 123;

    private static final int MAX_SESSION_DURATION_MS = 20000;

    private static final String PACKAGE_NAME = "test.package.name";

    private static final String PACKAGE_NAME_2 = "package.name2";

    private static final String[] SINGLE_PACKAGE_NAME_ARRAY = {PACKAGE_NAME};

    private static final String[] MULTIPLE_PACKAGE_NAME_ARRAY = {PACKAGE_NAME, PACKAGE_NAME_2};

    private static final String[] EMPTY_PACKAGE_NAME_ARRAY = {};

    private static final Bundle sSessionParams = new Bundle();

    static {
        sSessionParams.putBoolean(KEY_SAMPLE_BINDER_ONLY, true);
    }

    private static final ProfilingParamsAttribute PROFILING_PARAMS =
            new ProfilingParamsAttribute(
                    MAX_SESSION_DURATION_MS, PROFILING_TYPE_STACK_SAMPLING, sSessionParams);

    @Rule public MockitoRule mMockitoRule = MockitoJUnit.rule();

    @Mock private AnomalyReport mMockReport;

    @Mock private SystemServiceFetcher mSystemServiceFetcher;

    @Mock private PackageManager mPackageManager;

    @Mock private ProfilingSessionHelper mProfilingSessionHelper;

    @Mock private AnomalyProfilingClient mAnomalyProfilingManager;

    @Mock private RuleInternal mMockRule;

    private ProfileAnomalyHandler mHandler;

    @Before
    public void setUp() {
        mHandler =
                new ProfileAnomalyHandler(
                        mSystemServiceFetcher, mProfilingSessionHelper, mAnomalyProfilingManager);
        when(mSystemServiceFetcher.getPackageManager()).thenReturn(mPackageManager);
        when(mMockReport.get(ProfilingParamsAttribute.class)).thenReturn(PROFILING_PARAMS);
    }

    @Test
    public void execute_normalCondition_shouldStartProfile() {
        when(mMockReport.get(UidAttribute.class)).thenReturn(new UidAttribute(UID));
        when(mMockReport.getRule()).thenReturn(mMockRule);
        when(mPackageManager.getPackagesForUid(UID)).thenReturn(SINGLE_PACKAGE_NAME_ARRAY);

        when(mAnomalyProfilingManager.isTriggerRegistered(UID, PACKAGE_NAME, TRIGGER_TYPE_ANOMALY))
                .thenReturn(true);

        mHandler.execute(mMockReport);

        verify(mProfilingSessionHelper)
                .startProfiling(eq(UID), eq(PACKAGE_NAME), anyInt(), any(), anyInt());
    }

    @Test
    public void execute_multiplePackageName_shouldNotStartProfile() {
        when(mMockReport.get(UidAttribute.class)).thenReturn(new UidAttribute(UID));
        when(mMockReport.getRule()).thenReturn(mMockRule);
        when(mPackageManager.getPackagesForUid(UID)).thenReturn(MULTIPLE_PACKAGE_NAME_ARRAY);

        when(mAnomalyProfilingManager.isTriggerRegistered(UID, PACKAGE_NAME, TRIGGER_TYPE_ANOMALY))
                .thenReturn(true);
        when(mAnomalyProfilingManager.isTriggerRegistered(
                        UID, PACKAGE_NAME_2, TRIGGER_TYPE_ANOMALY))
                .thenReturn(true);

        mHandler.execute(mMockReport);

        verify(mProfilingSessionHelper, never())
                .startProfiling(eq(UID), anyString(), anyInt(), any(), anyInt());
    }

    @Test
    public void execute_noTriggerRegistered_shouldNotStartProfiling() {
        when(mMockReport.get(UidAttribute.class)).thenReturn(new UidAttribute(UID));
        when(mMockReport.getRule()).thenReturn(mMockRule);
        when(mPackageManager.getPackagesForUid(UID)).thenReturn(MULTIPLE_PACKAGE_NAME_ARRAY);

        when(mAnomalyProfilingManager.isTriggerRegistered(UID, PACKAGE_NAME, TRIGGER_TYPE_ANOMALY))
                .thenReturn(false);
        when(mAnomalyProfilingManager.isTriggerRegistered(
                        UID, PACKAGE_NAME_2, TRIGGER_TYPE_ANOMALY))
                .thenReturn(false);

        mHandler.execute(mMockReport);

        verify(mProfilingSessionHelper, never())
                .startProfiling(eq(UID), anyString(), anyInt(), any(), anyInt());
    }

    @Test
    public void execute_noPackageName_shouldNotStartProfiling() {
        when(mMockReport.get(UidAttribute.class)).thenReturn(new UidAttribute(UID));
        when(mMockReport.getRule()).thenReturn(mMockRule);
        when(mPackageManager.getPackagesForUid(UID)).thenReturn(EMPTY_PACKAGE_NAME_ARRAY);

        mHandler.execute(mMockReport);

        verify(mProfilingSessionHelper, never())
                .startProfiling(anyInt(), anyString(), anyInt(), any(), anyInt());
    }

    @Test
    public void execute_noUidAttribute_shouldNotStartProfiling() {
        when(mMockReport.get(UidAttribute.class)).thenReturn(null);
        when(mMockReport.getRule()).thenReturn(mMockRule);
        when(mPackageManager.getPackagesForUid(UID)).thenReturn(EMPTY_PACKAGE_NAME_ARRAY);

        mHandler.execute(mMockReport);

        verify(mProfilingSessionHelper, never())
                .startProfiling(anyInt(), anyString(), anyInt(), any(), anyInt());
    }
}
