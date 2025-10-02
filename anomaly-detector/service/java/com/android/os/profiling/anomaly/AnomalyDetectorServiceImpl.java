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

import static android.Manifest.permission.CONFIGURE_ANOMALY_DETECTOR;

import android.content.Context;
import android.os.IAnomalyDetectorService;
import android.os.RuleParcel;

import java.util.List;

/**
 * Implementation of {@link android.os.IAnomalyDetectorService} binder service.
 *
 * @hide
 */
public final class AnomalyDetectorServiceImpl extends IAnomalyDetectorService.Stub {

    private final Context mContext;

    public AnomalyDetectorServiceImpl(Context context) {
        mContext = context;
    }

    @Override
    public void setRules(List<RuleParcel> ruleParcelList) {
        mContext.enforceCallingOrSelfPermission(
                CONFIGURE_ANOMALY_DETECTOR,
                "the caller does not have the required permission to set anomaly detector rules");
        // TODO(b/423096026): Use the rules to detect anomalies.
    }
}
