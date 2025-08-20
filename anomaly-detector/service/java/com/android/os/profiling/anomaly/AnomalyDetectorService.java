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

package com.android.os.profiling.anomaly;

import android.annotation.FlaggedApi;
import android.annotation.Nullable;
import android.content.Context;
import android.os.OutcomeReceiver;
import android.os.profiling.anomaly.flags.Flags;
import android.util.ArrayMap;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.os.profiling.anomaly.collector.SignalCollector;
import com.android.os.profiling.anomaly.collector.SignalCollectorConfig;
import com.android.os.profiling.anomaly.collector.SignalCollectorData;
import com.android.os.profiling.anomaly.collector.SubscriptionId;
import com.android.server.LocalManagerRegistry;
import com.android.server.SystemService;

import java.util.Map;
import java.util.Objects;

/**
 * Anomaly Detector Service.
 * <p>
 * This entire service is part of a feature controlled by the
 * {@link Flags#FLAG_ANOMALY_DETECTOR_CORE} flag. It is started by the SystemServer only when this
 * flag is enabled. As a result, the entire class is annotated with {@link FlaggedApi} to signify
 * that its existence and all of its APIs are conditional upon this feature flag.
 *
 * @hide
 */
@FlaggedApi(Flags.FLAG_ANOMALY_DETECTOR_CORE)
public final class AnomalyDetectorService extends SystemService {
    private static final String TAG = "AnomalyDetectorService";

    private final AnomalyDetectorServiceImpl mAnomalyDetectorServiceImpl;

    @VisibleForTesting
    final AnomalyDetectorManagerLocal mLocalManager;

    // Stores CollectorEntry, keyed by the config class type.
    @VisibleForTesting
    final Map<Class<? extends SignalCollectorConfig>, CollectorEntry> mRegisteredCollectors =
            new ArrayMap<>();

    public AnomalyDetectorService(Context context) {
        super(context);

        mAnomalyDetectorServiceImpl = new AnomalyDetectorServiceImpl();

        mLocalManager = new Local();
    }

    @Override
    public void onStart() {
        Slog.i(TAG, "onStart()");

        LocalManagerRegistry.addManager(AnomalyDetectorManagerLocal.class, mLocalManager);

        publishBinderService(Context.ANOMALY_DETECTOR_SERVICE, mAnomalyDetectorServiceImpl);
    }

    /**
     * An entry that holds a {@link SignalCollector} and its associated data type class.
     */
    @VisibleForTesting
    static final class CollectorEntry {
        final SignalCollector<?, ?> mCollector;
        final Class<? extends SignalCollectorData> mDataType;

        CollectorEntry(SignalCollector<?, ?> collector,
                Class<? extends SignalCollectorData> dataType) {
            this.mCollector = Objects.requireNonNull(collector, "Collector cannot be null");
            this.mDataType = Objects.requireNonNull(dataType, "Data type cannot be null");
        }

        public SignalCollector<?, ?> getCollector() {
            return mCollector;
        }

        public Class<? extends SignalCollectorData> getDataType() {
            return mDataType;
        }

        @Override
        public String toString() {
            return "CollectorEntry{"
                    + "collector=" + mCollector.getClass().getSimpleName()
                    + ", dataType=" + mDataType.getSimpleName() + '}';
        }
    }

