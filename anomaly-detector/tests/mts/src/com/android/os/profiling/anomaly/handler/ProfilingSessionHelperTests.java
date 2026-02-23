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

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;

import android.os.Bundle;

import androidx.test.runner.AndroidJUnit4;

import com.android.os.profiling.anomaly.wrapper.AnomalyProfilingClient;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.function.Consumer;

/** Tests for {@link ProfilingSessionHelper} class */
@RunWith(AndroidJUnit4.class)
public class ProfilingSessionHelperTests {
    @Rule public MockitoRule mMockitoRule = MockitoJUnit.rule();

    @Mock private AnomalyProfilingClient mAnomalyProfilingManager;

    private static final int UID = 123;

    private static final String PACKAGE_NAME = "test.package.name";

    private static final int MAX_SESSION_DURATION_MS = 20000;

    private ProfilingSessionHelper mProfilingSessionHelper;

    @Before
    public void setUp() {
        doNothing().when(mAnomalyProfilingManager).registerCallback(any(Consumer.class));
        mProfilingSessionHelper = new ProfilingSessionHelper(mAnomalyProfilingManager);
    }

    @Test
    public void startProfiling_shouldStart() {
        Bundle sessionParams = new Bundle();
        sessionParams.putBoolean(KEY_SAMPLE_BINDER_ONLY, true);
        ArgumentCaptor<Bundle> bundleArgumentCaptor = ArgumentCaptor.forClass(Bundle.class);

        mProfilingSessionHelper.startProfiling(
                UID,
                PACKAGE_NAME,
                MAX_SESSION_DURATION_MS,
                sessionParams,
                PROFILING_TYPE_STACK_SAMPLING);

        verify(mAnomalyProfilingManager)
                .collectAnomalyProfile(
                        eq(UID),
                        eq(PACKAGE_NAME),
                        eq(PROFILING_TYPE_STACK_SAMPLING),
                        eq(TRIGGER_TYPE_ANOMALY),
                        eq(null),
                        bundleArgumentCaptor.capture());
        Bundle capturedBundle = bundleArgumentCaptor.getValue();
        assertThat(capturedBundle.getBoolean(KEY_SAMPLE_BINDER_ONLY)).isTrue();
    }
}
