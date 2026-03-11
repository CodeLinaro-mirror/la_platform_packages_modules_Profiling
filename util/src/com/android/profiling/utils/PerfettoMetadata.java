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

package android.profiling.utils;

import android.annotation.WorkerThread;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.annotation.concurrent.NotThreadSafe;

/**
 * A class to construct metadata for a Perfetto trace, which can be used to highlight anomalies.
 *
 * <p>The JSON schema is as follows:
 *
 * <pre>
 * {
 *   "perfetto_metadata": {
 *     "version": &lt;String&gt;,
 *     "regions_of_interest": [
 *       {
 *         "timeframe_range": {
 *           "timeframe_start": &lt;long&gt;,
 *           "timeframe_end": &lt;long&gt;
 *         },
 *         "target_focus": {
 *           "uid": &lt;int&gt;,
 *           "android_package_name": &lt;String&gt;
 *         },
 *         "highlight_reason": &lt;String&gt;,
 *         "anomaly_details": {
 *           // Anomaly specific details ...
 *         }
 *       }
 *     ]
 *   }
 * }
 * </pre>
 */
@NotThreadSafe
public final class PerfettoMetadata {
    private static final String TAG = PerfettoMetadata.class.getSimpleName();
    @VisibleForTesting public static final String METADATA_FILE_NAME = "perfetto_metadata.json";
    @VisibleForTesting public static final String ZIP_FILE_SUFFIX = "-metadata.zip";
    private static final int BUFFER_SIZE = 64 * 1024; // 64KB

    // -- Root key --
    /** The root key for the Perfetto metadata object. */
    @VisibleForTesting public static final String ROOT_KEY = "perfetto_metadata";

    // Begin keys under root key.

    // -- Version --
    /** The version of the metadata schema. Used for compatibility checks. */
    @VisibleForTesting public static final String VERSION_KEY = "version";

    /** The current version of the metadata schema. */
    @VisibleForTesting public static final String METADATA_VERSION = "1";

    /** Key for the list of regions of interest in the trace. */
    @VisibleForTesting public static final String REGIONS_OF_INTEREST_KEY = "regions_of_interest";

    // -- Begin keys under regions of interest --

    /** Key for the timeframe range object within a region of interest. */
    @VisibleForTesting public static final String TIMEFRAME_RANGE_KEY = "timeframe_range";

    // -- Begin keys under timeframe range --
    /**
     * Key for the start timestamp of the timeframe (in nanoseconds as preferred by Perfetto) based
     * on the trace timeline.
     */
    @VisibleForTesting public static final String TIMEFRAME_START_NANO_KEY = "timeframe_start";

    /**
     * Key for the end timestamp of the timeframe (in nanoseconds as preferred by Perfetto) based on
     * the trace timeline.
     */
    @VisibleForTesting public static final String TIMEFRAME_END_NANO_KEY = "timeframe_end";

    // -- End keys under timeframe range --

    /** Key for the target focus object (e.g., UID, package name). */
    @VisibleForTesting public static final String TARGET_FOCUS_KEY = "target_focus";

    // -- Begin keys under target focus --
    /** Key for the UID of the target focus. */
    @VisibleForTesting public static final String UID_KEY = "uid";

    /** Key for the Android package name of the target focus. */
    @VisibleForTesting public static final String PACKAGE_NAME_KEY = "android_package_name";

    // -- End keys under target focus --

    /** Key for the reason why this region is highlighted (e.g., "binder_spam"). */
    @VisibleForTesting public static final String HIGHLIGHT_REASON_KEY = "highlight_reason";

    /** Key for the object containing specific details about the anomaly. */
    @VisibleForTesting public static final String ANOMALY_DETAILS_KEY = "anomaly_details";

    // -- End keys under regions of interest --

    // -- End keys under root key --

    private final JSONObject mRoot;

    public PerfettoMetadata() {
        mRoot = new JSONObject();
        try {
            JSONObject metadata = new JSONObject();
            mRoot.put(ROOT_KEY, metadata);
            // TODO(b/488082809): Define a schema and create a proper implementation for
            // versioning.
            metadata.put(VERSION_KEY, METADATA_VERSION);
            metadata.put(REGIONS_OF_INTEREST_KEY, new JSONArray());
        } catch (JSONException e) {
            // This should never happen.
            throw new RuntimeException("Failed to initialize PerfettoMetadata", e);
        }
    }

