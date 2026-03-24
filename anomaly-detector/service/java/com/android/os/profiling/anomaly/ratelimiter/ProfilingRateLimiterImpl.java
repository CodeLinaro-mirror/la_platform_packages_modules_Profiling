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

import android.annotation.Nullable;
import android.os.Handler;
import android.os.OutcomeReceiver;
import android.os.profiling.anomaly.RuleInternal.ConditionTypeInternal;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.os.profiling.anomaly.ratelimiter.persistence.RateLimiterState;
import com.android.os.profiling.anomaly.ratelimiter.persistence.RateLimiterStateStore;
import com.android.os.profiling.anomaly.util.LogUtil;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * Concrete implementation of the {@link ProfilingRateLimiter} interface.
 *
 * <p>This class orchestrates the rate-limiting logic by checking requests against a hierarchy of
 * rules: device-wide, per-UID, and per-UID plus anomaly attributes. It maintains the history of
 * profiling requests in an in-memory cache ({@link RateLimiterState}) and coordinates with a {@link
 * RateLimiterStateStore} to persist this state to disk, ensuring that cool-down periods are
 * respected even across device reboots.
 *
 * <p>Dependencies such as the clock, configuration, and state store are injected to facilitate
 * testing. This class is thread-safe.
 *
 * @hide
 */
public final class ProfilingRateLimiterImpl implements ProfilingRateLimiter {
    private static final LogUtil sLog = new LogUtil("ProfilingRateLimiterImpl");

    private final RateLimiterStateStore mStateStore;
    private final RateLimiterClock mClock;
    private final RateLimiterConfig mConfig;
    private final Handler mHandler;
    private final Runnable mWriteStateRunnable = this::writeState;

    // In-memory cache of the state to avoid constant disk I/O.
    @GuardedBy("mStateLock")
    private RateLimiterState mState;

    @GuardedBy("mStateLock")
    private boolean mIsStateLoaded = false;

    @GuardedBy("mStateLock")
    private boolean mTimeHasChangedDuringLoad = false;

    @GuardedBy("mStateLock")
    private boolean mStateDirty = false;

    private final Object mStateLock = new Object();

    public ProfilingRateLimiterImpl(
            RateLimiterStateStore stateStore,
            RateLimiterClock clock,
            RateLimiterConfig config,
            Handler handler) {
        mStateStore = stateStore;
        mClock = clock;
        mConfig = config;
        mHandler = handler;
        // Load the initial state from disk.
        mState = RateLimiterState.createEmpty();
        mStateStore.readState(
                new OutcomeReceiver<>() {
                    @Override
                    public void onResult(RateLimiterState result) {
                        synchronized (mStateLock) {
                            if (mTimeHasChangedDuringLoad) {
                                // A time change occurred while we were reading from disk.
                                // Discard the stale result and apply the conservative state.
                                sLog.w("Discarding stale state from disk due to time change.");
                                mState = RateLimiterState.createEmpty();
                                applyConservativeResetToStateLocked(mState);
                            } else {
                                mState = result;
                            }
                            mIsStateLoaded = true;
                        }
                    }

                    @Override
                    public void onError(Throwable error) {
                        sLog.e("Failed to read state asynchronously, failing closed.", error);
                        // On error, apply a conservative "fail-closed" state to prevent allowing
                        // all requests. This blocks new requests until the state can be
                        // successfully written again.
                        synchronized (mStateLock) {
                            applyConservativeResetToStateLocked(mState);
                            mIsStateLoaded = true;
                        }
                    }
                });
    }

    @Override
    public boolean isRequestAllowed(
            int uid,
            @ConditionTypeInternal String conditionType,
            @Nullable Map<String, String> signature) {
        synchronized (mStateLock) {
            if (!mIsStateLoaded) {
                sLog.i("RateLimiter state not loaded yet. Denying request for UID " + uid);
                return false;
            }

            long now = mClock.currentTimeMillis();

            // --- Level 1: Device-Wide Checks ---
            if (isDeviceFrequencyLimitExceeded(now)) {
                sLog.i("Device frequency limit exceeded.");
                return false;
            }

            // --- Level 2: Per-UID Check ---
            if (isUidCoolDownActive(uid, now)) {
                sLog.i("UID " + uid + " is in cool down.");
                return false;
            }

            // --- Level 3: Per-UID + Signature Check (if applicable) ---
            if (signature != null && !signature.isEmpty()) {
                if (isSignatureCoolDownActive(uid, conditionType, signature, now)) {
                    sLog.i("UID " + uid + " with signature is in cool down.");
                    return false;
                }
            }

            // --- If all checks passed, the request is allowed. Now, update the state. ---
            recordSuccessfulRequest(uid, signature, now);

            return true;
        }
    }

    @Override
    public void onTimeChanged() {
        synchronized (mStateLock) {
            if (mIsStateLoaded) {
                sLog.w("System time changed, adopting a conservative rate-limiting state.");
                applyConservativeResetToStateLocked(mState);
            } else {
                sLog.w("System time changed during initial state load.");
                mTimeHasChangedDuringLoad = true;
            }
        }
    }

