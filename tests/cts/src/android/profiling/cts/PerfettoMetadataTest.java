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

package android.profiling.cts;

import static com.google.common.truth.Truth.assertThat;

import android.profiling.utils.PerfettoMetadata;

import androidx.test.runner.AndroidJUnit4;

import com.google.common.truth.Expect;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.TimeUnit;

// TODO(b/473875650): Drop this test class when we have actual integration tests that verifies API
// behavior in ProfilingService.
@RunWith(AndroidJUnit4.class)
public class PerfettoMetadataTest {

    private static final String INTERFACE_NAME = "com.interface";
    private static final String METHOD_NAME = "method";
    private static final String EXPECTED_BINDER_TARGET = INTERFACE_NAME + "#" + METHOD_NAME;
    private static final double BINDER_SPAM_OBSERVED_RATE = 100.0;
    private static final double BINDER_SPAM_THRESHOLD_RATE = 50.0;
    private static final int UID = 123;
    private static final String PACKAGE_NAME = "com.example.package";
    private static final long START_TIME_MILLIS = 1000L;
    private static final long END_TIME_MILLIS = 2000L;
    private static final String HIGHLIGHT_REASON = "reason";
    private static final double EXPECTED_SPAM_RATE = 100.0;
    private static final double EXPECTED_THRESHOLD_RATE = 50.0;

    @Rule public final Expect mExpect = Expect.create();

    @Test
    public void createPerfettoMetadata() throws Exception {
        PerfettoMetadata metadata = new PerfettoMetadata();
        JSONObject root = new JSONObject(metadata.toString());

        assertThat(root.has(PerfettoMetadata.ROOT_KEY)).isTrue();
        JSONObject meta = root.getJSONObject(PerfettoMetadata.ROOT_KEY);
        mExpect.that(meta.getString(PerfettoMetadata.VERSION_KEY))
                .isEqualTo(PerfettoMetadata.METADATA_VERSION);
        mExpect.that(meta.getJSONArray(PerfettoMetadata.REGIONS_OF_INTEREST_KEY).length())
                .isEqualTo(0);
    }

    @Test
    public void addAnomaly_binderSpam_anomalyAdded() throws Exception {
        PerfettoMetadata metadata = new PerfettoMetadata();
        PerfettoMetadata.AnomalyDetails details =
                PerfettoMetadata.AnomalyDetails.ofBinderSpam(
                        INTERFACE_NAME,
                        METHOD_NAME,
                        BINDER_SPAM_OBSERVED_RATE,
                        BINDER_SPAM_THRESHOLD_RATE);

        metadata.addAnomaly(
                UID, PACKAGE_NAME, START_TIME_MILLIS, END_TIME_MILLIS, HIGHLIGHT_REASON, details);

        JSONObject root = new JSONObject(metadata.toString());
        JSONObject meta = root.getJSONObject(PerfettoMetadata.ROOT_KEY);
        JSONArray regions = meta.getJSONArray(PerfettoMetadata.REGIONS_OF_INTEREST_KEY);
        assertThat(regions.length()).isEqualTo(1);

        JSONObject region = regions.getJSONObject(0);
        mExpect.that(region.getString(PerfettoMetadata.HIGHLIGHT_REASON_KEY))
                .isEqualTo(HIGHLIGHT_REASON);

        JSONObject timeframe = region.getJSONObject(PerfettoMetadata.TIMEFRAME_RANGE_KEY);
        mExpect.that(timeframe.getLong(PerfettoMetadata.TIMEFRAME_START_NANO_KEY))
                .isEqualTo(TimeUnit.MILLISECONDS.toNanos(START_TIME_MILLIS));
        mExpect.that(timeframe.getLong(PerfettoMetadata.TIMEFRAME_END_NANO_KEY))
                .isEqualTo(TimeUnit.MILLISECONDS.toNanos(END_TIME_MILLIS));

        JSONObject focus = region.getJSONObject(PerfettoMetadata.TARGET_FOCUS_KEY);
        mExpect.that(focus.getInt(PerfettoMetadata.UID_KEY)).isEqualTo(UID);
        mExpect.that(focus.getString(PerfettoMetadata.PACKAGE_NAME_KEY)).isEqualTo(PACKAGE_NAME);

        JSONObject anomalyDetails = region.getJSONObject(PerfettoMetadata.ANOMALY_DETAILS_KEY);
        mExpect.that(
                        anomalyDetails.getString(
                                PerfettoMetadata.AnomalyDetails.SPAMMED_BINDER_TARGET_KEY))
                .isEqualTo(EXPECTED_BINDER_TARGET);
        mExpect.that(
                        anomalyDetails.getDouble(
                                PerfettoMetadata.AnomalyDetails.DETECTED_SPAM_RATE_KEY))
                .isEqualTo(EXPECTED_SPAM_RATE);
        mExpect.that(
                        anomalyDetails.getDouble(
                                PerfettoMetadata.AnomalyDetails.EXPECTED_RATE_THRESHOLD_KEY))
                .isEqualTo(EXPECTED_THRESHOLD_RATE);
    }

    @Test
    public void ofBinderSpam_createAnomalyDetailsOfBinderSpams() throws Exception {
        PerfettoMetadata.AnomalyDetails details =
                PerfettoMetadata.AnomalyDetails.ofBinderSpam(
                        INTERFACE_NAME,
                        METHOD_NAME,
                        BINDER_SPAM_OBSERVED_RATE,
                        BINDER_SPAM_THRESHOLD_RATE);

        JSONObject json = new JSONObject(details.toString());
        mExpect.that(json.getString(PerfettoMetadata.AnomalyDetails.SPAMMED_BINDER_TARGET_KEY))
                .isEqualTo(EXPECTED_BINDER_TARGET);
        mExpect.that(json.getDouble(PerfettoMetadata.AnomalyDetails.DETECTED_SPAM_RATE_KEY))
                .isEqualTo(EXPECTED_SPAM_RATE);
        mExpect.that(json.getDouble(PerfettoMetadata.AnomalyDetails.EXPECTED_RATE_THRESHOLD_KEY))
                .isEqualTo(EXPECTED_THRESHOLD_RATE);
    }
}
