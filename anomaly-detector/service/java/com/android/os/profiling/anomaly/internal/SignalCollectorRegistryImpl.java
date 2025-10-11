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

package com.android.os.profiling.anomaly.internal;

import android.annotation.Nullable;
import android.util.ArrayMap;
import android.util.Slog;

import com.android.os.profiling.anomaly.collector.SignalCollector;
import com.android.os.profiling.anomaly.collector.SignalCollectorConfig;
import com.android.os.profiling.anomaly.collector.SignalCollectorData;
import com.android.os.profiling.anomaly.core.SignalCollectorRegistry;
import com.android.os.profiling.anomaly.core.SignalTypeId;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Implementation of {@link SignalCollectorRegistry}.
 *
 * @hide
 */
public final class SignalCollectorRegistryImpl implements SignalCollectorRegistry {
    private static final String TAG = "SignalCollectorRegistry";

    /** Map from signal type ID to the registered collector. */
    private final Map<SignalTypeId, SignalCollector<?, ?>> mRegisteredCollectors = new ArrayMap<>();

    /** Map from callback to the executor it should be invoked on. */
    private final Map<Consumer<SignalCollector<?, ?>>, Executor> mCollectorRegisteredCallbacks =
            new ArrayMap<>();

    /** {@inheritDoc} */
    @Override
    public <T extends SignalCollectorConfig, U extends SignalCollectorData>
            void registerSignalCollector(
                    Class<T> configType, Class<U> dataType, SignalCollector<T, U> collector) {
        Objects.requireNonNull(configType, "Config type cannot be null");
        Objects.requireNonNull(dataType, "Data type cannot be null");
        Objects.requireNonNull(collector, "SignalCollector cannot be null");

        final Map<Consumer<SignalCollector<?, ?>>, Executor> callbacksToExecute;
        final SignalTypeId signalTypeId = new SignalTypeId(configType, dataType);
        synchronized (mRegisteredCollectors) {
            if (mRegisteredCollectors.containsKey(signalTypeId)) {
                throw new IllegalArgumentException(
                        "Collector for " + signalTypeId + " is already registered.");
            }
            mRegisteredCollectors.put(signalTypeId, collector);
            callbacksToExecute = new ArrayMap<>();
            callbacksToExecute.putAll(mCollectorRegisteredCallbacks);
        }

        for (Map.Entry<Consumer<SignalCollector<?, ?>>, Executor> entry :
                callbacksToExecute.entrySet()) {
            entry.getValue().execute(() -> entry.getKey().accept(collector));
        }

        Slog.i(TAG, "Registered SignalCollector for " + signalTypeId);
    }

    /** {@inheritDoc} */
    @Override
    public void addCollectorRegisteredCallback(
            Executor executor, Consumer<SignalCollector<?, ?>> callback) {
        Objects.requireNonNull(executor, "Executor cannot be null");
        Objects.requireNonNull(callback, "Callback cannot be null");
        List<SignalCollector<?, ?>> existingCollectors;
        synchronized (mRegisteredCollectors) {
            mCollectorRegisteredCallbacks.put(callback, executor);
            existingCollectors = new ArrayList<>(mRegisteredCollectors.values());
        }
        for (SignalCollector<?, ?> collector : existingCollectors) {
            executor.execute(() -> callback.accept(collector));
        }
    }

    /** {@inheritDoc} */
    @Override
    public void removeCollectorRegisteredCallback(Consumer<SignalCollector<?, ?>> callback) {
        Objects.requireNonNull(callback, "Callback cannot be null");
        synchronized (mRegisteredCollectors) {
            mCollectorRegisteredCallbacks.remove(callback);
        }
    }

    /** {@inheritDoc} */
    @Override
    @Nullable
    public <T extends SignalCollectorConfig, U extends SignalCollectorData>
            SignalCollector<T, U> getSignalCollector(Class<T> configType, Class<U> dataType) {
        Objects.requireNonNull(configType, "Config type cannot be null");
        Objects.requireNonNull(dataType, "Data type cannot be null");

        final SignalTypeId signalTypeId = new SignalTypeId(configType, dataType);
        synchronized (mRegisteredCollectors) {
            SignalCollector<?, ?> rawCollector = mRegisteredCollectors.get(signalTypeId);

            if (rawCollector == null) {
                Slog.w(TAG, "No collector entry found for " + signalTypeId);
                return null; // Collector not found
            }

            // The key (SignalTypeId) already ensures that configType and dataType match what was
            // registered. The cast is safe because registration ensures the collector instance
            // matches the types in the SignalTypeId.
            @SuppressWarnings("unchecked")
            SignalCollector<T, U> collector = (SignalCollector<T, U>) rawCollector;
            return collector;
        }
    }
}
