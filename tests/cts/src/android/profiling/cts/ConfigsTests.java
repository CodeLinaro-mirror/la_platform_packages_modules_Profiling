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

package android.profiling.cts;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;

import android.os.Bundle;
import android.os.ProfilingManager;
import android.os.profiling.Configs;
import android.os.profiling.DeviceConfigHelper;
import android.os.profiling.Flags;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.provider.DeviceConfig;

import androidx.test.runner.AndroidJUnit4;

import com.google.common.truth.Expect;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import perfetto.protos.FtraceConfigOuterClass.FtraceConfig;
import perfetto.protos.PerfEventConfigOuterClass.PerfEventConfig;
import perfetto.protos.PerfEventsOuterClass.PerfEvents;
import perfetto.protos.TraceConfigOuterClass.TraceConfig;

import java.util.HashMap;
import java.util.Map;

@RunWith(AndroidJUnit4.class)
public final class ConfigsTests {
    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    @Rule public final Expect expect = Expect.create();

    private static final String PACKAGE_NAME = "com.android.test";
    private static final int FILE_PROCESSING_DELAY_MS = 2000;
    private static final int HEAPPROFD_SHMEM_SIZE_BYTES = 8388608; // 8MB
    private static final int PROFILING_TYPE_INVALID = 999;
    private static final int MAX_PROFILING_TIME_BUFFER_MS = 10 * 1000;
    public static final int INVALID_POLICY = 999;

    @Test
    public void testGetInitialProfilingTimeMs_JavaHeapDump() {
        int time =
                Configs.getInitialProfilingTimeMs(
                        ProfilingManager.PROFILING_TYPE_JAVA_HEAP_DUMP, null);
        assertThat(time)
                .isEqualTo(
                        DeviceConfigHelper.DEFAULT_JAVA_HEAP_DUMP_DURATION_MS_DEFAULT
                                + FILE_PROCESSING_DELAY_MS);
    }

    @Test
    public void testGetInitialProfilingTimeMs_HeapProfile() {
        int time =
                Configs.getInitialProfilingTimeMs(
                        ProfilingManager.PROFILING_TYPE_HEAP_PROFILE, null);
        assertThat(time)
                .isEqualTo(
                        DeviceConfigHelper.DEFAULT_HEAP_PROFILE_DURATION_MS_DEFAULT
                                + FILE_PROCESSING_DELAY_MS);
    }

    @Test
    public void testGetInitialProfilingTimeMs_StackSampling() {
        int time =
                Configs.getInitialProfilingTimeMs(
                        ProfilingManager.PROFILING_TYPE_STACK_SAMPLING, null);
        assertThat(time)
                .isEqualTo(
                        DeviceConfigHelper.DEFAULT_STACK_SAMPLING_DURATION_MS_DEFAULT
                                + FILE_PROCESSING_DELAY_MS);
    }

    @Test
    public void testGetInitialProfilingTimeMs_SystemTrace() {
        int time =
                Configs.getInitialProfilingTimeMs(
                        ProfilingManager.PROFILING_TYPE_SYSTEM_TRACE, null);
        assertThat(time)
                .isEqualTo(
                        DeviceConfigHelper.DEFAULT_SYSTEM_TRACE_DURATION_MS_DEFAULT
                                + FILE_PROCESSING_DELAY_MS);
    }

    @Test
    public void testGenerateConfigForRequest_javaHeapDump() throws Exception {
        Bundle params = new Bundle();
        byte[] configBytes =
                Configs.generateConfigForRequest(
                        ProfilingManager.PROFILING_TYPE_JAVA_HEAP_DUMP, params, PACKAGE_NAME);

        TraceConfig config = TraceConfig.parseFrom(configBytes);
        assertThat(config).isNotNull();
        assertThat(config.getDurationMs())
                .isEqualTo(DeviceConfigHelper.DEFAULT_JAVA_HEAP_DUMP_DURATION_MS_DEFAULT);
        // A Java heap dump config should have exactly one data source.
        assertThat(config.getDataSourcesCount()).isEqualTo(1);

        TraceConfig.DataSource ds = config.getDataSources(0);
        expect.that(ds.getConfig().getName()).isEqualTo("android.java_hprof");
        expect.that(ds.getConfig().getJavaHprofConfig().getProcessCmdlineList())
                .contains(PACKAGE_NAME);
        expect.that(ds.getConfig().getJavaHprofConfig().getDumpSmaps()).isTrue();
    }

