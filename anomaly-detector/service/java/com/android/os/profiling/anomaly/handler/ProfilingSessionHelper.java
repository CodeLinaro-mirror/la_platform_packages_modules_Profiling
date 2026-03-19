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

import static java.util.zip.Deflater.NO_COMPRESSION;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.AnomalyProfilingClient;
import android.os.AnomalyProfilingManager;
import android.os.AnomalyRequestResult;
import android.os.Bundle;
import android.os.ProfilingManager;
import android.os.ProfilingResult;
import android.os.ProfilingTrigger;
import android.os.profiling.anomaly.RuleInternal;
import android.profiling.utils.PerfettoMetadata;
import android.util.SparseArray;

import com.android.internal.annotations.VisibleForTesting;
import com.android.os.profiling.anomaly.config.ProfilingConcurrencyConfig;
import com.android.os.profiling.anomaly.ratelimiter.ProfilingRateLimiter;
import com.android.os.profiling.anomaly.util.LogUtil;
import com.android.os.profiling.anomaly.wrapper.ExecutorServiceWrapper;

import java.util.concurrent.Executor;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * A helper class that helps the anomaly detector to communicate with Profiling manager, so it can
 * obtain a profile of the process that causes an anomaly.
 *
 * @hide
 */
public class ProfilingSessionHelper {
    private static final LogUtil sLog = new LogUtil("ProfilingSessionHelper");

    private static final String JSON_ROOT_KEY = "perfetto_metadata";

    private final Object mLock = new Object();

    private final AnomalyProfilingClient mAnomalyProfilingManager;

    private final Executor mIoExecutor;

    @VisibleForTesting
    final SparseArray<SessionInfo> mUidSessionInfoSparseArray = new SparseArray<>();

    public ProfilingSessionHelper() {
        this(new AnomalyProfilingManager(),
                ExecutorServiceWrapper.getIOExecutor());
    }

    @VisibleForTesting
    ProfilingSessionHelper(
            AnomalyProfilingClient anomalyProfilingManager,
            Executor executor) {
        mAnomalyProfilingManager = anomalyProfilingManager;
        mAnomalyProfilingManager.registerCallback(this::handleSessionResult);
        mIoExecutor = executor;
    }

    @VisibleForTesting
    void handleSessionResult(AnomalyRequestResult anomalyRequestResult) {
        int uid = anomalyRequestResult.getUid();
        SessionInfo sessionInfo = null;
        synchronized (mLock) {
            if (!mUidSessionInfoSparseArray.contains(uid)) {
                sLog.e(
                        String.format(
                                "Received AnomalyRequestResult for UID %d, but no SessionInfo to"
                                        + " relate it to",
                                uid));
                return;
            }
            sessionInfo = mUidSessionInfoSparseArray.get(uid);
            mUidSessionInfoSparseArray.remove(uid);
        }

        if (anomalyRequestResult.getErrorCode() != ProfilingResult.ERROR_NONE) {
            sLog.d(
                    String.format(
                            "Profiling ERROR, package name: %s, error code: %d",
                            sessionInfo.packageName, anomalyRequestResult.getErrorCode()));
            return;
        }

        if (!sessionInfo.shouldAccept) {
            sLog.d(
                    String.format(
                            "Profiling completed, but not marked as accepted, uid: %d, package"
                                    + " name: %s",
                            uid, sessionInfo.packageName));
            return;
        }

        sessionInfo = new SessionInfo(sessionInfo, anomalyRequestResult, sessionInfo.shouldAccept);
        processResultAcceptance(sessionInfo);
        sLog.d(
                String.format(
                        "Profiling completed, package name: %s, result path: %s",
                        sessionInfo.packageName, anomalyRequestResult.getResultFilePath()));
    }

