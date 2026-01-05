/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.os.profiling;

import static android.os.profiling.DeviceConfigHelper.updateBoolean;
import static android.os.profiling.DeviceConfigHelper.updateInt;
import static android.os.profiling.DeviceConfigHelper.updateLong;

import android.annotation.Nullable;
import android.os.Bundle;
import android.os.ProfilingManager;
import android.provider.DeviceConfig;

import perfetto.protos.DataSourceConfigOuterClass.DataSourceConfig;
import perfetto.protos.FtraceConfigOuterClass.FtraceConfig;
import perfetto.protos.HeapprofdConfigOuterClass.HeapprofdConfig;
import perfetto.protos.JavaHprofConfigOuterClass.JavaHprofConfig;
import perfetto.protos.PackagesListConfigOuterClass.PackagesListConfig;
import perfetto.protos.PerfEventConfigOuterClass.PerfEventConfig;
import perfetto.protos.PerfEventsOuterClass.PerfEvents;
import perfetto.protos.ProcessStatsConfigOuterClass.ProcessStatsConfig;
import perfetto.protos.TraceConfigOuterClass.TraceConfig;

public final class Configs {

    // Time to wait beyond trace timeout to ensure perfetto has time to finish writing output.
    private static final int FILE_PROCESSING_DELAY_MS = 2000;
    // Time used to account for any delay in starting up the underlying profiling process. This
    // value is used to calculate max profiling time.
    private static final int MAX_PROFILING_TIME_BUFFER_MS = 10 * 1000;

    private static final int FOUR_MB = 4096;

    private static final int ONE_DAY_MS = 24 * 60 * 60 * 1000;

    private static boolean sSystemTriggeredSystemTraceConfigsInitialized = false;
    private static boolean sSystemTraceConfigsInitialized = false;
    private static boolean sHeapProfileConfigsInitialized = false;
    private static boolean sJavaHeapDumpConfigsInitialized = false;
    private static boolean sStackSamplingConfigsInitialized = false;

    private static int sSystemTriggeredSystemTraceDurationMs;
    private static int sSystemTriggeredSystemTraceDiscardBufferSizeKb;
    private static int sSystemTriggeredSystemTraceRingBufferSizeKb;

    private static boolean sKillswitchSystemTrace;
    private static int sSystemTraceDurationMsDefault;
    private static int sSystemTraceDurationMsMin;
    private static int sSystemTraceDurationMsMax;
    private static int sSystemTraceSizeKbDefault;
    private static int sSystemTraceSizeKbMin;
    private static int sSystemTraceSizeKbMax;

    private static boolean sKillswitchHeapProfile;
    private static boolean sHeapProfileTrackJavaAllocationsDefault;
    private static int sHeapProfileFlushTimeoutMsDefault;
    private static int sHeapProfileDurationMsDefault;
    private static int sHeapProfileDurationMsMin;
    private static int sHeapProfileDurationMsMax;
    private static int sHeapProfileSizeKbDefault;
    private static int sHeapProfileSizeKbMin;
    private static int sHeapProfileSizeKbMax;
    private static long sHeapProfileSamplingIntervalBytesDefault;
    private static long sHeapProfileSamplingIntervalBytesMin;
    private static long sHeapProfileSamplingIntervalBytesMax;

    private static boolean sKillswitchJavaHeapDump;
    private static int sJavaHeapDumpDurationMsDefault;
    private static int sJavaHeapDumpDataSourceStopTimeoutMsDefault;
    private static int sJavaHeapDumpSizeKbDefault;
    private static int sJavaHeapDumpSizeKbMin;
    private static int sJavaHeapDumpSizeKbMax;

    private static boolean sKillswitchStackSampling;
    private static int sStackSamplingFlushTimeoutMsDefault;
    private static int sStackSamplingDurationMsDefault;
    private static int sStackSamplingDurationMsMin;
    private static int sStackSamplingDurationMsMax;
    private static int sStackSamplingSizeKbDefault;
    private static int sStackSamplingSizeKbMin;
    private static int sStackSamplingSizeKbMax;
    private static int sStackSamplingSamplingFrequencyDefault;
    private static int sStackSamplingSamplingFrequencyMin;
    private static int sStackSamplingSamplingFrequencyMax;
    private static int sStackSamplingDiscardBufferSizeKb;

    /**
     * Initialize System Triggered System Trace related DeviceConfig values if they have not been
     * yet.
     */
    private static void initializeSystemTriggeredSystemTraceConfigsIfNecessary() {
        if (sSystemTriggeredSystemTraceConfigsInitialized) {
            return;
        }

        DeviceConfig.Properties properties =
                DeviceConfigHelper.getAllSystemTriggeredSystemTraceProperties();

        sSystemTriggeredSystemTraceDurationMs =
                properties.getInt(
                        DeviceConfigHelper.SYSTEM_TRIGGERED_SYSTEM_TRACE_DURATION_MS,
                        30 * 60 * 1000 /* 30 minutes */);
        sSystemTriggeredSystemTraceDiscardBufferSizeKb =
                properties.getInt(
                        DeviceConfigHelper.SYSTEM_TRIGGERED_SYSTEM_TRACE_DISCARD_BUFFER_SIZE_KB,
                        FOUR_MB);
        sSystemTriggeredSystemTraceRingBufferSizeKb =
                properties.getInt(
                        DeviceConfigHelper.SYSTEM_TRIGGERED_SYSTEM_TRACE_RING_BUFFER_SIZE_KB,
                        Flags.backgroundBufferSizeIncrease() ? 65536 : 32768);

        sSystemTriggeredSystemTraceConfigsInitialized = true;
    }

    /** Initialize System Trace related DeviceConfig set values if they have not been yet. */
    private static void initializeSystemTraceConfigsIfNecessary() {
        if (sSystemTraceConfigsInitialized) {
            return;
        }

        DeviceConfig.Properties properties = DeviceConfigHelper.getAllSystemTraceProperties();

        sKillswitchSystemTrace =
                properties.getBoolean(
                        DeviceConfigHelper.KILLSWITCH_SYSTEM_TRACE,
                        DeviceConfigHelper.DEFAULT_KILLSWITCH_SYSTEM_TRACE);
        sSystemTraceDurationMsDefault =
                properties.getInt(
                        DeviceConfigHelper.SYSTEM_TRACE_DURATION_MS_DEFAULT,
                        DeviceConfigHelper.DEFAULT_SYSTEM_TRACE_DURATION_MS_DEFAULT);
        sSystemTraceDurationMsMin =
                properties.getInt(
                        DeviceConfigHelper.SYSTEM_TRACE_DURATION_MS_MIN,
                        DeviceConfigHelper.DEFAULT_SYSTEM_TRACE_DURATION_MS_MIN);
        sSystemTraceDurationMsMax =
                properties.getInt(
                        DeviceConfigHelper.SYSTEM_TRACE_DURATION_MS_MAX,
                        DeviceConfigHelper.DEFAULT_SYSTEM_TRACE_DURATION_MS_MAX);
        sSystemTraceSizeKbDefault =
                properties.getInt(
                        DeviceConfigHelper.SYSTEM_TRACE_SIZE_KB_DEFAULT,
                        DeviceConfigHelper.DEFAULT_SYSTEM_TRACE_SIZE_KB_DEFAULT);
        sSystemTraceSizeKbMin =
                properties.getInt(
                        DeviceConfigHelper.SYSTEM_TRACE_SIZE_KB_MIN,
                        DeviceConfigHelper.DEFAULT_SYSTEM_TRACE_SIZE_KB_MIN);
        sSystemTraceSizeKbMax =
                properties.getInt(
                        DeviceConfigHelper.SYSTEM_TRACE_SIZE_KB_MAX,
                        DeviceConfigHelper.DEFAULT_SYSTEM_TRACE_SIZE_KB_MAX);

        sSystemTraceConfigsInitialized = true;
    }