    /**
     * Class to be added to the LocalManagerRegistry to allow registration of signal collectors.
     * This would typically implement an updated AnomalyDetectorManagerLocal interface.
     */
    private final class Local implements AnomalyDetectorManagerLocal {
        /**
         * Registers a new {@link SignalCollector} with the anomaly detector.
         *
         * @param <T> The specific type of {@link SignalCollectorConfig} that this collector
         *            handles.
         * @param <U> The specific type of {@link SignalCollectorData} that this collector
         *            produces.
         * @param configType The Class object representing the type of SignalCollectorConfig this
         *                   collector handles. This will be used as the primary key.
         * @param dataType The Class object representing the type of SignalCollectorData this
         *                 collector produces.
         * @param collector The instance of the SignalCollector to be registered.
         * @throws IllegalArgumentException if a collector handling the same config type
         *                                  is already registered.
         * @throws NullPointerException if configType, dataType, or collector is null.
         */
        @Override
        public <T extends SignalCollectorConfig,
                U extends SignalCollectorData> void registerSignalCollector(Class<T> configType,
                Class<U> dataType, SignalCollector<T, U> collector) {
            Objects.requireNonNull(configType, "Config type cannot be null");
            Objects.requireNonNull(dataType, "Data type cannot be null");
            Objects.requireNonNull(collector, "SignalCollector cannot be null");

            synchronized (mRegisteredCollectors) {
                if (mRegisteredCollectors.containsKey(configType)) {
                    throw new IllegalArgumentException("Collector for config type '"
                            + configType.getSimpleName() + "' is already registered.");
                }
                CollectorEntry entry = new CollectorEntry(collector, dataType);
                mRegisteredCollectors.put(configType, entry);
            }
            Slog.i(TAG, "Registered SignalCollector for config: '"
                    + configType.getSimpleName()
                    + "', producing data: '" + dataType.getSimpleName() + "'");
        }
    }

    /**
     * Retrieves a registered SignalCollector by its config type and verifies the expected data
     * type.
     *
     * @param configType The exact Class type of SignalCollectorConfig this collector handles.
     * @param dataType The exact Class type of SignalCollectorData this collector is expected to
     *                 produce. This is used for verification.
     * @param <T> The expected type of SignalCollectorConfig.
     * @param <U> The expected type of SignalCollectorData.
     * @return The typed SignalCollector instance, or null if no collector is found for the
     *         specified config type, if the registered object is of an incompatible type,
     *         or if the registered data type does not match the expected dataType.
     * @hide
     */
    @Nullable
    public <T extends SignalCollectorConfig,
            U extends SignalCollectorData> SignalCollector<T, U> getSignalCollector(
                    Class<T> configType, Class<U> dataType) {
        Objects.requireNonNull(configType, "Config type cannot be null");
        Objects.requireNonNull(dataType, "Data type cannot be null");

        synchronized (mRegisteredCollectors) {
            CollectorEntry entry = mRegisteredCollectors.get(configType);

            if (entry == null) {
                Slog.w(TAG, "No collector entry found for config type: "
                        + configType.getSimpleName());
                return null; // Collector not found for this config type
            }

            Object rawCollector = entry.getCollector();

            if (!(rawCollector instanceof SignalCollector)) {
                Slog.e(TAG, "Type mismatch: Object retrieved for config type '"
                        + configType.getSimpleName() + "' is not a SignalCollector. Actual type: "
                        + rawCollector.getClass().getName());
                return null; // Treat as incompatible type
            }

            if (!dataType.equals(entry.getDataType())) {
                Slog.e(TAG, "Data type mismatch for config type '" + configType.getSimpleName()
                        + "'. Expected: " + dataType.getSimpleName()
                        + ", but registered with: " + entry.getDataType().getSimpleName());
                return null; // Registered data type does not match expected
            }

            // If we reach here, rawCollector is a SignalCollector<?, ?> and its registered
            // dataType matches the requested dataType. The configType also matches by virtue of
            // being the map key.
            // The cast to SignalCollector<T, U> is considered safe.
            @SuppressWarnings("unchecked")
            SignalCollector<T, U> collector = (SignalCollector<T, U>) rawCollector;
            return collector;
        }
    }

    /**
     * Request a subscription to data from a registered SignalCollector.
     *
     * @param config The specific configuration for the subscription.
     * @param dataType The Class object representing the type of SignalCollectorData expected.
     * @param listener The listener to receive data updates.
     * @param <T> The type of SignalCollectorConfig.
     * @param <U> The type of SignalCollectorData.
     * @return The {@link SubscriptionId} if successful.
     * @throws IllegalArgumentException if the collector is not found or types mismatch.
     *
     * @hide
     */
    public <T extends SignalCollectorConfig,
            U extends SignalCollectorData> SubscriptionId subscribeToData(T config,
            Class<U> dataType, OutcomeReceiver<U, Throwable> listener) {
        @SuppressWarnings("unchecked")
        Class<T> configType = (Class<T>) config.getClass();
        SignalCollector<T, U> collector = getSignalCollector(configType, dataType);

        if (collector == null) {
            throw new IllegalArgumentException("No suitable collector found for config type '"
                    + config.getClass().getSimpleName()
                    + "' and data type '" + dataType.getSimpleName() + "'.");
        }
        Slog.i(TAG, "Subscribing to data via collector for config: "
                + config.getClass().getSimpleName());
        return collector.subscribe(config, listener);
    }

