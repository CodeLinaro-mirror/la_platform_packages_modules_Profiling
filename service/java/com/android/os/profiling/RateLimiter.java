/*
 * Copyright (C) 2023 The Android Open Source Project
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

import static android.os.profiling.DeviceConfigHelper.updateBoolean;
import static android.os.profiling.DeviceConfigHelper.updateInt;
import static android.os.profiling.DeviceConfigHelper.updateLong;

import android.annotation.Nullable;
import android.os.Bundle;
import android.os.ProfilingManager;
import android.provider.DeviceConfig;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.List;

/** Instance of {@link RateLimiterBase} for general {@link ProfilingService} rate limiting. */
public class RateLimiter extends RateLimiterBase {
    private static final String TAG = RateLimiter.class.getSimpleName();

    private static final String RATE_LIMITER_STORE_DIR = "profiling_rate_limiter_store";
    private static final String RATE_LIMITER_INFO_FILE = "profiling_rate_limiter_info";

    private static final long TIME_HOUR_MS = 60 * 60 * 1000;
    private static final long TIME_DAY_MS = 24 * 60 * 60 * 1000;
    private static final long TIME_WEEK_MS = 7 * 24 * 60 * 60 * 1000;

    private static final int DEFAULT_MAX_COST_SYSTEM_HOUR = 20;
    private static final int DEFAULT_MAX_COST_PROCESS_HOUR = 10;
    private static final int DEFAULT_MAX_COST_SYSTEM_DAY = 50;
    private static final int DEFAULT_MAX_COST_PROCESS_DAY = 20;
    private static final int DEFAULT_MAX_COST_SYSTEM_WEEK = 150;
    private static final int DEFAULT_MAX_COST_PROCESS_WEEK = 30;
    private static final int DEFAULT_COST_PER_SESSION = 10;
    private static final int DEFAULT_COST_PER_SYSTEM_TRIGGERED_SESSION = 5;

    public static final long DEFAULT_PERSIST_TO_DISK_FREQUENCY_MS = 0;

    @VisibleForTesting public int mCostJavaHeapDump;
    @VisibleForTesting public int mCostHeapProfile;
    @VisibleForTesting public int mCostStackSampling;
    @VisibleForTesting public int mCostSystemTrace;
    @VisibleForTesting public int mCostSystemTriggeredSystemTrace;

    @VisibleForTesting public long mPersistToDiskFrequency;

    private final Object mLock = new Object();

    /** To be disabled for testing only. */
    @GuardedBy("mLock")
    private boolean mRateLimiterDisabled = false;

    public RateLimiter(HandlerCallback handlerCallback) {
        super(handlerCallback);

        DeviceConfig.Properties properties = DeviceConfigHelper.getAllRateLimiterProperties();

        mCostJavaHeapDump =
                properties.getInt(DeviceConfigHelper.COST_JAVA_HEAP_DUMP, DEFAULT_COST_PER_SESSION);
        mCostHeapProfile =
                properties.getInt(DeviceConfigHelper.COST_HEAP_PROFILE, DEFAULT_COST_PER_SESSION);
        mCostStackSampling =
                properties.getInt(DeviceConfigHelper.COST_STACK_SAMPLING, DEFAULT_COST_PER_SESSION);
        mCostSystemTrace =
                properties.getInt(DeviceConfigHelper.COST_SYSTEM_TRACE, DEFAULT_COST_PER_SESSION);
        mCostSystemTriggeredSystemTrace =
                properties.getInt(
                        DeviceConfigHelper.COST_SYSTEM_TRIGGERED_SYSTEM_TRACE,
                        DEFAULT_COST_PER_SYSTEM_TRIGGERED_SESSION);

        mPersistToDiskFrequency =
                properties.getLong(
                        DeviceConfigHelper.PERSIST_TO_DISK_FREQUENCY_MS,
                        DEFAULT_PERSIST_TO_DISK_FREQUENCY_MS);

        // Get initial value for whether rate limiter should be enforcing or if it should always
        // allow profiling requests. This is used for (automated and manual) testing only.
        synchronized (mLock) {
            mRateLimiterDisabled =
                    DeviceConfigHelper.getTestBoolean(
                            DeviceConfigHelper.RATE_LIMITER_DISABLE_PROPERTY, false);
        }
    }

    @Override
    protected List<TimeBucket> getTimeBuckets() {
        DeviceConfig.Properties properties = DeviceConfigHelper.getAllRateLimiterProperties();
        List<TimeBucket> timeBuckets = new ArrayList<TimeBucket>(3);
        timeBuckets.add(
                new TimeBucket(
                        TIME_HOUR_MS,
                        properties.getInt(
                                DeviceConfigHelper.MAX_COST_SYSTEM_1_HOUR,
                                DEFAULT_MAX_COST_SYSTEM_HOUR),
                        properties.getInt(
                                DeviceConfigHelper.MAX_COST_PROCESS_1_HOUR,
                                DEFAULT_MAX_COST_PROCESS_HOUR)));
        timeBuckets.add(
                new TimeBucket(
                        TIME_DAY_MS,
                        properties.getInt(
                                DeviceConfigHelper.MAX_COST_SYSTEM_24_HOUR,
                                DEFAULT_MAX_COST_SYSTEM_DAY),
                        properties.getInt(
                                DeviceConfigHelper.MAX_COST_PROCESS_24_HOUR,
                                DEFAULT_MAX_COST_PROCESS_DAY)));
        timeBuckets.add(
                new TimeBucket(
                        TIME_WEEK_MS,
                        properties.getInt(
                                DeviceConfigHelper.MAX_COST_SYSTEM_7_DAY,
                                DEFAULT_MAX_COST_SYSTEM_WEEK),
                        properties.getInt(
                                DeviceConfigHelper.MAX_COST_PROCESS_7_DAY,
                                DEFAULT_MAX_COST_PROCESS_WEEK)));

        return timeBuckets;
    }

