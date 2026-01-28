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

package com.android.os.profiling.anomaly.internal;

import android.os.OutcomeReceiver;
import android.os.profiling.anomaly.RuleInternal;
import android.os.profiling.anomaly.RuleInternal.AnomalyActionTypeInternal;
import android.os.profiling.anomaly.RuleInternal.ConditionTypeInternal;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.os.profiling.anomaly.core.AnomalyDetector;
import com.android.os.profiling.anomaly.core.AnomalyDetectorController;
import com.android.os.profiling.anomaly.core.AnomalyDetectorRegistry;
import com.android.os.profiling.anomaly.core.AnomalyHandler;
import com.android.os.profiling.anomaly.core.AnomalyHandlerRegistry;
import com.android.os.profiling.anomaly.core.AnomalyReport;
import com.android.os.profiling.anomaly.core.RuleStorage;
import com.android.os.profiling.anomaly.core.SignalCollectorRegistry;
import com.android.os.profiling.anomaly.core.SignalTypeId;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * An implementation of the {@link AnomalyDetectorController}. This class is responsible for
 * managing the rules and signal collectors for anomaly detection.
 *
 * @hide
 */
public class AnomalyDetectorControllerImpl
        implements AnomalyDetectorController, AnomalyDetector.OnAnomalyDetectedListener {

    private static final String TAG = "AnomalyDetectorController";

    private final RuleStorage mRuleStorage;
    private final SignalCollectorRegistry mSignalCollectorRegistry;
    private final AnomalyHandlerRegistry mAnomalyHandlerRegistry;
    private final AnomalyDetectorRegistry mAnomalyDetectorRegistry;
    private final Executor mExecutor;

    private final Object mLock = new Object();

    @GuardedBy("mLock")
    private final Map<RuleInternal, AnomalyDetector> mActiveDetectors = new ArrayMap<>();

    @GuardedBy("mLock")
    private Set<RuleInternal> mRules = new ArraySet<>();

    /**
     * Constructs a new AnomalyDetectorControllerImpl.
     *
     * @param ruleStorage The storage for anomaly detection rules.
     * @param signalCollectorRegistry The registry for signal collectors.
     * @param anomalyHandlerRegistry The registry for anomaly handlers.
     * @param anomalyDetectorRegistry The registry for anomaly detectors.
     * @param executor The executor to use for callbacks.
     */
    public AnomalyDetectorControllerImpl(
            RuleStorage ruleStorage,
            SignalCollectorRegistry signalCollectorRegistry,
            AnomalyHandlerRegistry anomalyHandlerRegistry,
            AnomalyDetectorRegistry anomalyDetectorRegistry,
            Executor executor) {
        mRuleStorage = ruleStorage;
        mSignalCollectorRegistry = signalCollectorRegistry;
        mAnomalyHandlerRegistry = anomalyHandlerRegistry;
        mAnomalyDetectorRegistry = anomalyDetectorRegistry;
        mExecutor = executor;
        mSignalCollectorRegistry.addCollectorRegisteredCallback(
                mExecutor, this::onSignalCollectorRegistered);
        mSignalCollectorRegistry.addCollectorUnregisteredCallback(
                mExecutor, this::onSignalCollectorUnregistered);
    }

    /** {@inheritDoc} */
    @Override
    public void setRules(Set<RuleInternal> rules) {
        Objects.requireNonNull(rules, "Set<Rule> cannot be null");
        // Asynchronously save rules to storage.
        saveRules(rules);
        // Set the new rules without waiting for the save to complete. This makes sure
        // the new rules are applied even if for some reason saving fails.
        setRulesInternal(rules);
    }

    private void setRulesInternal(Set<RuleInternal> rules) {
        synchronized (mLock) {
            // Flush old rules and detectors
            for (AnomalyDetector detector : mActiveDetectors.values()) {
                detector.setRule(null);
            }
            mActiveDetectors.clear();

            mRules = rules;

            for (RuleInternal rule : mRules) {
                tryToActivateRule(rule);
            }
        }
    }

    private void saveRules(Set<RuleInternal> rules) {
        mRuleStorage.save(
                rules,
                mExecutor,
                new OutcomeReceiver<>() {
                    @Override
                    public void onResult(Void result) {
                        Slog.i(TAG, "Rules saved to storage.");
                    }

                    @Override
                    public void onError(Throwable error) {
                        Slog.e(TAG, "Failed to save rules to storage", error);
                    }
                });
    }

    /** {@inheritDoc} */
    @Override
    public void onSystemServicesReady() {
        mRuleStorage.load(
                mExecutor,
                new OutcomeReceiver<Set<RuleInternal>, Throwable>() {
                    @Override
                    public void onResult(Set<RuleInternal> rules) {
                        Slog.i(TAG, "Rules loaded from storage.");
                        setRulesInternal(rules);
                    }

                    @Override
                    public void onError(Throwable error) {
                        Slog.e(TAG, "Failed to load rules from storage", error);
                    }
                });
    }

    /**
     * Attempts to create and activate a detector for the given rule.
     *
     * <p>This method will do nothing if a factory for the rule's condition is not registered or if
     * the detector cannot be created (e.g., due to missing dependencies).
     *
     * @param rule The rule to be activated.
     */
    @GuardedBy("mLock")
    private void tryToActivateRule(RuleInternal rule) {
        @ConditionTypeInternal String conditionType = rule.getConditionType();
        AnomalyDetector.AnomalyDetectorFactory factory =
                mAnomalyDetectorRegistry.getFactory(conditionType);

        if (factory == null) {
            Slog.w(TAG, "No AnomalyDetectorFactory for condition: " + conditionType);
            return;
        }

        AnomalyDetector detector =
                mAnomalyDetectorRegistry.createDetectorForRule(rule, mSignalCollectorRegistry);

        if (detector != null) {
            Slog.i(TAG, "Created detector for rule: " + rule);
            detector.setOnAnomalyDetectedListener(this);
            mActiveDetectors.put(rule, detector);
        } else {
            Slog.w(TAG, "Failed to create detector for rule: " + rule);
        }
    }

    /** {@inheritDoc} */
    @Override
    public void onAnomalyDetected(AnomalyReport report) {
        Slog.i(TAG, "Anomaly detected");

        for (@AnomalyActionTypeInternal int action : report.getRule().getAnomalyActions()) {
            AnomalyHandler handler = mAnomalyHandlerRegistry.getHandler(action);
            if (handler != null) {
                handler.execute(report);
            } else {
                Slog.w(TAG, "No handler registered for action: " + action);
            }
        }
    }

    /**
     * Called when a new SignalCollector is registered with the system.
     *
     * <p>This triggers a re-evaluation of all current rules to see if any that were previously
     * inactive can now be activated with this new collector.
     *
     * @param signalTypeId The type ID of the newly registered collector.
     */
    private void onSignalCollectorRegistered(SignalTypeId signalTypeId) {
        synchronized (mLock) {
            Slog.i(
                    TAG,
                    "New SignalCollector registered: " + signalTypeId + ". Re-evaluating rules.");

            for (RuleInternal rule : mRules) {
                if (!mActiveDetectors.containsKey(rule)) {
                    Slog.i(TAG, "Re-evaluating rule that was not previously activated: " + rule);
                    tryToActivateRule(rule);
                }
            }
        }
    }

    /**
     * Called when a SignalCollector is unregistered from the system.
     *
     * <p>This method iterates through the active detectors and notifies any detector that depends
     * on the unregistered collector. The affected detectors are then removed.
     *
     * @param signalTypeId The type of the collector that was unregistered.
     */
    private void onSignalCollectorUnregistered(SignalTypeId signalTypeId) {
        synchronized (mLock) {
            Slog.i(
                    TAG,
                    "SignalCollector for " + signalTypeId + " unregistered. Re-evaluating rules.");

            mActiveDetectors
                    .entrySet()
                    .removeIf(entry -> handleUnregisteredCollectorLocked(entry, signalTypeId));
        }
    }

    /**
     * Handles the logic for a signal collector being unregistered.
     *
     * <p>This method is called for each active detector and determines if the detector depends on
     * the unregistered collector. If it does, it notifies the detector and returns {@code true} to
     * indicate that the detector should be removed.
     *
     * @param entry A map entry containing the rule and its active detector.
     * @param signalTypeId The type ID of the unregistered collector.
     * @return {@code true} if the detector was affected and should be removed, {@code false}
     *     otherwise.
     */
    @GuardedBy("mLock")
    private boolean handleUnregisteredCollectorLocked(
            Map.Entry<RuleInternal, AnomalyDetector> entry, SignalTypeId signalTypeId) {
        RuleInternal rule = entry.getKey();
        AnomalyDetector detector = entry.getValue();
        AnomalyDetector.AnomalyDetectorFactory factory =
                mAnomalyDetectorRegistry.getFactory(rule.getConditionType());

        if (factory != null) {
            Set<SignalTypeId> requiredTypes = factory.getRequiredSignalCollectorTypes();
            if (requiredTypes.contains(signalTypeId)) {
                Slog.i(
                        TAG,
                        "Detector for rule "
                                + rule
                                + " depends on the unregistered collector "
                                + signalTypeId);
                detector.onSignalCollectorUnregistered(signalTypeId);
                Slog.i(TAG, "Removed detector for rule: " + rule);
                return true;
            }
        }
        return false;
    }
}
