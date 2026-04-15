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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.os.AnomalyProfilingClient;
import android.os.AnomalyRequestResult;
import android.os.Bundle;
import android.os.ProfilingResult;
import android.os.profiling.anomaly.RuleInternal;
import android.profiling.utils.PerfettoMetadata;

import androidx.test.runner.AndroidJUnit4;

import com.android.os.profiling.anomaly.config.ProfilingConcurrencyConfig;
import com.android.os.profiling.anomaly.ratelimiter.ProfilingRateLimiter;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Consumer;

/** Tests for {@link ProfilingSessionHelper} class */
@RunWith(AndroidJUnit4.class)
public class ProfilingSessionHelperTests {
    @Rule(order = 0) public MockitoRule mMockitoRule = MockitoJUnit.rule();

    @Rule(order = 1)
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Mock private AnomalyProfilingClient mAnomalyProfilingManager;
    @Mock private ProfilingRateLimiter mMockProfilingRateLimiter;
    @Mock private ProfilingConcurrencyConfig mMockProfilingConcurrencyConfig;

    private PerfettoMetadata.AnomalyDetails mAnomalyDetails = null;

    private static final int UID = 123;
    private static final int UID_2 = 456;

    private static final String PACKAGE_NAME = "test.package.name";

    private static final String FAKE_CONDITION_TYPE = "FAKE_CONDITION_TYPE";

    private static final String FAKE_INTERFACE_NAME = "fake.interface.name";
    private static final String FAKE_METHOD_NAME = "fakeMethodName";

    private static final String FAKE_RESULT_FILE_NAME = PACKAGE_NAME + ".perfetto-trace";
    private static final String FAKE_RESULT_CONTENT = "TestContent";
    private static final String FAKE_RESULT_TAG = "TestTag";

    private static final int MAX_SESSION_DURATION_MS = 20000;

    private static final int CONCURRENT_SESSIONS_LIMIT = 1;

    private static final long ANOMALY_DURATION_MS = 1000L;

    private static final int ANOMALY_TYPE_INDEX =
            RuleInternal.CONDITION_TYPE_BINDER_SPAM.lastIndexOf('.') + 1;
    private static final String EXPECTED_ANOMALY_TYPE =
            RuleInternal.CONDITION_TYPE_BINDER_SPAM.substring(ANOMALY_TYPE_INDEX);

    private ProfilingSessionHelper mProfilingSessionHelper;

