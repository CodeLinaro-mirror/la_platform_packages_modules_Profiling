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

import static android.os.ProfilingManager.KEY_DURATION_MS;

import android.annotation.Nullable;
import android.os.AnomalyProfilingManager;
import android.os.AnomalyRequestResult;
import android.os.Bundle;
import android.os.ProfilingManager;
import android.os.ProfilingResult;
import android.os.ProfilingTrigger;
import android.util.SparseArray;

import com.android.internal.annotations.VisibleForTesting;
import com.android.os.profiling.anomaly.util.LogUtil;
import com.android.os.profiling.anomaly.wrapper.AnomalyProfilingClient;
import com.android.os.profiling.anomaly.wrapper.AnomalyProfilingManagerWrapper;

import java.time.Instant;
import java.util.UUID;

/**
 * A helper class that helps the anomaly detector to communicate with Profiling manager, so it can
 * obtain a profile of the process that causes an anomaly.
 *
 * @hide
 */
public class ProfilingSessionHelper {
    private static final LogUtil sLog = new LogUtil("ProfilingHelper");

    private final Object mLock = new Object();

    private final AnomalyProfilingClient mAnomalyProfilingManager;

    private final SparseArray<SessionInfo> mUidSessionInfoSparseArray = new SparseArray<>();

    public ProfilingSessionHelper() {
        this(new AnomalyProfilingManagerWrapper(new AnomalyProfilingManager()));
    }

    @VisibleForTesting
    ProfilingSessionHelper(AnomalyProfilingClient anomalyProfilingManager) {
        mAnomalyProfilingManager = anomalyProfilingManager;
        mAnomalyProfilingManager.registerCallback(this::handleSessionResult);
    }

    private void handleSessionResult(AnomalyRequestResult anomalyRequestResult) {
        int uid = anomalyRequestResult.getUid();
        synchronized (mLock) {
            if (!mUidSessionInfoSparseArray.contains(uid)) {
                sLog.e(
                        String.format(
                                "Received AnomalyRequestResult for UID %d, but no SessionInfo to"
                                        + " relate it to",
                                uid));
                return;
            }
            mUidSessionInfoSparseArray.remove(uid);
        }

        // TODO: b/467021367 - In follow up CL, mark and check the session's acceptability
        if (anomalyRequestResult.getErrorCode() == ProfilingResult.ERROR_NONE) {
            // TODO: b/476499105 - Write metadata to a JSON file and put it in a zip
            // with the trace file
            sLog.d(
                    String.format(
                            "Profiling completed, session info: %s, result path:" + " %s",
                            anomalyRequestResult.getTag(),
                            anomalyRequestResult.getResultFilePath()));
        } else {
            sLog.d(
                    String.format(
                            "Profiling ERROR, session info: %s, error code: %d",
                            anomalyRequestResult.getTag(), anomalyRequestResult.getErrorCode()));
        }
    }

    /**
     * Start collecting a trace through ProfilingManager for the given UID and package name.
     *
     * @param uid The UID to collect the trace for
     * @param packageName The package name to collect the trace for
     */
    public void startProfiling(
            int uid,
            String packageName,
            int maxSessionDurationMs,
            Bundle sessionParams,
            @ProfilingManager.ProfilingType int profilingType) {
        // TODO: b/467021367 - Follow up CL: add logic for marking a session as accepted.
        Bundle params = new Bundle();
        params.putInt(KEY_DURATION_MS, maxSessionDurationMs);
        params.putAll(sessionParams);
        UUID sessionId =
                mAnomalyProfilingManager.collectAnomalyProfile(
                        uid,
                        packageName,
                        profilingType,
                        ProfilingTrigger.TRIGGER_TYPE_ANOMALY,
                        /* tag= */ null,
                        params);
        SessionInfo sessionInfo =
                new SessionInfo(
                        sessionId,
                        uid,
                        packageName,
                        /* result= */ null,
                        Instant.ofEpochMilli(System.currentTimeMillis()));
        synchronized (mLock) {
            mUidSessionInfoSparseArray.put(uid, sessionInfo);
        }
    }

    public record SessionInfo(
            UUID sessionId,
            int uid,
            String packageName,
            @Nullable AnomalyRequestResult result,
            Instant startTime) {
        public SessionInfo(SessionInfo sessionInfo, @Nullable AnomalyRequestResult result) {
            this(
                    sessionInfo.sessionId,
                    sessionInfo.uid,
                    sessionInfo.packageName,
                    result,
                    sessionInfo.startTime);
        }
    }
}