    @Test
    public void testGenerateConfigForRequest_heapProfile() throws Exception {
        Bundle params = new Bundle();
        byte[] configBytes =
                Configs.generateConfigForRequest(
                        ProfilingManager.PROFILING_TYPE_HEAP_PROFILE, params, PACKAGE_NAME);

        TraceConfig config = TraceConfig.parseFrom(configBytes);
        assertThat(config).isNotNull();
        assertThat(config.getDurationMs())
                .isEqualTo(DeviceConfigHelper.DEFAULT_HEAP_PROFILE_DURATION_MS_DEFAULT);
        // A heap profile config should have exactly one data source.
        assertThat(config.getDataSourcesCount()).isEqualTo(1);

        TraceConfig.DataSource ds = config.getDataSources(0);
        expect.that(ds.getConfig().getName()).isEqualTo("android.heapprofd");
        expect.that(ds.getConfig().getHeapprofdConfig().getProcessCmdlineList())
                .contains(PACKAGE_NAME);
        expect.that(ds.getConfig().getHeapprofdConfig().getShmemSizeBytes())
                .isEqualTo(HEAPPROFD_SHMEM_SIZE_BYTES);
        expect.that(ds.getConfig().getHeapprofdConfig().getSamplingIntervalBytes())
                .isEqualTo(DeviceConfigHelper.DEFAULT_HEAP_PROFILE_SAMPLING_INTERVAL_BYTES_DEFAULT);
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_REDACT_STACK_SAMPLING)
    public void testGenerateConfigForRequest_stackSampling() throws Exception {
        Bundle params = new Bundle();
        byte[] configBytes =
                Configs.generateConfigForRequest(
                        ProfilingManager.PROFILING_TYPE_STACK_SAMPLING, params, PACKAGE_NAME);

        TraceConfig config = TraceConfig.parseFrom(configBytes);
        assertThat(config).isNotNull();
        expect.that(config.getDurationMs())
                .isEqualTo(DeviceConfigHelper.DEFAULT_STACK_SAMPLING_DURATION_MS_DEFAULT);
        // Stack sampling adds 2 data sources, perf and package_list
        expect.that(config.getDataSourcesCount()).isEqualTo(2);

        // Check for linux.perf
        TraceConfig.DataSource dsPerf =
                config.getDataSourcesList().stream()
                        .filter(d -> d.getConfig().getName().equals("linux.perf"))
                        .findFirst()
                        .orElse(null);
        assertThat(dsPerf).isNotNull();

        PerfEventConfig perfConfig = dsPerf.getConfig().getPerfEventConfig();
        expect.that(perfConfig.getTimebase().getFrequency())
                .isEqualTo(DeviceConfigHelper.DEFAULT_STACK_SAMPLING_FREQUENCY_DEFAULT);
        expect.that(perfConfig.getTimebase().getCounter())
                .isEqualTo(PerfEvents.Counter.SW_CPU_CLOCK);
        expect.that(perfConfig.getCallstackSampling().getScope().getTargetCmdlineList())
                .contains(PACKAGE_NAME);

        // Check for android.packages_list.
        assertPackagesListDataSource(config);
    }

