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

import java.util.Map;

/**
 * An interface that abstracts the source of configuration values for the rate limiter, primarily
 * for making the logic testable.
 *
 * @hide
 */
public interface RateLimiterConfig {
    /**
     * Returns the duration of the rolling window for the device-wide frequency limit, in
     * milliseconds.
     */
    long getDeviceFrequencyWindowMillis();

    /** Returns the maximum number of profiling requests allowed device-wide within the window. */
    int getDeviceFrequencyMaxCount();

    /** Returns the cool-down period for a single UID, in milliseconds. */
    long getUidCoolDownMillis();

    /**
     * Gets the signature-level cool-down for a specific anomaly.
     *
     * <p>This method finds the best-matching rule from the server-provided configuration based on
     * the anomaly's characteristics.
     *
     * @param conditionType The type of the anomaly (e.g., {@link
     *     android.os.profiling.anomaly.RuleInternal#CONDITION_TYPE_BINDER_SPAM}).
     * @param signature A map of key-value pairs describing the anomaly's specific signature.
     * @return The cool-down period in milliseconds, or 0 if no rule matches (indicating no
     *     signature-level cool-down applies).
     */
    long getSignatureCoolDownMillis(String conditionType, Map<String, String> signature);

    /**
     * Returns the delay in milliseconds to wait before persisting the rate limiter state to disk
     * after it has been modified.
     */
    long getPersistenceDelayMillis();
}