    /**
     * Attempts to start a profiling session for a given UID and package name.
     *
     * <p>This method performs several checks before initiating profiling:
     *
     * <ol>
     *   <li><b>Concurrency Checks:</b>
     *       <ul>
     *         <li>A device-wide limit on the number of concurrent profiling sessions is enforced. A
     *             new session is denied if this limit is reached.
     *         <li>If a session is already running for the specified UID, a new one is not started.
     *             Instead, for certain anomaly types like Binder Spam, the existing session may be
     *             marked as "acceptable" to ensure its results are processed.
     *       </ul>
     *   <li><b>Rate Limiting:</b> The request is checked against the {@link ProfilingRateLimiter}.
     *       If the request is denied (e.g., due to exceeding device-wide limits or being within a
     *       cool-down period), the method logs the event and returns without starting a session.
     * </ol>
     *
     * <p>If all checks pass, it proceeds to call the {@link AnomalyProfilingClient} to start the
     * profiling trace and records the new session's information for tracking.
     *
     * @param uid The UID of the process to profile.
     * @param packageName The package name associated with the process.
     * @param maxSessionDurationMs The maximum duration for the profiling session in milliseconds.
     * @param sessionParams A {@link Bundle} of additional parameters for the profiling session.
     * @param profilingType The type of profiling to perform, as defined in {@link
     *     ProfilingManager.ProfilingType}.
     * @param conditionType The type of anomaly that triggered this request, as defined in {@link
     *     RuleInternal.ConditionTypeInternal}.
     * @param profilingRateLimiter The rate limiter instance to check if the request is allowed.
     * @param signature A map of key-value pairs representing the specific anomaly signature, used
     *     for fine-grained rate-limiting. May be {@code null}.
     * @param anomalyDetails The anomaly details to be written to the trace metadata
     * @param anomalyDurationMillis The duration of the anomaly in milliseconds
     */
    public void requestProfiling(
            int uid,
            String packageName,
            long maxSessionDurationMillis,
            Bundle sessionParams,
            @ProfilingManager.ProfilingType int profilingType,
            @RuleInternal.ConditionTypeInternal String conditionType,
            ProfilingRateLimiter profilingRateLimiter,
            @Nullable Map<String, String> signature,
            ProfilingConcurrencyConfig profilingConcurrencyConfig,
            @NonNull PerfettoMetadata.AnomalyDetails anomalyDetails,
            long anomalyDurationMillis) {
        synchronized (mLock) {
            if (mUidSessionInfoSparseArray.contains(uid)) {
                updateOngoingSessionInfo(
                        uid, conditionType, packageName, anomalyDetails, anomalyDurationMillis);
                // If the incoming request has a UID that is already in the ongoing session list,
                // a new session should not be started.
                return;
            }
            if (mUidSessionInfoSparseArray.size()
                    >= profilingConcurrencyConfig.getDeviceMaxConcurrentSessions()) {
                sLog.i("Concurrency limit reached. Denying profiling request for UID " + uid);
                return;
            }
        }

        if (!profilingRateLimiter.isRequestAllowed(uid, conditionType, signature)) {
            sLog.i("Profiling request for UID " + uid + " was rate-limited.");
            return;
        }

        Bundle params = new Bundle();
        params.putLong(KEY_DURATION_MS, maxSessionDurationMillis);
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
                        conditionType,
                        Instant.ofEpochMilli(System.currentTimeMillis()),
                        /* result= */ null,
                        // TODO: b/485962021 - make the accept/reject logic of sessions configurable
                        /* shouldAccept= */ !conditionType.equals(
                                RuleInternal.CONDITION_TYPE_BINDER_SPAM),
                        new PerfettoMetadata());
        synchronized (mLock) {
            mUidSessionInfoSparseArray.put(uid, sessionInfo);
        }
    }

    /**
     * Process the result of the given profiling session and send the result to the package in
     * question.
     *
     * <p>This method only processes the result if it is completed and its result should be
     * accepted.
     *
     * @param sessionInfo The {@link SessionInfo} of the session to be processed
     */
    private void processResultAcceptance(SessionInfo sessionInfo) {
        if (!sessionInfo.shouldAccept
                || sessionInfo.result == null
                || sessionInfo.result.getResultFilePath() == null) {
            return;
        }

        mIoExecutor.execute(() -> {
            try {
                String bundledResultPath =
                        sessionInfo.perfettoMetadata.attachToProfilingResult(
                                sessionInfo.result.getResultFilePath());
                int anomalyTypeIndex = sessionInfo.conditionType.lastIndexOf('.') + 1;
                String anomalyType = sessionInfo.conditionType.substring(anomalyTypeIndex);
                mAnomalyProfilingManager.sendAnomalyProfile(
                        sessionInfo.uid,
                        sessionInfo.packageName,
                        ProfilingTrigger.TRIGGER_TYPE_ANOMALY,
                        anomalyType,
                        bundledResultPath);
            } catch (IOException e) {
                sLog.e("Unable to attach metadata to profiling result: %s", e);
            }
        });
    }

    /**
     * Update the ongoing session info with the given UID and condition type, update its
     * perfetto metadata with the anomaly details, and mark the session as acceptable if the
     * given condition type is the same as the ongoing session.
     *
     * @param uid The UID of the session
     * @param conditionType The condition type (anomaly type) of the session
     * @param packageName The package name of the session
     * @param anomalyDetails The anomaly details to be written to the trace metadata
     * @param anomalyDurationMillis The duration of the anomaly in milliseconds
     */
    @VisibleForTesting
    void updateOngoingSessionInfo(
            int uid,
            @RuleInternal.ConditionTypeInternal String conditionType,
            String packageName,
            PerfettoMetadata.AnomalyDetails anomalyDetails,
            long anomalyDurationMillis) {
        SessionInfo ongoingSessionInfo = mUidSessionInfoSparseArray.get(uid);
        // Mark the ongoing session as acceptable, if the ongoing session and the incoming
        // request both have the same condition type.
        if (ongoingSessionInfo != null && ongoingSessionInfo.conditionType.equals(conditionType)) {
            long currentTimeRelativeToSessionStart =
                    System.currentTimeMillis() - ongoingSessionInfo.startTime.toEpochMilli();
            int anomalyTypeIndex = conditionType.lastIndexOf('.') + 1;
            String anomalyType = conditionType.substring(anomalyTypeIndex);
            try {
                ongoingSessionInfo.perfettoMetadata.addAnomaly(
                        uid,
                        packageName,
                        currentTimeRelativeToSessionStart - anomalyDurationMillis,
                        currentTimeRelativeToSessionStart,
                        anomalyType,
                        anomalyDetails);
            } catch (JSONException e) {
                sLog.e("Failed to add anomaly details to metadata", e);
            }

            SessionInfo sessionInfoAcceptingResult =
                    new SessionInfo(
                            ongoingSessionInfo,
                            /* result= */ null,
                            // TODO: b/485962021 - make the accept/reject logic configurable
                            /* shouldAccept= */ true);
            mUidSessionInfoSparseArray.put(uid, sessionInfoAcceptingResult);
        }
    }

    public record SessionInfo(
            UUID sessionId,
            int uid,
            String packageName,
            @RuleInternal.ConditionTypeInternal String conditionType,
            Instant startTime,
            @Nullable AnomalyRequestResult result,
            boolean shouldAccept,
            @NonNull PerfettoMetadata perfettoMetadata) {
        public SessionInfo(
                SessionInfo sessionInfo,
                @Nullable AnomalyRequestResult result,
                boolean shouldAccept) {
            this(
                    sessionInfo.sessionId,
                    sessionInfo.uid,
                    sessionInfo.packageName,
                    sessionInfo.conditionType,
                    sessionInfo.startTime,
                    result,
                    shouldAccept,
                    sessionInfo.perfettoMetadata);
        }
    }
}
