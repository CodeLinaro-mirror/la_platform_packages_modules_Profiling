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

import android.provider.DeviceConfig;

/**
 * Wrapper class for retrieving device configuration values.
 */
public final class DeviceConfigHelper {

    // Begin section: Testing specific constants
    // Values here can only be accessed with {@link #getTestBoolean}.

    // Namespace for testing only that is not registered on the server side
    public static final String NAMESPACE_TESTING = "profiling_testing";

    // Configs for testing only.
    public static final String RATE_LIMITER_DISABLE_PROPERTY = "rate_limiter.disabled";
    public static final String DISABLE_DELETE_UNREDACTED_TRACE =
            "delete_unredacted_trace.disabled";

    // End section: Testing specific constants

    // Begin section: Server registered constants
    // Values here can be accessed with all getters except {@link #getTestBoolean}.

    // Namespace for server configureable values
    public static final String NAMESPACE = "profiling";
    // System trace
    public static final String KILLSWITCH_SYSTEM_TRACE = "killswitch_system_trace";
    public static final String COST_SYSTEM_TRACE = "cost_system_trace";
    public static final String SYSTEM_TRACE_DURATION_MS_DEFAULT =
            "system_trace_duration_ms_default";
    public static final String SYSTEM_TRACE_DURATION_MS_MIN = "system_trace_duration_ms_min";
    public static final String SYSTEM_TRACE_DURATION_MS_MAX = "system_trace_duration_ms_max";
    public static final String SYSTEM_TRACE_SIZE_KB_DEFAULT = "system_trace_size_kb_default";
    public static final String SYSTEM_TRACE_SIZE_KB_MIN = "system_trace_size_kb_min";
    public static final String SYSTEM_TRACE_SIZE_KB_MAX = "system_trace_size_kb_max";

    // Heap Profile
    public static final String KILLSWITCH_HEAP_PROFILE = "killswitch_heap_profile";
    public static final String COST_HEAP_PROFILE = "cost_heap_profile";
    public static final String HEAP_PROFILE_TRACK_JAVA_ALLOCATIONS_DEFAULT =
            "heap_profile_track_java_allocations_default";
    public static final String HEAP_PROFILE_FLUSH_TIMEOUT_MS_DEFAULT =
            "heap_profile_flush_timeout_ms_default";
    public static final String HEAP_PROFILE_DURATION_MS_DEFAULT =
            "heap_profile_duration_ms_default";
    public static final String HEAP_PROFILE_DURATION_MS_MIN = "heap_profile_duration_ms_min";
    public static final String HEAP_PROFILE_DURATION_MS_MAX = "heap_profile_duration_ms_max";
    public static final String HEAP_PROFILE_SIZE_KB_DEFAULT = "heap_profile_size_kb_default";
    public static final String HEAP_PROFILE_SIZE_KB_MIN = "heap_profile_size_kb_min";
    public static final String HEAP_PROFILE_SIZE_KB_MAX = "heap_profile_size_kb_max";
    public static final String HEAP_PROFILE_SAMPLING_INTERVAL_BYTES_DEFAULT =
            "heap_profile_sampling_interval_bytes_default";
    public static final String HEAP_PROFILE_SAMPLING_INTERVAL_BYTES_MIN =
            "heap_profile_sampling_interval_bytes_min";
    public static final String HEAP_PROFILE_SAMPLING_INTERVAL_BYTES_MAX =
            "heap_profile_sampling_interval_bytes_max";

    // Java Heap Dump
    public static final String KILLSWITCH_JAVA_HEAP_DUMP = "killswitch_java_heap_dump";
    public static final String COST_JAVA_HEAP_DUMP = "cost_java_heap_dump";
    public static final String JAVA_HEAP_DUMP_DURATION_MS_DEFAULT =
            "java_heap_dump_duration_ms_default";
    public static final String JAVA_HEAP_DUMP_DATA_SOURCE_STOP_TIMEOUT_MS_DEFAULT =
            "java_heap_dump_data_source_stop_timeout_ms_default";
    public static final String JAVA_HEAP_DUMP_SIZE_KB_DEFAULT = "java_heap_dump_size_kb_default";
    public static final String JAVA_HEAP_DUMP_SIZE_KB_MIN = "java_heap_dump_size_kb_min";
    public static final String JAVA_HEAP_DUMP_SIZE_KB_MAX = "java_heap_dump_size_kb_max";

