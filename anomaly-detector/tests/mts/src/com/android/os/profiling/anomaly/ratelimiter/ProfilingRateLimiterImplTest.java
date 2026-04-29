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

package com.android.os.profiling.anomaly.ratelimiter;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.os.Handler;
import android.os.OutcomeReceiver;
import android.os.profiling.anomaly.RuleInternal.ConditionTypeInternal;

import com.android.os.profiling.anomaly.ratelimiter.persistence.RateLimiterState;
import com.android.os.profiling.anomaly.ratelimiter.persistence.RateLimiterStateStore;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.Map;
import java.util.concurrent.TimeUnit;

@RunWith(JUnit4.class)
public class ProfilingRateLimiterImplTest {

    @Rule public MockitoRule mMockitoRule = MockitoJUnit.rule();

    @Mock private RateLimiterStateStore mMockStateStore;
    @Mock private RateLimiterClock mMockClock;
    @Mock private RateLimiterConfig mTestConfig;
    @Mock private Handler mMockHandler;

    @ConditionTypeInternal private static final String TEST_CONDITION_TYPE = "test_condition";
    private static final int TEST_UID = 10001;
    private static final long MOCK_CURRENT_TIME_MS = 1_000_000L;
    private static final long MOCK_DEVICE_FREQUENCY_WINDOW_MILLIS = TimeUnit.HOURS.toMillis(12);
    private static final int TEST_DEVICE_MAX_REQUESTS = 5;
    private static final long MOCK_UID_COOL_DOWN_MILLIS = TimeUnit.HOURS.toMillis(12);
    private static final long MOCK_SIGNATURE_COOL_DOWN_MILLIS = TimeUnit.HOURS.toMillis(24);
    private static final long MILLISECONDS_IN_SECOND = TimeUnit.SECONDS.toMillis(1);
    private static final String INTERFACE_KEY = "interface";
    private static final String INTERFACE_VAL = "ITest";
    private static final long TEST_WINDOW_MILLIS = TimeUnit.SECONDS.toMillis(10);
    private static final long OLD_TIMESTAMP_OFFSET_MILLIS = TimeUnit.SECONDS.toMillis(20);
    private static final long RECENT_TIMESTAMP_OFFSET_MILLIS = TimeUnit.SECONDS.toMillis(5);
    private static final long MOCK_MAX_COOLDOWN_FOR_EVICTION_MILLIS = TimeUnit.DAYS.toMillis(7);

    private ProfilingRateLimiterImpl mRateLimiter;
    private RateLimiterState mState;

    @Before
    public void setUp() {
        // Initialize with a fresh, empty state for each test.
        mState = RateLimiterState.createEmpty();
        // Default behavior: load state immediately unless overridden by a specific test.
        doAnswer(
                        invocation -> {
                            OutcomeReceiver<RateLimiterState, Throwable> callback =
                                    invocation.getArgument(0);
                            callback.onResult(mState);
                            return null;
                        })
                .when(mMockStateStore)
                .readState(any());

        when(mMockClock.currentTimeMillis()).thenReturn(MOCK_CURRENT_TIME_MS);

        // Configure default mock behaviors for the config.
        when(mTestConfig.getDeviceFrequencyWindowMillis())
                .thenReturn(MOCK_DEVICE_FREQUENCY_WINDOW_MILLIS);
        when(mTestConfig.getDeviceFrequencyMaxCount()).thenReturn(TEST_DEVICE_MAX_REQUESTS);
        when(mTestConfig.getUidCoolDownMillis()).thenReturn(MOCK_UID_COOL_DOWN_MILLIS);
        when(mTestConfig.getSignatureCoolDownMillis(any(), any()))
                .thenReturn(MOCK_SIGNATURE_COOL_DOWN_MILLIS);
        when(mTestConfig.getMaxCoolDownForEvictionMillis())
                .thenReturn(MOCK_MAX_COOLDOWN_FOR_EVICTION_MILLIS);

        doAnswer(
                        invocation -> {
                            Runnable task = invocation.getArgument(0);
                            task.run();
                            return null;
                        })
                .when(mMockHandler)
                .postDelayed(any(Runnable.class), anyLong());

        mRateLimiter =
                new ProfilingRateLimiterImpl(
                        mMockStateStore, mMockClock, mTestConfig, mMockHandler);
    }

