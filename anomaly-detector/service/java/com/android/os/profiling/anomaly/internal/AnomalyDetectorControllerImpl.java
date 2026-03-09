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
import com.android.os.profiling.anomaly.util.LogUtil;

import java.util.Collections;
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
    private static final LogUtil sLog = new LogUtil(TAG);

    private final RuleStorage mRuleStorage;
    private final SignalCollectorRegistry mSignalCollectorRegistry;
    private final AnomalyHandlerRegistry mAnomalyHandlerRegistry;
    private final AnomalyDetectorRegistry mAnomalyDetectorRegistry;
    private final Executor mExecutor;

    private final Object mLock = new Object();

    @GuardedBy("mLock")
    private final Map<@ConditionTypeInternal String, AnomalyDetector> mActiveDetectors =
            new ArrayMap<>();

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

    /** {@inheritDoc} */
    @Override
    public Set<RuleInternal> getRules() {
        synchronized (mLock) {
            return Collections.unmodifiableSet(mRules);
        }
    }

    private void setRulesInternal(Set<RuleInternal> rules) {
        synchronized (mLock) {
            mRules = rules;
            updateDetectors();
        }
    }

    private void saveRules(Set<RuleInternal> rules) {
        mRuleStorage.save(
                rules,
                mExecutor,
                new OutcomeReceiver<>() {
                    @Override
                    public void onResult(Void result) {
                        sLog.i("Rules saved to storage.");
                    }

                    @Override
                    public void onError(Throwable error) {
                        sLog.e("Failed to save rules to storage", error);
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
                        sLog.i("Rules loaded from storage.");
                        setRulesInternal(rules);
                    }

                    @Override
                    public void onError(Throwable error) {
                        sLog.e("Failed to load rules from storage", error);
                    }
                });
    }

    /**
     * Updates the active detectors based on the current set of rules.
     *
     * <p>This method groups the rules by their condition type and updates the corresponding {@link
     * AnomalyDetector}. If a detector for a specific condition type does not exist, it attempts to
     * create one using the {@link AnomalyDetectorRegistry}. Detectors that no longer have any
     * associated rules are deactivated and removed.
     *
     * <p>If a detector cannot be created (e.g., due to missing dependencies), the rules for that
     * condition type will remain inactive until the necessary dependencies become available.
     */
    @GuardedBy("mLock")
    private void updateDetectors() {
        // Group rules by condition type
        Map<@ConditionTypeInternal String, Set<RuleInternal>> rulesByType = new ArrayMap<>();
        for (RuleInternal rule : mRules) {
            rulesByType.computeIfAbsent(rule.getConditionType(), k -> new ArraySet<>()).add(rule);
        }

        // For each type, get/create detector and set rules
        for (Map.Entry<@ConditionTypeInternal String, Set<RuleInternal>> entry :
                rulesByType.entrySet()) {
            @ConditionTypeInternal String conditionType = entry.getKey();
            Set<RuleInternal> rules = entry.getValue();

            AnomalyDetector detector = mActiveDetectors.get(conditionType);
            if (detector == null) {
                detector =
                        mAnomalyDetectorRegistry.createDetectorForCondition(
                                conditionType, mSignalCollectorRegistry);
                if (detector != null) {
                    detector.setOnAnomalyDetectedListener(this);
                    mActiveDetectors.put(conditionType, detector);
                } else {
                    sLog.w("Failed to create detector for condition: " + conditionType);
                    continue;
                }
            }
            detector.setRules(rules);
        }

        // Cleanup detectors for types that no longer have rules.
        mActiveDetectors
                .entrySet()
                .removeIf(
                        entry -> {
                            if (!rulesByType.containsKey(entry.getKey())) {
                                entry.getValue().setRules(Collections.emptySet());
                                return true;
                            }
                            return false;
                        });
    }

    /** {@inheritDoc} */
    @Override
    public void onAnomalyDetected(AnomalyReport report) {
        sLog.i("Anomaly detected");

        for (@AnomalyActionTypeInternal int action : report.getRule().getAnomalyActions()) {
            AnomalyHandler handler = mAnomalyHandlerRegistry.getHandler(action);
            if (handler != null) {
                handler.execute(report);
            } else {
                sLog.w("No handler registered for action: " + action);
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
            sLog.i("New SignalCollector registered: " + signalTypeId + ". Re-evaluating rules.");
            updateDetectors();
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
            sLog.i("SignalCollector for " + signalTypeId + " unregistered. Re-evaluating rules.");

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
     * @param entry A map entry containing the condition type and its active detector.
     * @param signalTypeId The type ID of the unregistered collector.
     * @return {@code true} if the detector was affected and should be removed, {@code false}
     *     otherwise.
     */
    @GuardedBy("mLock")
    private boolean handleUnregisteredCollectorLocked(
            Map.Entry<@ConditionTypeInternal String, AnomalyDetector> entry,
            SignalTypeId signalTypeId) {
        @ConditionTypeInternal String conditionType = entry.getKey();
        AnomalyDetector detector = entry.getValue();
        AnomalyDetector.AnomalyDetectorFactory factory =
                mAnomalyDetectorRegistry.getFactory(conditionType);

        if (factory != null) {
            Set<SignalTypeId> requiredTypes = factory.getRequiredSignalCollectorTypes();
            if (requiredTypes.contains(signalTypeId)) {
                sLog.i(
                        "Detector for condition "
                                + conditionType
                                + " depends on the unregistered collector "
                                + signalTypeId);
                detector.onSignalCollectorUnregistered(signalTypeId);
                sLog.i("Removed detector for condition: " + conditionType);
                return true;
            }
        }
        return false;
    }
}
