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
package android.os;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.os.OutcomeReceiver;
import android.os.profiling.Flags;

import java.lang.Exception;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * API for apps to request and listen for app specific profiling.
 */
@FlaggedApi(Flags.FLAG_TELEMETRY_APIS)
public class ProfilingManager {

    /** @hide */
    public ProfilingManager(Context context) {}

    /**
     * Request profiling via perfetto.
     *
     * <p class="note"> Note: use of this API directly is not recommended for most use cases.
     * Please use the higher level wrappers provided by androidx that will construct the request
     * correctly based on available options and simplified user provided request parameters.</p>
     *
     * <p class="note"> Note: requests are not guaranteed to be filled.</p>
     *
     * <p class="note"> Note: a listener must be set for the request to be considered for
     * fulfillment. Listeners can be set in this method, with {@see #registerForProfilingResult},
     * or both. If no listener is set the request will be discarded.</p>
     *
     * @param profilingRequest byte array representation of ProfilingRequest proto containing all
     *                         necessary information about the collection being requested.
     * @param tag Caller defined data to help identify the output.
     * @param cancellationSignal for caller requested cancellation.
     * @param executor The executor to call back with.
     * @param listener Listener to be triggered with result.
     */
    public void requestProfiling(
            @NonNull byte[] profilingRequest,
            @Nullable String tag,
            @Nullable CancellationSignal cancellationSignal,
            @Nullable Executor executor,
            @Nullable Consumer<ProfilingResult> listener) {
        // TODO b/293957254
    }

    /**
     * Register a listener to be called for all profiling results.
     *
     * @param executor The executor to call back with.
     * @param listener Listener to be triggered with result.
     */
    public void registerForProfilingResults(
            @NonNull Executor executor,
            @NonNull Consumer<ProfilingResult> listener) {
        // TODO b/293957254
    }

    /**
     * Unregister a listener to be called for all profiling results. If no listener is provided,
     * all listeners for this process that were not submitted with a currently running trace will
     * be removed.
     *
     * @param listener Listener to unregister and no longer be triggered with the result.
     */
    public void unregisterForProfilingResults(
            @Nullable Consumer<ProfilingResult> listener) {
        // TODO b/293957254
    }
}