    /**
     * Adds the anomaly input data to the metadata.
     *
     * @param uid The uid of the target focus.
     * @param packageName The package name of the target focus.
     * @param startTimeMillis The start time of the anomaly in milliseconds, based on the trace
     *     timeline. E.g., if the anomaly is detected 1000ms after the trace started, the
     *     startTimeMillis is 1000.
     * @param endTimeMillis The end time of the anomaly in milliseconds, based on the trace
     *     timeline. E.g., if the anomaly is detected 2000ms after the trace started, the
     *     endTimeMillis is 2000.
     * @param highlightReason The highlight reason of the anomaly, e.g. binder_spam.
     * @param anomalyDetails The details of the anomaly.
     * @throws JSONException If the JSON object cannot be modified.
     */
    public void addAnomaly(
            int uid,
            String packageName,
            long startTimeMillis,
            long endTimeMillis,
            String highlightReason,
            AnomalyDetails anomalyDetails)
            throws JSONException {
        JSONObject metadata = mRoot.getJSONObject(ROOT_KEY);
        JSONArray regions = metadata.getJSONArray(REGIONS_OF_INTEREST_KEY);
        regions.put(
                createRegionJson(
                        uid,
                        packageName,
                        TimeUnit.MILLISECONDS.toNanos(startTimeMillis),
                        TimeUnit.MILLISECONDS.toNanos(endTimeMillis),
                        highlightReason,
                        anomalyDetails.mJson));
    }

    private static JSONObject createRegionJson(
            int uid,
            String packageName,
            long startTimeNs,
            long endTimeNs,
            String highlightReason,
            JSONObject anomalyDetails)
            throws JSONException {
        JSONObject region = new JSONObject();

        // Timeframe
        JSONObject timeframe = new JSONObject();
        timeframe.put(TIMEFRAME_START_NANO_KEY, startTimeNs);
        timeframe.put(TIMEFRAME_END_NANO_KEY, endTimeNs);
        region.put(TIMEFRAME_RANGE_KEY, timeframe);

        // Target Focus
        JSONObject focus = new JSONObject();
        focus.put(UID_KEY, uid);
        focus.put(PACKAGE_NAME_KEY, packageName);
        region.put(TARGET_FOCUS_KEY, focus);

        region.put(HIGHLIGHT_REASON_KEY, highlightReason);

        region.put(ANOMALY_DETAILS_KEY, anomalyDetails);

        return region;
    }

    /**
     * Attaches this metadata as a separate file to a profiling result file by creating a zip
     * bundle.
     *
     * <p>This method does not compress the files when bundling. This is equivalent to invoking
     * <code> {@link #attachToProfilingResult(String, boolean)
     * attachToProfilingResult(profilingResultPath,&nbsp;false)}</code>.
     *
     * @param profilingResultPath The path to the original profiling result file.
     * @return The path to the bundled file which contains this metadata file and the profiling
     *     result file separately. The path will be the same as the original trace file, with a
     *     "-metadata.zip" suffix.
     * @throws IOException If an I/O error occurs.
     */
    @WorkerThread
    public String attachToProfilingResult(String profilingResultPath) throws IOException {
        return attachToProfilingResult(profilingResultPath, /* compressed= */ false);
    }