    @Override
    protected String getPersistFileDir() {
        return RATE_LIMITER_STORE_DIR;
    }

    @Override
    protected String getPersistFileName() {
        return RATE_LIMITER_INFO_FILE;
    }

    @Override
    protected long getPersistToDiskFrequencyMs() {
        return mPersistToDiskFrequency;
    }

    /** Whether rate limiter is currently disabled. */
    public boolean isRateLimiterDisabled() {
        synchronized (mLock) {
            return mRateLimiterDisabled;
        }
    }

    /**
     * Check whether a profiling session is allowed to run per current rate limiting restrictions.
     */
    public @RateLimitResult int isProfilingRequestAllowed(
            int uid, int profilingType, boolean isTriggered, @Nullable Bundle params) {
        synchronized (mLock) {
            if (mRateLimiterDisabled && !isTriggered) {
                // Rate limiter is disabled for testing, approve request and don't store cost.
                // This mechanism applies only to direct requests, not system triggered ones.
                Log.w(TAG, "Rate limiter disabled, request allowed.");
                return RATE_LIMIT_RESULT_ALLOWED;
            }
        }

        return isProfilingRequestAllowed(uid, getCostForProfiling(profilingType, isTriggered));
    }

    public void maybeUpdateConfigs(DeviceConfig.Properties properties) {
        // If the field is not present in the changed properties then we want the value to stay the
        // same, so use the current value as the default in the properties.get.
        mPersistToDiskFrequency =
                updateLong(
                        properties,
                        DeviceConfigHelper.PERSIST_TO_DISK_FREQUENCY_MS,
                        mPersistToDiskFrequency,
                        DEFAULT_PERSIST_TO_DISK_FREQUENCY_MS);
        mCostJavaHeapDump =
                updateInt(
                        properties,
                        DeviceConfigHelper.COST_JAVA_HEAP_DUMP,
                        mCostJavaHeapDump,
                        DEFAULT_COST_PER_SESSION);
        mCostHeapProfile =
                updateInt(
                        properties,
                        DeviceConfigHelper.COST_HEAP_PROFILE,
                        mCostHeapProfile,
                        DEFAULT_COST_PER_SESSION);
        mCostStackSampling =
                updateInt(
                        properties,
                        DeviceConfigHelper.COST_STACK_SAMPLING,
                        mCostStackSampling,
                        DEFAULT_COST_PER_SESSION);
        mCostSystemTrace =
                updateInt(
                        properties,
                        DeviceConfigHelper.COST_SYSTEM_TRACE,
                        mCostSystemTrace,
                        DEFAULT_COST_PER_SESSION);
        mCostSystemTriggeredSystemTrace =
                updateInt(
                        properties,
                        DeviceConfigHelper.COST_SYSTEM_TRIGGERED_SYSTEM_TRACE,
                        mCostSystemTriggeredSystemTrace,
                        DEFAULT_COST_PER_SYSTEM_TRIGGERED_SESSION);

        // For max cost values, set a invalid default value and pass through to {@link
        // RateLimiterBase}
        // to determine whether to update values.

        List<TimeBucket> timeBuckets = new ArrayList<TimeBucket>(3);
        timeBuckets.add(
                new TimeBucket(
                        TIME_HOUR_MS,
                        properties.getInt(DeviceConfigHelper.MAX_COST_SYSTEM_1_HOUR, -1),
                        properties.getInt(DeviceConfigHelper.MAX_COST_PROCESS_1_HOUR, -1)));
        timeBuckets.add(
                new TimeBucket(
                        TIME_DAY_MS,
                        properties.getInt(DeviceConfigHelper.MAX_COST_SYSTEM_24_HOUR, -1),
                        properties.getInt(DeviceConfigHelper.MAX_COST_PROCESS_24_HOUR, -1)));
        timeBuckets.add(
                new TimeBucket(
                        TIME_WEEK_MS,
                        properties.getInt(DeviceConfigHelper.MAX_COST_SYSTEM_7_DAY, -1),
                        properties.getInt(DeviceConfigHelper.MAX_COST_PROCESS_7_DAY, -1)));

        maybeUpdateMaxCosts(timeBuckets);
    }

    /** Update the disable rate limiter flag if present in the provided properties. */
    public void maybeUpdateRateLimiterDisabled(DeviceConfig.Properties properties) {
        synchronized (mLock) {
            mRateLimiterDisabled =
                    updateBoolean(
                            properties,
                            DeviceConfigHelper.RATE_LIMITER_DISABLE_PROPERTY,
                            mRateLimiterDisabled,
                            DeviceConfigHelper.DEFAULT_RATE_LIMITER_DISABLE_PROPERTY);
        }
    }

    private int getCostForProfiling(int profilingType, boolean isTriggered) {
        if (isTriggered) {
            return mCostSystemTriggeredSystemTrace;
        }
        return switch (profilingType) {
            case ProfilingManager.PROFILING_TYPE_JAVA_HEAP_DUMP -> mCostJavaHeapDump;
            case ProfilingManager.PROFILING_TYPE_HEAP_PROFILE -> mCostHeapProfile;
            case ProfilingManager.PROFILING_TYPE_STACK_SAMPLING -> mCostStackSampling;
            case ProfilingManager.PROFILING_TYPE_SYSTEM_TRACE -> mCostSystemTrace;
            default -> Integer.MAX_VALUE;
        };
    }
}
