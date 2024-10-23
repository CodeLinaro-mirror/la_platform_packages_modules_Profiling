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
import android.annotation.SystemApi;
import android.annotation.SystemApi.Client;
import android.os.profiling.Flags;

import com.android.internal.annotations.GuardedBy;

/**
 * Class for system to interact with {@link ProfilingService} to notify of trigger occurrences.
 *
 * @hide
 */
@FlaggedApi(Flags.FLAG_SYSTEM_TRIGGERED_PROFILING_NEW)
@SystemApi(client = Client.MODULE_LIBRARIES)
public class ProfilingServiceHelper {
    private static final String TAG = ProfilingServiceHelper.class.getSimpleName();
    private static final boolean DEBUG = false;

    private static final Object sLock = new Object();

    @Nullable
    @GuardedBy("sLock")
    private static ProfilingServiceHelper sInstance;

    private ProfilingServiceHelper() {}

    /** Returns an instance of {@link ProfilingServiceHelper}. */
    @NonNull
    public static ProfilingServiceHelper getInstance() {
        if (sInstance != null) {
            return sInstance;
        }

        synchronized (sLock) {
            if (sInstance == null) {
                sInstance = new ProfilingServiceHelper();
            }
            return sInstance;
        }
    }

    /** Send a trigger to {@link ProfilingService}. */
    public void onProfilingTriggerOccurred(int uid, @NonNull String packageName, int triggerType) {

    }
}