    @Test
    public void isRequestAllowed_whileLoading_isDenied() {
        // 1. Setup a RateLimiter that hasn't finished loading yet.
        // We need a new mock and instance because setUp() already initialized one that finished
        // loading.
        RateLimiterStateStore delayedStore = org.mockito.Mockito.mock(RateLimiterStateStore.class);
        // Do NOT call the callback immediately.
        ProfilingRateLimiterImpl limiter =
                new ProfilingRateLimiterImpl(delayedStore, mMockClock, mTestConfig, mMockHandler);

        // 2. Verify request is denied.
        boolean allowed = limiter.isRequestAllowed(TEST_UID, TEST_CONDITION_TYPE, null);
        assertThat(allowed).isFalse();

        // 3. Verify no state was written (since it wasn't allowed).
        verify(delayedStore, never()).writeState(any());
    }

    @Test
    public void isRequestAllowed_firstRequest_isAllowed() {
        boolean allowed = mRateLimiter.isRequestAllowed(TEST_UID, TEST_CONDITION_TYPE, null);
        assertThat(allowed).isTrue();

        ArgumentCaptor<RateLimiterState> captor = ArgumentCaptor.forClass(RateLimiterState.class);
        verify(mMockStateStore).writeState(captor.capture());
        RateLimiterState savedState = captor.getValue();

        assertThat(savedState.getDeviceTimestamps()).containsExactly(MOCK_CURRENT_TIME_MS);
        assertThat(savedState.getUidTimestamps().get(TEST_UID)).isEqualTo(MOCK_CURRENT_TIME_MS);
    }

    @Test
    public void isRequestAllowed_deviceFrequencyLimitExceeded_isDenied() {
        // Exceed the device-wide limit.
        for (int i = 0; i < TEST_DEVICE_MAX_REQUESTS; i++) {
            mState.getDeviceTimestamps().add(MOCK_CURRENT_TIME_MS - i * MILLISECONDS_IN_SECOND);
        }
        boolean allowed = mRateLimiter.isRequestAllowed(TEST_UID, TEST_CONDITION_TYPE, null);
        assertThat(allowed).isFalse();
        verify(mMockStateStore, never()).writeState(any());
    }

    @Test
    public void isRequestAllowed_uidInCoolDown_isDenied() {
        mState.getUidTimestamps()
                .put(TEST_UID, MOCK_CURRENT_TIME_MS - MILLISECONDS_IN_SECOND); // Recently recorded
        boolean allowed = mRateLimiter.isRequestAllowed(TEST_UID, TEST_CONDITION_TYPE, null);
        assertThat(allowed).isFalse();
        verify(mMockStateStore, never()).writeState(any());
    }

    @Test
    public void isRequestAllowed_signatureInCoolDown_isDenied() {
        Map<String, String> signature = Map.of(INTERFACE_KEY, INTERFACE_VAL);
        String key = ProfilingRateLimiterImpl.generateCanonicalKey(TEST_UID, signature);
        mState.getSignatureTimestamps().put(key, MOCK_CURRENT_TIME_MS - MILLISECONDS_IN_SECOND);

        boolean allowed = mRateLimiter.isRequestAllowed(TEST_UID, TEST_CONDITION_TYPE, signature);

        assertThat(allowed).isFalse();
        verify(mMockStateStore, never()).writeState(any());
    }

    @Test
    public void isRequestAllowed_allChecksPass_isAllowedAndStateUpdated() {
        Map<String, String> signature = Map.of(INTERFACE_KEY, INTERFACE_VAL);
        boolean allowed = mRateLimiter.isRequestAllowed(TEST_UID, TEST_CONDITION_TYPE, signature);

        assertThat(allowed).isTrue();

        ArgumentCaptor<RateLimiterState> captor = ArgumentCaptor.forClass(RateLimiterState.class);
        verify(mMockStateStore).writeState(captor.capture());
        RateLimiterState savedState = captor.getValue();

        // Verify all relevant timestamps are updated.
        assertThat(savedState.getDeviceTimestamps()).containsExactly(MOCK_CURRENT_TIME_MS);
        assertThat(savedState.getUidTimestamps().get(TEST_UID)).isEqualTo(MOCK_CURRENT_TIME_MS);
        String key = ProfilingRateLimiterImpl.generateCanonicalKey(TEST_UID, signature);
        assertThat(savedState.getSignatureTimestamps().get(key)).isEqualTo(MOCK_CURRENT_TIME_MS);
    }

