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
import android.os.profiling.anomaly.RuleInternal.ConditionTypeInternal;

import java.util.Map;

/**
 * Interface for the rate limiting mechanism, designed to safeguard system performance by governing
 * the frequency of profiling requests.
 *
 * @hide
 */
public interface ProfilingRateLimiter {
    /**
     * Checks if a profiling request is allowed based on a hierarchical set of rate-limiting rules.
     *
     * <p>This method applies all relevant rate limits based on the provided arguments:
     *
     * <ul>
     *   <li>Always applies device-wide frequency limits.
     *   <li>Always applies a per-UID cool-down period.
     *   <li>If {@code rateLimitSignature} are provided, it also applies a more specific cool-down
     *       based on the unique combination of the UID and the anomaly's characteristics.
     * </ul>
     *
     * @param uid The UID of the application making the request.
     * @param conditionType The type of the anomaly, used to select the correct cool-down.
     * @param rateLimitSignature A Map containing key-value pairs that describe the specific
     *     anomaly. This may be {@code null} or empty if no specific attributes apply, in which case
     *     only the base (device and UID) rate limits are checked.
     * @return {@code true} if the request is within all applicable limits and is allowed to
     *     proceed, {@code false} otherwise.
     */
    boolean isRequestAllowed(
            int uid,
            @ConditionTypeInternal String conditionType,
            @Nullable Map<String, String> rateLimitSignature);

    /** Handles system time changes by adopting a conservative state. */
    void onTimeChanged();
}
