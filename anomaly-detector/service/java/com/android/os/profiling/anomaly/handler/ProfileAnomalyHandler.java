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

package com.android.os.profiling.anomaly.handler;

import android.annotation.Nullable;
import android.content.pm.PackageManager;
import android.os.AnomalyProfilingManager;
import android.os.ProfilingTrigger;
import android.text.TextUtils;

import com.android.internal.annotations.VisibleForTesting;
import com.android.os.profiling.anomaly.attribute.ProfilingParamsAttribute;
import com.android.os.profiling.anomaly.attribute.UidAttribute;
import com.android.os.profiling.anomaly.core.AnomalyHandler;
import com.android.os.profiling.anomaly.core.AnomalyReport;
import com.android.os.profiling.anomaly.util.LogUtil;
import com.android.os.profiling.anomaly.wrapper.AnomalyProfilingClient;
import com.android.os.profiling.anomaly.wrapper.AnomalyProfilingManagerWrapper;
import com.android.os.profiling.anomaly.wrapper.SystemServiceFetcher;

import java.util.ArrayList;
import java.util.List;

/**
 * Handler class for starting a profiling session through ProfilingManager
 *
 * @hide
 */
public final class ProfileAnomalyHandler implements AnomalyHandler {
    private static final LogUtil sLog = new LogUtil("ProfileAnomalyHandler");

    private final SystemServiceFetcher mSystemServiceFetcher;

    private final ProfilingSessionHelper mProfilingSessionHelper;

    private final AnomalyProfilingClient mAnomalyProfilingManager;

    public ProfileAnomalyHandler(SystemServiceFetcher systemServiceFetcher) {
        this(
                systemServiceFetcher,
                new ProfilingSessionHelper(),
                new AnomalyProfilingManagerWrapper(new AnomalyProfilingManager()));
    }

    @VisibleForTesting
    ProfileAnomalyHandler(
            SystemServiceFetcher systemServiceFetcher,
            ProfilingSessionHelper profilingSessionHelper,
            AnomalyProfilingClient anomalyProfilingManager) {
        mSystemServiceFetcher = systemServiceFetcher;
        mProfilingSessionHelper = profilingSessionHelper;
        mAnomalyProfilingManager = anomalyProfilingManager;
    }

    @Override
    public void execute(AnomalyReport report) {
        UidAttribute uidAttribute = report.get(UidAttribute.class);
        if (uidAttribute == null) {
            sLog.e("Received AnomalyReport without UID Attribute");
            return;
        }

        // TODO: b/477968969 - check with rate limiter before starting the profiling session
        ProfilingParamsAttribute profilingManagerParametersAttribute =
                report.get(ProfilingParamsAttribute.class);
        if (profilingManagerParametersAttribute == null) {
            sLog.e("No profiling parameters attribute in AnomalyReport");
            return;
        }

        String packageName = getRegisteredPackageNameFromUid(uidAttribute.uid());
        if (TextUtils.isEmpty(packageName)) {
            return;
        }

        sLog.d(
                String.format(
                        "Anomaly report received, starting profiling for uid: %d, packageName: %s",
                        uidAttribute.uid(), packageName));

        mProfilingSessionHelper.startProfiling(
                uidAttribute.uid(),
                packageName,
                profilingManagerParametersAttribute.maxSessionDurationMs(),
                profilingManagerParametersAttribute.sessionParams(),
                profilingManagerParametersAttribute.profilingType());
    }

    /**
     * Get a package name, which is associated with a UID, and is registered with ProfilingManager
     * for the {@code ProfilingTrigger.TRIGGER_TYPE_ANOMALY}
     *
     * @param uid The UID to query
     * @return The package name in the list returned by PackageManager that meets the criteria
     *     above. Null if none meets it, or multiple package names meets it.
     */
    @Nullable
    private String getRegisteredPackageNameFromUid(int uid) {
        PackageManager packageManager = mSystemServiceFetcher.getPackageManager();
        // Get the list of package names associated with the UID.
        String[] packageNames = packageManager.getPackagesForUid(uid);

        // If there is no package name associated with the UID.
        if (packageNames == null || packageNames.length == 0) {
            sLog.e(String.format("Package name for UID %d is not found", uid));
            return null;
        }

        List<String> packageNamesWithTriggerRegistered = new ArrayList<>();

        // Check all package names for this UID and only return the package name if only 1 has
        // registered to ProfilingManager with TRIGGER_TYPE_ANOMALY
        for (String curPackageName : packageNames) {
            if (mAnomalyProfilingManager.isTriggerRegistered(
                    uid, curPackageName, ProfilingTrigger.TRIGGER_TYPE_ANOMALY)) {
                packageNamesWithTriggerRegistered.add(curPackageName);
            }
        }

        if (packageNamesWithTriggerRegistered.isEmpty()) {
            sLog.e(
                    String.format(
                            "Package name for UID %d with TRIGGER_TYPE_ANOMALY registered is not"
                                    + " found",
                            uid));
            return null;
        }

        // TODO: b/483177199 - Log this occurrence of corner case
        if (packageNamesWithTriggerRegistered.size() > 1) {
            sLog.e(
                    String.format(
                            "Found multiple package name for UID %d with TRIGGER_TYPE_ANOMALY"
                                    + " registered",
                            uid));
            return null;
        }

        return packageNamesWithTriggerRegistered.get(0);
    }
}