    /**
     * Attaches this metadata as a separate file to a profiling result file by creating a zip
     * bundle.
     *
     * @param profilingResultPath The path to the original profiling result file.
     * @param compressed Whether to compress the zip entries.
     * @return The path to the bundled file which contains this metadata file and the profiling
     *     result file separately. The path will be the same as the original trace file, with a
     *     "-metadata.zip" suffix.
     * @throws IOException If an I/O error occurs.
     */
    @WorkerThread
    public String attachToProfilingResult(String profilingResultPath, boolean compressed)
            throws IOException {
        if (Objects.requireNonNull(profilingResultPath, "profilingResultPath can not be null!")
                .isBlank()) {
            throw new IllegalArgumentException(
                    "Path to the profiling result file can not be blank!");
        }

        Path profilingResult;
        Path outputFilePath;
        try {
            profilingResult = Path.of(profilingResultPath);
            outputFilePath = Path.of(profilingResultPath + ZIP_FILE_SUFFIX);
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException(
                    "Invalid profiling result path: " + profilingResultPath, e);
        }

        if (!Files.isRegularFile(profilingResult) || !Files.isReadable(profilingResult)) {
            throw new IllegalArgumentException(
                    "Profiling result file does not exist, is a directory, or is not readable: "
                            + profilingResultPath);
        }

        try {
            try (OutputStream os =
                            Files.newOutputStream(outputFilePath, StandardOpenOption.CREATE_NEW);
                    BufferedOutputStream bos = new BufferedOutputStream(os);
                    ZipOutputStream zos = new ZipOutputStream(bos)) {
                if (!compressed) {
                    zos.setLevel(Deflater.NO_COMPRESSION);
                }

                // Add the trace file.
                ZipEntry traceEntry = new ZipEntry(profilingResult.getFileName().toString());
                zos.putNextEntry(traceEntry);
                try (InputStream is = Files.newInputStream(profilingResult);
                        BufferedInputStream bis = new BufferedInputStream(is)) {
                    byte[] buffer = new byte[BUFFER_SIZE];
                    int length;
                    while ((length = bis.read(buffer)) > 0) {
                        zos.write(buffer, 0, length);
                    }
                }
                zos.closeEntry();

                // Add the metadata file.
                byte[] metadataBytes = toString().getBytes(StandardCharsets.UTF_8);
                ZipEntry metadataEntry = new ZipEntry(METADATA_FILE_NAME);
                zos.putNextEntry(metadataEntry);
                zos.write(metadataBytes);
                zos.closeEntry();
            }
            return outputFilePath.toString();
        } catch (IOException | RuntimeException e) {
            Slog.e(TAG, "Failed to create profiling result and metadata bundle!", e);
            try {
                Files.deleteIfExists(outputFilePath);
            } catch (IOException cleanUpException) {
                Slog.e(TAG, "Failed to clean up partial bundle file!", cleanUpException);
            }
            // Rethrow so that the caller knows it failed.
            throw e;
        }
    }

    /**
     * Returns the metadata as a string.
     *
     * @return The metadata as a JSON string.
     */
    @Override
    public String toString() {
        return mRoot.toString();
    }

    /**
     * A class that represents the details of an anomaly.
     *
     * <p>The JSON schema depends on the anomaly type.
     *
     * <p>For binder spam:
     *
     * <pre>
     * {
     *   "spammed_binder_target": &lt;String&gt;,
     *   "detected_spam_rate": &lt;double&gt;,
     *   "expected_rate_threshold": &lt;double&gt;,
     * }
     * </pre>
     */
    public static final class AnomalyDetails {
        // -- Begin Binder spam keys --
        /**
         * Key for the binder interface and method that was spammed (e.g., "com.example.IFoo#bar").
         */
        public static final String SPAMMED_BINDER_TARGET_KEY = "spammed_binder_target";

        /** Key for the threshold rate that triggered the anomaly. */
        public static final String EXPECTED_RATE_THRESHOLD_KEY = "expected_rate_threshold";

        /** Key for the detected spam rate. */
        public static final String DETECTED_SPAM_RATE_KEY = "detected_spam_rate";

        // -- End Binder spam keys --

        private final JSONObject mJson = new JSONObject();

        // private constructor to force users to use the static factory methods.
        private AnomalyDetails() {}

        /**
         * Creates an AnomalyDetails object for a memory limit anomaly.
         *
         * @return An AnomalyDetails object with the memory limit details.
         */
        public static AnomalyDetails ofMemoryLimit() {
            return new AnomalyDetails();
        }

        /**
         * Creates an AnomalyDetails object for a binder spam anomaly.
         *
         * @param interfaceName The interface name of the spammed binder target.
         * @param methodName The method name of the spammed binder target.
         * @param observedRate The observed rate (calls/s) of the spammed binder target.
         * @param thresholdRate The threshold rate (calls/s) of the spammed binder target.
         * @return An AnomalyDetails object with the binder spam details.
         * @throws JSONException If the JSON object cannot be created.
         */
        public static AnomalyDetails ofBinderSpam(
                String interfaceName, String methodName, double observedRate, double thresholdRate)
                throws JSONException {
            AnomalyDetails details = new AnomalyDetails();
            details.mJson.put(SPAMMED_BINDER_TARGET_KEY, interfaceName + "#" + methodName);
            details.mJson.put(DETECTED_SPAM_RATE_KEY, observedRate);
            details.mJson.put(EXPECTED_RATE_THRESHOLD_KEY, thresholdRate);
            return details;
        }

        /**
         * Returns the anomaly details as a string.
         *
         * @return The anomaly details as a JSON string.
         */
        @Override
        public String toString() {
            return mJson.toString();
        }
    }
}
