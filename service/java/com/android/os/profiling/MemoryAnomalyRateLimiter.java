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

package android.os.profiling;

import android.profiling.utils.RateLimiterBase;

import java.util.ArrayList;
import java.util.List;

/** Instance of {@link RateLimiterBase} for rate limiting of memory limit anomaly only. */
public class MemoryAnomalyRateLimiter extends RateLimiterBase {

    // Uses the same store as {@link RateLimiter}.
    private static final String PERSIST_STORE_DIR = "profiling_rate_limiter_store";

    private static final String PERSIST_STORE_FILE_NAME = "memory_limit_anomaly_rate_limiter_info";

    private static final long RATE_LIMIT_SYSTEM_TIME_RANGE_MS = 3 * 60 * 60 * 1000; // 3 hours

    private static final long RATE_LIMIT_PROCESS_TIME_RANGE_MS = 3 * 24 * 60 * 60 * 1000; // 3 days

    private static final int RATE_LIMIT_SYSTEM_QUANTITY = 2;

    private static final int RATE_LIMIT_PROCESS_QUANTITY = 1;

    private static final int RATE_LIMIT_COST = 1;

    public MemoryAnomalyRateLimiter(HandlerCallback handlerCallback) {
        super(handlerCallback);
    }

    @Override
    protected List<TimeBucket> getTimeBuckets() {
        List<TimeBucket> timeBuckets = new ArrayList<TimeBucket>(2);

        // Add a system only bucket using max val for process max cost so that it is effectively
        // disabled.
        timeBuckets.add(
                new TimeBucket(
                        RATE_LIMIT_SYSTEM_TIME_RANGE_MS,
                        RATE_LIMIT_SYSTEM_QUANTITY,
                        Integer.MAX_VALUE));

        // Add a process only bucket using max val for system max cost so that it is effectively
        // disabled.
        timeBuckets.add(
                new TimeBucket(
                        RATE_LIMIT_PROCESS_TIME_RANGE_MS,
                        Integer.MAX_VALUE,
                        RATE_LIMIT_PROCESS_QUANTITY));

        return timeBuckets;
    }

    @Override
    protected String getPersistFileDir() {
        return PERSIST_STORE_DIR;
    }

    @Override
    protected String getPersistFileName() {
        return PERSIST_STORE_FILE_NAME;
    }

    @Override
    protected long getPersistToDiskFrequencyMs() {
        return RateLimiter.DEFAULT_PERSIST_TO_DISK_FREQUENCY_MS;
    }

    /** Check if profiling request is allowed by rate limiting. */
    public @RateLimitResult int isProfilingRequestAllowed(int uid) {
        return isProfilingRequestAllowed(uid, RATE_LIMIT_COST);
    }
}