    /**
     * Request the current data snapshot from a registered SignalCollector.
     *
     * @param subscriptionId The {@link SubscriptionId} identifying the specific subscription
     *                       for which to retrieve current data.
     * @param configType The expected Class type of SignalCollectorConfig (for type safety in
     *                   retrieval).
     * @param dataType The expected Class type of SignalCollectorData (for type safety in
     *                 retrieval).
     * @param <T> The type of SignalCollectorConfig.
     * @param <U> The type of SignalCollectorData.
     * @return The current data snapshot.
     * @throws IllegalArgumentException if a collector for the specified config and data types is
     *                                  not found.
     * @hide
     */
    public <T extends SignalCollectorConfig,
            U extends SignalCollectorData> U getCurrentData(SubscriptionId subscriptionId,
                Class<T> configType, Class<U> dataType) {
        SignalCollector<T, U> collector = getSignalCollector(configType, dataType);
        if (collector == null) {
            throw new IllegalArgumentException("No suitable collector found for config type '"
                    + configType.getSimpleName() + "' and data type '"
                    + dataType.getSimpleName() + "'.");
        }
        Slog.i(TAG, "Getting current data from collector for config: "
                + configType.getSimpleName());
        return collector.getData(subscriptionId);
    }

    /**
     * Request an immediate update for a specific subscription from a registered SignalCollector.
     *
     * @param subscriptionId The {@link SubscriptionId} to request an update for.
     * @param configType The expected Class type of SignalCollectorConfig (for type safety in
     *                   retrieval).
     * @param dataType The expected Class type of SignalCollectorData (for type safety in
     *                 retrieval).
     * @param <T> The type of SignalCollectorConfig.
     * @param <U> The type of SignalCollectorData.
     * @throws IllegalArgumentException if a collector for the specified config and data types is
     * not found, or the subscriptionId is invalid.
     *
     * @hide
     */
    public <T extends SignalCollectorConfig,
            U extends SignalCollectorData> void requestSubscriptionUpdate(
                    SubscriptionId subscriptionId, Class<T> configType, Class<U> dataType) {
        SignalCollector<T, U> collector = getSignalCollector(configType, dataType);
        if (collector == null) {
            throw new IllegalArgumentException("No suitable collector found for config type '"
                    + configType.getSimpleName() + "' and data type '" + dataType.getSimpleName()
                    + "'.");
        }
        Slog.i(TAG, "Requesting update for subscription '" + subscriptionId
                + "' via collector for config: " + configType.getSimpleName());
        collector.requestUpdate(subscriptionId);
    }


    /**
     * Unsubscribe from a data stream.
     *
     * @param subscriptionId The {@link SubscriptionId} to unsubscribe.
     * @param configType The expected Class type of SignalCollectorConfig (for type safety in
     *                   retrieval).
     * @param dataType The expected Class type of SignalCollectorData (for type safety in
     *                 retrieval).
     * @param <T> The type of SignalCollectorConfig.
     * @param <U> The type of SignalCollectorData.
     * @throws IllegalArgumentException if a collector for the specified config and data types is
     *                                  not found.
     * @hide
     */
    public <T extends SignalCollectorConfig,
            U extends SignalCollectorData> void unsubscribeFromData(SubscriptionId subscriptionId,
                Class<T> configType, Class<U> dataType) {
        SignalCollector<T, U> collector = getSignalCollector(configType, dataType);
        if (collector == null) {
            throw new IllegalArgumentException("No suitable collector found for config type '"
                    + configType.getSimpleName() + "' and data type '" + dataType.getSimpleName()
                    + "'.");
        }
        Slog.i(TAG, "Unsubscribing from data via collector for config: "
                + configType.getSimpleName() + " with subscriptionId: " + subscriptionId);
        collector.unsubscribe(subscriptionId);
    }
}