    /** Initialize Java Heap Dump related DeviceConfig set values if they have not been yet. */
    private static void initializeJavaHeapDumpConfigsIfNecessary() {
        if (sJavaHeapDumpConfigsInitialized) {
            return;
        }

        DeviceConfig.Properties properties = DeviceConfigHelper.getAllJavaHeapDumpProperties();

        sKillswitchJavaHeapDump =
                properties.getBoolean(
                        DeviceConfigHelper.KILLSWITCH_JAVA_HEAP_DUMP,
                        DeviceConfigHelper.DEFAULT_KILLSWITCH_JAVA_HEAP_DUMP);
        sJavaHeapDumpDurationMsDefault =
                properties.getInt(
                        DeviceConfigHelper.JAVA_HEAP_DUMP_DURATION_MS_DEFAULT,
                        DeviceConfigHelper.DEFAULT_JAVA_HEAP_DUMP_DURATION_MS_DEFAULT);
        sJavaHeapDumpDataSourceStopTimeoutMsDefault =
                properties.getInt(
                        DeviceConfigHelper.JAVA_HEAP_DUMP_DATA_SOURCE_STOP_TIMEOUT_MS_DEFAULT,
                        DeviceConfigHelper
                                .DEFAULT_JAVA_HEAP_DUMP_DATA_SOURCE_STOP_TIMEOUT_MS_DEFAULT);
        sJavaHeapDumpSizeKbDefault =
                properties.getInt(
                        DeviceConfigHelper.JAVA_HEAP_DUMP_SIZE_KB_DEFAULT,
                        DeviceConfigHelper.DEFAULT_JAVA_HEAP_DUMP_SIZE_KB_DEFAULT);
        sJavaHeapDumpSizeKbMin =
                properties.getInt(
                        DeviceConfigHelper.JAVA_HEAP_DUMP_SIZE_KB_MIN,
                        DeviceConfigHelper.DEFAULT_JAVA_HEAP_DUMP_SIZE_KB_MIN);
        sJavaHeapDumpSizeKbMax =
                properties.getInt(
                        DeviceConfigHelper.JAVA_HEAP_DUMP_SIZE_KB_MAX,
                        DeviceConfigHelper.DEFAULT_JAVA_HEAP_DUMP_SIZE_KB_MAX);

        sJavaHeapDumpConfigsInitialized = true;
    }

    /** Initialize Heap Profile related DeviceConfig set values if they have not been yet. */
    private static void initializeHeapProfileConfigsIfNecessary() {
        if (sHeapProfileConfigsInitialized) {
            return;
        }

        DeviceConfig.Properties properties = DeviceConfigHelper.getAllHeapProfileProperties();

        sKillswitchHeapProfile =
                properties.getBoolean(
                        DeviceConfigHelper.KILLSWITCH_HEAP_PROFILE,
                        DeviceConfigHelper.DEFAULT_KILLSWITCH_HEAP_PROFILE);
        sHeapProfileTrackJavaAllocationsDefault =
                properties.getBoolean(
                        DeviceConfigHelper.HEAP_PROFILE_TRACK_JAVA_ALLOCATIONS_DEFAULT,
                        DeviceConfigHelper.DEFAULT_HEAP_PROFILE_TRACK_JAVA_ALLOCATIONS_DEFAULT);
        sHeapProfileFlushTimeoutMsDefault =
                properties.getInt(
                        DeviceConfigHelper.HEAP_PROFILE_FLUSH_TIMEOUT_MS_DEFAULT,
                        DeviceConfigHelper.DEFAULT_HEAP_PROFILE_FLUSH_TIMEOUT_MS_DEFAULT);
        sHeapProfileDurationMsDefault =
                properties.getInt(
                        DeviceConfigHelper.HEAP_PROFILE_DURATION_MS_DEFAULT,
                        DeviceConfigHelper.DEFAULT_HEAP_PROFILE_DURATION_MS_DEFAULT);
        sHeapProfileDurationMsMin =
                properties.getInt(
                        DeviceConfigHelper.HEAP_PROFILE_DURATION_MS_MIN,
                        DeviceConfigHelper.DEFAULT_HEAP_PROFILE_DURATION_MS_MIN);
        sHeapProfileDurationMsMax =
                properties.getInt(
                        DeviceConfigHelper.HEAP_PROFILE_DURATION_MS_MAX,
                        DeviceConfigHelper.DEFAULT_HEAP_PROFILE_DURATION_MS_MAX);
        sHeapProfileSizeKbDefault =
                properties.getInt(
                        DeviceConfigHelper.HEAP_PROFILE_SIZE_KB_DEFAULT,
                        DeviceConfigHelper.DEFAULT_HEAP_PROFILE_SIZE_KB_DEFAULT);
        sHeapProfileSizeKbMin =
                properties.getInt(
                        DeviceConfigHelper.HEAP_PROFILE_SIZE_KB_MIN,
                        DeviceConfigHelper.DEFAULT_HEAP_PROFILE_SIZE_KB_MIN);
        sHeapProfileSizeKbMax =
                properties.getInt(
                        DeviceConfigHelper.HEAP_PROFILE_SIZE_KB_MAX,
                        DeviceConfigHelper.DEFAULT_HEAP_PROFILE_SIZE_KB_MAX);
        sHeapProfileSamplingIntervalBytesDefault =
                properties.getLong(
                        DeviceConfigHelper.HEAP_PROFILE_SAMPLING_INTERVAL_BYTES_DEFAULT,
                        DeviceConfigHelper.DEFAULT_HEAP_PROFILE_SAMPLING_INTERVAL_BYTES_DEFAULT);
        sHeapProfileSamplingIntervalBytesMin =
                properties.getLong(
                        DeviceConfigHelper.HEAP_PROFILE_SAMPLING_INTERVAL_BYTES_MIN,
                        DeviceConfigHelper.DEFAULT_HEAP_PROFILE_SAMPLING_INTERVAL_BYTES_MIN);
        sHeapProfileSamplingIntervalBytesMax =
                properties.getLong(
                        DeviceConfigHelper.HEAP_PROFILE_SAMPLING_INTERVAL_BYTES_MAX,
                        DeviceConfigHelper.DEFAULT_HEAP_PROFILE_SAMPLING_INTERVAL_BYTES_MAX);

        sHeapProfileConfigsInitialized = true;
    }

