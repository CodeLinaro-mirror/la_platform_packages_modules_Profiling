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

package android.os;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresNoPermission;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Dedicated manager class for {@link com.android.os.profiling.anomaly.AnomalyDetectorService} to
 * interact with {@link ProfilingService} to collect profiling. Anomaly detector interacts with
 * {@link ProfilingService} through the APIs defined here only.
 *
 * <p>Prior to requesting any profiling, a callback should be registered using {@link
 * #registerCallback} in order to receive results and/or status updates on profiling requests.
 *
 * <p>Each method which either requests profiling or sending a result to an app will return a UUID
 * key. This key should be used for mapping results/status updates back to the requests which it
 * relates to. Additionally, this key should be used for {@link #stopProfiling} to stop an ongoing
 * profiling session.
 *
 * @hide
 */
public final class AnomalyProfilingManager {

    private static final String TAG = AnomalyProfilingManager.class.getSimpleName();
    private static final boolean DEBUG = false;

    /*
     * Values 100-199 are reserved for use in {@link AnomalyProfilingManager}, do not use values
     * outside this range. Other profiling type values are defined in {@link ProfilingManager}.
     */

    /**
     * Profiling type to request a copy of the ongoing background system trace. If a background
     * trace is not running, this request will not be fulfilled.
     *
     * <p>If requesting a new system trace, use {@link
     * ProfilingManager#PROFILING_TYPE_SYSTEM_TRACE}.
     *
     * <p>This profiling type is defined here, rather than in {@link ProfilingManager} with the
     * other profiling types, as this type is not and will not supported by {@link
     * ProfilingManager}, it is supported through the APIs defined here only. Non anomaly triggers
     * execute this profiling type via {@link ProfilingServiceHelper#onProfilingTriggerOccurred} or
     * {@link ProfilingManager#requestRunningSystemTrace} with differences in enforcements and
     * parameters.
     */
    public static final int PROFILING_TYPE_SYSTEM_TRACE_ONGOING = 100;

    private final Object mLock = new Object();

    @Nullable
    @GuardedBy("mLock")
    private IProfilingService mProfilingService = null;

    @Nullable
    @GuardedBy("mLock")
    private Consumer<AnomalyRequestResult> mCallback = null;

    @IntDef(
            prefix = {"PROFILING_TYPE_"},
            value = {
                ProfilingManager.PROFILING_TYPE_JAVA_HEAP_DUMP,
                ProfilingManager.PROFILING_TYPE_HEAP_PROFILE,
                ProfilingManager.PROFILING_TYPE_STACK_SAMPLING,
                ProfilingManager.PROFILING_TYPE_SYSTEM_TRACE,
                PROFILING_TYPE_SYSTEM_TRACE_ONGOING,
            })
    @Retention(RetentionPolicy.SOURCE)
    public @interface AnomalyProfilingType {}

    @IntDef(
            prefix = {"TRIGGER_TYPE_"},
            value = {
                ProfilingTrigger.TRIGGER_TYPE_ANOMALY,
                ProfilingTrigger.TRIGGER_TYPE_APP_COMPAT,
            })
    @Retention(RetentionPolicy.SOURCE)
    public @interface AnomalyTriggerType {}

    public AnomalyProfilingManager() {
        if (!android.os.profiling.anomaly.flags.Flags.anomalyDetectorCore()) {
            throw new IllegalStateException(
                    "Attempting to use AnomalyProfilingManager with flag off.");
        }
    }

    /**
     * Register a callback to receive profiling results.
     *
     * <p>Note: This callback will receive all anomaly callbacks, even if they are from another
     * instance of this class.
     *
     * <p>Note: The callback registered here will replace any callback previously registered by
     * calling this method.
     */
    public void registerCallback(@NonNull Consumer<AnomalyRequestResult> callback) {
        mCallback = callback;
    }

    /** Check whether a trigger is registered to specific process. */
    public boolean isTriggerRegistered(int uid, @NonNull String packageName, int triggerType) {
        try {
            return getOrCreateIProfilingServiceLocked()
                    .isTriggerRegistered(uid, packageName, triggerType);
        } catch (RemoteException e) {
            if (DEBUG) Log.e(TAG, "Exception checking trigger registered", e);
            return false;
        }
    }

    /**
     * Send a system anomaly to the specified process.
     *
     * <p>The file must already be placed in Profiling's temporary directory
     * (/data/misc/perfetto-traces/profiling). {@link ProfilingService} will handle moving it to the
     * app's directory and then sending the result to the app.
     *
     * @param uid The uid of the process to send the result to.
     * @param packageName The package name of the process to send the result to.
     * @param triggerType The trigger type of this profile. Must be an Anomaly trigger type.
     * @param resultFileName The file name of the file to send. Include the name only, not the path.
     * @param tag An optional tag to include in the result sent to the app.
     * @return A key which can be used to associate a callback back to its request.
     */
    public UUID sendAnomalyProfile(
            int uid,
            @NonNull String packageName,
            @AnomalyTriggerType int triggerType,
            @Nullable String tag,
            @NonNull String resultFileName) {
        synchronized (mLock) {
            final UUID key = UUID.randomUUID();
            try {
                getOrCreateIProfilingServiceLocked()
                        .sendAnomalyProfile(
                                key.getMostSignificantBits(),
                                key.getLeastSignificantBits(),
                                uid,
                                packageName,
                                triggerType,
                                tag,
                                resultFileName);
            } catch (RemoteException e) {
                if (DEBUG) Log.e(TAG, "Exception sending request to ProfilingService.", e);
            }
            return key;
        }
    }

    /**
     * Collect profiling for an anomaly, but return it to the requester and not to the process it
     * relates to.
     *
     * @param uid The uid of the process to collect the profile of.
     * @param packageName The package name of the process to collect the profile of.
     * @param profilingType The type of profiling which should be collected.
     * @param triggerType The trigger type of this profile. Must be an Anomaly trigger type.
     * @param tag An optional tag to include in the result sent to the app.
     * @param params An optional collection of parameters to apply to the profile configuration.
     * @return A key which can be used to associate a callback back to its request, as well as to
     *     stop the ongoing profiling.
     */
    public UUID collectAnomalyProfile(
            int uid,
            @NonNull String packageName,
            @AnomalyProfilingType int profilingType,
            @AnomalyTriggerType int triggerType,
            @Nullable String tag,
            @Nullable Bundle params) {
        final UUID key = UUID.randomUUID();
        synchronized (mLock) {
            try {
                getOrCreateIProfilingServiceLocked()
                        .collectAnomalyProfile(
                                key.getMostSignificantBits(),
                                key.getLeastSignificantBits(),
                                uid,
                                packageName,
                                profilingType,
                                triggerType,
                                true, /* returnToAnomalyDetectorOnly */
                                tag,
                                params);
            } catch (RemoteException e) {
                if (DEBUG) Log.e(TAG, "Exception sending request to ProfilingService.", e);
            }
        }
        return key;
    }

    /**
     * Collect profiling for an anomaly and send it to the relevant process.
     *
     * @param uid The uid of the process to collect the profile of and send the result to.
     * @param packageName The package name of the process to collect the profile of and send the
     *     result to.
     * @param profilingType The type of profiling which should be collected.
     * @param triggerType The trigger type of this profile. Must be an Anomaly trigger type.
     * @param tag An optional tag to include in the result sent to the app.
     * @param params An optional collection of parameters to apply to the profile configuration.
     * @return A key which can be used to associate a callback back to its request, as well as to
     *     stop the ongoing profiling.
     */
    public UUID collectAndSendAnomalyProfile(
            int uid,
            @NonNull String packageName,
            @AnomalyProfilingType int profilingType,
            @AnomalyTriggerType int triggerType,
            @Nullable String tag,
            @Nullable Bundle params) {
        synchronized (mLock) {
            final UUID key = UUID.randomUUID();
            try {
                getOrCreateIProfilingServiceLocked()
                        .collectAnomalyProfile(
                                key.getMostSignificantBits(),
                                key.getLeastSignificantBits(),
                                uid,
                                packageName,
                                profilingType,
                                triggerType,
                                false, /* returnToAnomalyDetectorOnly */
                                tag,
                                params);
            } catch (RemoteException e) {
                if (DEBUG) Log.e(TAG, "Exception sending request to ProfilingService.", e);
            }
            return key;
        }
    }

    /**
     * Stop an active profiling session.
     *
     * <p>Processing of the session will continue following the sessions original request, meaning
     * if a valid result is obtained it will be sent to either the app or anomaly detector as
     * defined by its original request.
     *
     * @param key Provided as a return type in each method that allows the request of profiling.
     */
    public void stopProfiling(UUID key) {
        synchronized (mLock) {
            try {
                getOrCreateIProfilingServiceLocked()
                        .requestCancel(key.getMostSignificantBits(), key.getLeastSignificantBits());
            } catch (RemoteException e) {
                // Ignore, if we can't communicate with service then there's nothing else that we
                // can do here.
                if (DEBUG) Log.e(TAG, "Exception trying to stop profiling", e);
            }
        }
    }

    @GuardedBy("mLock")
    private @NonNull IProfilingService getOrCreateIProfilingServiceLocked() throws RemoteException {
        if (mProfilingService != null) {
            return mProfilingService;
        }

        mProfilingService =
                IProfilingService.Stub.asInterface(
                        ProfilingFrameworkInitializer.getProfilingServiceManager()
                                .getProfilingServiceRegisterer()
                                .get());

        if (mProfilingService == null) {
            // Service is not accessible, all requests will fail.
            throw new RemoteException("Could not access ProfilingService.");
        }

        // Register callback
        mProfilingService.registerAnomalyCallback(
                new IProfilingAnomalyCallback.Stub() {

                    @Override
                    @RequiresNoPermission
                    public void sendResult(
                            @Nullable String resultFile,
                            long keyMostSigBits,
                            long keyLeastSigBits,
                            int uid,
                            int errorCode,
                            @Nullable String tag,
                            int triggerType) {
                        if (mCallback == null) {
                            // No callback was registered, can't send to anomaly detector. Status
                            // will not be sent again.
                            if (DEBUG) {
                                Log.e(
                                        TAG,
                                        "Anomaly profiling request result received with no"
                                                + " callbacks registered.",
                                        new Throwable());
                            }
                            return;
                        }

                        mCallback.accept(
                                new AnomalyRequestResult(
                                        new UUID(keyMostSigBits, keyLeastSigBits),
                                        uid,
                                        errorCode,
                                        resultFile,
                                        tag,
                                        triggerType));
                    }
                });

        return mProfilingService;
    }
}
