/*
 * Copyright (C) 2024 The Android Open Source Project
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

import android.annotation.NonNull;
import android.os.ProfilingTriggersWrapper;

public final class ProfilingTriggerData {
    // LINT.IfChange(params)
    private final int mUid;
    @NonNull private final String mPackageName;
    private final int mTriggerType;
    private final int mRateLimitingPeriodHours;
    private long mLastTriggeredTimeMs = 0;
    // LINT.ThenChange(:from_proto)

    public ProfilingTriggerData(int uid, @NonNull String packageName, int triggerType,
            int rateLimitingPeriodHours) {
        mUid = uid;
        mPackageName = packageName;
        mTriggerType = triggerType;
        mRateLimitingPeriodHours = rateLimitingPeriodHours;
    }

    // LINT.IfChange(from_proto)
    /** Create object from proto. */
    public ProfilingTriggerData(@NonNull ProfilingTriggersWrapper.ProfilingTrigger triggerProto) {
        mUid = triggerProto.getUid();
        mPackageName = triggerProto.getPackageName();
        mTriggerType = triggerProto.getTriggerType();
        mRateLimitingPeriodHours = triggerProto.getRateLimitingPeriodHours();
        mLastTriggeredTimeMs = triggerProto.getLastTriggeredTimeMillis();
    }
    // LINT.ThenChange(:to_proto)


    public void setLastTriggeredTimeMs(long lastTriggeredTimeMs) {
        mLastTriggeredTimeMs = lastTriggeredTimeMs;
    }

    public int getUid() {
        return mUid;
    }

    public int getTriggerType() {
        return mTriggerType;
    }

    public String getPackageName() {
        return mPackageName;
    }

    public int getRateLimitingPeriodHours() {
        return mRateLimitingPeriodHours;
    }

    public long getLastTriggeredTimeMs() {
        return mLastTriggeredTimeMs;
    }

    // LINT.IfChange(to_proto)
    /** Write object to proto. */
    public ProfilingTriggersWrapper.ProfilingTrigger toProto() {
        ProfilingTriggersWrapper.ProfilingTrigger.Builder builder =
                ProfilingTriggersWrapper.ProfilingTrigger.newBuilder();

        builder.setUid(mUid);
        builder.setPackageName(mPackageName);
        builder.setTriggerType(mTriggerType);
        builder.setRateLimitingPeriodHours(mRateLimitingPeriodHours);
        builder.setLastTriggeredTimeMillis(mLastTriggeredTimeMs);

        return builder.build();
    }
    // LINT.ThenChange(/tests/cts/src/android/profiling/cts/ProfilingServiceTests.java:trigger_equals)
}