    @Before
    public void setUp() throws Exception {
        doNothing().when(mAnomalyProfilingManager).registerCallback(any(Consumer.class));
        mAnomalyDetails =
                PerfettoMetadata.AnomalyDetails.ofBinderSpam(
                        FAKE_INTERFACE_NAME, FAKE_METHOD_NAME, 1.0, 2.0);
        mProfilingSessionHelper =
                new ProfilingSessionHelper(
                        mAnomalyProfilingManager,
                        r -> r.run());
        when(mMockProfilingRateLimiter.isRequestAllowed(anyInt(), any(), any())).thenReturn(true);
        when(mMockProfilingConcurrencyConfig.getDeviceMaxConcurrentSessions())
                .thenReturn(CONCURRENT_SESSIONS_LIMIT);
        when(mAnomalyProfilingManager.collectAnomalyProfile(
                        anyInt(), any(), anyInt(), anyInt(), any(), any()))
                .thenReturn(UUID.randomUUID());
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
                RuleInternal.CONDITION_TYPE_BINDER_SPAM,
                mMockProfilingRateLimiter,
                /* signature= */ null,
                mMockProfilingConcurrencyConfig,
                mAnomalyDetails,
                ANOMALY_DURATION_MS);

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
                RuleInternal.CONDITION_TYPE_BINDER_SPAM,
                mMockProfilingRateLimiter,
                /* signature= */ null,
                mMockProfilingConcurrencyConfig,
                mAnomalyDetails,
                ANOMALY_DURATION_MS);

        assertThat(mProfilingSessionHelper.mUidSessionInfoSparseArray.get(UID).shouldAccept())
                .isFalse();

        mProfilingSessionHelper.requestProfiling(
                UID,
                PACKAGE_NAME,
                MAX_SESSION_DURATION_MS,
                sessionParams,
                PROFILING_TYPE_STACK_SAMPLING,
                RuleInternal.CONDITION_TYPE_BINDER_SPAM,
                mMockProfilingRateLimiter,
                /* signature= */ null,
                mMockProfilingConcurrencyConfig,
                mAnomalyDetails,
                ANOMALY_DURATION_MS);

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
    public void requestProfiling_ongoingSessionForTheSameUid_updateSessionInfoCorrectly()
                throws Exception {
        Bundle sessionParams = new Bundle();
        sessionParams.putBoolean(KEY_SAMPLE_BINDER_ONLY, true);

        mProfilingSessionHelper.requestProfiling(
                UID,
                PACKAGE_NAME,
                MAX_SESSION_DURATION_MS,
                sessionParams,
                PROFILING_TYPE_STACK_SAMPLING,
                RuleInternal.CONDITION_TYPE_BINDER_SPAM,
                mMockProfilingRateLimiter,
                /* signature= */ null,
                mMockProfilingConcurrencyConfig,
                mAnomalyDetails,
                ANOMALY_DURATION_MS);
        mProfilingSessionHelper.requestProfiling(
                UID,
                PACKAGE_NAME,
                MAX_SESSION_DURATION_MS,
                sessionParams,
                PROFILING_TYPE_STACK_SAMPLING,
                RuleInternal.CONDITION_TYPE_BINDER_SPAM,
                mMockProfilingRateLimiter,
                /* signature= */ null,
                mMockProfilingConcurrencyConfig,
                mAnomalyDetails,
                ANOMALY_DURATION_MS);

        ProfilingSessionHelper.SessionInfo sessionInfo =
                mProfilingSessionHelper.mUidSessionInfoSparseArray.get(UID);
        assertThat(sessionInfo).isNotNull();
        assertThat(sessionInfo.shouldAccept()).isTrue();
        String actualPerfettoMetadataString = sessionInfo.perfettoMetadata().toString();
        assertThat(actualPerfettoMetadataString).contains(String.valueOf(UID));
        assertThat(actualPerfettoMetadataString).contains(PACKAGE_NAME);
        assertThat(actualPerfettoMetadataString).contains(EXPECTED_ANOMALY_TYPE);
        assertThat(actualPerfettoMetadataString).contains(FAKE_INTERFACE_NAME);
        assertThat(actualPerfettoMetadataString).contains(FAKE_METHOD_NAME);
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
                FAKE_CONDITION_TYPE,
                mMockProfilingRateLimiter,
                /* signature= */ null,
                mMockProfilingConcurrencyConfig,
                mAnomalyDetails,
                ANOMALY_DURATION_MS);

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
    public void updateOngoingSessionInfo_withMatchingConditionType_updatesSessionInfo() {
        UUID sessionId = new UUID(1L, 2L);
        PerfettoMetadata metadata = new PerfettoMetadata();
        Instant startTime = Instant.ofEpochMilli(System.currentTimeMillis());
        ProfilingSessionHelper.SessionInfo initialSessionInfo =
                new ProfilingSessionHelper.SessionInfo(
                        sessionId,
                        UID,
                        PACKAGE_NAME,
                        RuleInternal.CONDITION_TYPE_BINDER_SPAM,
                        startTime,
                        /* result= */ null,
                        /* shouldAccept= */ false,
                        metadata);
        mProfilingSessionHelper.mUidSessionInfoSparseArray.put(UID, initialSessionInfo);

        mProfilingSessionHelper.updateOngoingSessionInfo(
                UID,
                RuleInternal.CONDITION_TYPE_BINDER_SPAM,
                PACKAGE_NAME,
                mAnomalyDetails,
                ANOMALY_DURATION_MS);

        ProfilingSessionHelper.SessionInfo updatedSessionInfo =
                mProfilingSessionHelper.mUidSessionInfoSparseArray.get(UID);
        assertThat(updatedSessionInfo.shouldAccept()).isTrue();
        String actualPerfettoMetadataString = updatedSessionInfo.perfettoMetadata().toString();
        assertThat(actualPerfettoMetadataString).contains(String.valueOf(UID));
        assertThat(actualPerfettoMetadataString).contains(PACKAGE_NAME);
        assertThat(actualPerfettoMetadataString).contains(EXPECTED_ANOMALY_TYPE);
        assertThat(actualPerfettoMetadataString).contains(FAKE_INTERFACE_NAME);
        assertThat(actualPerfettoMetadataString).contains(FAKE_METHOD_NAME);
    }

    @Test
    public void updateOngoingSessionInfo_withMismatchedConditionType_doesNotUpdateSessionInfo() {
        UUID sessionId = new UUID(1L, 2L);
        PerfettoMetadata metadata = new PerfettoMetadata();
        Instant startTime = Instant.ofEpochMilli(System.currentTimeMillis());
        ProfilingSessionHelper.SessionInfo initialSessionInfo =
                new ProfilingSessionHelper.SessionInfo(
                        sessionId,
                        UID,
                        PACKAGE_NAME,
                        RuleInternal.CONDITION_TYPE_BINDER_SPAM,
                        startTime,
                        /* result= */ null,
                        /* shouldAccept= */ false,
                        metadata);
        mProfilingSessionHelper.mUidSessionInfoSparseArray.put(UID, initialSessionInfo);

        mProfilingSessionHelper.updateOngoingSessionInfo(
                UID,
                FAKE_CONDITION_TYPE,
                PACKAGE_NAME,
                mAnomalyDetails,
                ANOMALY_DURATION_MS);

        ProfilingSessionHelper.SessionInfo updatedSessionInfo =
                mProfilingSessionHelper.mUidSessionInfoSparseArray.get(UID);
        assertThat(updatedSessionInfo.shouldAccept()).isFalse();
        String actualPerfettoMetadataString = updatedSessionInfo.perfettoMetadata().toString();
        assertThat(actualPerfettoMetadataString).doesNotContain(String.valueOf(UID));
        assertThat(actualPerfettoMetadataString).doesNotContain(PACKAGE_NAME);
        assertThat(actualPerfettoMetadataString).doesNotContain(EXPECTED_ANOMALY_TYPE);
        assertThat(actualPerfettoMetadataString).doesNotContain(FAKE_INTERFACE_NAME);
        assertThat(actualPerfettoMetadataString).doesNotContain(FAKE_METHOD_NAME);
    }

    @Test
    public void updateOngoingSessionInfo_noOngoingSession_doesNothing() {
        mProfilingSessionHelper.updateOngoingSessionInfo(
                UID,
                RuleInternal.CONDITION_TYPE_BINDER_SPAM,
                PACKAGE_NAME,
                mAnomalyDetails,
                ANOMALY_DURATION_MS);

        assertThat(mProfilingSessionHelper.mUidSessionInfoSparseArray.get(UID)).isNull();
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
                RuleInternal.CONDITION_TYPE_BINDER_SPAM,
                mMockProfilingRateLimiter,
                /* signature= */ null,
                mMockProfilingConcurrencyConfig,
                mAnomalyDetails,
                ANOMALY_DURATION_MS);
        mProfilingSessionHelper.handleSessionResult(
                new AnomalyRequestResult(
                        new UUID(789L, 456L),
                        UID,
                        ProfilingResult.ERROR_NONE,
                        FAKE_RESULT_FILE_NAME,
                        FAKE_RESULT_TAG,
                        TRIGGER_TYPE_ANOMALY));

        assertThat(mProfilingSessionHelper.mUidSessionInfoSparseArray.contains(UID)).isFalse();
    }

    @Test
    public void handleSessionResult_shouldSendAnomalyProfile() throws Exception {
        File fakeResultFile = temporaryFolder.newFile(FAKE_RESULT_FILE_NAME);
        fakeResultFile.createNewFile();
        fakeResultFile.setReadable(true);
        try (FileOutputStream fos = new FileOutputStream(fakeResultFile)) {
            fos.write(FAKE_RESULT_CONTENT.getBytes(StandardCharsets.UTF_8));
        }
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
                RuleInternal.CONDITION_TYPE_BINDER_SPAM,
                mMockProfilingRateLimiter,
                /* signature= */ null,
                mMockProfilingConcurrencyConfig,
                mAnomalyDetails,
                ANOMALY_DURATION_MS);
        mProfilingSessionHelper.requestProfiling(
                UID,
                PACKAGE_NAME,
                MAX_SESSION_DURATION_MS,
                sessionParams,
                PROFILING_TYPE_STACK_SAMPLING,
                RuleInternal.CONDITION_TYPE_BINDER_SPAM,
                mMockProfilingRateLimiter,
                /* signature= */ null,
                mMockProfilingConcurrencyConfig,
                mAnomalyDetails,
                ANOMALY_DURATION_MS);
        mProfilingSessionHelper.handleSessionResult(
                new AnomalyRequestResult(
                        new UUID(789L, 456L),
                        UID,
                        ProfilingResult.ERROR_NONE,
                        fakeResultFile.getAbsolutePath(),
                        FAKE_RESULT_TAG,
                        TRIGGER_TYPE_ANOMALY));

        ArgumentCaptor<String> stringArgumentCaptor = ArgumentCaptor.forClass(String.class);
        verify(mAnomalyProfilingManager)
                .sendAnomalyProfile(
                        eq(UID),
                        eq(PACKAGE_NAME),
                        eq(TRIGGER_TYPE_ANOMALY),
                        eq(binderSpamConditionType),
                        stringArgumentCaptor.capture());
        assertThat(stringArgumentCaptor.getValue())
                .isEqualTo(FAKE_RESULT_FILE_NAME + "-metadata.zip");
        assertThat(stringArgumentCaptor.getValue()).doesNotContain("/");
    }

    @Test
    public void requestProfiling_rateLimited_shouldNotStartProfiling() {
        when(mMockProfilingRateLimiter.isRequestAllowed(anyInt(), any(), any())).thenReturn(false);

        Bundle sessionParams = new Bundle();
        sessionParams.putBoolean(KEY_SAMPLE_BINDER_ONLY, true);

        mProfilingSessionHelper.requestProfiling(
                UID,
                PACKAGE_NAME,
                MAX_SESSION_DURATION_MS,
                sessionParams,
                PROFILING_TYPE_STACK_SAMPLING,
                RuleInternal.CONDITION_TYPE_BINDER_SPAM,
                mMockProfilingRateLimiter,
                /* signature= */ null,
                mMockProfilingConcurrencyConfig,
                mAnomalyDetails,
                ANOMALY_DURATION_MS);

        verify(mAnomalyProfilingManager, never())
                .collectAnomalyProfile(anyInt(), any(), anyInt(), anyInt(), any(), any());
    }

    @Test
    public void requestProfiling_concurrencyLimitReached_shouldNotStartProfiling() {
        when(mMockProfilingConcurrencyConfig.getDeviceMaxConcurrentSessions())
                .thenReturn(CONCURRENT_SESSIONS_LIMIT);

        // Start one session, which should succeed.
        mProfilingSessionHelper.requestProfiling(
                UID,
                PACKAGE_NAME,
                MAX_SESSION_DURATION_MS,
                new Bundle(),
                PROFILING_TYPE_STACK_SAMPLING,
                RuleInternal.CONDITION_TYPE_BINDER_SPAM,
                mMockProfilingRateLimiter,
                /* signature= */ null,
                mMockProfilingConcurrencyConfig,
                mAnomalyDetails,
                ANOMALY_DURATION_MS);

        verify(mAnomalyProfilingManager, times(1))
                .collectAnomalyProfile(anyInt(), any(), anyInt(), anyInt(), any(), any());

        // Attempt to start a second session, which should be denied due to the concurrency limit.
        mProfilingSessionHelper.requestProfiling(
                UID_2,
                PACKAGE_NAME,
                MAX_SESSION_DURATION_MS,
                new Bundle(),
                PROFILING_TYPE_STACK_SAMPLING,
                RuleInternal.CONDITION_TYPE_BINDER_SPAM,
                mMockProfilingRateLimiter,
                /* signature= */ null,
                mMockProfilingConcurrencyConfig,
                mAnomalyDetails,
                ANOMALY_DURATION_MS);

        // Verify that collectAnomalyProfile was not called a second time.
        verify(mAnomalyProfilingManager, times(1))
                .collectAnomalyProfile(anyInt(), any(), anyInt(), anyInt(), any(), any());
    }
}