    /**
     * Wipes the current state and replaces it with a "fully consumed" state. This method must only
     * be called from a synchronized context.
     */
    @GuardedBy("mStateLock")
    private void applyConservativeResetToStateLocked(RateLimiterState stateToModify) {
        long now = mClock.currentTimeMillis();
        int maxDeviceRequests = mConfig.getDeviceFrequencyMaxCount();

        stateToModify.getDeviceTimestamps().clear();
        stateToModify.getDeviceTimestamps().addAll(Collections.nCopies(maxDeviceRequests, now));

        // Update all existing UID and signature timestamps to 'now'.
        // This ensures their individual cool-downs are respected, even if they are
        // longer than the device-wide window.
        stateToModify.getUidTimestamps().replaceAll((uid, oldTimestamp) -> now);
        stateToModify.getSignatureTimestamps().replaceAll((signature, oldTimestamp) -> now);

        scheduleWriteStateLocked();
    }

    @GuardedBy("mStateLock")
    private void scheduleWriteStateLocked() {
        if (!mIsStateLoaded) {
            sLog.w("State not loaded, skipping write.");
            return;
        }

        long now = mClock.currentTimeMillis();
        evictOldTimestampsLocked(mState, now);

        mStateDirty = true;
        mHandler.removeCallbacks(mWriteStateRunnable);
        mHandler.postDelayed(mWriteStateRunnable, mConfig.getPersistenceDelayMillis());
    }

    private void writeState() {
        RateLimiterState stateToWrite;
        synchronized (mStateLock) {
            if (!mStateDirty) {
                return;
            }
            stateToWrite = mState.createCopy();
            mStateDirty = false;
        }
        mStateStore.writeState(stateToWrite);
    }

    @GuardedBy("mStateLock")
    private void evictOldTimestampsLocked(RateLimiterState state, long now) {
        long maxCoolDown = mConfig.getMaxCoolDownForEvictionMillis();
        state.getUidTimestamps().values().removeIf(timestamp -> (now - timestamp) > maxCoolDown);
        state.getSignatureTimestamps()
                .values()
                .removeIf(timestamp -> (now - timestamp) > maxCoolDown);
    }

    @GuardedBy("mStateLock")
    private boolean isDeviceFrequencyLimitExceeded(long now) {
        long windowMillis = mConfig.getDeviceFrequencyWindowMillis();
        int maxCount = mConfig.getDeviceFrequencyMaxCount();
        // Remove old timestamps that are outside the rolling window.
        mState.getDeviceTimestamps().removeIf(timestamp -> (now - timestamp) > windowMillis);

        // After cleanup, check if the count is still too high.
        return mState.getDeviceTimestamps().size() >= maxCount;
    }

    @GuardedBy("mStateLock")
    private boolean isUidCoolDownActive(int uid, long now) {
        long coolDownMillis = mConfig.getUidCoolDownMillis();
        if (coolDownMillis <= 0) {
            return false;
        }

        Long lastRequestTime = mState.getUidTimestamps().get(uid);
        if (lastRequestTime == null) {
            return false; // No previous request for this UID.
        }

        return (now - lastRequestTime) < coolDownMillis;
    }

    @GuardedBy("mStateLock")
    private boolean isSignatureCoolDownActive(
            int uid, String conditionType, Map<String, String> signature, long now) {
        long coolDownMillis = mConfig.getSignatureCoolDownMillis(conditionType, signature);
        if (coolDownMillis <= 0) {
            return false;
        }

        String signatureKey = generateCanonicalKey(uid, signature);
        Long lastRequestTime = mState.getSignatureTimestamps().get(signatureKey);
        if (lastRequestTime == null) {
            return false;
        }
        return (now - lastRequestTime) < coolDownMillis;
    }

    /** Updates the state after a request is approved and persists it. */
    @GuardedBy("mStateLock")
    private void recordSuccessfulRequest(
            int uid, @Nullable Map<String, String> signature, long now) {
        // 1. Add 'now' to the list of device-wide request timestamps.
        mState.getDeviceTimestamps().add(now);

        // 2. Update the last request time for this UID.
        mState.getUidTimestamps().put(uid, now);

        // 3. If signature exist, update the timestamp for that specific anomaly signature.
        if (signature != null && !signature.isEmpty()) {
            String signatureKey = generateCanonicalKey(uid, signature);
            mState.getSignatureTimestamps().put(signatureKey, now);
        }

        scheduleWriteStateLocked();
    }

    @VisibleForTesting
    static String generateCanonicalKey(int uid, Map<String, String> signature) {
        if (signature == null || signature.isEmpty()) {
            return String.valueOf(uid);
        }

        // A TreeMap automatically sorts the keys, ensuring the output is canonical.
        Map<String, String> sortedSignature = new TreeMap<>(signature);

        StringBuilder sb = new StringBuilder();
        sb.append(uid).append("|");

        for (Map.Entry<String, String> entry : sortedSignature.entrySet()) {
            sb.append(entry.getKey()).append("=").append(entry.getValue()).append(";");
        }

        return sb.toString();
    }
}