    /** Initialize Stack Sampling related DeviceConfig set values if they have not been yet. */
    private static void initializeStackSamplingConfigsIfNecessary() {
        if (sStackSamplingConfigsInitialized) {
            return;
        }

        DeviceConfig.Properties properties = DeviceConfigHelper.getAllStackSamplingProperties();

        sKillswitchStackSampling =
                properties.getBoolean(
                        DeviceConfigHelper.KILLSWITCH_STACK_SAMPLING,
                        DeviceConfigHelper.DEFAULT_KILLSWITCH_STACK_SAMPLING);
        sStackSamplingFlushTimeoutMsDefault =
                properties.getInt(
                        DeviceConfigHelper.STACK_SAMPLING_FLUSH_TIMEOUT_MS_DEFAULT,
                        DeviceConfigHelper.DEFAULT_STACK_SAMPLING_FLUSH_TIMEOUT_MS_DEFAULT);
        sStackSamplingDurationMsDefault =
                properties.getInt(
                        DeviceConfigHelper.STACK_SAMPLING_DURATION_MS_DEFAULT,
                        DeviceConfigHelper.DEFAULT_STACK_SAMPLING_DURATION_MS_DEFAULT);
        sStackSamplingDurationMsMin =
                properties.getInt(
                        DeviceConfigHelper.STACK_SAMPLING_DURATION_MS_MIN,
                        DeviceConfigHelper.DEFAULT_STACK_SAMPLING_DURATION_MS_MIN);
        sStackSamplingDurationMsMax =
                properties.getInt(
                        DeviceConfigHelper.STACK_SAMPLING_DURATION_MS_MAX,
                        DeviceConfigHelper.DEFAULT_STACK_SAMPLING_DURATION_MS_MAX);
        sStackSamplingSizeKbDefault =
                properties.getInt(
                        DeviceConfigHelper.STACK_SAMPLING_SAMPLING_SIZE_KB_DEFAULT,
                        DeviceConfigHelper.DEFAULT_STACK_SAMPLING_SAMPLING_SIZE_KB_DEFAULT);
        sStackSamplingSizeKbMin =
                properties.getInt(
                        DeviceConfigHelper.STACK_SAMPLING_SAMPLING_SIZE_KB_MIN,
                        DeviceConfigHelper.DEFAULT_STACK_SAMPLING_SAMPLING_SIZE_KB_MIN);
        sStackSamplingSizeKbMax =
                properties.getInt(
                        DeviceConfigHelper.STACK_SAMPLING_SAMPLING_SIZE_KB_MAX,
                        DeviceConfigHelper.DEFAULT_STACK_SAMPLING_SAMPLING_SIZE_KB_MAX);
        sStackSamplingSamplingFrequencyDefault =
                properties.getInt(
                        DeviceConfigHelper.STACK_SAMPLING_FREQUENCY_DEFAULT,
                        DeviceConfigHelper.DEFAULT_STACK_SAMPLING_FREQUENCY_DEFAULT);
        sStackSamplingSamplingFrequencyMin =
                properties.getInt(
                        DeviceConfigHelper.STACK_SAMPLING_FREQUENCY_MIN,
                        DeviceConfigHelper.DEFAULT_STACK_SAMPLING_FREQUENCY_MIN);
        sStackSamplingSamplingFrequencyMax =
                properties.getInt(
                        DeviceConfigHelper.STACK_SAMPLING_FREQUENCY_MAX,
                        DeviceConfigHelper.DEFAULT_STACK_SAMPLING_FREQUENCY_MAX);
        sStackSamplingDiscardBufferSizeKb =
                properties.getInt(
                        DeviceConfigHelper.STACK_SAMPLING_DISCARD_BUFFER_SIZE_KB, FOUR_MB);

        sStackSamplingConfigsInitialized = true;
    }

