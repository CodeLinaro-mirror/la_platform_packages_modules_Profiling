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

package com.android.os.profiling.anomaly.handler;

import android.app.ActivityManager;
import android.os.Process;
import android.util.Slog;

import com.android.os.profiling.anomaly.attribute.UidAttribute;
import com.android.os.profiling.anomaly.core.AnomalyHandler;
import com.android.os.profiling.anomaly.core.AnomalyReport;
import com.android.os.profiling.anomaly.wrapper.SystemServiceFetcher;

/**
 * Kills the application that caused the anomaly.
 *
 * @hide
 */
public final class KillAnomalyHandler implements AnomalyHandler {
    private static final String TAG = "KillAnomalyHandler";
    private final SystemServiceFetcher mSystemServiceFetcher;

    public KillAnomalyHandler(SystemServiceFetcher systemServiceFetcher) {
        mSystemServiceFetcher = systemServiceFetcher;
    }

    @Override
    public void execute(AnomalyReport report) {
        UidAttribute uidAttribute = report.get(UidAttribute.class);
        if (uidAttribute == null) {
            Slog.w(
                    TAG,
                    "Cannot execute kill action: "
                            + "AnomalyReport does not contain a UidAttribute component.");
            return;
        }

        int uid = uidAttribute.uid();
        if (uid < Process.FIRST_APPLICATION_UID) {
            Slog.w(TAG, "Cannot kill UID " + uid + ": not an application UID.");
            return;
        }

        Slog.i(TAG, "Executing kill action for UID: " + uid);
        ActivityManager activityManager = mSystemServiceFetcher.getActivityManager();
        if (activityManager != null) {
            activityManager.killUid(uid, "Anomaly detected");
        }
    }
}
