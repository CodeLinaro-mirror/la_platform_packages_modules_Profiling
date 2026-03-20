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

package com.android.os.profiling.anomaly.ratelimiter.persistence;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A data class that holds the mutable state for the {@link
 * com.android.os.profiling.anomaly.ratelimiter.ProfilingRateLimiter}.
 *
 * <p>This class is not thread-safe and is intended to be managed by a single, synchronized owner.
 * It acts as a clean data container, separating the rate-limiting state from the logic that uses
 * it.
 *
 * @hide
 */
public final class RateLimiterState {
    /**
     * A list of timestamps representing the last few device-wide profiling requests. Used to
     * enforce the device-wide frequency limit (e.g., max 5 requests in 12 hours).
     */
    private final List<Long> mDeviceTimestamps;

    /**
     * A map from a UID to the timestamp of its most recent profiling request. Used to enforce the
     * per-UID cool-down period.
     */
    private final Map<Integer, Long> mUidTimestamps;

    /**
     * A map from a canonical key (representing a UID and a unique set of anomaly signature) to the
     * timestamp of its most recent profiling request. Used for fine-grained, signature-specific
     * cool-downs.
     */
    private final Map<String, Long> mSignatureTimestamps;

    public RateLimiterState(
            List<Long> deviceTimestamps,
            Map<Integer, Long> uidTimestamps,
            Map<String, Long> signatureTimestamps) {
        mDeviceTimestamps = deviceTimestamps;
        mUidTimestamps = uidTimestamps;
        mSignatureTimestamps = signatureTimestamps;
    }

    public List<Long> getDeviceTimestamps() {
        return mDeviceTimestamps;
    }

    public Map<Integer, Long> getUidTimestamps() {
        return mUidTimestamps;
    }

    public Map<String, Long> getSignatureTimestamps() {
        return mSignatureTimestamps;
    }

    /**
     * Creates a new {@link RateLimiterState} with empty, mutable collections.
     *
     * @return A new, empty state object.
     */
    public static RateLimiterState createEmpty() {
        return new RateLimiterState(new ArrayList<>(), new HashMap<>(), new HashMap<>());
    }

    /**
     * Creates a deep copy of the current state.
     *
     * @return A new {@link RateLimiterState} instance with copied data.
     */
    public RateLimiterState createCopy() {
        return new RateLimiterState(
                new ArrayList<>(mDeviceTimestamps),
                new HashMap<>(mUidTimestamps),
                new HashMap<>(mSignatureTimestamps));
    }
}