    /**
     * Update DeviceConfig set configuration values if present in the provided properties, leaving
     * not present values unchanged.
     *
     * <p>Will only update values that have already been initialized as initialization is required
     * before use and the changed values will be available under the normal access path.
     */
    public static void maybeUpdateConfigs(DeviceConfig.Properties properties) {
        // TODO(b/330940387): Revisit defaults before release

        if (sSystemTraceConfigsInitialized) {
            sKillswitchSystemTrace =
                    updateBoolean(
                            properties,
                            DeviceConfigHelper.KILLSWITCH_SYSTEM_TRACE,
                            sKillswitchSystemTrace,
                            DeviceConfigHelper.DEFAULT_KILLSWITCH_SYSTEM_TRACE);
            sSystemTraceDurationMsDefault =
                    updateInt(
                            properties,
                            DeviceConfigHelper.SYSTEM_TRACE_DURATION_MS_DEFAULT,
                            sSystemTraceDurationMsDefault,
                            DeviceConfigHelper.DEFAULT_SYSTEM_TRACE_DURATION_MS_DEFAULT);
            sSystemTraceDurationMsMin =
                    updateInt(
                            properties,
                            DeviceConfigHelper.SYSTEM_TRACE_DURATION_MS_MIN,
                            sSystemTraceDurationMsMin,
                            DeviceConfigHelper.DEFAULT_SYSTEM_TRACE_DURATION_MS_MIN);
            sSystemTraceDurationMsMax =
                    updateInt(
                            properties,
                            DeviceConfigHelper.SYSTEM_TRACE_DURATION_MS_MAX,
                            sSystemTraceDurationMsMax,
                            DeviceConfigHelper.DEFAULT_SYSTEM_TRACE_DURATION_MS_MAX);
            sSystemTraceSizeKbDefault =
                    updateInt(
                            properties,
                            DeviceConfigHelper.SYSTEM_TRACE_SIZE_KB_DEFAULT,
                            sSystemTraceSizeKbDefault,
                            DeviceConfigHelper.DEFAULT_SYSTEM_TRACE_SIZE_KB_DEFAULT);
            sSystemTraceSizeKbMin =
                    updateInt(
                            properties,
                            DeviceConfigHelper.SYSTEM_TRACE_SIZE_KB_MIN,
                            sSystemTraceSizeKbMin,
                            DeviceConfigHelper.DEFAULT_SYSTEM_TRACE_SIZE_KB_MIN);
            sSystemTraceSizeKbMax =
                    updateInt(
                            properties,
                            DeviceConfigHelper.SYSTEM_TRACE_SIZE_KB_MAX,
                            sSystemTraceSizeKbMax,
                            DeviceConfigHelper.DEFAULT_SYSTEM_TRACE_SIZE_KB_MAX);
        }

        if (sHeapProfileConfigsInitialized) {
            sKillswitchHeapProfile =
                    updateBoolean(
                            properties,
                            DeviceConfigHelper.KILLSWITCH_HEAP_PROFILE,
                            sKillswitchHeapProfile,
                            DeviceConfigHelper.DEFAULT_KILLSWITCH_HEAP_PROFILE);
            sHeapProfileTrackJavaAllocationsDefault =
                    updateBoolean(
                            properties,
                            DeviceConfigHelper.HEAP_PROFILE_TRACK_JAVA_ALLOCATIONS_DEFAULT,
                            sHeapProfileTrackJavaAllocationsDefault,
                            DeviceConfigHelper.DEFAULT_HEAP_PROFILE_TRACK_JAVA_ALLOCATIONS_DEFAULT);
            sHeapProfileFlushTimeoutMsDefault =
                    updateInt(
                            properties,
                            DeviceConfigHelper.HEAP_PROFILE_FLUSH_TIMEOUT_MS_DEFAULT,
                            sHeapProfileFlushTimeoutMsDefault,
                            DeviceConfigHelper.DEFAULT_HEAP_PROFILE_FLUSH_TIMEOUT_MS_DEFAULT);
            sHeapProfileDurationMsDefault =
                    updateInt(
                            properties,
                            DeviceConfigHelper.HEAP_PROFILE_DURATION_MS_DEFAULT,
                            sHeapProfileDurationMsDefault,
                            DeviceConfigHelper.DEFAULT_HEAP_PROFILE_DURATION_MS_DEFAULT);
            sHeapProfileDurationMsMin =
                    updateInt(
                            properties,
                            DeviceConfigHelper.HEAP_PROFILE_DURATION_MS_MIN,
                            sHeapProfileDurationMsMin,
                            DeviceConfigHelper.DEFAULT_HEAP_PROFILE_DURATION_MS_MIN);
            sHeapProfileDurationMsMax =
                    updateInt(
                            properties,
                            DeviceConfigHelper.HEAP_PROFILE_DURATION_MS_MAX,
                            sHeapProfileDurationMsMax,
                            DeviceConfigHelper.DEFAULT_HEAP_PROFILE_DURATION_MS_MAX);
            sHeapProfileSizeKbDefault =
                    updateInt(
                            properties,
                            DeviceConfigHelper.HEAP_PROFILE_SIZE_KB_DEFAULT,
                            sHeapProfileSizeKbDefault,
                            DeviceConfigHelper.DEFAULT_HEAP_PROFILE_SIZE_KB_DEFAULT);
            sHeapProfileSizeKbMin =
                    updateInt(
                            properties,
                            DeviceConfigHelper.HEAP_PROFILE_SIZE_KB_MIN,
                            sHeapProfileSizeKbMin,
                            DeviceConfigHelper.DEFAULT_HEAP_PROFILE_SIZE_KB_MIN);
            sHeapProfileSizeKbMax =
                    updateInt(
                            properties,
                            DeviceConfigHelper.HEAP_PROFILE_SIZE_KB_MAX,
                            sHeapProfileSizeKbMax,
                            DeviceConfigHelper.DEFAULT_HEAP_PROFILE_SIZE_KB_MAX);
            sHeapProfileSamplingIntervalBytesDefault =
                    updateLong(
                            properties,
                            DeviceConfigHelper.HEAP_PROFILE_SAMPLING_INTERVAL_BYTES_DEFAULT,
                            sHeapProfileSamplingIntervalBytesDefault,
                            DeviceConfigHelper
                                    .DEFAULT_HEAP_PROFILE_SAMPLING_INTERVAL_BYTES_DEFAULT);
            sHeapProfileSamplingIntervalBytesMin =
                    updateLong(
                            properties,
                            DeviceConfigHelper.HEAP_PROFILE_SAMPLING_INTERVAL_BYTES_MIN,
                            sHeapProfileSamplingIntervalBytesMin,
                            DeviceConfigHelper.DEFAULT_HEAP_PROFILE_SAMPLING_INTERVAL_BYTES_MIN);
            sHeapProfileSamplingIntervalBytesMax =
                    updateLong(
                            properties,
                            DeviceConfigHelper.HEAP_PROFILE_SAMPLING_INTERVAL_BYTES_MAX,
                            sHeapProfileSamplingIntervalBytesMax,
                            DeviceConfigHelper.DEFAULT_HEAP_PROFILE_SAMPLING_INTERVAL_BYTES_MAX);
        }

        if (sJavaHeapDumpConfigsInitialized) {
            sKillswitchJavaHeapDump =
                    updateBoolean(
                            properties,
                            DeviceConfigHelper.KILLSWITCH_JAVA_HEAP_DUMP,
                            sKillswitchJavaHeapDump,
                            DeviceConfigHelper.DEFAULT_KILLSWITCH_JAVA_HEAP_DUMP);
            sJavaHeapDumpDurationMsDefault =
                    updateInt(
                            properties,
                            DeviceConfigHelper.JAVA_HEAP_DUMP_DURATION_MS_DEFAULT,
                            sJavaHeapDumpDurationMsDefault,
                            DeviceConfigHelper.DEFAULT_JAVA_HEAP_DUMP_DURATION_MS_DEFAULT);
            sJavaHeapDumpDataSourceStopTimeoutMsDefault =
                    updateInt(
                            properties,
                            DeviceConfigHelper.JAVA_HEAP_DUMP_DATA_SOURCE_STOP_TIMEOUT_MS_DEFAULT,
                            sJavaHeapDumpDataSourceStopTimeoutMsDefault,
                            DeviceConfigHelper
                                    .DEFAULT_JAVA_HEAP_DUMP_DATA_SOURCE_STOP_TIMEOUT_MS_DEFAULT);
            sJavaHeapDumpSizeKbDefault =
                    updateInt(
                            properties,
                            DeviceConfigHelper.JAVA_HEAP_DUMP_SIZE_KB_DEFAULT,
                            sJavaHeapDumpSizeKbDefault,
                            DeviceConfigHelper.DEFAULT_JAVA_HEAP_DUMP_SIZE_KB_DEFAULT);
            sJavaHeapDumpSizeKbMin =
                    updateInt(
                            properties,
                            DeviceConfigHelper.JAVA_HEAP_DUMP_SIZE_KB_MIN,
                            sJavaHeapDumpSizeKbMin,
                            DeviceConfigHelper.DEFAULT_JAVA_HEAP_DUMP_SIZE_KB_MIN);
            sJavaHeapDumpSizeKbMax =
                    updateInt(
                            properties,
                            DeviceConfigHelper.JAVA_HEAP_DUMP_SIZE_KB_MAX,
                            sJavaHeapDumpSizeKbMax,
                            DeviceConfigHelper.DEFAULT_JAVA_HEAP_DUMP_SIZE_KB_MAX);
        }

        if (sStackSamplingConfigsInitialized) {
            sKillswitchStackSampling =
                    updateBoolean(
                            properties,
                            DeviceConfigHelper.KILLSWITCH_STACK_SAMPLING,
                            sKillswitchStackSampling,
                            DeviceConfigHelper.DEFAULT_KILLSWITCH_STACK_SAMPLING);
            sStackSamplingFlushTimeoutMsDefault =
                    updateInt(
                            properties,
                            DeviceConfigHelper.STACK_SAMPLING_FLUSH_TIMEOUT_MS_DEFAULT,
                            sStackSamplingFlushTimeoutMsDefault,
                            DeviceConfigHelper.DEFAULT_STACK_SAMPLING_FLUSH_TIMEOUT_MS_DEFAULT);
            sStackSamplingDurationMsDefault =
                    updateInt(
                            properties,
                            DeviceConfigHelper.STACK_SAMPLING_DURATION_MS_DEFAULT,
                            sStackSamplingDurationMsDefault,
                            DeviceConfigHelper.DEFAULT_STACK_SAMPLING_DURATION_MS_DEFAULT);
            sStackSamplingDurationMsMin =
                    updateInt(
                            properties,
                            DeviceConfigHelper.STACK_SAMPLING_DURATION_MS_MIN,
                            sStackSamplingDurationMsMin,
                            DeviceConfigHelper.DEFAULT_STACK_SAMPLING_DURATION_MS_MIN);
            sStackSamplingDurationMsMax =
                    updateInt(
                            properties,
                            DeviceConfigHelper.STACK_SAMPLING_DURATION_MS_MAX,
                            sStackSamplingDurationMsMax,
                            DeviceConfigHelper.DEFAULT_STACK_SAMPLING_DURATION_MS_MAX);
            sStackSamplingSizeKbDefault =
                    updateInt(
                            properties,
                            DeviceConfigHelper.STACK_SAMPLING_SAMPLING_SIZE_KB_DEFAULT,
                            sStackSamplingSizeKbDefault,
                            DeviceConfigHelper.DEFAULT_STACK_SAMPLING_SAMPLING_SIZE_KB_DEFAULT);
            sStackSamplingSizeKbMin =
                    updateInt(
                            properties,
                            DeviceConfigHelper.STACK_SAMPLING_SAMPLING_SIZE_KB_MIN,
                            sStackSamplingSizeKbMin,
                            DeviceConfigHelper.DEFAULT_STACK_SAMPLING_SAMPLING_SIZE_KB_MIN);
            sStackSamplingSizeKbMax =
                    updateInt(
                            properties,
                            DeviceConfigHelper.STACK_SAMPLING_SAMPLING_SIZE_KB_MAX,
                            sStackSamplingSizeKbMax,
                            DeviceConfigHelper.DEFAULT_STACK_SAMPLING_SAMPLING_SIZE_KB_MAX);
            sStackSamplingSamplingFrequencyDefault =
                    updateInt(
                            properties,
                            DeviceConfigHelper.STACK_SAMPLING_FREQUENCY_DEFAULT,
                            sStackSamplingSamplingFrequencyDefault,
                            DeviceConfigHelper.DEFAULT_STACK_SAMPLING_FREQUENCY_DEFAULT);
            sStackSamplingSamplingFrequencyMin =
                    updateInt(
                            properties,
                            DeviceConfigHelper.STACK_SAMPLING_FREQUENCY_MIN,
                            sStackSamplingSamplingFrequencyMin,
                            DeviceConfigHelper.DEFAULT_STACK_SAMPLING_FREQUENCY_MIN);
            sStackSamplingSamplingFrequencyMax =
                    updateInt(
                            properties,
                            DeviceConfigHelper.STACK_SAMPLING_FREQUENCY_MAX,
                            sStackSamplingSamplingFrequencyMax,
                            DeviceConfigHelper.DEFAULT_STACK_SAMPLING_FREQUENCY_MAX);
        }
    }

