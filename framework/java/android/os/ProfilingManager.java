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
import android.annotation.TargetApi;
import android.content.Context;
import android.os.Binder;
import android.os.CancellationSignal;
import android.os.IProfilingService;
import android.os.ProfilingRequest;
import android.os.profiling.Flags;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.lang.Exception;
import java.lang.IllegalArgumentException;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * API for apps to request and listen for app specific profiling.
 */
@FlaggedApi(Flags.FLAG_TELEMETRY_APIS)
public final class ProfilingManager {
    private static final String TAG = ProfilingManager.class.getSimpleName();
    private static final boolean DEBUG = false;

    private static final Object sLock = new Object();
    private final Context mContext;

    @GuardedBy("sLock")
    private final ArrayList<ProfilingRequestCallbackWrapper> mCallbacks = new ArrayList<>();

    @GuardedBy("sLock")
    private IProfilingService mProfilingService;

    /**
     * Constructor for ProfilingManager.
     *
     * @hide
     */
    public ProfilingManager(Context context) {
        mContext = context;
    }

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
        synchronized (sLock) {
            try {
                final UUID key = UUID.randomUUID();

                if (executor != null && listener != null) {
                    // Listeners are provided, store them.
                    mCallbacks.add(new ProfilingRequestCallbackWrapper(executor, listener, key));
                } else if (mCallbacks.isEmpty()) {
                    // No listeners have been registered by any path, toss the request.
                    throw new IllegalArgumentException(
                            "No listeners have been registered. Request has been discarded.");
                }
                // If neither case above was hit, app wide listeners were provided. Continue.

                final IProfilingService service = getIProfilingServiceLocked();
                if (service == null) {
                    executor.execute(() -> listener.accept(
                            new ProfilingResult(ProfilingResult.ERROR_UNKNOWN, null, tag,
                                "ProfilingService is not available")));
                    if (DEBUG) Log.d(TAG, "ProfilingService is not available");
                    return;
                }
                // For key, use most and least signifcant bits so we can create an identical UUID
                // after passing over binder.
                service.requestProfiling(profilingRequest, tag, key.getMostSignificantBits(),
                        key.getLeastSignificantBits());
                if (cancellationSignal != null) {
                    cancellationSignal.setOnCancelListener(
                        () -> {
                            synchronized (sLock) {
                                try {
                                    service.requestCancel(key.getMostSignificantBits(),
                                            key.getLeastSignificantBits());
                                } catch (RemoteException e) {
                                    // Ignore, request in flight already and we can't stop it.
                                }
                            }
                        }
                    );
                }
            } catch (RemoteException e) {
                if (DEBUG) Log.d(TAG, "Binder exception processing request", e);
                executor.execute(() -> listener.accept(
                        new ProfilingResult(ProfilingResult.ERROR_UNKNOWN, null, tag,
                                "Binder exception processing request")));
                throw new RuntimeException("Unable to request profiling.");
            }
        }
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
        synchronized (sLock) {
            mCallbacks.add(new ProfilingRequestCallbackWrapper(executor, listener, null));
        }
    }

    /**
     * Unregister a listener to be called for all profiling results. If no listener is provided,
     * all listeners for this process that were not submitted with a currently running trace will
     * be removed.
     *
     * @param listener Listener to unregister and no longer be triggered with the result.
     */
    public void unregisterForProfilingResults(
            @NonNull Consumer<ProfilingResult> listener) {
        synchronized (sLock) {
            if (mCallbacks.isEmpty()) {
                // No callbacks, nothing to remove.
                return;
            }
            for (int i = 0; i < mCallbacks.size(); i++) {
                ProfilingRequestCallbackWrapper wrapper = mCallbacks.get(i);
                if (listener.equals(wrapper.mListener)) {
                    mCallbacks.remove(i);
                    return;
                }
            }
        }
    }

    @TargetApi(35)
    @GuardedBy("sLock")
    private @Nullable IProfilingService getIProfilingServiceLocked() {
        if (mProfilingService != null) {
            return mProfilingService;
        }
        mProfilingService = IProfilingService.Stub.asInterface(
                ProfilingFrameworkInitializer.getProfilingServiceManager()
                    .getProfilingServiceRegisterer().get());
        if (mProfilingService == null) {
            // Service is not accessible, all requests will fail.
            return mProfilingService;
        }
        try {
            mProfilingService.registerResultsCallback(new IProfilingResultCallback.Stub() {
                @Override
                public void sendResult(long keyMostSigBits, long keyLeastSigBits, int status,
                        String filePath, String tag, String error) {
                    synchronized (sLock) {
                        if (mCallbacks.isEmpty()) {
                            // This shouldn't happen - no callbacks, nowhere to report this result.
                            if (DEBUG) Log.d(TAG, "No callbacks");
                            return;
                        }
                        UUID key = new UUID(keyMostSigBits, keyLeastSigBits);
                        int removeListenerPos = -1;
                        for (int i = 0; i < mCallbacks.size(); i++) {
                            ProfilingRequestCallbackWrapper wrapper = mCallbacks.get(i);
                            if (key.equals(wrapper.mKey)) {
                                // At most 1 listener can have a key matching this result: the one
                                // registered with the request, remove that one only.
                                if (removeListenerPos == -1) {
                                    removeListenerPos = i;
                                } else {
                                    // This should never happen.
                                    if (DEBUG) Log.d(TAG, "More than 1 listener with the same key");
                                }
                            } else if (wrapper.mKey != null) {
                                // If the key is not null, and doesn't matched the result key, then
                                // this key belongs to another request and should not be triggered.
                                continue;
                            }
                            wrapper.mExecutor.execute(() -> wrapper.mListener.accept(
                                    new ProfilingResult(status, filePath, tag, error)));
                        }
                        if (removeListenerPos != -1) {
                            mCallbacks.remove(removeListenerPos);
                        }
                    }
                }
            });
        } catch (RemoteException e) {
            if (DEBUG) Log.d(TAG, "Exception registering service callback", e);
            throw new RuntimeException("Unable to register profiling result callback."
                    + " All Profiling requests will fail.");
        }
        return mProfilingService;
    }

    private static final class ProfilingRequestCallbackWrapper {
        /** executor provided with callback request */
        final @NonNull Executor mExecutor;

        /** listener provided with callback request */
        final @NonNull Consumer<ProfilingResult> mListener;

        /**
         * Unique key generated with each profiling request {@see #requestProfiling}, but not with
         * requests to register a listener only {@see #registerForProfilingResult}.
         *
         * Key is used to match the result with the listener added with the request so that it can
         * removed after being triggered while the general registered callbacks remain active.
         */
        final @Nullable UUID mKey;

        ProfilingRequestCallbackWrapper(@NonNull Executor executor,
                @NonNull Consumer<ProfilingResult> listener,
                @Nullable UUID key) {
            mExecutor = executor;
            mListener = listener;
            mKey = key;
        }
    }
}
