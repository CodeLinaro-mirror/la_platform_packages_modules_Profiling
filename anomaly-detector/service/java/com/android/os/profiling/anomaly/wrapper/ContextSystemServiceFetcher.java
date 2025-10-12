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

package com.android.os.profiling.anomaly.wrapper;

import android.app.ActivityManager;
import android.content.Context;

import java.util.Objects;

/**
 * Real implementation using the Android Context.
 *
 * @hide
 */
public final class ContextSystemServiceFetcher implements SystemServiceFetcher {
    private final Context mContext;

    public ContextSystemServiceFetcher(Context context) {
        Objects.requireNonNull(context, "Context cannot be null");
        mContext = context.getApplicationContext();
    }

    @Override
    public ActivityManager getActivityManager() {
        return mContext.getSystemService(ActivityManager.class);
    }
}
