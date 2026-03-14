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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.os.AnomalyProfilingClient;
import android.os.AnomalyRequestResult;
import android.os.Bundle;
import android.os.ProfilingResult;
import android.os.profiling.anomaly.RuleInternal;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.UUID;
import java.util.function.Consumer;

/** Tests for {@link ProfilingSessionHelper} class */
@RunWith(AndroidJUnit4.class)
public class ProfilingSessionHelperTests {
    @Rule public MockitoRule mMockitoRule = MockitoJUnit.rule();

    @Mock private AnomalyProfilingClient mAnomalyProfilingManager;

    private static final int UID = 123;

    private static final String PACKAGE_NAME = "test.package.name";

    private static final String FAKE_CONDITION_TYPE = "FAKE_CONDITION_TYPE";

    private static final int MAX_SESSION_DURATION_MS = 20000;

    private ProfilingSessionHelper mProfilingSessionHelper;

    @Before
    public void setUp() {
        doNothing().when(mAnomalyProfilingManager).registerCallback(any(Consumer.class));
        mProfilingSessionHelper = new ProfilingSessionHelper(mAnomalyProfilingManager);
    }

    @Test
    public void requestProfiling_shouldStartOrAccept() {
        Bundle sessionParams = new Bundle();
        sessionParams.putBoolean(KEY_SAMPLE_BINDER_ONLY, true);
        ArgumentCaptor<Bundle> bundleArgumentCaptor = ArgumentCaptor.forClass(Bundle.class);

        mProfilingSessionHelper.requestProfiling(
                UID,
                PACKAGE_NAME,
                MAX_SESSION_DURATION_MS,
                sessionParams,
                PROFILING_TYPE_STACK_SAMPLING,
                RuleInternal.CONDITION_TYPE_BINDER_SPAM);

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

    @Test
    public void requestProfiling_Session_aSecondTimeForTheSameUid_shouldMarkSessionAcceptable() {
        Bundle sessionParams = new Bundle();
        sessionParams.putBoolean(KEY_SAMPLE_BINDER_ONLY, true);
        ArgumentCaptor<Bundle> bundleArgumentCaptor = ArgumentCaptor.forClass(Bundle.class);

        mProfilingSessionHelper.requestProfiling(
                UID,
                PACKAGE_NAME,
                MAX_SESSION_DURATION_MS,
                sessionParams,
                PROFILING_TYPE_STACK_SAMPLING,
                RuleInternal.CONDITION_TYPE_BINDER_SPAM);
        mProfilingSessionHelper.requestProfiling(
                UID,
                PACKAGE_NAME,
                MAX_SESSION_DURATION_MS,
                sessionParams,
                PROFILING_TYPE_STACK_SAMPLING,
                RuleInternal.CONDITION_TYPE_BINDER_SPAM);

        verify(mAnomalyProfilingManager, times(1))
                .collectAnomalyProfile(
                        eq(UID),
                        eq(PACKAGE_NAME),
                        eq(PROFILING_TYPE_STACK_SAMPLING),
                        eq(TRIGGER_TYPE_ANOMALY),
                        eq(null),
                        bundleArgumentCaptor.capture());
        assertThat(mProfilingSessionHelper.mUidSessionInfoSparseArray.get(UID)).isNotNull();
        assertThat(mProfilingSessionHelper.mUidSessionInfoSparseArray.get(UID).shouldAccept())
                .isTrue();
    }

    @Test
    public void requestProfiling_Session_whenNotBinderSpam_shouldMarkSessionAcceptable() {
        Bundle sessionParams = new Bundle();
        sessionParams.putBoolean(KEY_SAMPLE_BINDER_ONLY, true);
        ArgumentCaptor<Bundle> bundleArgumentCaptor = ArgumentCaptor.forClass(Bundle.class);

        mProfilingSessionHelper.requestProfiling(
                UID,
                PACKAGE_NAME,
                MAX_SESSION_DURATION_MS,
                sessionParams,
                PROFILING_TYPE_STACK_SAMPLING,
                FAKE_CONDITION_TYPE);

        verify(mAnomalyProfilingManager, times(1))
                .collectAnomalyProfile(
                        eq(UID),
                        eq(PACKAGE_NAME),
                        eq(PROFILING_TYPE_STACK_SAMPLING),
                        eq(TRIGGER_TYPE_ANOMALY),
                        eq(null),
                        bundleArgumentCaptor.capture());
        assertThat(mProfilingSessionHelper.mUidSessionInfoSparseArray.get(UID)).isNotNull();
        assertThat(mProfilingSessionHelper.mUidSessionInfoSparseArray.get(UID).shouldAccept())
                .isTrue();
    }

    @Test
    public void handleSessionResult_shouldRemoveSessionInfo() {
        Bundle sessionParams = new Bundle();
        sessionParams.putBoolean(KEY_SAMPLE_BINDER_ONLY, true);

        mProfilingSessionHelper.requestProfiling(
                UID,
                PACKAGE_NAME,
                MAX_SESSION_DURATION_MS,
                sessionParams,
                PROFILING_TYPE_STACK_SAMPLING,
                RuleInternal.CONDITION_TYPE_BINDER_SPAM);
        mProfilingSessionHelper.handleSessionResult(
                new AnomalyRequestResult(
                        new UUID(789L, 456L),
                        UID,
                        ProfilingResult.ERROR_NONE,
                        "TestPath",
                        "TestTag",
                        TRIGGER_TYPE_ANOMALY));

        assertThat(mProfilingSessionHelper.mUidSessionInfoSparseArray.contains(UID)).isFalse();
    }

    @Test
    public void handleSessionResult_shouldSendAnomalyProfile() {
        Bundle sessionParams = new Bundle();
        sessionParams.putBoolean(KEY_SAMPLE_BINDER_ONLY, true);
        String binderSpamConditionType =
                RuleInternal.CONDITION_TYPE_BINDER_SPAM.substring(
                        RuleInternal.CONDITION_TYPE_BINDER_SPAM.lastIndexOf('.') + 1);

        mProfilingSessionHelper.requestProfiling(
                UID,
                PACKAGE_NAME,
                MAX_SESSION_DURATION_MS,
                sessionParams,
                PROFILING_TYPE_STACK_SAMPLING,
                RuleInternal.CONDITION_TYPE_BINDER_SPAM);
        mProfilingSessionHelper.requestProfiling(
                UID,
                PACKAGE_NAME,
                MAX_SESSION_DURATION_MS,
                sessionParams,
                PROFILING_TYPE_STACK_SAMPLING,
                RuleInternal.CONDITION_TYPE_BINDER_SPAM);
        mProfilingSessionHelper.handleSessionResult(
                new AnomalyRequestResult(
                        new UUID(789L, 456L),
                        UID,
                        ProfilingResult.ERROR_NONE,
                        "TestPath",
                        "TestTag",
                        TRIGGER_TYPE_ANOMALY));

        ArgumentCaptor<String> stringArgumentCaptor = ArgumentCaptor.forClass(String.class);
        verify(mAnomalyProfilingManager)
                .sendAnomalyProfile(
                        eq(UID),
                        eq(PACKAGE_NAME),
                        eq(TRIGGER_TYPE_ANOMALY),
                        eq(binderSpamConditionType),
                        stringArgumentCaptor.capture());
        assertThat(stringArgumentCaptor.getValue()).contains(PACKAGE_NAME);
        assertThat(stringArgumentCaptor.getValue()).contains(".zip");
    }
}