    /** This method transforms a request into a useable config for perfetto. */
    public static byte[] generateConfigForRequest(
            int profilingType, final @Nullable Bundle params, String packageName)
            throws IllegalArgumentException {
        // Create a copy to modify. Entries will be removed from the copy as they're accessed to
        // ensure that no invalid parameters are present.
        Bundle paramsCopy = params == null ? null : new Bundle(params);

        switch (profilingType) {
            // Java heap dump
            case ProfilingManager.PROFILING_TYPE_JAVA_HEAP_DUMP:
                return generateJavaHeapDumpConfig(packageName, paramsCopy);

            // Heap profile
            case ProfilingManager.PROFILING_TYPE_HEAP_PROFILE:
                return generateHeapProfileConfig(packageName, paramsCopy);

            // Stack sampling
            case ProfilingManager.PROFILING_TYPE_STACK_SAMPLING:
                return generateStackSamplingConfig(packageName, paramsCopy);

            // System trace
            case ProfilingManager.PROFILING_TYPE_SYSTEM_TRACE:
                return generateSystemTraceConfig(packageName, paramsCopy);

            // Invalid type
            default:
                throw new IllegalArgumentException("Invalid profiling type");
        }
    }

    /**
     * This method returns how long in ms to wait initially before checking if profiling is complete
     * and rescheduling another check or post processing and cleaning up the result in the event
     * that it's not stopped manually.
     */
    public static int getInitialProfilingTimeMs(int profilingType, @Nullable Bundle params) {
        int duration;

        switch (profilingType) {
            case ProfilingManager.PROFILING_TYPE_JAVA_HEAP_DUMP:
                initializeJavaHeapDumpConfigsIfNecessary();
                duration = sJavaHeapDumpDurationMsDefault;
                break;

            case ProfilingManager.PROFILING_TYPE_HEAP_PROFILE:
                initializeHeapProfileConfigsIfNecessary();
                duration =
                        getWithinBounds(
                                ProfilingManager.KEY_DURATION_MS,
                                sHeapProfileDurationMsDefault,
                                sHeapProfileDurationMsMin,
                                sHeapProfileDurationMsMax,
                                params);
                break;

            case ProfilingManager.PROFILING_TYPE_STACK_SAMPLING:
                initializeStackSamplingConfigsIfNecessary();
                duration =
                        getWithinBounds(
                                ProfilingManager.KEY_DURATION_MS,
                                sStackSamplingDurationMsDefault,
                                sStackSamplingDurationMsMin,
                                sStackSamplingDurationMsMax,
                                params);
                break;

            case ProfilingManager.PROFILING_TYPE_SYSTEM_TRACE:
                initializeSystemTraceConfigsIfNecessary();
                duration =
                        getWithinBounds(
                                ProfilingManager.KEY_DURATION_MS,
                                sSystemTraceDurationMsDefault,
                                sSystemTraceDurationMsMin,
                                sSystemTraceDurationMsMax,
                                params);
                break;
            default:
                throw new IllegalArgumentException("Invalid profiling type");
        }
        return duration + FILE_PROCESSING_DELAY_MS;
    }

    /** This method returns the maximum profiling time allowed for the different profiling types. */
    public static int getMaxProfilingTimeAllowedMs(int profilingType, @Nullable Bundle params) {
        // Get the initial delay
        int maxAllowedProcessingTime = getInitialProfilingTimeMs(profilingType, params);

        // Add the respective flush and data source timeouts for the types that have them.
        switch (profilingType) {
            case ProfilingManager.PROFILING_TYPE_HEAP_PROFILE:
                maxAllowedProcessingTime += sHeapProfileFlushTimeoutMsDefault;
                break;

            case ProfilingManager.PROFILING_TYPE_JAVA_HEAP_DUMP:
                maxAllowedProcessingTime += sJavaHeapDumpDataSourceStopTimeoutMsDefault;
                break;

            case ProfilingManager.PROFILING_TYPE_STACK_SAMPLING:
                maxAllowedProcessingTime += sStackSamplingFlushTimeoutMsDefault;
                break;
        }
        // Add extra buffer time to account for the time it may take to start the underlying
        // process.
        return maxAllowedProcessingTime + MAX_PROFILING_TIME_BUFFER_MS;
    }

    private static TraceConfig.BufferConfig.FillPolicy getBufferFillPolicy(int bufferFillPolicy)
            throws IllegalArgumentException {
        switch (bufferFillPolicy) {
            case ProfilingManager.VALUE_BUFFER_FILL_POLICY_DISCARD:
                return TraceConfig.BufferConfig.FillPolicy.DISCARD;
            case ProfilingManager.VALUE_BUFFER_FILL_POLICY_RING_BUFFER:
                return TraceConfig.BufferConfig.FillPolicy.RING_BUFFER;
            default:
                throw new IllegalArgumentException("Invalid buffer fill policy.");
        }
    }

    private static int getWithinBounds(
            String key, int defaultValue, int minValue, int maxValue, @Nullable Bundle params) {
        if (params == null) {
            return defaultValue;
        }
        int value = params.getInt(key, defaultValue);
        if (value < minValue) {
            return minValue;
        } else if (value > maxValue) {
            return maxValue;
        } else {
            return value;
        }
    }

    private static boolean getAndRemove(String key, boolean defaultValue, @Nullable Bundle bundle) {
        if (bundle == null) {
            return defaultValue;
        }
        if (bundle.containsKey(key)) {
            boolean value = bundle.getBoolean(key);
            bundle.remove(key);
            return value;
        }
        return defaultValue;
    }

    private static int getAndRemove(String key, int defaultValue, @Nullable Bundle bundle) {
        if (bundle == null) {
            return defaultValue;
        }
        if (bundle.containsKey(key)) {
            int value = bundle.getInt(key);
            bundle.remove(key);
            return value;
        }
        return defaultValue;
    }

