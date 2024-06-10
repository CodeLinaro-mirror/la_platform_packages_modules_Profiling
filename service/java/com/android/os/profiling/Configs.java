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

    private static boolean sSystemTraceConfigsInitialized = false;
    private static boolean sHeapProfileConfigsInitialized = false;
    private static boolean sJavaHeapDumpConfigsInitialized = false;
    private static boolean sStackSamplingConfigsInitialized = false;

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
    private static int sHeapProfileSamplingIntervalBytesDefault;
    private static int sHeapProfileSamplingIntervalBytesMin;
    private static int sHeapProfileSamplingIntervalBytesMax;

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

    /** Initialize System Trace related DeviceConfig set values if they have not been yet. */
    private static void initializeSystemTraceConfigsIfNecessary() {
        if (sSystemTraceConfigsInitialized) {
            return;
        }

        DeviceConfig.Properties properties = DeviceConfigHelper.getAllSystemTraceProperties();

        sKillswitchSystemTrace = properties.getBoolean(
                DeviceConfigHelper.KILLSWITCH_SYSTEM_TRACE, false);
        sSystemTraceDurationMsDefault = properties.getInt(
                DeviceConfigHelper.SYSTEM_TRACE_DURATION_MS_DEFAULT, 300000);
        sSystemTraceDurationMsMin = properties.getInt(
                DeviceConfigHelper.SYSTEM_TRACE_DURATION_MS_MIN, 1000);
        sSystemTraceDurationMsMax = properties.getInt(
                DeviceConfigHelper.SYSTEM_TRACE_DURATION_MS_MAX, 600000);
        sSystemTraceSizeKbDefault = properties.getInt(
                DeviceConfigHelper.SYSTEM_TRACE_SIZE_KB_DEFAULT, 32768);
        sSystemTraceSizeKbMin = properties.getInt(
                DeviceConfigHelper.SYSTEM_TRACE_SIZE_KB_MIN, 4);
        sSystemTraceSizeKbMax = properties.getInt(
                DeviceConfigHelper.SYSTEM_TRACE_SIZE_KB_MAX, 32768);

        sSystemTraceConfigsInitialized = true;
    }

    /** Initialize Java Heap Dump related DeviceConfig set values if they have not been yet. */
    private static void initializeJavaHeapDumpConfigsIfNecessary() {
        if (sJavaHeapDumpConfigsInitialized) {
            return;
        }

        DeviceConfig.Properties properties = DeviceConfigHelper.getAllJavaHeapDumpProperties();

        sKillswitchJavaHeapDump = properties.getBoolean(
                DeviceConfigHelper.KILLSWITCH_JAVA_HEAP_DUMP, false);
        sJavaHeapDumpDurationMsDefault = properties.getInt(
                DeviceConfigHelper.JAVA_HEAP_DUMP_DURATION_MS_DEFAULT, 1000);
        sJavaHeapDumpDataSourceStopTimeoutMsDefault = properties.getInt(
                DeviceConfigHelper.JAVA_HEAP_DUMP_DATA_SOURCE_STOP_TIMEOUT_MS_DEFAULT, 100000);
        sJavaHeapDumpSizeKbDefault = properties.getInt(
                DeviceConfigHelper.JAVA_HEAP_DUMP_SIZE_KB_DEFAULT, 256000);
        sJavaHeapDumpSizeKbMin = properties.getInt(
                DeviceConfigHelper.JAVA_HEAP_DUMP_SIZE_KB_MIN, 4);
        sJavaHeapDumpSizeKbMax = properties.getInt(
                DeviceConfigHelper.JAVA_HEAP_DUMP_SIZE_KB_MAX, 256000);

        sJavaHeapDumpConfigsInitialized = true;
    }

    /** Initialize Heap Profile related DeviceConfig set values if they have not been yet. */
    private static void initializeHeapProfileConfigsIfNecessary() {
        if (sHeapProfileConfigsInitialized) {
            return;
        }

        DeviceConfig.Properties properties = DeviceConfigHelper.getAllHeapProfileProperties();

        sKillswitchHeapProfile = properties.getBoolean(
                DeviceConfigHelper.KILLSWITCH_HEAP_PROFILE, false);
        sHeapProfileTrackJavaAllocationsDefault = properties.getBoolean(
                DeviceConfigHelper.HEAP_PROFILE_TRACK_JAVA_ALLOCATIONS_DEFAULT, false);
        sHeapProfileFlushTimeoutMsDefault = properties.getInt(
                DeviceConfigHelper.HEAP_PROFILE_FLUSH_TIMEOUT_MS_DEFAULT, 30000);
        sHeapProfileDurationMsDefault = properties.getInt(
                DeviceConfigHelper.HEAP_PROFILE_DURATION_MS_DEFAULT, 120000);
        sHeapProfileDurationMsMin = properties.getInt(
                DeviceConfigHelper.HEAP_PROFILE_DURATION_MS_MIN, 1000);
        sHeapProfileDurationMsMax = properties.getInt(
                DeviceConfigHelper.HEAP_PROFILE_DURATION_MS_MAX, 300000);
        sHeapProfileSizeKbDefault = properties.getInt(
                DeviceConfigHelper.HEAP_PROFILE_SIZE_KB_DEFAULT, 65536);
        sHeapProfileSizeKbMin = properties.getInt(
                DeviceConfigHelper.HEAP_PROFILE_SIZE_KB_MIN, 4);
        sHeapProfileSizeKbMax = properties.getInt(
                DeviceConfigHelper.HEAP_PROFILE_SIZE_KB_MAX, 65536);
        sHeapProfileSamplingIntervalBytesDefault = properties.getInt(
                DeviceConfigHelper.HEAP_PROFILE_SAMPLING_INTERVAL_BYTES_DEFAULT, 4096);
        sHeapProfileSamplingIntervalBytesMin = properties.getInt(
                DeviceConfigHelper.HEAP_PROFILE_SAMPLING_INTERVAL_BYTES_MIN, 1);
        sHeapProfileSamplingIntervalBytesMax = properties.getInt(
                DeviceConfigHelper.HEAP_PROFILE_SAMPLING_INTERVAL_BYTES_MAX, 65536);

        sHeapProfileConfigsInitialized = true;
    }

    /** Initialize Stack Sampling related DeviceConfig set values if they have not been yet. */
    private static void initializeStackSamplingConfigsIfNecessary() {
        if (sStackSamplingConfigsInitialized) {
            return;
        }

        DeviceConfig.Properties properties = DeviceConfigHelper.getAllStackSamplingProperties();

        sKillswitchStackSampling = properties.getBoolean(
                DeviceConfigHelper.KILLSWITCH_STACK_SAMPLING, false);
        sStackSamplingFlushTimeoutMsDefault = properties.getInt(
                DeviceConfigHelper.STACK_SAMPLING_FLUSH_TIMEOUT_MS_DEFAULT, 30000);
        sStackSamplingDurationMsDefault = properties.getInt(
                DeviceConfigHelper.STACK_SAMPLING_DURATION_MS_DEFAULT, 60000);
        sStackSamplingDurationMsMin = properties.getInt(
                DeviceConfigHelper.STACK_SAMPLING_DURATION_MS_MIN, 1000);
        sStackSamplingDurationMsMax = properties.getInt(
                DeviceConfigHelper.STACK_SAMPLING_DURATION_MS_MAX, 300000);
        sStackSamplingSizeKbDefault = properties.getInt(
                DeviceConfigHelper.STACK_SAMPLING_SAMPLING_SIZE_KB_DEFAULT, 65536);
        sStackSamplingSizeKbMin = properties.getInt(
                DeviceConfigHelper.STACK_SAMPLING_SAMPLING_SIZE_KB_MIN, 4);
        sStackSamplingSizeKbMax = properties.getInt(
                DeviceConfigHelper.STACK_SAMPLING_SAMPLING_SIZE_KB_MAX, 65536);
        sStackSamplingSamplingFrequencyDefault = properties.getInt(
                DeviceConfigHelper.STACK_SAMPLING_FREQUENCY_DEFAULT, 100);
        sStackSamplingSamplingFrequencyMin = properties.getInt(
                DeviceConfigHelper.STACK_SAMPLING_FREQUENCY_MIN, 1);
        sStackSamplingSamplingFrequencyMax = properties.getInt(
                DeviceConfigHelper.STACK_SAMPLING_FREQUENCY_MAX, 200);

        sStackSamplingConfigsInitialized = true;
    }

    /**
     * Update DeviceConfig set configuration values if present in the provided properties, leaving
     * not present values unchanged.
     *
     * Will only update values that have already been initialized as initialization is required
     * before use and the changed values will be available under the normal access path.
     */
    public static void maybeUpdateConfigs(DeviceConfig.Properties properties) {
        // TODO(b/330940387): Revisit defaults before release

        if (sSystemTraceConfigsInitialized) {
            sKillswitchSystemTrace = properties.getBoolean(
                    DeviceConfigHelper.KILLSWITCH_SYSTEM_TRACE, sKillswitchSystemTrace);
            sSystemTraceDurationMsDefault = properties.getInt(
                    DeviceConfigHelper.SYSTEM_TRACE_DURATION_MS_DEFAULT,
                    sSystemTraceDurationMsDefault);
            sSystemTraceDurationMsMin = properties.getInt(
                    DeviceConfigHelper.SYSTEM_TRACE_DURATION_MS_MIN, sSystemTraceDurationMsMin);
            sSystemTraceDurationMsMax = properties.getInt(
                    DeviceConfigHelper.SYSTEM_TRACE_DURATION_MS_MAX, sSystemTraceDurationMsMax);
            sSystemTraceSizeKbDefault = properties.getInt(
                    DeviceConfigHelper.SYSTEM_TRACE_SIZE_KB_DEFAULT, sSystemTraceSizeKbDefault);
            sSystemTraceSizeKbMin = properties.getInt(
                    DeviceConfigHelper.SYSTEM_TRACE_SIZE_KB_MIN, sSystemTraceSizeKbMin);
            sSystemTraceSizeKbMax = properties.getInt(
                    DeviceConfigHelper.SYSTEM_TRACE_SIZE_KB_MAX, sSystemTraceSizeKbMax);
        }

        if (sHeapProfileConfigsInitialized) {
            sKillswitchHeapProfile = properties.getBoolean(
                    DeviceConfigHelper.KILLSWITCH_HEAP_PROFILE, sKillswitchHeapProfile);
            sHeapProfileTrackJavaAllocationsDefault = properties.getBoolean(
                    DeviceConfigHelper.HEAP_PROFILE_TRACK_JAVA_ALLOCATIONS_DEFAULT,
                    sHeapProfileTrackJavaAllocationsDefault);
            sHeapProfileFlushTimeoutMsDefault = properties.getInt(
                    DeviceConfigHelper.HEAP_PROFILE_FLUSH_TIMEOUT_MS_DEFAULT,
                    sHeapProfileFlushTimeoutMsDefault);
            sHeapProfileDurationMsDefault = properties.getInt(
                    DeviceConfigHelper.HEAP_PROFILE_DURATION_MS_DEFAULT,
                    sHeapProfileDurationMsDefault);
            sHeapProfileDurationMsMin = properties.getInt(
                    DeviceConfigHelper.HEAP_PROFILE_DURATION_MS_MIN, sHeapProfileDurationMsMin);
            sHeapProfileDurationMsMax = properties.getInt(
                    DeviceConfigHelper.HEAP_PROFILE_DURATION_MS_MAX, sHeapProfileDurationMsMax);
            sHeapProfileSizeKbDefault = properties.getInt(
                    DeviceConfigHelper.HEAP_PROFILE_SIZE_KB_DEFAULT, sHeapProfileSizeKbDefault);
            sHeapProfileSizeKbMin = properties.getInt(
                    DeviceConfigHelper.HEAP_PROFILE_SIZE_KB_MIN, sHeapProfileSizeKbMin);
            sHeapProfileSizeKbMax = properties.getInt(
                    DeviceConfigHelper.HEAP_PROFILE_SIZE_KB_MAX, sHeapProfileSizeKbMax);
            sHeapProfileSamplingIntervalBytesDefault = properties.getInt(
                    DeviceConfigHelper.HEAP_PROFILE_SAMPLING_INTERVAL_BYTES_DEFAULT,
                    sHeapProfileSamplingIntervalBytesDefault);
            sHeapProfileSamplingIntervalBytesMin = properties.getInt(
                    DeviceConfigHelper.HEAP_PROFILE_SAMPLING_INTERVAL_BYTES_MIN,
                    sHeapProfileSamplingIntervalBytesMin);
            sHeapProfileSamplingIntervalBytesMax = properties.getInt(
                    DeviceConfigHelper.HEAP_PROFILE_SAMPLING_INTERVAL_BYTES_MAX,
                    sHeapProfileSamplingIntervalBytesMax);
        }

        if (sJavaHeapDumpConfigsInitialized) {
            sKillswitchJavaHeapDump = properties.getBoolean(
                    DeviceConfigHelper.KILLSWITCH_JAVA_HEAP_DUMP, sKillswitchJavaHeapDump);
            sJavaHeapDumpDurationMsDefault = properties.getInt(
                    DeviceConfigHelper.JAVA_HEAP_DUMP_DURATION_MS_DEFAULT,
                    sJavaHeapDumpDurationMsDefault);
            sJavaHeapDumpDataSourceStopTimeoutMsDefault = properties.getInt(
                    DeviceConfigHelper.JAVA_HEAP_DUMP_DATA_SOURCE_STOP_TIMEOUT_MS_DEFAULT,
                    sJavaHeapDumpDataSourceStopTimeoutMsDefault);
            sJavaHeapDumpSizeKbDefault = properties.getInt(
                    DeviceConfigHelper.JAVA_HEAP_DUMP_SIZE_KB_DEFAULT, sJavaHeapDumpSizeKbDefault);
            sJavaHeapDumpSizeKbMin = properties.getInt(
                    DeviceConfigHelper.JAVA_HEAP_DUMP_SIZE_KB_MIN, sJavaHeapDumpSizeKbMin);
            sJavaHeapDumpSizeKbMax = properties.getInt(
                    DeviceConfigHelper.JAVA_HEAP_DUMP_SIZE_KB_MAX, sJavaHeapDumpSizeKbMax);
        }

        if (sStackSamplingConfigsInitialized) {
            sKillswitchStackSampling = properties.getBoolean(
                    DeviceConfigHelper.KILLSWITCH_STACK_SAMPLING, sKillswitchStackSampling);
            sStackSamplingFlushTimeoutMsDefault = properties.getInt(
                    DeviceConfigHelper.STACK_SAMPLING_FLUSH_TIMEOUT_MS_DEFAULT,
                    sStackSamplingFlushTimeoutMsDefault);
            sStackSamplingDurationMsDefault = properties.getInt(
                    DeviceConfigHelper.STACK_SAMPLING_DURATION_MS_DEFAULT,
                    sStackSamplingDurationMsDefault);
            sStackSamplingDurationMsMin = properties.getInt(
                    DeviceConfigHelper.STACK_SAMPLING_DURATION_MS_MIN, sStackSamplingDurationMsMin);
            sStackSamplingDurationMsMax = properties.getInt(
                    DeviceConfigHelper.STACK_SAMPLING_DURATION_MS_MAX, sStackSamplingDurationMsMax);
            sStackSamplingSizeKbDefault = properties.getInt(
                    DeviceConfigHelper.STACK_SAMPLING_SAMPLING_SIZE_KB_DEFAULT,
                    sStackSamplingSizeKbDefault);
            sStackSamplingSizeKbMin = properties.getInt(
                    DeviceConfigHelper.STACK_SAMPLING_SAMPLING_SIZE_KB_MIN,
                    sStackSamplingSizeKbMin);
            sStackSamplingSizeKbMax = properties.getInt(
                    DeviceConfigHelper.STACK_SAMPLING_SAMPLING_SIZE_KB_MAX,
                    sStackSamplingSizeKbMax);
            sStackSamplingSamplingFrequencyDefault = properties.getInt(
                    DeviceConfigHelper.STACK_SAMPLING_FREQUENCY_DEFAULT,
                    sStackSamplingSamplingFrequencyDefault);
            sStackSamplingSamplingFrequencyMin = properties.getInt(
                    DeviceConfigHelper.STACK_SAMPLING_FREQUENCY_MIN,
                    sStackSamplingSamplingFrequencyMin);
            sStackSamplingSamplingFrequencyMax = properties.getInt(
                    DeviceConfigHelper.STACK_SAMPLING_FREQUENCY_MAX,
                    sStackSamplingSamplingFrequencyMax);
        }
    }

    /** This method transforms a request into a useable config for perfetto. */
    public static byte[] generateConfigForRequest(int profilingType, final @Nullable Bundle params,
            String packageName) throws IllegalArgumentException {
        // Create a copy to modify. Entries will be removed from the copy as they're accessed to
        // ensure that no invalid parameters are present.
        Bundle paramsCopy = params == null ? null : new Bundle(params);

        switch (profilingType) {
            // Java heap dump
            case ProfilingManager.PROFILING_TYPE_JAVA_HEAP_DUMP:
                // This should be unnecessary, but make sure configs are initialized just in case.
                initializeJavaHeapDumpConfigsIfNecessary();

                if (sKillswitchJavaHeapDump) {
                    throw new IllegalArgumentException("Java heap dump is disabled");
                }

                int javaHeapDumpSizeKb = roundUpForBufferSize(getAndRemoveWithinBounds(
                        ProfilingManager.KEY_SIZE_KB,
                        sJavaHeapDumpSizeKbDefault,
                        sJavaHeapDumpSizeKbMin,
                        sJavaHeapDumpSizeKbMax,
                        paramsCopy));

                confirmEmptyOrThrow(paramsCopy);

                return generateJavaHeapDumpConfig(packageName, javaHeapDumpSizeKb);

            // Heap profile
            case ProfilingManager.PROFILING_TYPE_HEAP_PROFILE:
                // This should be unnecessary, but make sure configs are initialized just in case.
                initializeHeapProfileConfigsIfNecessary();

                if (sKillswitchHeapProfile) {
                    throw new IllegalArgumentException("Heap profile is disabled");
                }

                boolean trackJavaAllocations = getAndRemove(
                        ProfilingManager.KEY_TRACK_JAVA_ALLOCATIONS,
                        sHeapProfileTrackJavaAllocationsDefault, paramsCopy);
                long samplingIntervalBytes = getAndRemoveWithinBounds(
                        ProfilingManager.KEY_SAMPLING_INTERVAL_BYTES,
                        sHeapProfileSamplingIntervalBytesDefault,
                        sHeapProfileSamplingIntervalBytesMin,
                        sHeapProfileSamplingIntervalBytesMax,
                        paramsCopy);
                int heapProfileDurationMs = getAndRemoveWithinBounds(
                        ProfilingManager.KEY_DURATION_MS,
                        sHeapProfileDurationMsDefault,
                        sHeapProfileDurationMsMin,
                        sHeapProfileDurationMsMax,
                        paramsCopy);
                int heapProfileSizeKb = roundUpForBufferSize(getAndRemoveWithinBounds(
                        ProfilingManager.KEY_SIZE_KB,
                        sHeapProfileSizeKbDefault,
                        sHeapProfileSizeKbMin,
                        sHeapProfileSizeKbMax,
                        paramsCopy));

                confirmEmptyOrThrow(paramsCopy);

                return generateHeapProfileConfig(packageName, heapProfileSizeKb,
                        heapProfileDurationMs, samplingIntervalBytes, trackJavaAllocations);

            // Stack sampling
            case ProfilingManager.PROFILING_TYPE_STACK_SAMPLING:
                // This should be unnecessary, but make sure configs are initialized just in case.
                initializeStackSamplingConfigsIfNecessary();

                if (sKillswitchStackSampling) {
                    throw new IllegalArgumentException("Stack sampling is disabled");
                }

                long frequency = getAndRemoveWithinBounds(ProfilingManager.KEY_FREQUENCY_HZ,
                        sStackSamplingSamplingFrequencyDefault,
                        sStackSamplingSamplingFrequencyMin,
                        sStackSamplingSamplingFrequencyMax,
                        paramsCopy);
                int stackSamplingDurationMs = getAndRemoveWithinBounds(
                        ProfilingManager.KEY_DURATION_MS,
                        sStackSamplingDurationMsDefault,
                        sStackSamplingDurationMsMin,
                        sStackSamplingDurationMsMax,
                        paramsCopy);
                int stackSamplingSizeKb = roundUpForBufferSize(getAndRemoveWithinBounds(
                        ProfilingManager.KEY_SIZE_KB,
                        sStackSamplingSizeKbDefault,
                        sStackSamplingSizeKbMin,
                        sStackSamplingSizeKbMax,
                        paramsCopy));

                confirmEmptyOrThrow(paramsCopy);

                return generateStackSamplingConfig(packageName, stackSamplingSizeKb,
                        stackSamplingDurationMs, frequency);

            // System trace
            case ProfilingManager.PROFILING_TYPE_SYSTEM_TRACE:
                // This should be unnecessary, but make sure configs are initialized just in case.
                initializeSystemTraceConfigsIfNecessary();

                if (!Flags.redactionEnabled()) {
                    throw new IllegalArgumentException("Trace is not currently supported");
                }

                if (sKillswitchSystemTrace) {
                    throw new IllegalArgumentException("System trace is disabled");
                }

                int systemTraceDurationMs = getAndRemoveWithinBounds(
                        ProfilingManager.KEY_DURATION_MS,
                        sSystemTraceDurationMsDefault,
                        sSystemTraceDurationMsMin,
                        sSystemTraceDurationMsMax,
                        paramsCopy);
                int systemTraceSizeKb = roundUpForBufferSize(getAndRemoveWithinBounds(
                        ProfilingManager.KEY_SIZE_KB,
                        sSystemTraceSizeKbDefault,
                        sSystemTraceSizeKbMin,
                        sSystemTraceSizeKbMax,
                        paramsCopy));
                TraceConfig.BufferConfig.FillPolicy systemTraceBufferFillPolicy =
                        getBufferFillPolicy(getAndRemove(ProfilingManager.KEY_BUFFER_FILL_POLICY,
                                ProfilingManager.VALUE_BUFFER_FILL_POLICY_RING_BUFFER, paramsCopy));

                confirmEmptyOrThrow(paramsCopy);

                return generateSystemTraceConfig(packageName, systemTraceSizeKb,
                        systemTraceDurationMs, systemTraceBufferFillPolicy);

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
    public static int getInitialProfilingTimeMs(int profilingType,
            @Nullable Bundle params) {
        int duration;
        switch (profilingType) {
            case ProfilingManager.PROFILING_TYPE_JAVA_HEAP_DUMP:
                initializeJavaHeapDumpConfigsIfNecessary();
                duration = sJavaHeapDumpDurationMsDefault;
                break;

            case ProfilingManager.PROFILING_TYPE_HEAP_PROFILE:
                initializeHeapProfileConfigsIfNecessary();
                duration = getWithinBounds(ProfilingManager.KEY_DURATION_MS,
                        sHeapProfileDurationMsDefault, sHeapProfileDurationMsMin,
                        sHeapProfileDurationMsMax, params);
                break;

            case ProfilingManager.PROFILING_TYPE_STACK_SAMPLING:
                initializeStackSamplingConfigsIfNecessary();
                duration = getWithinBounds(ProfilingManager.KEY_DURATION_MS,
                        sStackSamplingDurationMsDefault, sStackSamplingDurationMsMin,
                        sStackSamplingDurationMsMax, params);
                break;

            case ProfilingManager.PROFILING_TYPE_SYSTEM_TRACE:
                initializeSystemTraceConfigsIfNecessary();
                duration = getWithinBounds(ProfilingManager.KEY_DURATION_MS,
                        sSystemTraceDurationMsDefault, sSystemTraceDurationMsMin,
                        sSystemTraceDurationMsMax, params);
                break;

            default:
                throw new IllegalArgumentException("Invalid profiling type");
        }
        return duration + FILE_PROCESSING_DELAY_MS;
    }

    /**
     * This method returns the maximum profiling time allowed for the different profiling types.
     */
    public static int getMaxProfilingTimeAllowedMs(int profilingType, @Nullable Bundle params) {
        // Get the initial delay
        int maxAllowedProcessingTime =
                getInitialProfilingTimeMs(profilingType, params);

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

    private static int getWithinBounds(String key, int defaultValue, int minValue,
            int maxValue, @Nullable Bundle params) {
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

    private static int getAndRemoveWithinBounds(String key, int defaultValue, int minValue,
            int maxValue, @Nullable Bundle bundle) {
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

    /** Buffer sizes are preferred to be multiples of 4kb, round up to next lowest 4 multiple. */
    private static int roundUpForBufferSize(int bufferSize) {
        return (bufferSize % 4 == 0) ? bufferSize : bufferSize + (4 - (bufferSize % 4));
    }

    private static void confirmEmptyOrThrow(@Nullable Bundle bundle)
            throws IllegalArgumentException {
        if (bundle != null && !bundle.isEmpty()) {
            throw new IllegalArgumentException(
                    "Bundle contains invalid or unsupported parameters");
        }
    }

    private static byte[] generateJavaHeapDumpConfig(String packageName, int bufferSizeKb) {
        TraceConfig.Builder builder = TraceConfig.newBuilder();

        // Add a buffer
        TraceConfig.BufferConfig buffer = TraceConfig.BufferConfig.newBuilder()
                .setSizeKb(bufferSizeKb)
                .setFillPolicy(TraceConfig.BufferConfig.FillPolicy.DISCARD)
                .build();
        builder.addBuffers(buffer);

        // Add data source
        JavaHprofConfig javaHprofConfig = JavaHprofConfig.newBuilder()
                .addProcessCmdline(packageName)
                .setDumpSmaps(true)
                .build();
        DataSourceConfig dataSourceConfig = DataSourceConfig.newBuilder()
                .setName("android.java_hprof")
                .setJavaHprofConfig(javaHprofConfig)
                .build();
        TraceConfig.DataSource dataSource = TraceConfig.DataSource.newBuilder()
                .setConfig(dataSourceConfig)
                .build();
        builder.addDataSources(dataSource);

        // Add duration and timeout
        builder.setDurationMs(sJavaHeapDumpDurationMsDefault);
        builder.setDataSourceStopTimeoutMs(sJavaHeapDumpDataSourceStopTimeoutMsDefault);

        return builder.build().toByteArray();
    }

    private static byte[] generateHeapProfileConfig(String packageName, int bufferSizeKb,
            int durationMs, long samplingIntervalBytes, boolean trackJavaAllocations) {
        TraceConfig.Builder builder = TraceConfig.newBuilder();

        // Add a buffer
        TraceConfig.BufferConfig buffer = TraceConfig.BufferConfig.newBuilder()
                .setSizeKb(bufferSizeKb)
                .setFillPolicy(TraceConfig.BufferConfig.FillPolicy.DISCARD)
                .build();
        builder.addBuffers(buffer);

        // Add data source
        HeapprofdConfig.Builder heapprofdConfigBuilder = HeapprofdConfig.newBuilder()
                .setShmemSizeBytes(8388608) //8MB
                .setSamplingIntervalBytes(samplingIntervalBytes)
                .addProcessCmdline(packageName);
        if (trackJavaAllocations) {
            heapprofdConfigBuilder.addHeaps("com.android.art");
        }
        DataSourceConfig dataSourceConfig = DataSourceConfig.newBuilder()
                .setName("android.heapprofd")
                .setHeapprofdConfig(heapprofdConfigBuilder.build())
                .build();
        TraceConfig.DataSource dataSource = TraceConfig.DataSource.newBuilder()
                .setConfig(dataSourceConfig)
                .build();
        builder.addDataSources(dataSource);

        // Add duration and timeout
        builder.setDurationMs(durationMs);
        builder.setFlushTimeoutMs(sHeapProfileFlushTimeoutMsDefault);

        return builder.build().toByteArray();
    }

    private static byte[] generateStackSamplingConfig(String packageName, int bufferSizeKb,
            int durationMs, long frequency) {
        TraceConfig.Builder builder = TraceConfig.newBuilder();

        // Add a buffer
        TraceConfig.BufferConfig buffer = TraceConfig.BufferConfig.newBuilder()
                .setSizeKb(bufferSizeKb)
                .setFillPolicy(TraceConfig.BufferConfig.FillPolicy.DISCARD)
                .build();
        builder.addBuffers(buffer);

        // Add data source
        PerfEvents.Timebase timebase = PerfEvents.Timebase.newBuilder()
                .setCounter(PerfEvents.Counter.SW_CPU_CLOCK)
                .setFrequency(frequency)
                .setTimestampClock(PerfEvents.PerfClock.PERF_CLOCK_MONOTONIC)
                .build();
        PerfEventConfig.Scope scope = PerfEventConfig.Scope.newBuilder()
                .addTargetCmdline(packageName)
                .build();
        PerfEventConfig.CallstackSampling callstackSampling = PerfEventConfig.CallstackSampling
                .newBuilder()
                .setScope(scope)
                .build();
        PerfEventConfig perfEventConfig = PerfEventConfig.newBuilder()
                .setTimebase(timebase)
                .setCallstackSampling(callstackSampling)
                .build();
        DataSourceConfig dataSourceConfig = DataSourceConfig.newBuilder()
                .setName("linux.perf")
                .setPerfEventConfig(perfEventConfig)
                .build();
        TraceConfig.DataSource dataSource = TraceConfig.DataSource.newBuilder()
                .setConfig(dataSourceConfig)
                .build();
        builder.addDataSources(dataSource);

        // Add duration and timeout
        builder.setDurationMs(durationMs);
        builder.setFlushTimeoutMs(sStackSamplingFlushTimeoutMsDefault);

        return builder.build().toByteArray();
    }

    private static byte[] generateSystemTraceConfig(String packageName, int bufferSizeKb,
            int durationMs, TraceConfig.BufferConfig.FillPolicy bufferFillPolicy) {
        TraceConfig.Builder builder = TraceConfig.newBuilder();

        // Add 2 buffers, discard for data sources dumped at beginning and ring for contiuously
        // updated data sources.
        TraceConfig.BufferConfig buffer0 = TraceConfig.BufferConfig.newBuilder()
                .setSizeKb(4096)
                .setFillPolicy(TraceConfig.BufferConfig.FillPolicy.DISCARD)
                .build();
        builder.addBuffers(buffer0);
        TraceConfig.BufferConfig buffer1 = TraceConfig.BufferConfig.newBuilder()
                .setSizeKb(bufferSizeKb)
                .setFillPolicy(bufferFillPolicy)
                .build();
        builder.addBuffers(buffer1);

        // Add a whole bunch of data sources

        // Scan and dump all processes to buffer 0 when trace starts
        ProcessStatsConfig processStatsConfig = ProcessStatsConfig.newBuilder()
                .setScanAllProcessesOnStart(true)
                .build();
        DataSourceConfig dataSourceConfigProcessStats = DataSourceConfig.newBuilder()
                .setName("linux.process_stats")
                .setTargetBuffer(0)
                .setProcessStatsConfig(processStatsConfig)
                .build();
        TraceConfig.DataSource dataSourceProcessStats = TraceConfig.DataSource.newBuilder()
                .setConfig(dataSourceConfigProcessStats)
                .build();
        builder.addDataSources(dataSourceProcessStats);

        // Dump details about the requesting package to buffer 0
        PackagesListConfig packagesListConfig = PackagesListConfig.newBuilder()
                .addPackageNameFilter(packageName)
                .build();
        DataSourceConfig dataSourceConfigPackagesList = DataSourceConfig.newBuilder()
                .setName("android.packages_list")
                .setTargetBuffer(0)
                .setPackagesListConfig(packagesListConfig)
                .build();
        TraceConfig.DataSource dataSourcePackagesList = TraceConfig.DataSource.newBuilder()
                .setConfig(dataSourceConfigPackagesList)
                .build();
        builder.addDataSources(dataSourcePackagesList);

        // Dump select ftrace events to buffer 1
        FtraceConfig.CompactSchedConfig compactSchedConfig = FtraceConfig.CompactSchedConfig
                .newBuilder()
                .setEnabled(true)
                .build();
        FtraceConfig ftraceConfig = FtraceConfig.newBuilder()
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
                .addAtraceCategories("gfx")
                // Enable events for requesting app only:
                .addAtraceApps(packageName)
                .build();
        DataSourceConfig dataSourceConfigFtrace = DataSourceConfig.newBuilder()
                .setName("linux.ftrace")
                .setTargetBuffer(1)
                .setFtraceConfig(ftraceConfig)
                .build();
        TraceConfig.DataSource dataSourceFtrace = TraceConfig.DataSource.newBuilder()
                .setConfig(dataSourceConfigFtrace)
                .build();
        builder.addDataSources(dataSourceFtrace);

        // Dump surfaceflinger frame timeline to buffer 1
        DataSourceConfig dataSourceConfigSurfaceFlinger = DataSourceConfig.newBuilder()
                .setName("android.surfaceflinger.frametimeline")
                .setTargetBuffer(1)
                .build();
        TraceConfig.DataSource dataSourceSurfaceFlinger = TraceConfig.DataSource.newBuilder()
                .setConfig(dataSourceConfigSurfaceFlinger)
                .build();
        builder.addDataSources(dataSourceSurfaceFlinger);

        // Clear incremental state
        TraceConfig.IncrementalStateConfig incrementalStateConfig = TraceConfig
                .IncrementalStateConfig.newBuilder()
                        .setClearPeriodMs(10000)
                        .build();
        builder.setIncrementalStateConfig(incrementalStateConfig);

        // Add duration
        builder.setDurationMs(durationMs);

        return builder.build().toByteArray();
    }

}
