/*
 * Copyright (C) 2026 The Android Open Source Project
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

package com.android.os.profiling.anomaly.wrapper;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.AnomalyProfilingManager;
import android.os.AnomalyRequestResult;
import android.os.Bundle;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * Interface for {@link AnomalyProfilingManager} to allow for mocking in tests.
 *
 * @hide
 */
public interface AnomalyProfilingClient {
    /**
     * @see AnomalyProfilingManager#registerCallback(Consumer)
     */
    void registerCallback(@NonNull Consumer<AnomalyRequestResult> callback);

    /**
     * @see AnomalyProfilingManager#isTriggerRegistered(int, String, int)
     */
    boolean isTriggerRegistered(int uid, @NonNull String packageName, int triggerType);

    /**
     * @see AnomalyProfilingManager#sendAnomalyProfile(int, String, int, String, String)
     */
    UUID sendAnomalyProfile(
            int uid,
            @NonNull String packageName,
            @AnomalyProfilingManager.AnomalyTriggerType int triggerType,
            @Nullable String tag,
            @NonNull String resultFileName);

    /**
     * @see AnomalyProfilingManager#collectAnomalyProfile(int, String, int, int, String, Bundle)
     */
    UUID collectAnomalyProfile(
            int uid,
            @NonNull String packageName,
            @AnomalyProfilingManager.AnomalyProfilingType int profilingType,
            @AnomalyProfilingManager.AnomalyTriggerType int triggerType,
            @Nullable String tag,
            @Nullable Bundle params);

    /**
     * @see AnomalyProfilingManager#collectAndSendAnomalyProfile(int, String, int, int, String,
     *     Bundle)
     */
    UUID collectAndSendAnomalyProfile(
            int uid,
            @NonNull String packageName,
            @AnomalyProfilingManager.AnomalyProfilingType int profilingType,
            @AnomalyProfilingManager.AnomalyTriggerType int triggerType,
            @Nullable String tag,
            @Nullable Bundle params);

    /**
     * @see AnomalyProfilingManager#stopProfiling(UUID)
     */
    void stopProfiling(UUID key);
}