    @Test
    public void isRequestAllowed_oldTimestampsAreCleanedUp_isAllowed() {
        when(mTestConfig.getDeviceFrequencyWindowMillis()).thenReturn(TEST_WINDOW_MILLIS); // 10s

        // Record a request that is old enough to be outside the window.
        mState.getDeviceTimestamps()
                .add(MOCK_CURRENT_TIME_MS - OLD_TIMESTAMP_OFFSET_MILLIS); // 20s ago

        // Fill the rest of the quota with recent requests.
        for (int i = 0; i < TEST_DEVICE_MAX_REQUESTS - 1; i++) {
            mState.getDeviceTimestamps()
                    .add(MOCK_CURRENT_TIME_MS - RECENT_TIMESTAMP_OFFSET_MILLIS); // 5s ago
        }

        // The limiter should clean up the old timestamp and allow the new request.
        boolean allowed = mRateLimiter.isRequestAllowed(TEST_UID, TEST_CONDITION_TYPE, null);
        assertThat(allowed).isTrue();
    }

    @Test
    public void onTimeChanged_adoptsConservativeStateAndRecovers() {
        // 1. Setup an initial state and trigger a time change.
        mRateLimiter.onTimeChanged();

        // 2. Verify that a new request is immediately denied ("fail-closed").
        boolean allowedImmediatelyAfter =
                mRateLimiter.isRequestAllowed(TEST_UID, TEST_CONDITION_TYPE, null);
        assertThat(allowedImmediatelyAfter).isFalse();

        // 3. Advance time just past the device frequency window by updating the mock.
        when(mMockClock.currentTimeMillis())
                .thenReturn(MOCK_CURRENT_TIME_MS + MOCK_DEVICE_FREQUENCY_WINDOW_MILLIS + 1);

        // 4. Verify that a new request is now allowed, showing the system has recovered.
        boolean allowedAfterWindow =
                mRateLimiter.isRequestAllowed(TEST_UID + 1, TEST_CONDITION_TYPE, null);
        assertThat(allowedAfterWindow).isTrue();
    }

    @Test
    public void isRequestAllowed_oldTimestampsEvicted_fromUidAndSignatureMaps() {
        long oldTime = MOCK_CURRENT_TIME_MS - MOCK_MAX_COOLDOWN_FOR_EVICTION_MILLIS - 1000;
        mState.getUidTimestamps().put(TEST_UID + 1, oldTime);

        String oldSignatureKey = (TEST_UID + 1) + "|old=sig;";
        mState.getSignatureTimestamps().put(oldSignatureKey, oldTime);

        boolean allowed = mRateLimiter.isRequestAllowed(TEST_UID, TEST_CONDITION_TYPE, null);
        assertThat(allowed).isTrue();

        ArgumentCaptor<RateLimiterState> captor = ArgumentCaptor.forClass(RateLimiterState.class);
        verify(mMockStateStore).writeState(captor.capture());
        RateLimiterState savedState = captor.getValue();

        assertThat(savedState.getUidTimestamps().containsKey(TEST_UID + 1)).isFalse();
        assertThat(savedState.getSignatureTimestamps().containsKey(oldSignatureKey)).isFalse();
    }

    @Test
    public void isRequestAllowed_zeroCooldown_allowedEvenIfTimeGoesBackwards() {
        // Set cooldown to 0
        when(mTestConfig.getUidCoolDownMillis()).thenReturn(0L);
        when(mTestConfig.getSignatureCoolDownMillis(any(), any())).thenReturn(0L);

        // Record an initial request at MOCK_CURRENT_TIME_MS
        mState.getUidTimestamps().put(TEST_UID, MOCK_CURRENT_TIME_MS);

        Map<String, String> signature = Map.of(INTERFACE_KEY, INTERFACE_VAL);
        String signatureKey = ProfilingRateLimiterImpl.generateCanonicalKey(TEST_UID, signature);
        mState.getSignatureTimestamps().put(signatureKey, MOCK_CURRENT_TIME_MS);

        // Move time backwards!
        long pastTimeMs = MOCK_CURRENT_TIME_MS - 5000L;
        when(mMockClock.currentTimeMillis()).thenReturn(pastTimeMs);

        // Verify request is allowed despite time being < lastRequestTime
        boolean allowed = mRateLimiter.isRequestAllowed(TEST_UID, TEST_CONDITION_TYPE, signature);
        assertThat(allowed).isTrue();
    }
}
