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

public final class Configs {

    static final String HEAP_PROFILE_TRACK_JAVA_ALLOCATIONS = "heaps: \"com.android.art\"";
    private static final String BUFFER_FILL_POLICY_DISCARD = "DISCARD";
    private static final String BUFFER_FILL_POLICY_RING_BUFFER = "RING_BUFFER";

    private static final String STUB_DURATION = "{{duration}}";
    private static final String STUB_PACKAGE_NAME = "{{package_name}}";
    private static final String STUB_TRACK_JAVA_ALLOCATIONS = "{{track_java_allocations}}";
    private static final String STUB_SAMPLING_INTERVAL = "{{sampling_interval}}";
    private static final String STUB_FREQUENCY = "{{frequency}}";
    private static final String STUB_SIZE = "{{size_kb}}";
    private static final String STUB_FLUSH_TIMEOUT = "{{flush_timeout}}";
    private static final String STUB_DATA_SOURCE_STOP_TIMEOUT = "{{data_source_stop_timeout}}";
    private static final String STUB_BUFFER_FILL_POLICY = "{{buffer_fill_policy}}";

    static final String CONFIG_HEAP_PROFILE = "buffers {\n"
            + "  size_kb: " + STUB_SIZE + "\n"
            + "  fill_policy: DISCARD\n"
            + "}\n"
            + "\n"
            + "data_sources {\n"
            + "  config {\n"
            + "    name: \"android.heapprofd\"\n"
            + "    heapprofd_config {\n"
            + "      # 8Mb\n"
            + "      shmem_size_bytes: 8388608\n"
            + "      sampling_interval_bytes: " + STUB_SAMPLING_INTERVAL + "\n"
            + "      process_cmdline: \"" + STUB_PACKAGE_NAME + "\"\n"
            + "      " + STUB_TRACK_JAVA_ALLOCATIONS + "\n"
            + "    }\n"
            + "  }\n"
            + "}\n"
            + "\n"
            + "flush_timeout_ms: " + STUB_FLUSH_TIMEOUT + "\n"
            + "duration_ms: " + STUB_DURATION;
    static final String CONFIG_JAVA_HEAP_DUMP = "buffers {\n"
            + "  # This is the maximum size of the trace. The buffer will be mmap'd but, only\n"
            + "  # the non empty pages contribute to RSS.\n"
            + "  size_kb: " + STUB_SIZE + "\n"
            + "  fill_policy: DISCARD\n"
            + "}\n"
            + "\n"
            + "data_sources {\n"
            + "  config {\n"
            + "    name: \"android.java_hprof\"\n"
            + "    java_hprof_config {\n"
            + "      process_cmdline: \"" + STUB_PACKAGE_NAME + "\"\n"
            + "      dump_smaps: true\n"
            + "    }\n"
            + "  }\n"
            + "}\n"
            + "\n"
            + "# Wait 1s for the dump to start\n"
            + "duration_ms: " + STUB_DURATION + "\n"
            + "# Wait up to 100s for the dump to finish\n"
            + "data_source_stop_timeout_ms: " + STUB_DATA_SOURCE_STOP_TIMEOUT;
    static final String CONFIG_STACK_SAMPLING = "buffers {\n"
            + "  size_kb: " + STUB_SIZE + "\n"
            + "  fill_policy: DISCARD\n"
            + "}\n"
            + "\n"
            + "data_sources {\n"
            + "  config {\n"
            + "    name: \"linux.perf\"\n"
            + "    perf_event_config {\n"
            + "      timebase {\n"
            + "        counter: SW_CPU_CLOCK\n"
            + "        frequency: " + STUB_FREQUENCY + "\n"
            + "        timestamp_clock: PERF_CLOCK_MONOTONIC\n"
            + "      }\n"
            + "      callstack_sampling {\n"
            + "        scope {\n"
            + "          target_cmdline: \"" + STUB_PACKAGE_NAME + "\"\n"
            + "        }\n"
            + "      }\n"
            + "    }\n"
            + "  }\n"
            + "}\n"
            + "\n"
            + "flush_timeout_ms: " + STUB_FLUSH_TIMEOUT + "\n"
            + "duration_ms: " + STUB_DURATION;
    static final String CONFIG_SYSTEM_TRACE = "buffers {\n"
            + "  size_kb: 4096\n"
            + "  fill_policy: DISCARD\n"
            + "}\n"
            + "buffers {\n"
            + "  size_kb: " + STUB_SIZE + "\n"
            + "  fill_policy: " + STUB_BUFFER_FILL_POLICY + "\n"
            + "}\n"
            + "\n"
            + "data_sources {\n"
            + "  config {\n"
            + "    name: \"linux.process_stats\"\n"
            + "    target_buffer: 0\n"
            + "    process_stats_config {\n"
            + "      scan_all_processes_on_start: true\n"
            + "    }\n"
            + "  }\n"
            + "}\n"
            + "\n"
            + "data_sources {\n"
            + "  config {\n"
            + "    name: \"android.packages_list\"\n"
            + "    target_buffer: 0\n"
            + "    packages_list_config {\n"
            + "      package_name_filter: \"" + STUB_PACKAGE_NAME + "\"\n"
            + "    }\n"
            + "  }\n"
            + "}\n"
            + "\n"
            + "data_sources {\n"
            + "  config {\n"
            + "    name: \"linux.ftrace\"\n"
            + "    target_buffer: 1\n"
            + "    ftrace_config {\n"
            + "      throttle_rss_stat: true\n"
            + "      disable_generic_events: true\n"
            + "      compact_sched {\n"
            + "        enabled: true\n"
            + "      }\n"
            + "\n"
            + "      # RSS and ION buffer events:\n"
            + "      ftrace_events: \"gpu_mem/gpu_mem_total\"\n"
            + "\n"
            + "      # Scheduling information & process tracking. Useful for:\n"
            + "      # - what is happening on each CPU at each moment\n"
            + "      # - why a thread was descheduled\n"
            + "      # - parent/child relationships between processes and threads.\n"
            + "      ftrace_events: \"power/suspend_resume\"\n"
            + "      ftrace_events: \"sched/sched_process_free\"\n"
            + "      ftrace_events: \"sched/sched_switch\"\n"
            + "      ftrace_events: \"task/task_newtask\"\n"
            + "      ftrace_events: \"task/task_rename\"\n"
            + "\n"
            + "      # Wakeup info. Allows you to compute how long a task was\n"
            + "      # blocked due to CPU contention.\n"
            + "      ftrace_events: \"sched/sched_waking\"\n"
            + "      ftrace_events: \"sched/sched_wakeup_new\"\n"
            + "\n"
            + "      # vmscan and mm_compaction events.\n"
            + "      ftrace_events: \"vmscan/mm_vmscan_kswapd_wake\"\n"
            + "      ftrace_events: \"vmscan/mm_vmscan_kswapd_sleep\"\n"
            + "      ftrace_events: \"vmscan/mm_vmscan_direct_reclaim_begin\"\n"
            + "      ftrace_events: \"vmscan/mm_vmscan_direct_reclaim_end\"\n"
            + "      ftrace_events: \"compaction/mm_compaction_begin\"\n"
            + "      ftrace_events: \"compaction/mm_compaction_end\"\n"
            + "\n"
            + "      # Atrace activity manager:\n"
            + "      atrace_categories: \"am\"\n"
            + "\n"
            + "      # Java and C:\n"
            + "      atrace_categories: \"dalvik\"\n"
            + "      atrace_categories: \"bionic\"\n"
            + "\n"
            + "      atrace_categories: \"binder_driver\"\n"
            + "\n"
            + "      atrace_categories: \"view\"\n"
            + "\n"
            + "      atrace_categories: \"input\"\n"
            + "\n"
            + "      atrace_categories: \"gfx\"\n"
            + "\n"
            + "      atrace_apps: \"" + STUB_PACKAGE_NAME + "\"\n"
            + "    }\n"
            + "  }\n"
            + "}\n"
            + "\n"
            + "data_sources {\n"
            + "  config {\n"
            + "    name: \"android.surfaceflinger.frametimeline\"\n"
            + "    target_buffer: 1\n"
            + "  }\n"
            + "}\n"
            + "incremental_state_config {\n"
            + "  clear_period_ms: 10000\n"
            + "}\n"
            + "duration_ms: " + STUB_DURATION;

