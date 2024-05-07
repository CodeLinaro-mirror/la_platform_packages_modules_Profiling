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

public final class Configs {

    static final String HEAP_PROFILE_TRACK_JAVA_ALLOCATIONS = "heaps: \"com.android.art\"";

    private static final String STUB_DURATION = "{{duration}}";
    private static final String STUB_PACKAGE_NAME = "{{package_name}}";
    private static final String STUB_TRACK_JAVA_ALLOCATIONS = "{{track_java_allocations}}";
    private static final String STUB_SAMPLING_INTERVAL = "{{sampling_interval}}";
    private static final String STUB_FREQUENCY = "{{frequency}}";
    private static final String STUB_SIZE = "{{size_kb}}";
    private static final String STUB_FLUSH_TIMEOUT = "{{flush_timeout}}";
    private static final String STUB_DATA_SOURCE_STOP_TIMEOUT = "{{data_source_stop_timeout}}";

    static final String CONFIG_HEAP_PROFILE = "buffers {\n"
            + "  size_kb: " + STUB_SIZE + "\n"
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
            + "  size_kb: " + STUB_SIZE + "\n"
            + "  fill_policy: RING_BUFFER\n"
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
            + "  }\n"
            + "}\n"
            + "\n"
            + "data_sources {\n"
            + "  config {\n"
            + "    name: \"linux.ftrace\"\n"
            + "    target_buffer: 0\n"
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
            + "    target_buffer: 0\n"
            + "  }\n"
            + "}\n"
            + "duration_ms: " + STUB_DURATION;

    // Time to wait beyond trace timeout to ensure perfetto has time to finish writing output.
    private static final int FILE_PROCESSING_DELAY_MS = 5000;

    private static final boolean sKillswitchSystemTrace;
    private static final int sSystemTraceDurationMsDefault;
    private static final int sSystemTraceDurationMsMin;
    private static final int sSystemTraceDurationMsMax;
    private static final int sSystemTraceSizeKbDefault;
    private static final int sSystemTraceSizeKbMin;
    private static final int sSystemTraceSizeKbMax;

    private static final boolean sKillswitchHeapProfile;
    private static final boolean sHeapProfileTrackJavaAllocationsDefault;
    private static final int sHeapProfileFlushTimeoutMsDefault;
    private static final int sHeapProfileDurationMsDefault;
    private static final int sHeapProfileDurationMsMin;
    private static final int sHeapProfileDurationMsMax;
    private static final int sHeapProfileSizeKbDefault;
    private static final int sHeapProfileSizeKbMin;
    private static final int sHeapProfileSizeKbMax;
    private static final int sHeapProfileSamplingIntervalBytesDefault;
    private static final int sHeapProfileSamplingIntervalBytesMin;
    private static final int sHeapProfileSamplingIntervalBytesMax;

    private static final boolean sKillswitchJavaHeapDump;
    private static final int sJavaHeapDumpDurationMsDefault;
    private static final int sJavaHeapDumpDataSourceStopTimeoutMsDefault;
    private static final int sJavaHeapDumpSizeKbDefault;
    private static final int sJavaHeapDumpSizeKbMin;
    private static final int sJavaHeapDumpSizeKbMax;

    private static final boolean sKillswitchStackSampling;
    private static final int sStackSamplingFlushTimeoutMsDefault;
    private static final int sStackSamplingDurationMsDefault;
    private static final int sStackSamplingDurationMsMin;
    private static final int sStackSamplingDurationMsMax;
    private static final int sStackSamplingSizeKbDefault;
    private static final int sStackSamplingSizeKbMin;
    private static final int sStackSamplingSizeKbMax;
    private static final int sStackSamplingSamplingFrequencyDefault;
    private static final int sStackSamplingSamplingFrequencyMin;
    private static final int sStackSamplingSamplingFrequencyMax;