    @Test
    public void testGenerateConfigForRequest_systemTrace() throws Exception {
        Bundle params = new Bundle();
        byte[] configBytes =
                Configs.generateConfigForRequest(
                        ProfilingManager.PROFILING_TYPE_SYSTEM_TRACE, params, PACKAGE_NAME);

        TraceConfig config = TraceConfig.parseFrom(configBytes);
        assertThat(config).isNotNull();
        expect.that(config.getDurationMs())
                .isEqualTo(DeviceConfigHelper.DEFAULT_SYSTEM_TRACE_DURATION_MS_DEFAULT);
        // A system trace adds 4 data sources: process_stats, packages_list, ftrace, surfaceflinger
        expect.that(config.getDataSourcesCount()).isEqualTo(4);

        // Check for linux.ftrace
        TraceConfig.DataSource ds =
                config.getDataSourcesList().stream()
                        .filter(d -> d.getConfig().getName().equals("linux.ftrace"))
                        .findFirst()
                        .orElse(null);
        assertThat(ds).isNotNull();

        FtraceConfig ftraceConfig = ds.getConfig().getFtraceConfig();
        expect.that(ftraceConfig.getAtraceAppsList()).contains(PACKAGE_NAME);

        // Verify all expected ftrace events are present
        expect.that(ftraceConfig.getFtraceEventsList())
                .containsAtLeast(
                        "gpu_mem/gpu_mem_total",
                        "power/suspend_resume",
                        "sched/sched_process_free",
                        "sched/sched_switch",
                        "task/task_newtask",
                        "task/task_rename",
                        "sched/sched_waking",
                        "sched/sched_wakeup_new",
                        "vmscan/mm_vmscan_direct_reclaim_begin",
                        "vmscan/mm_vmscan_direct_reclaim_end");

        // Verify all expected atrace categories are present
        expect.that(ftraceConfig.getAtraceCategoriesList())
                .containsAtLeast("am", "dalvik", "bionic", "binder_driver", "view", "input", "gfx");

        // Check for android.packages_list.
        assertPackagesListDataSource(config);

        // Check for linux.process_stats
        boolean hasProcessStats =
                config.getDataSourcesList().stream()
                        .anyMatch(d -> d.getConfig().getName().equals("linux.process_stats"));
        expect.that(hasProcessStats).isTrue();

        // Check for android.surfaceflinger.frametimeline
        boolean hasSurfaceFlinger =
                config.getDataSourcesList().stream()
                        .anyMatch(
                                d ->
                                        d.getConfig()
                                                .getName()
                                                .equals("android.surfaceflinger.frametimeline"));
        expect.that(hasSurfaceFlinger).isTrue();
    }

    @Test
    public void testGenerateConfigForRequest_InvalidType() {
        assertThrows(
                IllegalArgumentException.class,
                () -> Configs.generateConfigForRequest(PROFILING_TYPE_INVALID, null, PACKAGE_NAME));
    }

    @Test
    public void testMaybeUpdateConfigs() {
        // Initialize first to ensure configs are loaded
        Configs.getInitialProfilingTimeMs(ProfilingManager.PROFILING_TYPE_SYSTEM_TRACE, null);

        Map<String, String> propertiesMap = new HashMap<>();
        int testDuration = 12345;
        propertiesMap.put(
                DeviceConfigHelper.SYSTEM_TRACE_DURATION_MS_DEFAULT, String.valueOf(testDuration));
        DeviceConfig.Properties properties =
                new DeviceConfig.Properties(DeviceConfigHelper.NAMESPACE, propertiesMap);

        Configs.maybeUpdateConfigs(properties);

        // Verify the update took effect.
        // getInitialProfilingTimeMs returns duration + FILE_PROCESSING_DELAY_MS
        int time =
                Configs.getInitialProfilingTimeMs(
                        ProfilingManager.PROFILING_TYPE_SYSTEM_TRACE, null);

        // We check if it matches the new duration + delay
        assertThat(time).isEqualTo(testDuration + FILE_PROCESSING_DELAY_MS);
    }