    // Stack Sampling
    public static final String KILLSWITCH_STACK_SAMPLING = "killswitch_stack_sampling";
    public static final String COST_STACK_SAMPLING = "cost_stack_sampling";
    public static final String STACK_SAMPLING_FLUSH_TIMEOUT_MS_DEFAULT =
            "stack_sampling_flush_timeout_ms_default";
    public static final String STACK_SAMPLING_DURATION_MS_DEFAULT =
            "stack_sampling_duration_ms_default";
    public static final String STACK_SAMPLING_DURATION_MS_MIN = "stack_sampling_duration_ms_min";
    public static final String STACK_SAMPLING_DURATION_MS_MAX = "stack_sampling_duration_ms_max";
    public static final String STACK_SAMPLING_SAMPLING_SIZE_KB_DEFAULT =
            "stack_sampling_size_kb_default";
    public static final String STACK_SAMPLING_SAMPLING_SIZE_KB_MIN = "stack_sampling_size_kb_min";
    public static final String STACK_SAMPLING_SAMPLING_SIZE_KB_MAX = "stack_sampling_size_kb_max";
    public static final String STACK_SAMPLING_FREQUENCY_DEFAULT =
            "stack_sampling_frequency_default";
    public static final String STACK_SAMPLING_FREQUENCY_MIN = "stack_sampling_frequency_min";
    public static final String STACK_SAMPLING_FREQUENCY_MAX = "stack_sampling_frequency_max";

    // Rate limiter configs
    public static final String PERSIST_TO_DISK_FREQUENCY_MS = "persist_to_disk_frequency_ms";
    public static final String MAX_COST_SYSTEM_1_HOUR = "max_cost_system_1_hour";
    public static final String MAX_COST_PROCESS_1_HOUR = "max_cost_process_1_hour";
    public static final String MAX_COST_SYSTEM_24_HOUR = "max_cost_system_24_hour";
    public static final String MAX_COST_PROCESS_24_HOUR = "max_cost_process_24_hour";
    public static final String MAX_COST_SYSTEM_7_DAY = "max_cost_system_7_day";
    public static final String MAX_COST_PROCESS_7_DAY = "max_cost_process_7_day";

    // Perfetto configs
    public static final String PERFETTO_DESTROY_TIMEOUT_MS = "perfetto_destroy_timeout_ms";

    // General configs
    public static final String MAX_RESULT_REDELIVERY_COUNT = "max_result_redelivery_count";
    public static final String CLEAR_TEMPORARY_DIRECTORY_FREQUENCY_MS =
            "clear_temporary_directory_frequency_ms";
    public static final String CLEAR_TEMPORARY_DIRECTORY_BOOT_DELAY_MS =
            "clear_temporary_directory_boot_delay_ms";

    // Post Processing Configs
    public static final String PROFILING_RECHECK_DELAY_MS = "profiling_recheck_delay_ms";

    // End section: Server registered constants

    /**
     * Get string param for provided device config name from server side device config namespace
     * or return default if unavailable for any reason.
     */
    public static String getString(String name, String defaultValue) {
        return DeviceConfig.getString(NAMESPACE, name, defaultValue);
    }

    /**
     * Get boolean param for provided device config name from server side device config namespace
     * or return default if unavailable for any reason.
     */
    public static boolean getBoolean(String name, boolean defaultValue) {
        return DeviceConfig.getBoolean(NAMESPACE, name, defaultValue);
    }

    /**
     * Get int param for provided device config name from server side device config namespace
     * or return default if unavailable for any reason.
     */
    public static int getInt(String name, int defaultValue) {
        return DeviceConfig.getInt(NAMESPACE, name, defaultValue);
    }

    /**
     * Get long param for provided device config name from server side device config namespace
     * or return default if unavailable for any reason.
     */
    public static long getLong(String name, long defaultValue) {
        return DeviceConfig.getLong(NAMESPACE, name, defaultValue);
    }

    /**
     * Get boolean param for provided device config name from test only device config namespace
     * or return default if unavailable for any reason.
     */
    public static boolean getTestBoolean(String name, boolean defaultValue) {
        return DeviceConfig.getBoolean(NAMESPACE_TESTING, name, defaultValue);
    }