    private static int getAndRemoveWithinBounds(
            String key, int defaultValue, int minValue, int maxValue, @Nullable Bundle bundle) {
        if (bundle == null) {
            return defaultValue;
        }
        if (bundle.containsKey(key)) {
            int value = bundle.getInt(key);
            bundle.remove(key);
            if (value < minValue) {
                value = minValue;
            } else if (value > maxValue) {
                value = maxValue;
            }
            return value;
        }
        return defaultValue;
    }

    private static long getAndRemoveWithinBounds(
            String key, long defaultValue, long minValue, long maxValue, @Nullable Bundle bundle) {
        if (bundle == null) {
            return defaultValue;
        }
        if (bundle.containsKey(key)) {
            long value = bundle.getLong(key);
            bundle.remove(key);
            if (value < minValue) {
                value = minValue;
            } else if (value > maxValue) {
                value = maxValue;
            }
            return value;
        }
        return defaultValue;
    }

    /** Buffer sizes are preferred to be multiples of 4kb, round up to next lowest 4 multiple. */
    private static int roundUpForBufferSize(int bufferSize) {
        return (bufferSize % 4 == 0) ? bufferSize : bufferSize + (4 - (bufferSize % 4));
    }

    private static void confirmEmptyOrThrow(@Nullable Bundle bundle)
            throws IllegalArgumentException {
        if (bundle != null && !bundle.isEmpty()) {
            throw new IllegalArgumentException("Bundle contains invalid or unsupported parameters");
        }
    }

    private static byte[] generateJavaHeapDumpConfig(String packageName, Bundle params) {
        // This should be unnecessary, but make sure configs are initialized just in case.
        initializeJavaHeapDumpConfigsIfNecessary();

        if (sKillswitchJavaHeapDump) {
            throw new IllegalArgumentException("Java heap dump is disabled");
        }

        int bufferSizeKb =
                roundUpForBufferSize(
                        getAndRemoveWithinBounds(
                                ProfilingManager.KEY_SIZE_KB,
                                sJavaHeapDumpSizeKbDefault,
                                sJavaHeapDumpSizeKbMin,
                                sJavaHeapDumpSizeKbMax,
                                params));

        confirmEmptyOrThrow(params);

        TraceConfig.Builder builder = TraceConfig.newBuilder();

        // Add a buffer
        TraceConfig.BufferConfig buffer =
                TraceConfig.BufferConfig.newBuilder()
                        .setSizeKb(bufferSizeKb)
                        .setFillPolicy(TraceConfig.BufferConfig.FillPolicy.DISCARD)
                        .build();
        builder.addBuffers(buffer);

        // Add data source
        JavaHprofConfig javaHprofConfig =
                JavaHprofConfig.newBuilder()
                        .addProcessCmdline(packageName)
                        .setDumpSmaps(true)
                        .build();
        DataSourceConfig dataSourceConfig =
                DataSourceConfig.newBuilder()
                        .setName("android.java_hprof")
                        .setJavaHprofConfig(javaHprofConfig)
                        .build();
        TraceConfig.DataSource dataSource =
                TraceConfig.DataSource.newBuilder().setConfig(dataSourceConfig).build();
        builder.addDataSources(dataSource);

        // Add duration and timeout
        builder.setDurationMs(sJavaHeapDumpDurationMsDefault);
        builder.setDataSourceStopTimeoutMs(sJavaHeapDumpDataSourceStopTimeoutMsDefault);

        return builder.build().toByteArray();
    }

    private static byte[] generateHeapProfileConfig(String packageName, Bundle params) {
        // This should be unnecessary, but make sure configs are initialized just in case.
        initializeHeapProfileConfigsIfNecessary();

        if (sKillswitchHeapProfile) {
            throw new IllegalArgumentException("Heap profile is disabled");
        }

        boolean trackJavaAllocations =
                getAndRemove(
                        ProfilingManager.KEY_TRACK_JAVA_ALLOCATIONS,
                        sHeapProfileTrackJavaAllocationsDefault,
                        params);
        long samplingIntervalBytes =
                getAndRemoveWithinBounds(
                        ProfilingManager.KEY_SAMPLING_INTERVAL_BYTES,
                        sHeapProfileSamplingIntervalBytesDefault,
                        sHeapProfileSamplingIntervalBytesMin,
                        sHeapProfileSamplingIntervalBytesMax,
                        params);
        int durationMs =
                getAndRemoveWithinBounds(
                        ProfilingManager.KEY_DURATION_MS,
                        sHeapProfileDurationMsDefault,
                        sHeapProfileDurationMsMin,
                        sHeapProfileDurationMsMax,
                        params);
        int bufferSizeKb =
                roundUpForBufferSize(
                        getAndRemoveWithinBounds(
                                ProfilingManager.KEY_SIZE_KB,
                                sHeapProfileSizeKbDefault,
                                sHeapProfileSizeKbMin,
                                sHeapProfileSizeKbMax,
                                params));

        confirmEmptyOrThrow(params);

        TraceConfig.Builder builder = TraceConfig.newBuilder();

        // Add a buffer
        TraceConfig.BufferConfig buffer =
                TraceConfig.BufferConfig.newBuilder()
                        .setSizeKb(bufferSizeKb)
                        .setFillPolicy(TraceConfig.BufferConfig.FillPolicy.DISCARD)
                        .build();
        builder.addBuffers(buffer);

        // Add data source
        HeapprofdConfig.Builder heapprofdConfigBuilder =
                HeapprofdConfig.newBuilder()
                        .setShmemSizeBytes(8388608) // 8MB
                        .setSamplingIntervalBytes(samplingIntervalBytes)
                        .addProcessCmdline(packageName);
        if (trackJavaAllocations) {
            heapprofdConfigBuilder.addHeaps("com.android.art");
        }
        DataSourceConfig dataSourceConfig =
                DataSourceConfig.newBuilder()
                        .setName("android.heapprofd")
                        .setHeapprofdConfig(heapprofdConfigBuilder.build())
                        .build();
        TraceConfig.DataSource dataSource =
                TraceConfig.DataSource.newBuilder().setConfig(dataSourceConfig).build();
        builder.addDataSources(dataSource);

        // Add duration and timeout
        builder.setDurationMs(durationMs);
        builder.setFlushTimeoutMs(sHeapProfileFlushTimeoutMsDefault);

        return builder.build().toByteArray();
    }