    static {
        // TODO(b/330940387): Revisit defaults before release

        sKillswitchSystemTrace = DeviceConfigHelper.getBoolean(
                DeviceConfigHelper.KILLSWITCH_SYSTEM_TRACE, false);
        sSystemTraceDurationMsDefault = DeviceConfigHelper.getInt(
                DeviceConfigHelper.SYSTEM_TRACE_DURATION_MS_DEFAULT, 300000);
        sSystemTraceDurationMsMin = DeviceConfigHelper.getInt(
                DeviceConfigHelper.SYSTEM_TRACE_DURATION_MS_MIN, 1000);
        sSystemTraceDurationMsMax = DeviceConfigHelper.getInt(
                DeviceConfigHelper.SYSTEM_TRACE_DURATION_MS_MAX, 600000);
        sSystemTraceSizeKbDefault = DeviceConfigHelper.getInt(
                DeviceConfigHelper.SYSTEM_TRACE_SIZE_KB_DEFAULT, 32768);
        sSystemTraceSizeKbMin = DeviceConfigHelper.getInt(
                DeviceConfigHelper.SYSTEM_TRACE_SIZE_KB_MIN, 1);
        sSystemTraceSizeKbMax = DeviceConfigHelper.getInt(
                DeviceConfigHelper.SYSTEM_TRACE_SIZE_KB_MAX, 32768);

        sKillswitchHeapProfile = DeviceConfigHelper.getBoolean(
                DeviceConfigHelper.KILLSWITCH_HEAP_PROFILE, false);
        sHeapProfileTrackJavaAllocationsDefault = DeviceConfigHelper.getBoolean(
                DeviceConfigHelper.HEAP_PROFILE_TRACK_JAVA_ALLOCATIONS_DEFAULT, false);
        sHeapProfileFlushTimeoutMsDefault = DeviceConfigHelper.getInt(
                DeviceConfigHelper.HEAP_PROFILE_FLUSH_TIMEOUT_MS_DEFAULT, 30000);
        sHeapProfileDurationMsDefault = DeviceConfigHelper.getInt(
                DeviceConfigHelper.HEAP_PROFILE_DURATION_MS_DEFAULT, 120000);
        sHeapProfileDurationMsMin = DeviceConfigHelper.getInt(
                DeviceConfigHelper.HEAP_PROFILE_DURATION_MS_MIN, 1000);
        sHeapProfileDurationMsMax = DeviceConfigHelper.getInt(
                DeviceConfigHelper.HEAP_PROFILE_DURATION_MS_MAX, 300000);
        sHeapProfileSizeKbDefault = DeviceConfigHelper.getInt(
                DeviceConfigHelper.HEAP_PROFILE_SIZE_KB_DEFAULT, 65536);
        sHeapProfileSizeKbMin = DeviceConfigHelper.getInt(
                DeviceConfigHelper.HEAP_PROFILE_SIZE_KB_MIN, 1);
        sHeapProfileSizeKbMax = DeviceConfigHelper.getInt(
                DeviceConfigHelper.HEAP_PROFILE_SIZE_KB_MAX, 65536);
        sHeapProfileSamplingIntervalBytesDefault = DeviceConfigHelper.getInt(
                DeviceConfigHelper.HEAP_PROFILE_SAMPLING_INTERVAL_BYTES_DEFAULT, 4096);
        sHeapProfileSamplingIntervalBytesMin = DeviceConfigHelper.getInt(
                DeviceConfigHelper.HEAP_PROFILE_SAMPLING_INTERVAL_BYTES_MIN, 1028);
        sHeapProfileSamplingIntervalBytesMax = DeviceConfigHelper.getInt(
                DeviceConfigHelper.HEAP_PROFILE_SAMPLING_INTERVAL_BYTES_MAX, 8192);

        sKillswitchJavaHeapDump = DeviceConfigHelper.getBoolean(
                DeviceConfigHelper.KILLSWITCH_JAVA_HEAP_DUMP, false);
        sJavaHeapDumpDurationMsDefault = DeviceConfigHelper.getInt(
                DeviceConfigHelper.JAVA_HEAP_DUMP_DURATION_MS_DEFAULT, 1000);
        sJavaHeapDumpDataSourceStopTimeoutMsDefault = DeviceConfigHelper.getInt(
                DeviceConfigHelper.JAVA_HEAP_DUMP_DATA_SOURCE_STOP_TIMEOUT_MS_DEFAULT, 100000);
        sJavaHeapDumpSizeKbDefault = DeviceConfigHelper.getInt(
                DeviceConfigHelper.JAVA_HEAP_DUMP_SIZE_KB_DEFAULT, 256000);
        sJavaHeapDumpSizeKbMin = DeviceConfigHelper.getInt(
                DeviceConfigHelper.JAVA_HEAP_DUMP_SIZE_KB_MIN, 1);
        sJavaHeapDumpSizeKbMax = DeviceConfigHelper.getInt(
                DeviceConfigHelper.JAVA_HEAP_DUMP_SIZE_KB_MAX, 256000);

        sKillswitchStackSampling = DeviceConfigHelper.getBoolean(
                DeviceConfigHelper.KILLSWITCH_STACK_SAMPLING, false);
        sStackSamplingFlushTimeoutMsDefault = DeviceConfigHelper.getInt(
                DeviceConfigHelper.STACK_SAMPLING_FLUSH_TIMEOUT_MS_DEFAULT, 30000);
        sStackSamplingDurationMsDefault = DeviceConfigHelper.getInt(
                DeviceConfigHelper.STACK_SAMPLING_DURATION_MS_DEFAULT, 60000);
        sStackSamplingDurationMsMin = DeviceConfigHelper.getInt(
                DeviceConfigHelper.STACK_SAMPLING_DURATION_MS_MIN, 1000);
        sStackSamplingDurationMsMax = DeviceConfigHelper.getInt(
                DeviceConfigHelper.STACK_SAMPLING_DURATION_MS_MAX, 300000);
        sStackSamplingSizeKbDefault = DeviceConfigHelper.getInt(
                DeviceConfigHelper.STACK_SAMPLING_SAMPLING_SIZE_KB_DEFAULT, 65536);
        sStackSamplingSizeKbMin = DeviceConfigHelper.getInt(
                DeviceConfigHelper.STACK_SAMPLING_SAMPLING_SIZE_KB_MIN, 1);
        sStackSamplingSizeKbMax = DeviceConfigHelper.getInt(
                DeviceConfigHelper.STACK_SAMPLING_SAMPLING_SIZE_KB_MAX, 65536);
        sStackSamplingSamplingFrequencyDefault = DeviceConfigHelper.getInt(
                DeviceConfigHelper.STACK_SAMPLING_FREQUENCY_DEFAULT, 100);
        sStackSamplingSamplingFrequencyMin = DeviceConfigHelper.getInt(
                DeviceConfigHelper.STACK_SAMPLING_FREQUENCY_MIN, 1);
        sStackSamplingSamplingFrequencyMax = DeviceConfigHelper.getInt(
                DeviceConfigHelper.STACK_SAMPLING_FREQUENCY_MAX, 200);
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

                confirmEmptyOrThrow(paramsCopy);

                return CONFIG_SYSTEM_TRACE
                        .replace(STUB_PACKAGE_NAME, packageName)
                        .replace(STUB_DURATION, String.valueOf(systemTraceDuration))
                        .replace(STUB_SIZE, String.valueOf(systemTraceSizeKb));

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
                duration = sJavaHeapDumpDurationMsDefault + 10000; //TODO(b/327660454): remove const
                break;

            case ProfilingManager.PROFILING_TYPE_HEAP_PROFILE:
                duration = getWithinBounds(ProfilingManager.KEY_DURATION_MS,
                        sHeapProfileDurationMsDefault, sHeapProfileDurationMsMin,
                        sHeapProfileDurationMsMax, params);
                break;

            case ProfilingManager.PROFILING_TYPE_STACK_SAMPLING:
                duration = getWithinBounds(ProfilingManager.KEY_DURATION_MS,
                        sStackSamplingDurationMsDefault, sStackSamplingDurationMsMin,
                        sStackSamplingDurationMsMax, params);
                break;

            case ProfilingManager.PROFILING_TYPE_SYSTEM_TRACE:
                duration = getWithinBounds(ProfilingManager.KEY_DURATION_MS,
                        sSystemTraceDurationMsDefault, sSystemTraceDurationMsMin,
                        sSystemTraceDurationMsMax, params);
                break;

            default:
                throw new IllegalArgumentException("Invalid profiling type");
        }
        return duration + FILE_PROCESSING_DELAY_MS;
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