    /** Get all properties related to Java Heap Dump configuration. */
    public static DeviceConfig.Properties getAllJavaHeapDumpProperties() {
        return DeviceConfig.getProperties(NAMESPACE,
                KILLSWITCH_JAVA_HEAP_DUMP,
                JAVA_HEAP_DUMP_DURATION_MS_DEFAULT,
                JAVA_HEAP_DUMP_DATA_SOURCE_STOP_TIMEOUT_MS_DEFAULT,
                JAVA_HEAP_DUMP_SIZE_KB_DEFAULT,
                JAVA_HEAP_DUMP_SIZE_KB_MIN,
                JAVA_HEAP_DUMP_SIZE_KB_MAX);
    }

    /** Get all properties related to Heap Profile configuration. */
    public static DeviceConfig.Properties getAllHeapProfileProperties() {
        return DeviceConfig.getProperties(NAMESPACE,
                KILLSWITCH_HEAP_PROFILE,
                HEAP_PROFILE_TRACK_JAVA_ALLOCATIONS_DEFAULT,
                HEAP_PROFILE_FLUSH_TIMEOUT_MS_DEFAULT,
                HEAP_PROFILE_DURATION_MS_DEFAULT,
                HEAP_PROFILE_DURATION_MS_MIN,
                HEAP_PROFILE_DURATION_MS_MAX,
                HEAP_PROFILE_SIZE_KB_DEFAULT,
                HEAP_PROFILE_SIZE_KB_MIN,
                HEAP_PROFILE_SIZE_KB_MAX,
                HEAP_PROFILE_SAMPLING_INTERVAL_BYTES_DEFAULT,
                HEAP_PROFILE_SAMPLING_INTERVAL_BYTES_MIN,
                HEAP_PROFILE_SAMPLING_INTERVAL_BYTES_MAX);
    }

    /** Get all properties related to Stack Sampling configuration. */
    public static DeviceConfig.Properties getAllStackSamplingProperties() {
        return DeviceConfig.getProperties(NAMESPACE,
                KILLSWITCH_STACK_SAMPLING,
                STACK_SAMPLING_FLUSH_TIMEOUT_MS_DEFAULT,
                STACK_SAMPLING_DURATION_MS_DEFAULT,
                STACK_SAMPLING_DURATION_MS_MIN,
                STACK_SAMPLING_DURATION_MS_MAX,
                STACK_SAMPLING_SAMPLING_SIZE_KB_DEFAULT,
                STACK_SAMPLING_SAMPLING_SIZE_KB_MIN,
                STACK_SAMPLING_SAMPLING_SIZE_KB_MAX,
                STACK_SAMPLING_FREQUENCY_DEFAULT,
                STACK_SAMPLING_FREQUENCY_MIN,
                STACK_SAMPLING_FREQUENCY_MAX);
    }

    /** Get all properties related to System Trace configuration. */
    public static DeviceConfig.Properties getAllSystemTraceProperties() {
        return DeviceConfig.getProperties(NAMESPACE,
                KILLSWITCH_SYSTEM_TRACE,
                SYSTEM_TRACE_DURATION_MS_DEFAULT,
                SYSTEM_TRACE_DURATION_MS_MIN,
                SYSTEM_TRACE_DURATION_MS_MAX,
                SYSTEM_TRACE_SIZE_KB_DEFAULT,
                SYSTEM_TRACE_SIZE_KB_MIN,
                SYSTEM_TRACE_SIZE_KB_MAX);
    }

    /** Get all properties related to rate limiter. */
    public static DeviceConfig.Properties getAllRateLimiterProperties() {
        return DeviceConfig.getProperties(NAMESPACE,
                MAX_COST_PROCESS_1_HOUR,
                MAX_COST_SYSTEM_1_HOUR,
                MAX_COST_PROCESS_24_HOUR,
                MAX_COST_SYSTEM_24_HOUR,
                MAX_COST_PROCESS_7_DAY,
                MAX_COST_SYSTEM_7_DAY,
                COST_JAVA_HEAP_DUMP,
                COST_HEAP_PROFILE,
                COST_STACK_SAMPLING,
                COST_SYSTEM_TRACE,
                PERSIST_TO_DISK_FREQUENCY_MS);
    }

}
