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
import android.app.ActivityManager;
import android.os.profiling.anomaly.flags.Flags;
import android.text.TextUtils;

import com.android.internal.annotations.VisibleForTesting;
import com.android.os.profiling.anomaly.collector.SignalCollectorData;

import java.time.Duration;
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

    /** The server process UID. */
    private final int mServerUid;

    /** The count of the binder calls from the calling UID incurred over the timespan. */
    private final int mCallCount;

    /** The AIDL interface of the binder call. */
    private final String mInterfaceName;

    /** The AIDL method name of the binder call. */
    private final String mMethodName;

    /** The timespan of the binder calls incurred over. */
    private final Duration mTimespan;

    /**
     * The importance of the calling process. See {@link
     * ActivityManager.RunningAppProcessInfo#importance}
     */
    private final int mCallerImportance;

    private BinderSpamData(Builder builder) {
        mCallingUid = builder.mCallingUid;
        mServerUid = builder.mServerUid;
        mCallCount = builder.mCallCount;
        mInterfaceName = builder.mInterfaceName;
        mMethodName = builder.mMethodName;
        mTimespan = builder.mTimespan;
        mCallerImportance = builder.mCallerImportance;
    }

    /**
     * Get the count of the binder transactions that incurred over the timespan returned by {@link
     * #getTimespan()}. Note that this value is the maximum count since last report.
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
     * Get the timespan that the call count returned by {@link #getCallCount()} incurred over. The
     * default value is 1 second if not specifically set.
     */
    @NonNull
    public Duration getTimespan() {
        return mTimespan;
    }

    /** Get the calling uid of the binder transactions this signal contains. */
    public int getCallingUid() {
        return mCallingUid;
    }

    /** Get the server uid of the binder transactions this signal contains. */
    public int getServerUid() {
        return mServerUid;
    }

    /**
     * Get the importance of the calling process. See {@link
     * ActivityManager.RunningAppProcessInfo#importance}.
     */
    public int getCallerImportance() {
        return mCallerImportance;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof BinderSpamData that)) {
            return false;
        }
        return mCallingUid == that.mCallingUid
                && mServerUid == that.mServerUid
                && mCallCount == that.mCallCount
                && mCallerImportance == that.mCallerImportance
                && Objects.equals(mInterfaceName, that.mInterfaceName)
                && Objects.equals(mMethodName, that.mMethodName)
                && Objects.equals(mTimespan, that.mTimespan);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                mCallingUid,
                mServerUid,
                mCallCount,
                mInterfaceName,
                mMethodName,
                mTimespan,
                mCallerImportance);
    }

    public static final class Builder {
        /** The default value of timespan. */
        @VisibleForTesting static final Duration DEFAULT_TIMESPAN = Duration.ofSeconds(1);

        /** Either the direct client process UID or the source client process UID. */
        private int mCallingUid = -1;

        /** The server process UID. */
        private int mServerUid = -1;

        /** The count of the binder calls from the calling UID incurred over the timespan. */
        private int mCallCount;

        /** The AIDL interface of the binder call. */
        private String mInterfaceName;

        /** The AIDL method name of the binder call. */
        private String mMethodName;

        /** The timespan of the binder calls incurred over. */
        private Duration mTimespan = DEFAULT_TIMESPAN;

        /**
         * The importance of the calling process. See {@link
         * ActivityManager.RunningAppProcessInfo#importance}.
         */
        private int mCallerImportance;

        /**
         * Set the calling UID.
         *
         * @param callingUid Either the direct client process UID or the source client process UID.
         * @return this builder for method chaining
         */
        @NonNull
        public Builder setCallingUid(int callingUid) {
            if (callingUid < 0) {
                throw new IllegalArgumentException("Invalid calling UID!");
            }
            mCallingUid = callingUid;
            return this;
        }

        /**
         * Set the server UID.
         *
         * @param serverUid The server process UID.
         * @return this builder for method chaining
         */
        @NonNull
        public Builder setServerUid(int serverUid) {
            if (serverUid < 0) {
                throw new IllegalArgumentException("Invalid server UID!");
            }
            mServerUid = serverUid;
            return this;
        }

        /**
         * Set the call count
         *
         * @param callCount The count of the binder calls from the calling UID incurred over the
         *     timespan set by {@link #setTimespan(Duration)}.
         * @return this builder for method chaining
         */
        @NonNull
        public Builder setCallCount(int callCount) {
            if (callCount <= 0) {
                throw new IllegalArgumentException("Invalid call count!");
            }
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
            if (TextUtils.isEmpty(interfaceName)) {
                throw new IllegalArgumentException("Interface name must not be empty!");
            }
            mInterfaceName = interfaceName;
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
            if (TextUtils.isEmpty(methodName)) {
                throw new IllegalArgumentException("Method name must not be empty!");
            }
            mMethodName = methodName;
            return this;
        }

        /**
         * Set the duration of the timespan
         *
         * @param timespan the timespan that the call count set by {@link #setCallCount(int)}
         *     incurred over. The default value is 1 second if not specifically set.
         * @return this builder for method chaining
         */
        @NonNull
        public Builder setTimespan(@NonNull Duration timespan) {
            if (Objects.requireNonNull(timespan).isPositive()) {
                mTimespan = timespan;
                return this;
            }
            throw new IllegalArgumentException("Timespan must be positive!");
        }

        /**
         * Set the importance of the calling process.
         *
         * @param importance The importance of the calling process. See {@link
         *     ActivityManager.RunningAppProcessInfo#importance}.
         * @return this builder for method chaining
         */
        @NonNull
        public Builder setCallerImportance(int importance) {
            mCallerImportance = importance;
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
                throw new IllegalArgumentException("Calling UID must be set!");
            }
            if (mServerUid < 0) {
                throw new IllegalArgumentException("Server UID must be set!");
            }
            if (mCallCount == 0) {
                throw new IllegalArgumentException("Call count must be set!");
            }
            Objects.requireNonNull(mInterfaceName);
            Objects.requireNonNull(mMethodName);
            return new BinderSpamData(this);
        }
    }
}
