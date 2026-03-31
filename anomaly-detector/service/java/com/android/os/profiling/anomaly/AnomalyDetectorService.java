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
import android.annotation.NonNull;
import android.annotation.PermissionManuallyEnforced;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.OutcomeReceiver;
import android.os.ParcelFileDescriptor;
import android.os.profiling.anomaly.IAnomalyDetectorService;
import android.os.profiling.anomaly.RuleInternal;
import android.os.profiling.anomaly.RuleInternal.AnomalyActionTypeInternal;
import android.os.profiling.anomaly.RuleParcel;
import android.os.profiling.anomaly.flags.Flags;
import android.util.ArraySet;

import com.android.internal.annotations.VisibleForTesting;
import com.android.modules.utils.build.SdkLevel;
import com.android.os.profiling.anomaly.collector.SignalCollector;
import com.android.os.profiling.anomaly.collector.SignalCollectorConfig;
import com.android.os.profiling.anomaly.collector.SignalCollectorData;
import com.android.os.profiling.anomaly.config.AnomalyDetectorProperties;
import com.android.os.profiling.anomaly.config.ProfilingConcurrencyConfig;
import com.android.os.profiling.anomaly.config.ProfilingConcurrencyConfigImpl;
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
import com.android.os.profiling.anomaly.ratelimiter.ProfilingRateLimiter;
import com.android.os.profiling.anomaly.ratelimiter.ProfilingRateLimiterImpl;
import com.android.os.profiling.anomaly.ratelimiter.RateLimiterClock;
import com.android.os.profiling.anomaly.ratelimiter.RateLimiterConfig;
import com.android.os.profiling.anomaly.ratelimiter.RateLimiterConfigImpl;
import com.android.os.profiling.anomaly.ratelimiter.SystemClockImpl;
import com.android.os.profiling.anomaly.ratelimiter.persistence.ProtoStateStore;
import com.android.os.profiling.anomaly.ratelimiter.persistence.RateLimiterState;
import com.android.os.profiling.anomaly.ratelimiter.persistence.RateLimiterStateStore;
import com.android.os.profiling.anomaly.util.LogUtil;
import com.android.os.profiling.anomaly.wrapper.ContextSystemServiceFetcher;
import com.android.os.profiling.anomaly.wrapper.ExecutorServiceWrapper;
import com.android.server.LocalManagerRegistry;
import com.android.server.SystemService;

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Anomaly Detector Service.
 *
 * <p>This entire service is part of a feature controlled by the {@link
 * Flags#FLAG_ANOMALY_DETECTOR_CORE_C} flag. It is started by the SystemServer only when this flag
 * is enabled. As a result, the entire class is annotated with {@link FlaggedApi} to signify that
 * its existence and all of its APIs are conditional upon this feature flag.
 *
 * @hide
 */
@FlaggedApi(Flags.FLAG_ANOMALY_DETECTOR_CORE_C)
public final class AnomalyDetectorService extends SystemService {
    static final String FULL_BINDER_SPAM_DETECTION_CONFIG_FILE_NAME =
            "anomaly_detection.full_binder_spam_detection";
    private static final String TAG = "AnomalyDetectorService";
    private static final LogUtil sLog = new LogUtil(TAG);

    @VisibleForTesting final BinderService mBinderService;

    @VisibleForTesting final AnomalyDetectorManagerLocal mLocalManager;
    private final SignalCollectorRegistry mSignalCollectorRegistry;

    @VisibleForTesting final AnomalyDetectorControllerImpl mController;
    @VisibleForTesting final ProfilingRateLimiter mProfilingRateLimiter;
    private final Executor mIoExecutor;
    private final File mAnomalyServiceDir;

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
        mIoExecutor = ExecutorServiceWrapper.getIOExecutor();

        File systemDir = new File(Environment.getDataDirectory(), "system");
        mAnomalyServiceDir = new File(systemDir, "anomaly_service");
        RuleStorage ruleStorage;
        RateLimiterStateStore rateLimiterStateStore;
        if (mAnomalyServiceDir.exists() || mAnomalyServiceDir.mkdirs()) {
            File rulesFile = new File(mAnomalyServiceDir, "rules.pb");
            ruleStorage = new RuleStorageImpl(rulesFile, mIoExecutor);
            File rateLimiterFile = new File(mAnomalyServiceDir, "rate_limiter_state.pb");
            rateLimiterStateStore = new ProtoStateStore(rateLimiterFile, mIoExecutor);
        } else {
            sLog.e("Failed to create directory: " + mAnomalyServiceDir.getPath());
            // Create a no-op storage if the directory cannot be created.
            ruleStorage =
                    new RuleStorage() {
                        @Override
                        public void load(
                                Executor executor,
                                OutcomeReceiver<Set<RuleInternal>, Throwable> callback) {
                            executor.execute(() -> callback.onResult(Set.of()));
                        }

                        @Override
                        public void save(
                                Set<RuleInternal> rules,
                                Executor executor,
                                OutcomeReceiver<Void, Throwable> callback) {
                            executor.execute(() -> callback.onResult(null));
                        }
                    };
            rateLimiterStateStore =
                    new RateLimiterStateStore() {
                        @Override
                        public void readState(
                                OutcomeReceiver<RateLimiterState, Throwable> callback) {
                            callback.onResult(RateLimiterState.createEmpty());
                        }

                        @Override
                        public void writeState(RateLimiterState state) {
                            // no-op
                        }
                    };
        }

        mSignalCollectorRegistry = new SignalCollectorRegistryImpl();
        RateLimiterClock clock = new SystemClockImpl();
        AnomalyDetectorProperties propertiesProvider = new AnomalyDetectorProperties();
        RateLimiterConfig rateLimiterConfig = new RateLimiterConfigImpl(propertiesProvider);
        ProfilingConcurrencyConfig profilingConcurrencyConfig =
                new ProfilingConcurrencyConfigImpl(propertiesProvider);
        HandlerThread rateLimiterHandlerThread = new HandlerThread("AnomalyRateLimiter");
        rateLimiterHandlerThread.start();
        Handler rateLimiterHandler = new Handler(rateLimiterHandlerThread.getLooper());
        mProfilingRateLimiter =
                new ProfilingRateLimiterImpl(
                        rateLimiterStateStore, clock, rateLimiterConfig, rateLimiterHandler);
        AnomalyHandlerRegistry handlerRegistry =
                new AnomalyHandlerRegistryImpl(
                        new ContextSystemServiceFetcher(context),
                        mProfilingRateLimiter,
                        profilingConcurrencyConfig);

        // Manually create the set of all known detector factories.
        // This is the central place to register a new detector with the system.
        Set<AnomalyDetector.AnomalyDetectorFactory> allFactories =
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
        mBinderService = new BinderService(context, mController, mAnomalyServiceDir.toPath());
        mLocalManager = new Local();
    }

    @Override
    public void onStart() {
        // AnomalyDetector service should only be enabled beyond C.
        if (!SdkLevel.isAtLeastC()) {
            return;
        }
        sLog.i("onStart()");

        LocalManagerRegistry.addManager(AnomalyDetectorManagerLocal.class, mLocalManager);

        publishBinderService(Context.ANOMALY_DETECTOR_SERVICE, mBinderService);

        IntentFilter filter = new IntentFilter(Intent.ACTION_TIME_CHANGED);
        getContext()
                .registerReceiver(
                        new BroadcastReceiver() {
                            @Override
                            public void onReceive(Context context, Intent intent) {
                                if (Intent.ACTION_TIME_CHANGED.equals(intent.getAction())) {
                                    mProfilingRateLimiter.onTimeChanged();
                                }
                            }
                        },
                        filter);
    }

    @Override
    public void onBootPhase(int phase) {
        // AnomalyDetector service should only be enabled beyond C.
        if (!SdkLevel.isAtLeastC()) {
            return;
        }

        if (phase == SystemService.PHASE_SYSTEM_SERVICES_READY) {
            mController.onSystemServicesReady();
        }

        if (phase == SystemService.PHASE_BOOT_COMPLETED) {
            mIoExecutor.execute(
                    () -> {
                        try {
                            Files.deleteIfExists(
                                    mAnomalyServiceDir
                                            .toPath()
                                            .resolve(FULL_BINDER_SPAM_DETECTION_CONFIG_FILE_NAME));
                        } catch (IOException e) {
                            sLog.e(
                                    "Failed to delete the full binder spam detection config file",
                                    e);
                        }
                    });
        }
    }

    /** Implementation of the IAnomalyDetectorService binder service. */
    @VisibleForTesting
    static final class BinderService extends IAnomalyDetectorService.Stub {
        private final Context mContext;

        final AnomalyDetectorController mController;
        private final Path mAnomalyServiceDir;

        BinderService(
                Context context, AnomalyDetectorController controller, Path anomalyServiceDir) {
            mContext = context;
            mController = controller;
            mAnomalyServiceDir = anomalyServiceDir;
        }

        /**
         * dump implements the 'dumpsys anomaly_detector', currently for debugging (no guarantees on
         * format stability for now)
         *
         * @param fd The raw file descriptor that the dump is being sent to.
         * @param pw The file to which you should dump your state. This will be closed for you after
         *     you return.
         * @param args additional arguments to the dump request.
         */
        @PermissionManuallyEnforced
        @Override
        protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.DUMP)
                    != PackageManager.PERMISSION_GRANTED) {
                return;
            }
            dump(pw, args);
        }

        @VisibleForTesting
        void dump(PrintWriter pw, String[] args) {
            Set<RuleInternal> rules = mController.getRules();
            if (rules != null) {
                pw.println("Rules:");
                for (RuleInternal r : rules) {
                    pw.println(
                            r.getConditionType()
                                    + " "
                                    + r.getAnomalyActions()
                                    + " "
                                    + r.getRuleCondition());
                }
            }
        }

        @Override
        // Permission has been enforced by the caller, see Binder#onShellCommand()
        @PermissionManuallyEnforced
        public int handleShellCommand(
                @NonNull ParcelFileDescriptor in,
                @NonNull ParcelFileDescriptor out,
                @NonNull ParcelFileDescriptor err,
                @NonNull String[] args) {
            return new AnomalyDetectorShellCommandHandler(mAnomalyServiceDir)
                    .exec(
                            this,
                            in.getFileDescriptor(),
                            out.getFileDescriptor(),
                            err.getFileDescriptor(),
                            args);
        }

        /**
         * setRules is the entrypoint from allowed apps that interact with the control plane (server
         * or local) to inject the rules.
         *
         * @param ruleParcelList The list of rules to set.
         */
        @Override
        @PermissionManuallyEnforced
        public void setRules(List<RuleParcel> ruleParcelList) {
            mContext.enforceCallingOrSelfPermission(
                    CONFIGURE_ANOMALY_DETECTOR,
                    "the caller does not have the required permission to set anomaly detector"
                            + " rules");
            mController.setRules(convertRuleParcelsToRules(ruleParcelList));
        }

        private static Set<RuleInternal> convertRuleParcelsToRules(
                List<RuleParcel> ruleParcelList) {
            Set<RuleInternal> rules = new ArraySet<>();
            for (RuleParcel ruleParcel : ruleParcelList) {
                RuleInternal.Builder ruleBuilder =
                        new RuleInternal.Builder()
                                .setName(ruleParcel.name)
                                .setConditionType(ruleParcel.conditionType)
                                .setRuleCondition(ruleParcel.ruleCondition);
                for (@AnomalyActionTypeInternal int action : ruleParcel.anomalyActions) {
                    ruleBuilder.addAnomalyAction(action);
                }
                rules.add(ruleBuilder.build());
            }
            return rules;
        }
    }

    /**
     * Class to be added to the LocalManagerRegistry to allow registration of signal collectors.
     * This would typically implement an updated AnomalyDetectorManagerLocal interface.
     */
    private final class Local implements AnomalyDetectorManagerLocal {
        @Override
        public <T extends SignalCollectorConfig, U extends SignalCollectorData>
                void registerSignalCollector(
                        Class<T> configType, Class<U> dataType, SignalCollector<T, U> collector) {
            mSignalCollectorRegistry.registerSignalCollector(configType, dataType, collector);
        }

        @Override
        public <T extends SignalCollectorConfig, U extends SignalCollectorData>
                void unregisterSignalCollector(Class<T> configType, Class<U> dataType) {
            mSignalCollectorRegistry.unregisterSignalCollector(configType, dataType);
        }
    }
}