    @Test
    public void testGetMaxProfilingTimeAllowedMs_JavaHeapDump() {
        // Ensure configs are initialized
        Configs.getInitialProfilingTimeMs(ProfilingManager.PROFILING_TYPE_JAVA_HEAP_DUMP, null);
        int maxTimeJavaHeapDump =
                Configs.getMaxProfilingTimeAllowedMs(
                        ProfilingManager.PROFILING_TYPE_JAVA_HEAP_DUMP, null);
        int expectedJavaHeapDump =
                DeviceConfigHelper.DEFAULT_JAVA_HEAP_DUMP_DURATION_MS_DEFAULT
                        + FILE_PROCESSING_DELAY_MS
                        + DeviceConfigHelper
                                .DEFAULT_JAVA_HEAP_DUMP_DATA_SOURCE_STOP_TIMEOUT_MS_DEFAULT
                        + MAX_PROFILING_TIME_BUFFER_MS;
        assertThat(maxTimeJavaHeapDump).isEqualTo(expectedJavaHeapDump);
    }

    @Test
    public void testGetMaxProfilingTimeAllowedMs_HeapProfile() {
        // Ensure configs are initialized
        Configs.getInitialProfilingTimeMs(ProfilingManager.PROFILING_TYPE_HEAP_PROFILE, null);
        int maxTimeHeapProfile =
                Configs.getMaxProfilingTimeAllowedMs(
                        ProfilingManager.PROFILING_TYPE_HEAP_PROFILE, null);
        int expectedHeapProfile =
                DeviceConfigHelper.DEFAULT_HEAP_PROFILE_DURATION_MS_DEFAULT
                        + FILE_PROCESSING_DELAY_MS
                        + DeviceConfigHelper.DEFAULT_HEAP_PROFILE_FLUSH_TIMEOUT_MS_DEFAULT
                        + MAX_PROFILING_TIME_BUFFER_MS;
        assertThat(maxTimeHeapProfile).isEqualTo(expectedHeapProfile);
    }

    @Test
    public void testGetMaxProfilingTimeAllowedMs_StackSampling() {
        // Ensure configs are initialized
        Configs.getInitialProfilingTimeMs(ProfilingManager.PROFILING_TYPE_STACK_SAMPLING, null);
        int maxTimeStackSampling =
                Configs.getMaxProfilingTimeAllowedMs(
                        ProfilingManager.PROFILING_TYPE_STACK_SAMPLING, null);
        int expectedStackSampling =
                DeviceConfigHelper.DEFAULT_STACK_SAMPLING_DURATION_MS_DEFAULT
                        + FILE_PROCESSING_DELAY_MS
                        + DeviceConfigHelper.DEFAULT_STACK_SAMPLING_FLUSH_TIMEOUT_MS_DEFAULT
                        + MAX_PROFILING_TIME_BUFFER_MS;
        assertThat(maxTimeStackSampling).isEqualTo(expectedStackSampling);
    }

    @Test
    public void testGetMaxProfilingTimeAllowedMs_SystemTrace() {
        // Ensure configs are initialized
        Configs.getInitialProfilingTimeMs(ProfilingManager.PROFILING_TYPE_SYSTEM_TRACE, null);
        int maxTimeSystemTrace =
                Configs.getMaxProfilingTimeAllowedMs(
                        ProfilingManager.PROFILING_TYPE_SYSTEM_TRACE, null);
        int expectedSystemTrace =
                DeviceConfigHelper.DEFAULT_SYSTEM_TRACE_DURATION_MS_DEFAULT
                        + FILE_PROCESSING_DELAY_MS
                        + MAX_PROFILING_TIME_BUFFER_MS;
        assertThat(maxTimeSystemTrace).isEqualTo(expectedSystemTrace);
    }

    @Test
    public void testKillswitch_SystemTrace() {
        verifyKillswitch(
                ProfilingManager.PROFILING_TYPE_SYSTEM_TRACE,
                DeviceConfigHelper.KILLSWITCH_SYSTEM_TRACE,
                "System trace is disabled");
    }

