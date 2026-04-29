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
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.os.OutcomeReceiver;
import android.os.profiling.anomaly.flags.Flags;

/**
 * Defines the contract for a data collector provided by the platform. The platform will implement
 * this interface, and the anomaly detector will use it to subscribe to data streams, request
 * current data snapshots, and trigger immediate updates.
 *
 * @param <T> The specific type of {@link SignalCollectorConfig} this collector understands.
 * @param <U> The specific type of {@link SignalCollectorData} this collector produces.
 * @hide
 */
@SystemApi(client = SYSTEM_SERVER)
@FlaggedApi(Flags.FLAG_ANOMALY_DETECTOR_CORE_C)
public interface SignalCollector<T extends SignalCollectorConfig, U extends SignalCollectorData> {

    /**
     * Subscribes the anomaly detector to a stream of data based on the provided configuration. The
     * platform will start collecting data according to the {@code config} and deliver updates
     * asynchronously via the {@code listener}.
     *
     * @param config The specific configuration for the data stream.
     * @param listener The callback to receive data updates or errors.
     * @return A {@link SubscriptionId} representing the unique handle for this subscription. This
     *     ID can be used later to unsubscribe or request updates.
     * @throws IllegalArgumentException if the configuration is invalid or unsupported.
     */
    @NonNull
    SubscriptionId subscribe(@NonNull T config, @NonNull OutcomeReceiver<U, Throwable> listener);

    /**
     * Unsubscribes from a previously established data stream using its handle. Once unsubscribed,
     * no further data will be delivered for this handle.
     *
     * @param subscriptionId The {@link SubscriptionId} of the subscription to cancel.
     * @throws IllegalArgumentException if the handle is invalid or not found.
     */
    void unsubscribe(@NonNull SubscriptionId subscriptionId);

    /**
     * Retrieves the current, instantaneous value of the data associated with the context identified
     * by the given {@code subscriptionId}.
     *
     * <p>This call is synchronous and provides the latest available data snapshot that this
     * specific collector type produces for the given subscription context. It may bypass the
     * original subscription's asynchronous delivery model or specific configuration constraints for
     * this immediate request.
     *
     * @param subscriptionId The {@link SubscriptionId} identifying the context for which to
     *     retrieve the current data.
     * @return The current data snapshot (U). Returns {@code null} if no data is currently available
     *     for the specified context.
     * @throws IllegalArgumentException if the {@code subscriptionId} is not recognized or invalid.
     */
    @Nullable
    U getData(@NonNull SubscriptionId subscriptionId);

    /**
     * Forces an immediate data collection and delivery for a specific active subscription. The
     * collected data will be delivered asynchronously via the {@link OutcomeReceiver} originally
     * provided during the {@link #subscribe(SignalCollectorConfig, OutcomeReceiver)} call for the
     * given handle. This can be used to request an immediate update, independent of any interval or
     * trigger.
     *
     * @param subscriptionId The {@link SubscriptionId} of the active subscription for which to
     *     request an update.
     * @throws IllegalArgumentException if the provided handle is invalid or does not correspond to
     *     an active subscription.
     */
    void requestUpdate(@NonNull SubscriptionId subscriptionId);
}