    private static byte[] generateStackSamplingConfig(String packageName, Bundle params) {

        // This should be unnecessary, but make sure configs are initialized just in case.
        initializeStackSamplingConfigsIfNecessary();

        if (sKillswitchStackSampling) {
            throw new IllegalArgumentException("Stack sampling is disabled");
        }

        int durationMs =
                getAndRemoveWithinBounds(
                        ProfilingManager.KEY_DURATION_MS,
                        sStackSamplingDurationMsDefault,
                        sStackSamplingDurationMsMin,
                        sStackSamplingDurationMsMax,
                        params);
        int bufferSizeKb =
                roundUpForBufferSize(
                        getAndRemoveWithinBounds(
                                ProfilingManager.KEY_SIZE_KB,
                                sStackSamplingSizeKbDefault,
                                sStackSamplingSizeKbMin,
                                sStackSamplingSizeKbMax,
                                params));
        TraceConfig.BufferConfig.FillPolicy bufferFillPolicy =
                getBufferFillPolicy(
                        getAndRemove(
                                ProfilingManager.KEY_BUFFER_FILL_POLICY,
                                ProfilingManager.VALUE_BUFFER_FILL_POLICY_DISCARD,
                                params));
        StackSamplingParams stackSamplingParams = new StackSamplingParams(params);

        confirmEmptyOrThrow(params);

        TraceConfig.Builder builder = TraceConfig.newBuilder();

        if (Flags.redactStackSampling()) {
            // Add 2 buffers, one for the package list and one for the stack sampling data.
            TraceConfig.BufferConfig buffer0 =
                    TraceConfig.BufferConfig.newBuilder()
                            .setSizeKb(sStackSamplingDiscardBufferSizeKb)
                            .setFillPolicy(TraceConfig.BufferConfig.FillPolicy.DISCARD)
                            .build();
            builder.addBuffers(buffer0);
            TraceConfig.BufferConfig buffer1 =
                    TraceConfig.BufferConfig.newBuilder()
                            .setSizeKb(bufferSizeKb)
                            .setFillPolicy(bufferFillPolicy)
                            .build();
            builder.addBuffers(buffer1);

            // Add package list data source
            PackagesListConfig.Builder packagesListConfigBuilder = PackagesListConfig.newBuilder();
            packagesListConfigBuilder.addPackageNameFilter(packageName);
            DataSourceConfig dataSourceConfigPackagesList =
                    DataSourceConfig.newBuilder()
                            .setName("android.packages_list")
                            .setTargetBuffer(0)
                            .setPackagesListConfig(packagesListConfigBuilder.build())
                            .build();
            TraceConfig.DataSource dataSourcePackagesList =
                    TraceConfig.DataSource.newBuilder()
                            .setConfig(dataSourceConfigPackagesList)
                            .build();
            builder.addDataSources(dataSourcePackagesList);

            // Use target buffer 1 as we created a buffer 0 for the package list.
            addStackSamplingGeneralConfigs(
                    builder, 1 /* targetBuffer */, packageName, stackSamplingParams);
        } else {
            // Add a buffer
            TraceConfig.BufferConfig buffer =
                    TraceConfig.BufferConfig.newBuilder()
                            .setSizeKb(bufferSizeKb)
                            .setFillPolicy(bufferFillPolicy)
                            .build();
            builder.addBuffers(buffer);

            // Use target buffer 0 as we just created the singular buffer above.
            addStackSamplingGeneralConfigs(
                    builder, 0 /* targetBuffer */, packageName, stackSamplingParams);
        }

        // Add duration
        builder.setDurationMs(durationMs);

        return builder.build().toByteArray();
    }

    private static void addStackSamplingGeneralConfigs(
            TraceConfig.Builder builder,
            int targetBuffer,
            String packageName,
            StackSamplingParams stackSamplingParams) {

        // Create appropriate timebase based on parameters.
        PerfEvents.Timebase timebase = null;
        if (stackSamplingParams.mSampleBinderOnly) {
            PerfEvents.Tracepoint tracepoint =
                    PerfEvents.Tracepoint.newBuilder().setName("binder:binder_transaction").build();
            timebase =
                    PerfEvents.Timebase.newBuilder()
                            .setTracepoint(tracepoint)
                            .setName("binder_transaction")
                            .setFrequency(stackSamplingParams.mFrequency)
                            .setTimestampClock(PerfEvents.PerfClock.PERF_CLOCK_MONOTONIC)
                            .build();
        } else {
            timebase =
                    PerfEvents.Timebase.newBuilder()
                            .setCounter(PerfEvents.Counter.SW_CPU_CLOCK)
                            .setFrequency(stackSamplingParams.mFrequency)
                            .setTimestampClock(PerfEvents.PerfClock.PERF_CLOCK_MONOTONIC)
                            .build();
        }

        // Add data sources which apply for all current stack sampling configs.
        // Create a scope limited to the supplied package name only.
        PerfEventConfig.Scope scope =
                PerfEventConfig.Scope.newBuilder().addTargetCmdline(packageName).build();
        PerfEventConfig.CallstackSampling callstackSampling =
                PerfEventConfig.CallstackSampling.newBuilder().setScope(scope).build();
        // Configuration for the traced_perf profiler which interacts with linux.perf, the source
        // for all current supported stack sampling configs.
        PerfEventConfig perfEventConfig =
                PerfEventConfig.newBuilder()
                        .setTimebase(timebase)
                        .setCallstackSampling(callstackSampling)
                        .build();
        DataSourceConfig dataSourceConfig =
                DataSourceConfig.newBuilder()
                        .setName("linux.perf")
                        .setPerfEventConfig(perfEventConfig)
                        .setTargetBuffer(targetBuffer)
                        .build();
        TraceConfig.DataSource dataSource =
                TraceConfig.DataSource.newBuilder().setConfig(dataSourceConfig).build();
        builder.addDataSources(dataSource);

        // Add timeout
        builder.setFlushTimeoutMs(sStackSamplingFlushTimeoutMsDefault);
    }

    private static byte[] generateSystemTraceConfig(String packageName, Bundle params) {
        // This should be unnecessary, but make sure configs are initialized just in case.
        initializeSystemTraceConfigsIfNecessary();

        if (sKillswitchSystemTrace) {
            throw new IllegalArgumentException("System trace is disabled");
        }

        StackSamplingParams stackSamplingParams = null;

        if (getAndRemove(ProfilingManager.KEY_COLLECT_STACK_SAMPLING, false, params)) {
            if (!Flags.systemTraceAddStackSampling()) {
                throw new IllegalArgumentException(
                        "Adding stack sampling to system trace is not supported.");
            }

            initializeStackSamplingConfigsIfNecessary();

            if (sKillswitchStackSampling) {
                throw new IllegalArgumentException("Stack sampling is disabled");
            }

            stackSamplingParams = new StackSamplingParams(params);
        }

        int durationMs =
                getAndRemoveWithinBounds(
                        ProfilingManager.KEY_DURATION_MS,
                        sSystemTraceDurationMsDefault,
                        sSystemTraceDurationMsMin,
                        sSystemTraceDurationMsMax,
                        params);
        int bufferSizeKb =
                roundUpForBufferSize(
                        getAndRemoveWithinBounds(
                                ProfilingManager.KEY_SIZE_KB,
                                sSystemTraceSizeKbDefault,
                                sSystemTraceSizeKbMin,
                                sSystemTraceSizeKbMax,
                                params));
        TraceConfig.BufferConfig.FillPolicy bufferFillPolicy =
                getBufferFillPolicy(
                        getAndRemove(
                                ProfilingManager.KEY_BUFFER_FILL_POLICY,
                                ProfilingManager.VALUE_BUFFER_FILL_POLICY_RING_BUFFER,
                                params));

        confirmEmptyOrThrow(params);

        TraceConfig.Builder builder = TraceConfig.newBuilder();

        addSystemTraceGeneralConfigs(
                builder,
                new String[] {packageName},
                FOUR_MB,
                bufferSizeKb,
                durationMs,
                bufferFillPolicy);

        if (stackSamplingParams != null) {
            // Use target buffer 1 as addSystemTraceGeneralConfigs will create 2 buffers: buffer 0
            // for one time collections on start, and buffer 1 for everything else.
            addStackSamplingGeneralConfigs(
                    builder, 1 /* targetBuffer */, packageName, stackSamplingParams);
        }

        return builder.build().toByteArray();
    }