    @Test
    public void testKillswitch_HeapProfile() {
        verifyKillswitch(
                ProfilingManager.PROFILING_TYPE_HEAP_PROFILE,
                DeviceConfigHelper.KILLSWITCH_HEAP_PROFILE,
                "Heap profile is disabled");
    }

    @Test
    public void testKillswitch_JavaHeapDump() {
        verifyKillswitch(
                ProfilingManager.PROFILING_TYPE_JAVA_HEAP_DUMP,
                DeviceConfigHelper.KILLSWITCH_JAVA_HEAP_DUMP,
                "Java heap dump is disabled");
    }

    @Test
    public void testKillswitch_StackSampling() {
        verifyKillswitch(
                ProfilingManager.PROFILING_TYPE_STACK_SAMPLING,
                DeviceConfigHelper.KILLSWITCH_STACK_SAMPLING,
                "Stack sampling is disabled");
    }

    @Test
    public void testGenerateConfigForRequest_StackSampling_BinderOnly() throws Exception {
        Bundle params = new Bundle();
        params.putBoolean(ProfilingManager.KEY_SAMPLE_BINDER_ONLY, true);

        byte[] configBytes =
                Configs.generateConfigForRequest(
                        ProfilingManager.PROFILING_TYPE_STACK_SAMPLING, params, PACKAGE_NAME);

        TraceConfig config = TraceConfig.parseFrom(configBytes);
        assertThat(config).isNotNull();

        boolean hasBinder =
                config.getDataSourcesList().stream()
                        .anyMatch(
                                ds -> {
                                    if (ds.getConfig().hasPerfEventConfig()) {

                                        return ds.getConfig()
                                                .getPerfEventConfig()
                                                .getTimebase()
                                                .getName()
                                                .equals("binder_transaction");
                                    }

                                    return false;
                                });
        assertThat(hasBinder).isTrue();
    }

    @Test
    public void testGenerateConfigForRequest_InvalidBufferFillPolicy() {
        Bundle params = new Bundle();
        params.putInt(ProfilingManager.KEY_BUFFER_FILL_POLICY, INVALID_POLICY);

        Throwable e =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> {
                            Configs.generateConfigForRequest(
                                    ProfilingManager.PROFILING_TYPE_STACK_SAMPLING,
                                    params,
                                    PACKAGE_NAME);
                        });
        assertThat(e).hasMessageThat().contains("Invalid buffer fill policy");
    }

    private void assertPackagesListDataSource(TraceConfig config) {
        TraceConfig.DataSource dsPackagesList =
                config.getDataSourcesList().stream()
                        .filter(d -> d.getConfig().getName().equals("android.packages_list"))
                        .findFirst()
                        .orElse(null);
        expect.that(dsPackagesList).isNotNull();
        expect.that(dsPackagesList.getConfig().getPackagesListConfig().getPackageNameFilterList())
                .contains(PACKAGE_NAME);
    }

    private void verifyKillswitch(int profilingType, String property, String expectedMessage) {
        // Ensure initialized
        Configs.getInitialProfilingTimeMs(profilingType, null);

        // Set killswitch to true
        Map<String, String> propertiesMap = new HashMap<>();
        propertiesMap.put(property, "true");
        DeviceConfig.Properties properties =
                new DeviceConfig.Properties(DeviceConfigHelper.NAMESPACE, propertiesMap);

        Configs.maybeUpdateConfigs(properties);

        try {
            Throwable e =
                    assertThrows(
                            IllegalArgumentException.class,
                            () ->
                                    Configs.generateConfigForRequest(
                                            profilingType, null, PACKAGE_NAME));
            assertThat(e).hasMessageThat().contains(expectedMessage);
        } finally {
            // Reset killswitch to false
            propertiesMap.put(property, "false");
            properties = new DeviceConfig.Properties(DeviceConfigHelper.NAMESPACE, propertiesMap);
            Configs.maybeUpdateConfigs(properties);
        }
    }
}
