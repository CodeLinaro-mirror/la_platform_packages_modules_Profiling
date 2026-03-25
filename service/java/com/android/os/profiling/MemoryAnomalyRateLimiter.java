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

import static android.os.profiling.DeviceConfigHelper.updateInt;

import android.profiling.utils.RateLimiterBase;
import android.provider.DeviceConfig;

import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.List;

/** Instance of {@link RateLimiterBase} for rate limiting of memory limit anomaly only. */
public class MemoryAnomalyRateLimiter extends RateLimiterBase {

    // Uses the same store as {@link RateLimiter}.
    private static final String PERSIST_STORE_DIR = "profiling_rate_limiter_store";

    private static final String PERSIST_STORE_FILE_NAME = "memory_limit_anomaly_rate_limiter_info";

    private static final long RATE_LIMIT_SYSTEM_TIME_RANGE_MS = 3 * 60 * 60 * 1000; // 3 hours

    private static final long RATE_LIMIT_PROCESS_TIME_RANGE_MS = 3 * 24 * 60 * 60 * 1000; // 3 days

    private volatile int mSystemLimit;
    private volatile int mProcessLimit;

    private static final int RATE_LIMIT_COST = 1;

    public MemoryAnomalyRateLimiter(HandlerCallback handlerCallback) {
        super(handlerCallback);
    }

    @Override
    protected List<TimeBucket> getTimeBuckets() {
        DeviceConfig.Properties properties =
                DeviceConfigHelper.getAllMemoryAnomalyRateLimiterProperties();

        mSystemLimit =
                properties.getInt(
                        DeviceConfigHelper.MEMORY_ANOMALY_RATE_LIMIT_SYSTEM_QUANTITY,
                        DeviceConfigHelper.DEFAULT_MEMORY_ANOMALY_RATE_LIMIT_SYSTEM_QUANTITY);

        mProcessLimit =
                properties.getInt(
                        DeviceConfigHelper.MEMORY_ANOMALY_RATE_LIMIT_PROCESS_QUANTITY,
                        DeviceConfigHelper.DEFAULT_MEMORY_ANOMALY_RATE_LIMIT_PROCESS_QUANTITY);

        return buildTimeBuckets(mSystemLimit, mProcessLimit);
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

    /**
     * Updates the configuration values based on the provided DeviceConfig properties.
     *
     * @param properties The DeviceConfig.Properties object containing potential updates.
     */
    public void maybeUpdateConfigs(DeviceConfig.Properties properties) {
        synchronized (mLock) {
            mSystemLimit =
                    updateInt(
                            properties,
                            DeviceConfigHelper.MEMORY_ANOMALY_RATE_LIMIT_SYSTEM_QUANTITY,
                            mSystemLimit,
                            DeviceConfigHelper.DEFAULT_MEMORY_ANOMALY_RATE_LIMIT_SYSTEM_QUANTITY);

            mProcessLimit =
                    updateInt(
                            properties,
                            DeviceConfigHelper.MEMORY_ANOMALY_RATE_LIMIT_PROCESS_QUANTITY,
                            mProcessLimit,
                            DeviceConfigHelper.DEFAULT_MEMORY_ANOMALY_RATE_LIMIT_PROCESS_QUANTITY);

            maybeUpdateMaxCosts(buildTimeBuckets(mSystemLimit, mProcessLimit));
        }
    }

    /** For testing only. Update max per system and process costs. */
    @VisibleForTesting
    public void setMaxCosts(int systemQuantity, int processQuantity) {
        synchronized (mLock) {
            mSystemLimit = systemQuantity;
            mProcessLimit = processQuantity;
            maybeUpdateMaxCosts(buildTimeBuckets(mSystemLimit, mProcessLimit));
        }
    }

    private List<TimeBucket> buildTimeBuckets(int systemLimit, int processLimit) {
        List<TimeBucket> timeBuckets = new ArrayList<TimeBucket>(2);

        // Add a system only bucket using max val for process max cost so that it is effectively
        // disabled.
        timeBuckets.add(
                new TimeBucket(RATE_LIMIT_SYSTEM_TIME_RANGE_MS, systemLimit, Integer.MAX_VALUE));

        // Add a process only bucket using max val for system max cost so that it is effectively
        // disabled.
        timeBuckets.add(
                new TimeBucket(RATE_LIMIT_PROCESS_TIME_RANGE_MS, Integer.MAX_VALUE, processLimit));

        return timeBuckets;
    }
}
