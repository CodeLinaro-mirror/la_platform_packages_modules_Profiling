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

/**
 * An interface to abstract time-related operations, primarily for making the rate limiter logic
 * testable.
 *
 * @hide
 */
public interface RateLimiterClock {
    /**
     * Returns the current time in milliseconds since the epoch (wall-clock time). This is
     * equivalent to {@link System#currentTimeMillis()}.
     *
     * @return The difference, measured in milliseconds, between the current time and midnight,
     *     January 1, 1970 UTC.
     */
    long currentTimeMillis();
}