    // Time to wait beyond trace timeout to ensure perfetto has time to finish writing output.
    private static final int FILE_PROCESSING_DELAY_MS = 5000;

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
                DeviceConfigHelper.SYSTEM_TRACE_SIZE_KB_MIN, 1);
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
                DeviceConfigHelper.JAVA_HEAP_DUMP_SIZE_KB_MIN, 1);
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
                DeviceConfigHelper.HEAP_PROFILE_SIZE_KB_MIN, 1);
        sHeapProfileSizeKbMax = properties.getInt(
                DeviceConfigHelper.HEAP_PROFILE_SIZE_KB_MAX, 65536);
        sHeapProfileSamplingIntervalBytesDefault = properties.getInt(
                DeviceConfigHelper.HEAP_PROFILE_SAMPLING_INTERVAL_BYTES_DEFAULT, 4096);
        sHeapProfileSamplingIntervalBytesMin = properties.getInt(
                DeviceConfigHelper.HEAP_PROFILE_SAMPLING_INTERVAL_BYTES_MIN, 1028);
        sHeapProfileSamplingIntervalBytesMax = properties.getInt(
                DeviceConfigHelper.HEAP_PROFILE_SAMPLING_INTERVAL_BYTES_MAX, 8192);

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
                DeviceConfigHelper.STACK_SAMPLING_SAMPLING_SIZE_KB_MIN, 1);
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
    public static String generateConfigForRequest(int profilingType, final @Nullable Bundle params,
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

                int javaHeapDumpSizeKb = getAndRemoveWithinBounds(ProfilingManager.KEY_SIZE_KB,
                        sJavaHeapDumpSizeKbDefault,
                        sJavaHeapDumpSizeKbMin,
                        sJavaHeapDumpSizeKbMax,
                        paramsCopy);

                confirmEmptyOrThrow(paramsCopy);

                return CONFIG_JAVA_HEAP_DUMP
                        .replace(STUB_PACKAGE_NAME, packageName)
                        .replace(STUB_DURATION, String.valueOf(sJavaHeapDumpDurationMsDefault))
                        .replace(STUB_SIZE, String.valueOf(javaHeapDumpSizeKb))
                        .replace(STUB_DATA_SOURCE_STOP_TIMEOUT,
                                    String.valueOf(sJavaHeapDumpDataSourceStopTimeoutMsDefault));

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
                int heapProfileDuration = getAndRemoveWithinBounds(ProfilingManager.KEY_DURATION_MS,
                        sHeapProfileDurationMsDefault,
                        sHeapProfileDurationMsMin,
                        sHeapProfileDurationMsMax,
                        paramsCopy);
                int heapProfileSizeKb = getAndRemoveWithinBounds(ProfilingManager.KEY_SIZE_KB,
                        sHeapProfileSizeKbDefault,
                        sHeapProfileSizeKbMin,
                        sHeapProfileSizeKbMax,
                        paramsCopy);

                confirmEmptyOrThrow(paramsCopy);

                return CONFIG_HEAP_PROFILE
                        .replace(STUB_PACKAGE_NAME, packageName)
                        .replace(STUB_TRACK_JAVA_ALLOCATIONS, trackJavaAllocations
                                ? HEAP_PROFILE_TRACK_JAVA_ALLOCATIONS : "")
                        .replace(STUB_SAMPLING_INTERVAL, String.valueOf(samplingIntervalBytes))
                        .replace(STUB_DURATION, String.valueOf(heapProfileDuration))
                        .replace(STUB_SIZE, String.valueOf(heapProfileSizeKb))
                        .replace(STUB_FLUSH_TIMEOUT,
                                String.valueOf(sHeapProfileFlushTimeoutMsDefault));

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
                int stackSamplingDuration = getAndRemoveWithinBounds(
                        ProfilingManager.KEY_DURATION_MS,
                        sStackSamplingDurationMsDefault,
                        sStackSamplingDurationMsMin,
                        sStackSamplingDurationMsMax,
                        paramsCopy);
                int stackSamplingSizeKb = getAndRemoveWithinBounds(ProfilingManager.KEY_SIZE_KB,
                        sStackSamplingSizeKbDefault,
                        sStackSamplingSizeKbMin,
                        sStackSamplingSizeKbMax,
                        paramsCopy);

                confirmEmptyOrThrow(paramsCopy);

                return CONFIG_STACK_SAMPLING
                        .replace(STUB_PACKAGE_NAME, packageName)
                        .replace(STUB_FREQUENCY, String.valueOf(frequency))
                        .replace(STUB_DURATION, String.valueOf(stackSamplingDuration))
                        .replace(STUB_SIZE, String.valueOf(stackSamplingSizeKb))
                        .replace(STUB_FLUSH_TIMEOUT,
                                    String.valueOf(sStackSamplingFlushTimeoutMsDefault));

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

                int systemTraceDuration = getAndRemoveWithinBounds(
                        ProfilingManager.KEY_DURATION_MS,
                        sSystemTraceDurationMsDefault,
                        sSystemTraceDurationMsMin,
                        sSystemTraceDurationMsMax,
                        paramsCopy);
                int systemTraceSizeKb = getAndRemoveWithinBounds(ProfilingManager.KEY_SIZE_KB,
                        sSystemTraceSizeKbDefault,
                        sSystemTraceSizeKbMin,
                        sSystemTraceSizeKbMax,
                        paramsCopy);
                String systemTraceBufferFillPolicy = getBufferFillPolicyString(
                        getAndRemove(ProfilingManager.KEY_BUFFER_FILL_POLICY,
                                ProfilingManager.VALUE_BUFFER_FILL_POLICY_RING_BUFFER, paramsCopy));

                confirmEmptyOrThrow(paramsCopy);

                return CONFIG_SYSTEM_TRACE
                        .replace(STUB_PACKAGE_NAME, packageName)
                        .replace(STUB_DURATION, String.valueOf(systemTraceDuration))
                        .replace(STUB_SIZE, String.valueOf(systemTraceSizeKb))
                        .replace(STUB_BUFFER_FILL_POLICY, systemTraceBufferFillPolicy);

            // Invalid type
            default:
                throw new IllegalArgumentException("Invalid profiling type");
        }
    }

    /**
     * This method returns how long in ms to wait before post processing and cleaning up the result
     * in the event that it's not stopped manually.
     */
    public static int getPostProcessingScheduleDelayMs(int profilingType, @Nullable Bundle params) {
        // TODO: b/327660454 adjust timeout/logic to ensure perfetto is finished
        int duration;
        switch (profilingType) {
            case ProfilingManager.PROFILING_TYPE_JAVA_HEAP_DUMP:
                initializeJavaHeapDumpConfigsIfNecessary();
                duration = sJavaHeapDumpDurationMsDefault + 10000; //TODO(b/327660454): remove const
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

    private static String getBufferFillPolicyString(int bufferFillPolicy)
            throws IllegalArgumentException {
        switch (bufferFillPolicy) {
            case ProfilingManager.VALUE_BUFFER_FILL_POLICY_DISCARD:
                return BUFFER_FILL_POLICY_DISCARD;
            case ProfilingManager.VALUE_BUFFER_FILL_POLICY_RING_BUFFER:
                return BUFFER_FILL_POLICY_RING_BUFFER;
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

    private static void confirmEmptyOrThrow(@Nullable Bundle bundle)
            throws IllegalArgumentException {
        if (bundle != null && !bundle.isEmpty()) {
            throw new IllegalArgumentException(
                    "Bundle contains invalid or unsupported parameters");
        }
    }
}
