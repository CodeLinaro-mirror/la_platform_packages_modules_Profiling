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

import com.android.os.profiling.anomaly.attribute.SummaryAttribute;
import com.android.os.profiling.anomaly.core.AnomalyHandler;
import com.android.os.profiling.anomaly.core.AnomalyReport;
import com.android.os.profiling.anomaly.util.LogUtil;

/**
 * Logs a report of the anomaly to logcat.
 *
 * @hide
 */
public final class LogAnomalyHandler implements AnomalyHandler {
    private static final String TAG = "LogAnomalyHandler";
    private static final LogUtil sLog = new LogUtil(TAG);

    @Override
    public void execute(AnomalyReport report) {
        SummaryAttribute summaryAttribute = report.get(SummaryAttribute.class);
        if (summaryAttribute == null) {
            sLog.w(
                    "Cannot log summary: "
                            + "AnomalyReport does not contain a SummaryAttribute component.");
            return;
        }

        String summary = summaryAttribute.summary();
        sLog.w("ANOMALY DETECTED: " + summary);
    }
}
