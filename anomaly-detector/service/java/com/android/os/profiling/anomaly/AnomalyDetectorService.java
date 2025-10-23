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

package com.android.os.profiling.anomaly;

import static android.Manifest.permission.CONFIGURE_ANOMALY_DETECTOR;

import android.annotation.FlaggedApi;
import android.content.Context;
import android.os.Environment;
import android.os.OutcomeReceiver;
import android.os.profiling.anomaly.IAnomalyDetectorService;
import android.os.profiling.anomaly.Rule;
import android.os.profiling.anomaly.RuleParcel;
import android.os.profiling.anomaly.flags.Flags;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.os.profiling.anomaly.collector.SignalCollector;
import com.android.os.profiling.anomaly.collector.SignalCollectorConfig;
import com.android.os.profiling.anomaly.collector.SignalCollectorData;
import com.android.os.profiling.anomaly.core.AnomalyDetector;
import com.android.os.profiling.anomaly.core.AnomalyDetectorController;
import com.android.os.profiling.anomaly.core.AnomalyDetectorRegistry;
import com.android.os.profiling.anomaly.core.AnomalyHandlerRegistry;
import com.android.os.profiling.anomaly.core.RuleStorage;
import com.android.os.profiling.anomaly.core.SignalCollectorRegistry;
import com.android.os.profiling.anomaly.detector.BinderSpamAnomalyDetector;
import com.android.os.profiling.anomaly.internal.AnomalyDetectorControllerImpl;
import com.android.os.profiling.anomaly.internal.AnomalyDetectorRegistryImpl;
import com.android.os.profiling.anomaly.internal.AnomalyHandlerRegistryImpl;
import com.android.os.profiling.anomaly.internal.RuleStorageImpl;
import com.android.os.profiling.anomaly.internal.SignalCollectorRegistryImpl;
import com.android.server.LocalManagerRegistry;
import com.android.server.SystemService;

import java.io.File;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Anomaly Detector Service.
 *
 * <p>This entire service is part of a feature controlled by the {@link
 * Flags#FLAG_ANOMALY_DETECTOR_CORE} flag. It is started by the SystemServer only when this flag is
 * enabled. As a result, the entire class is annotated with {@link FlaggedApi} to signify that its
 * existence and all of its APIs are conditional upon this feature flag.
 *
 * @hide
 */
@FlaggedApi(Flags.FLAG_ANOMALY_DETECTOR_CORE)
public final class AnomalyDetectorService extends SystemService {
    private static final String TAG = "AnomalyDetectorService";

    private final BinderService mBinderService;

    @VisibleForTesting final AnomalyDetectorManagerLocal mLocalManager;

    private final SignalCollectorRegistry mSignalCollectorRegistry;

    @VisibleForTesting final AnomalyDetectorControllerImpl mController;

    /**
     * Constructs a new AnomalyDetectorService.
     *
     * <p>This constructor acts as the "composition root" for the anomaly detection system. It is
     * responsible for instantiating and wiring together all the core components, such as the
     * registries and the controller.
     *
     * @param context The system context.
     */
    public AnomalyDetectorService(Context context) {
        super(context);

        // Dedicated executor for background I/O operations.
        Executor ioExecutor = Executors.newSingleThreadExecutor();

        File systemDir = new File(Environment.getDataDirectory(), "system");
        File anomalyServiceDir = new File(systemDir, "anomaly_service");
        RuleStorage ruleStorage;
        if (anomalyServiceDir.exists() || anomalyServiceDir.mkdirs()) {
            File rulesFile = new File(anomalyServiceDir, "rules.pb");
            ruleStorage = new RuleStorageImpl(rulesFile, ioExecutor);
        } else {
            Slog.e(TAG, "Failed to create directory: " + anomalyServiceDir.getPath());
            // Create a no-op storage if the directory cannot be created.
            ruleStorage =
                    new RuleStorage() {
                        @Override
                        public void load(
                                Executor executor, OutcomeReceiver<Set<Rule>, Throwable> callback) {
                            executor.execute(() -> callback.onResult(Set.of()));
                        }

                        @Override
                        public void save(
                                Set<Rule> rules,
                                Executor executor,
                                OutcomeReceiver<Void, Throwable> callback) {
                            executor.execute(() -> callback.onResult(null));
                        }
                    };
        }

        mSignalCollectorRegistry = new SignalCollectorRegistryImpl();
        AnomalyHandlerRegistry handlerRegistry = new AnomalyHandlerRegistryImpl(context);

        // Manually create the set of all known detector factories.
        // This is the central place to register a new detector with the system.
        Set<AnomalyDetector.AnomalyDetectorFactory<?>> allFactories =
                Set.of(BinderSpamAnomalyDetector.FACTORY);

        AnomalyDetectorRegistry anomalyDetectorRegistry =
                new AnomalyDetectorRegistryImpl(allFactories);

        mController =
                new AnomalyDetectorControllerImpl(
                        ruleStorage,
                        mSignalCollectorRegistry,
                        handlerRegistry,
                        anomalyDetectorRegistry,
                        Executors.newCachedThreadPool());
        mBinderService = new BinderService(context, mController);
        mLocalManager = new Local();
    }

    /** {@inheritDoc} */
    @Override
    public void onStart() {
        Slog.i(TAG, "onStart()");

        LocalManagerRegistry.addManager(AnomalyDetectorManagerLocal.class, mLocalManager);

        publishBinderService(Context.ANOMALY_DETECTOR_SERVICE, mBinderService);
    }

    /** {@inheritDoc} */
    @Override
    public void onBootPhase(int phase) {
        if (phase == SystemService.PHASE_SYSTEM_SERVICES_READY) {
            mController.onSystemServicesReady();
        }
    }

    /** Implementation of the IAnomalyDetectorService binder service. */
    private static final class BinderService extends IAnomalyDetectorService.Stub {
        private final Context mContext;

        @SuppressWarnings("unused") // This will be used once APIs are implemented.
        private final AnomalyDetectorController mController;

        BinderService(Context context, AnomalyDetectorController controller) {
            mContext = context;
            mController = controller;
        }

        @Override
        public void setRules(List<RuleParcel> ruleParcelList) {
            mContext.enforceCallingOrSelfPermission(
                    CONFIGURE_ANOMALY_DETECTOR,
                    "the caller does not have the required permission to set anomaly detector"
                            + " rules");
            // TODO(b/423096026): Use the rules to detect anomalies.
        }
    }

    /**
     * Class to be added to the LocalManagerRegistry to allow registration of signal collectors.
     * This would typically implement an updated AnomalyDetectorManagerLocal interface.
     */
    private final class Local implements AnomalyDetectorManagerLocal {
        /** {@inheritDoc} */
        @Override
        public <T extends SignalCollectorConfig, U extends SignalCollectorData>
                void registerSignalCollector(
                        Class<T> configType, Class<U> dataType, SignalCollector<T, U> collector) {
            mSignalCollectorRegistry.registerSignalCollector(configType, dataType, collector);
        }
    }
}
