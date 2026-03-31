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

import android.annotation.Nullable;
import android.os.AnomalyProfilingClient;
import android.os.AnomalyProfilingManager;
import android.os.AnomalyRequestResult;
import android.os.Bundle;
import android.os.ProfilingManager;
import android.os.ProfilingResult;
import android.os.ProfilingTrigger;
import android.os.profiling.anomaly.RuleInternal;
import android.util.SparseArray;

import com.android.internal.annotations.VisibleForTesting;
import com.android.os.profiling.anomaly.util.LogUtil;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.UUID;
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

    @VisibleForTesting
    final SparseArray<SessionInfo> mUidSessionInfoSparseArray = new SparseArray<>();

    public ProfilingSessionHelper() {
        this(new AnomalyProfilingManager());
    }

    @VisibleForTesting
    ProfilingSessionHelper(AnomalyProfilingClient anomalyProfilingManager) {
        mAnomalyProfilingManager = anomalyProfilingManager;
        mAnomalyProfilingManager.registerCallback(this::handleSessionResult);
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
     * Start collecting a trace through ProfilingManager for the given UID and package name. If a
     * session is already ongoing, mark the session as acceptable, which allows it to be delivered
     * to the package in question when the profiling session is completed.
     *
     * @param uid The UID to collect the trace for
     * @param packageName The package name to collect the trace for
     */
    public void requestProfiling(
            int uid,
            String packageName,
            int maxSessionDurationMs,
            Bundle sessionParams,
            @ProfilingManager.ProfilingType int profilingType,
            @RuleInternal.ConditionTypeInternal String conditionType) {
        synchronized (mLock) {
            if (mUidSessionInfoSparseArray.contains(uid)) {
                markSessionAcceptable(uid, conditionType);
                // If the incoming request has a UID that is already in the ongoing session list,
                // a new session should not be started.
                return;
            }
        }

        Bundle params = new Bundle();
        params.putInt(KEY_DURATION_MS, maxSessionDurationMs);
        params.putAll(sessionParams);
        // TODO: b/477968969 - check with rate limiter before starting the profiling session
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
                                RuleInternal.CONDITION_TYPE_BINDER_SPAM));
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

        Path resultFilePath = Paths.get(sessionInfo.result.getResultFilePath());
        try {
            String metadata = getMetadataJson(sessionInfo);
            // Zip the trace file and add metadata
            // TODO: b/485370930 - determine what method to use for bundling file together
            File zipFile =
                    resultFilePath
                            .resolveSibling(
                                    sessionInfo.packageName
                                            + sessionInfo.startTime.toEpochMilli()
                                            + ".zip")
                            .toFile();
            addResultAndMetadataToZipFile(resultFilePath.toFile(), metadata, zipFile);
        } catch (JSONException e) {
            sLog.e("Failed to generate metadata from SessionInfo", e);
        }
    }

    /**
     * Add result and metadata files to a zip file
     *
     * @param resultFile A {@link File} to the result trace file
     * @param metadata A {@link String} of metadata
     * @param zipFile A {@link File} to the zip file
     */
    private void addResultAndMetadataToZipFile(File resultFile, String metadata, File zipFile) {
        try (FileOutputStream fileOutputStream = new FileOutputStream(zipFile)) {
            ZipOutputStream zipOutputStream = new ZipOutputStream(fileOutputStream);
            // Skipping compression here to avoid compressing an already compressed file
            zipOutputStream.setLevel(NO_COMPRESSION);
            // Copy the trace file to the zip
            zipOutputStream.putNextEntry(new ZipEntry(resultFile.getName()));
            byte[] buffer = new byte[1024];
            try (FileInputStream fis = new FileInputStream(resultFile)) {
                int length;
                while ((length = fis.read(buffer)) > 0) {
                    zipOutputStream.write(buffer, 0, length);
                }
            }
            zipOutputStream.closeEntry();

            // Add metadata to the zip
            zipOutputStream.putNextEntry(new ZipEntry("metadata"));
            zipOutputStream.write(metadata.getBytes(StandardCharsets.UTF_8));
            zipOutputStream.closeEntry();
            zipOutputStream.close();
        } catch (IOException e) {
            sLog.e("Unable to create the zip file: %s", e);
        }
    }

    /**
     * Convert the given {@link SessionInfo} to a JSON String containing the metadata in it
     *
     * @param sessionInfo The {@link SessionInfo} from which to generate the result JSON
     * @return A {@code String} containing the metadata, to be written to the metadata file
     */
    private String getMetadataJson(SessionInfo sessionInfo) throws JSONException {
        JSONObject jsonObject = new JSONObject();
        // TODO: b/476499105 - complete the JSON structure, add info to SessionInfo if needed
        jsonObject.put(JSON_ROOT_KEY, "placeholder");

        return jsonObject.toString();
    }

    /**
     * Mark an ongoing session acceptable.
     *
     * @param uid The UID of the session
     * @param conditionType The condition type (anomaly type) of the session
     */
    private void markSessionAcceptable(
            int uid, @RuleInternal.ConditionTypeInternal String conditionType) {
        SessionInfo ongoingSessionInfo = mUidSessionInfoSparseArray.get(uid);
        // Mark the ongoing session as acceptable, if the ongoing session and the incoming
        // request both have the condition type of Binder Spam.
        if (ongoingSessionInfo != null && ongoingSessionInfo.conditionType.equals(conditionType)) {
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
            boolean shouldAccept) {
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
                    shouldAccept);
        }
    }
}
