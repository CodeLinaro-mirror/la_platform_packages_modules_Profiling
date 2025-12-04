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

package com.android.os.profiling.anomaly.collector;

import static android.annotation.SystemApi.Client.SYSTEM_SERVER;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.os.profiling.anomaly.flags.Flags;

import com.android.internal.annotations.VisibleForTesting;

import java.util.Objects;
import java.util.UUID;

/**
 * Represents a unique identifier for a data subscription. This is a value object that wraps a UUID
 * for better type safety, and readability.
 *
 * @hide
 */
@SystemApi(client = SYSTEM_SERVER)
@FlaggedApi(Flags.FLAG_ANOMALY_DETECTOR_CORE)
public final class SubscriptionId {
    private final UUID mUuid;

    /**
     * Private constructor to enforce creation via static factory methods.
     *
     * @param uuid The underlying UUID. Must not be null.
     */
    @VisibleForTesting
    SubscriptionId(@NonNull UUID uuid) {
        Objects.requireNonNull(uuid, "SubscriptionId UUID cannot be null");
        mUuid = uuid;
    }

    /**
     * Generates a new, unique, random SubscriptionId.
     *
     * @return A new SubscriptionId instance.
     */
    @NonNull
    public static SubscriptionId generateNew() {
        return new SubscriptionId(UUID.randomUUID());
    }

    /** {@inheritDoc} */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SubscriptionId that = (SubscriptionId) o;
        return mUuid.equals(that.mUuid);
    }

    /** {@inheritDoc} */
    @Override
    public int hashCode() {
        return mUuid.hashCode();
    }

    /** {@inheritDoc} */
    @Override
    public String toString() {
        return mUuid.toString();
    }
}
