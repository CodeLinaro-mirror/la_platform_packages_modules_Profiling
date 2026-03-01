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

import com.android.os.profiling.anomaly.attribute.BinderSpamDetailsAttribute;
import com.android.os.profiling.anomaly.attribute.UidAttribute;
import com.android.os.profiling.anomaly.handler.AnomalyStatsAtomsLog;
import com.android.os.profiling.anomaly.core.AnomalyHandler;
import com.android.os.profiling.anomaly.core.AnomalyReport;
import com.android.os.profiling.anomaly.util.LogUtil;


/**
 * Logs anomaly stats when an anomaly is detected.
 *
 * @hide
 */
public final class AnomalyStatsHandler implements AnomalyHandler {
    private static final LogUtil sLog = new LogUtil("AnomalyStatsHandler");

    @Override
    public void execute(AnomalyReport report) {
        final BinderSpamDetailsAttribute binderSpamDetails =
                report.get(BinderSpamDetailsAttribute.class);
        final UidAttribute uidAttribute = report.get(UidAttribute.class);

        if (uidAttribute == null) {
            sLog.w("Cannot log atom for anomaly with null uid.");
            return;
        }

        if (binderSpamDetails != null) {
            AnomalyStatsAtomsLog.write(AnomalyStatsAtomsLog.ANOMALY_STATS_BINDER_SPAM,
                    uidAttribute.uid(),
                    binderSpamDetails.interfaceName(),
                    binderSpamDetails.methodName(),
                    binderSpamDetails.observedCallCount(),
                    binderSpamDetails.observedInterval().toMillis());
        } else {
            // TODO(b/483136751): Log unknown anomaly types.
            sLog.w("Cannot log atom for unknown anomaly type.");
        }
    }
}
