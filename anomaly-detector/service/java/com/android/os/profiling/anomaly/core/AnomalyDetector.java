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

package com.android.os.profiling.anomaly.core;

import android.annotation.Nullable;
import android.os.profiling.anomaly.RuleInternal;
import android.os.profiling.anomaly.RuleInternal.ConditionType;

import java.util.Set;

/**
 * An abstract base class for an anomaly detector.
 *
 * <p>Each detector is responsible for evaluating data against a single {@link RuleInternal} and
 * reporting when an anomaly is found.
 *
 * @hide
 */
public abstract class AnomalyDetector {

    /** Listener for when an anomaly is detected. */
    public interface OnAnomalyDetectedListener {
        /**
         * Called when an anomaly has been detected.
         *
         * @param report The details of the detected anomaly.
         */
        void onAnomalyDetected(AnomalyReport report);
    }

    private OnAnomalyDetectedListener mListener;

    /** A factory for creating anomaly detectors and getting their metadata. */
    public interface AnomalyDetectorFactory {
        /**
         * Creates a new instance of the anomaly detector.
         *
         * @param registry The registry used to look up signal collectors.
         */
        AnomalyDetector create(SignalCollectorRegistry registry);

        /** Returns the set of signal collector types required by this anomaly detector. */
        Set<SignalTypeId> getRequiredSignalCollectorTypes();

        /** Returns the condition type that this factory's detectors handle. */
        @ConditionType
        String getConditionType();
    }

    /**
     * Sets the rule that this detector should use for its evaluation.
     *
     * <p>When a rule is set, the detector should subscribe to any necessary data streams. When the
     * rule is set to {@code null}, the detector should unsubscribe and clean up its resources.
     *
     * @param rule The rule to apply, or {@code null} to clear the current rule.
     */
    public abstract void setRule(RuleInternal rule);

    /**
     * Sets the listener to be notified when an anomaly is detected.
     *
     * @param listener The listener to notify, or {@code null} to clear the existing listener.
     */
    public void setOnAnomalyDetectedListener(@Nullable OnAnomalyDetectedListener listener) {
        mListener = listener;
    }

    /**
     * Reports a detected anomaly to the registered listener.
     *
     * @param report The details of the detected anomaly.
     */
    protected void reportAnomaly(AnomalyReport report) {
        if (mListener != null) {
            mListener.onAnomalyDetected(report);
        }
    }
}
