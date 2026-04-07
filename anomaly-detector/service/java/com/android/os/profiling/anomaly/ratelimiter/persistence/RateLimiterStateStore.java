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

import android.os.OutcomeReceiver;

/**
 * An interface that abstracts the persistence logic for the rate limiter's state. Implementations
 * are responsible for reading and writing {@link RateLimiterState} to and from a durable storage
 * medium.
 *
 * @hide
 */
public interface RateLimiterStateStore {
    /**
     * Reads the rate limiter state from persistent storage asynchronously.
     *
     * @param callback The callback to be notified when the state is loaded or if an error occurs.
     */
    void readState(OutcomeReceiver<RateLimiterState, Throwable> callback);

    /**
     * Writes the given rate limiter state to persistent storage. This operation may be performed
     * asynchronously.
     *
     * @param state The {@link RateLimiterState} object to serialize and persist.
     */
    void writeState(RateLimiterState state);
}