    /**
     * Generate config for system triggered background system trace.
     *
     * @param extraLong should only be set to true for testing.
     */
    public static byte[] generateSystemTriggeredTraceConfig(
            String uniqueSessionName, String[] packageNames, boolean extraLong) {
        // Make sure we have our config values set. This is the only config specific method which is
        // called directly and therefore needs to verify the config value initialization directly.
        initializeSystemTriggeredSystemTraceConfigsIfNecessary();

        TraceConfig.Builder builder = TraceConfig.newBuilder();

        addSystemTraceGeneralConfigs(
                builder,
                packageNames,
                sSystemTriggeredSystemTraceDiscardBufferSizeKb,
                sSystemTriggeredSystemTraceRingBufferSizeKb,
                extraLong ? ONE_DAY_MS : sSystemTriggeredSystemTraceDurationMs,
                TraceConfig.BufferConfig.FillPolicy.RING_BUFFER);

        builder.setUniqueSessionName(uniqueSessionName);

        return builder.build().toByteArray();
    }

    private static void addSystemTraceGeneralConfigs(
            TraceConfig.Builder builder,
            String[] packageNames,
            int bufferOneSizeKb,
            int bufferTwoSizeKb,
            int durationMs,
            TraceConfig.BufferConfig.FillPolicy bufferTwoFillPolicy) {
        // Add 2 buffers, discard for data sources dumped at beginning and caller set for all other
        // data sources.
        TraceConfig.BufferConfig buffer0 =
                TraceConfig.BufferConfig.newBuilder()
                        .setSizeKb(bufferOneSizeKb)
                        .setFillPolicy(TraceConfig.BufferConfig.FillPolicy.DISCARD)
                        .build();
        builder.addBuffers(buffer0);
        TraceConfig.BufferConfig buffer1 =
                TraceConfig.BufferConfig.newBuilder()
                        .setSizeKb(bufferTwoSizeKb)
                        .setFillPolicy(bufferTwoFillPolicy)
                        .build();
        builder.addBuffers(buffer1);

        // Add a whole bunch of data sources

        // Scan and dump all processes to buffer 0 when trace starts
        ProcessStatsConfig processStatsConfig =
                ProcessStatsConfig.newBuilder().setScanAllProcessesOnStart(true).build();
        DataSourceConfig dataSourceConfigProcessStats =
                DataSourceConfig.newBuilder()
                        .setName("linux.process_stats")
                        .setTargetBuffer(0)
                        .setProcessStatsConfig(processStatsConfig)
                        .build();
        TraceConfig.DataSource dataSourceProcessStats =
                TraceConfig.DataSource.newBuilder().setConfig(dataSourceConfigProcessStats).build();
        builder.addDataSources(dataSourceProcessStats);

        // Initialize the builders that require package names so we only need to iterate through the
        // list once. These will be used in the following two sections.
        PackagesListConfig.Builder packagesListConfigBuilder = PackagesListConfig.newBuilder();
        FtraceConfig.Builder ftraceConfigBuilder = FtraceConfig.newBuilder();

        for (int i = 0; i < packageNames.length; i++) {
            String packageName = packageNames[i];

            // Enable atrace events for each app.
            ftraceConfigBuilder.addAtraceApps(packageName);

            // Add to package list config so data is kept by filter.
            packagesListConfigBuilder.addPackageNameFilter(packageName);
        }

        // Dump details about all listed packages to buffer 0. Redactor will filter out the ones
        // that should not end up in the finished output.
        DataSourceConfig dataSourceConfigPackagesList =
                DataSourceConfig.newBuilder()
                        .setName("android.packages_list")
                        .setTargetBuffer(0)
                        .setPackagesListConfig(packagesListConfigBuilder.build())
                        .build();
        TraceConfig.DataSource dataSourcePackagesList =
                TraceConfig.DataSource.newBuilder().setConfig(dataSourceConfigPackagesList).build();
        builder.addDataSources(dataSourcePackagesList);

        // Dump select ftrace events to buffer 1
        FtraceConfig.CompactSchedConfig compactSchedConfig =
                FtraceConfig.CompactSchedConfig.newBuilder().setEnabled(true).build();
        ftraceConfigBuilder
                .setThrottleRssStat(true)
                .setDisableGenericEvents(true)
                .setCompactSched(compactSchedConfig)
                // RSS and ION buffer events:
                .addFtraceEvents("gpu_mem/gpu_mem_total")
                // Scheduling information & process tracking. Useful for:
                // - what is happening on each CPU at each moment
                // - why a thread was descheduled
                // - parent/child relationships between processes and threads
                .addFtraceEvents("power/suspend_resume")
                .addFtraceEvents("sched/sched_process_free")
                .addFtraceEvents("sched/sched_switch")
                .addFtraceEvents("task/task_newtask")
                .addFtraceEvents("task/task_rename")
                // Wakeup info. Allows you to compute how long a task was:
                .addFtraceEvents("sched/sched_waking")
                .addFtraceEvents("sched/sched_wakeup_new")
                // vmscan and mm_compaction events:
                .addFtraceEvents("vmscan/mm_vmscan_direct_reclaim_begin")
                .addFtraceEvents("vmscan/mm_vmscan_direct_reclaim_end")
                // Atrace activity manager:
                .addAtraceCategories("am")
                // Java and C:
                .addAtraceCategories("dalvik")
                // Bionic C library:
                .addAtraceCategories("bionic")
                // Binder kernel driver
                .addAtraceCategories("binder_driver")
                // View system:
                .addAtraceCategories("view")
                // Input:
                .addAtraceCategories("input")
                // Graphics:
                .addAtraceCategories("gfx");

        DataSourceConfig dataSourceConfigFtrace =
                DataSourceConfig.newBuilder()
                        .setName("linux.ftrace")
                        .setTargetBuffer(1)
                        .setFtraceConfig(ftraceConfigBuilder.build())
                        .build();
        TraceConfig.DataSource dataSourceFtrace =
                TraceConfig.DataSource.newBuilder().setConfig(dataSourceConfigFtrace).build();
        builder.addDataSources(dataSourceFtrace);

        // Dump surfaceflinger frame timeline to buffer 1
        DataSourceConfig dataSourceConfigSurfaceFlinger =
                DataSourceConfig.newBuilder()
                        .setName("android.surfaceflinger.frametimeline")
                        .setTargetBuffer(1)
                        .build();
        TraceConfig.DataSource dataSourceSurfaceFlinger =
                TraceConfig.DataSource.newBuilder()
                        .setConfig(dataSourceConfigSurfaceFlinger)
                        .build();
        builder.addDataSources(dataSourceSurfaceFlinger);

        // Clear incremental state
        TraceConfig.IncrementalStateConfig incrementalStateConfig =
                TraceConfig.IncrementalStateConfig.newBuilder().setClearPeriodMs(10000).build();
        builder.setIncrementalStateConfig(incrementalStateConfig);

        // Add duration
        builder.setDurationMs(durationMs);
    }

    private static final class StackSamplingParams {
        final long mFrequency;
        final boolean mSampleBinderOnly;

        StackSamplingParams(Bundle params) {
            mFrequency =
                    getAndRemoveWithinBounds(
                            ProfilingManager.KEY_FREQUENCY_HZ,
                            sStackSamplingSamplingFrequencyDefault,
                            sStackSamplingSamplingFrequencyMin,
                            sStackSamplingSamplingFrequencyMax,
                            params);
            mSampleBinderOnly =
                    getAndRemove(ProfilingManager.KEY_SAMPLE_BINDER_ONLY, false, params);
        }
    }
}
