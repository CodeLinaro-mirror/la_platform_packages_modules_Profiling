/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.os.profiling.anomaly.collector.binder;

import static android.annotation.SystemApi.Client.SYSTEM_SERVER;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.os.profiling.anomaly.flags.Flags;
import android.text.TextUtils;

import com.android.internal.annotations.VisibleForTesting;
import com.android.os.profiling.anomaly.collector.SignalCollectorData;

import java.util.Objects;

/**
 * A signal type representing a potential binder spam anomaly to be detected by anomaly detector.
 * Instances of this class contain the specific data collected for binder transactions that are
 * configured by {@link BinderSpamConfig} to be monitored.
 *
 * @hide
 */
@SystemApi(client = SYSTEM_SERVER)
@FlaggedApi(Flags.FLAG_ANOMALY_DETECTOR_CORE)
public final class BinderSpamData implements SignalCollectorData {
    /** Either the direct client process UID or the source client process UID. */
    private final int mCallingUid;

    /** The count of the binder calls from the calling UID incurred over the timespan. */
    private final int mCallCount;

    /** The AIDL interface of the binder call. */
    private final String mInterfaceName;

    /** The AIDL method name of the binder call. */
    private final String mMethodName;

    /** The timespan of the binder calls incurred over. */
    private final long mTimespanMillis;

    private BinderSpamData(Builder builder) {
        this.mCallingUid = builder.mCallingUid;
        this.mCallCount = builder.mCallCount;
        this.mInterfaceName = builder.mInterfaceName;
        this.mMethodName = builder.mMethodName;
        this.mTimespanMillis = builder.mTimespanMillis;
    }

    /**
     * Get the count of the binder transactions that incurred over the timespan returned by {@link
     * #getTimespanMillis()}. Note that this value is the maximum count since last report.
     */
    public int getCallCount() {
        return mCallCount;
    }

    /** Get the interface name of the binder transactions this signal contains. */
    @NonNull
    public String getInterfaceName() {
        return mInterfaceName;
    }

    /** Get the method name of the binder transactions this signal contains. */
    @NonNull
    public String getMethodName() {
        return mMethodName;
    }

    /**
     * Get the timespan that the call count returned by {@link #getCallCount()} incurred over, in
     * milliseconds. The default value is 1000 if not specifically set.
     */
    public long getTimespanMillis() {
        return mTimespanMillis;
    }

    /** Get the calling uid of the binder transactions this signal contains. */
    public int getCallingUid() {
        return mCallingUid;
    }

    public static final class Builder {
        /** The default value of timespan in milliseconds. */
        @VisibleForTesting static final long DEFAULT_TIMESPAN_MILLIS = 1000;

        /** Either the direct client process UID or the source client process UID. */
        private int mCallingUid = -1;

        /** The count of the binder calls from the calling UID incurred over the timespan. */
        private int mCallCount;

        /** The AIDL interface of the binder call. */
        private String mInterfaceName;

        /** The AIDL method name of the binder call. */
        private String mMethodName;

        /** The timespan of the binder calls incurred over. */
        private long mTimespanMillis = DEFAULT_TIMESPAN_MILLIS;

        /**
         * Set the calling UID.
         *
         * @param callingUid Either the direct client process UID or the source client process UID.
         * @return this builder for method chaining
         */
        @NonNull
        public Builder setCallingUid(int callingUid) {
            mCallingUid = callingUid;
            return this;
        }

        /**
         * Set the call count
         *
         * @param callCount The count of the binder calls from the calling UID incurred over the
         *     timespan set by {@link #setTimespanMillis(long)}.
         * @return this builder for method chaining
         */
        @NonNull
        public Builder setCallCount(int callCount) {
            mCallCount = callCount;
            return this;
        }

        /**
         * Set the AIDL interface name
         *
         * @param interfaceName The full qualified name of the AIDL interface.
         * @return this builder for method chaining
         */
        @NonNull
        public Builder setInterfaceName(@NonNull String interfaceName) {
            // TODO(b/440140585): Validate the format of the interface name.
            mInterfaceName = Objects.requireNonNull(interfaceName);
            return this;
        }

        /**
         * Set the AIDL method name
         *
         * @param methodName The name of the AIDL method.
         * @return this builder for method chaining
         */
        @NonNull
        public Builder setMethodName(@NonNull String methodName) {
            mMethodName = Objects.requireNonNull(methodName);
            return this;
        }

        /**
         * Set the duration of the timespan
         *
         * @param timespanMillis the timespan that the call count set by {@link #setCallCount(int)}
         *     incurred over in milliseconds.
         * @return this builder for method chaining
         */
        @NonNull
        public Builder setTimespanMillis(long timespanMillis) {
            mTimespanMillis = timespanMillis;
            return this;
        }

        /**
         * Validate fields and build the signal
         *
         * @return the {@link BinderSpamData} with the set fields.
         */
        @NonNull
        public BinderSpamData build() {
            // Validate here instead of in the setters, because we do not want to build without
            // these values being set.
            if (mCallingUid < 0) {
                throw new IllegalArgumentException("Calling UID must be set to valid UID!");
            }
            if (mCallCount <= 0) {
                throw new IllegalArgumentException("Call count must be greater than 0!");
            }
            if (TextUtils.isEmpty(mInterfaceName) || TextUtils.isEmpty(mMethodName)) {
                throw new IllegalArgumentException("Interface and method names must be set!");
            }
            if (mTimespanMillis <= 0) {
                throw new IllegalArgumentException("TimespanMillis must be greater than 0!");
            }
            return new BinderSpamData(this);
        }
    }
}
