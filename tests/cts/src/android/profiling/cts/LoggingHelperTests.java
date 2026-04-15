/*
 * Copyright (C) 2026 The Android Open Source Project
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

import static android.os.ProfilingManager.PROFILING_TYPE_HEAP_PROFILE;
import static android.os.ProfilingManager.PROFILING_TYPE_JAVA_HEAP_DUMP;
import static android.os.ProfilingManager.PROFILING_TYPE_STACK_SAMPLING;
import static android.os.ProfilingManager.PROFILING_TYPE_SYSTEM_TRACE;
import static android.os.ProfilingResult.ERROR_FAILED_RATE_LIMIT_SYSTEM;
import static android.os.ProfilingTrigger.TRIGGER_TYPE_ANOMALY;
import static android.os.ProfilingTrigger.TRIGGER_TYPE_ANR;
import static android.os.ProfilingTrigger.TRIGGER_TYPE_APP_COMPAT;
import static android.os.ProfilingTrigger.TRIGGER_TYPE_APP_FULLY_DRAWN;
import static android.os.ProfilingTrigger.TRIGGER_TYPE_KILL_FORCE_STOP;
import static android.os.ProfilingTrigger.TRIGGER_TYPE_OOM;
import static android.os.profiling.LoggingHelper.ANOMALY_MEMORY_LIMIT_TAG;
import static android.os.profiling.LoggingHelper.BACKGROUND_TRACE_STATE_STARTED;
import static android.os.profiling.LoggingHelper.PROFILING_STOPPED_REASON_TIMED_OUT;
import static android.os.profiling.LoggingHelper.PROFILING_TAG_ANOMALY_MEMORY_LIMIT;
import static android.os.profiling.LoggingHelper.PROFILING_TAG_USER_SPECIFIED;
import static android.os.profiling.LoggingHelper.REQUEST_RESULT_ERROR;
import static android.os.profiling.LoggingHelper.REQUEST_RESULT_PROFILING_STARTED;
import static android.os.profiling.LoggingHelper.TRIGGER_CALLBACK_STATUS_SUCCESS;
import static android.os.profiling.LoggingHelper.TRIGGER_STATUS_FULFILLED;
import static android.os.profiling.LoggingHelper.TRIGGER_STATUS_RATE_LIMIT_APP;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doNothing;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;

import android.os.Bundle;
import android.os.profiling.LoggingHelper;
import android.os.profiling.ProfilingStatsLog;

import androidx.test.runner.AndroidJUnit4;

import com.android.modules.utils.testing.ExtendedMockitoRule;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests for {@link LoggingHelper}. */
@RunWith(AndroidJUnit4.class)
public class LoggingHelperTests {

    @Rule
    public final ExtendedMockitoRule mExtendedMockitoRule =
            new ExtendedMockitoRule.Builder(this).mockStatic(ProfilingStatsLog.class).build();

    private static final int TEST_UID = 1000;

    /**
     * The following constants represent the internal mapping from API types to logging enum values
     * defined in frameworks/proto_logging/stats/enums/profiling/enums.proto.
     */
    private static final int LOG_ENUM_JAVA_HEAP_DUMP = 1;

    private static final int LOG_ENUM_HEAP_PROFILE = 2;
    private static final int LOG_ENUM_STACK_SAMPLING = 3;
    private static final int LOG_ENUM_SYSTEM_TRACE = 4;

    private static final int LOG_ENUM_TRIGGER_APP_FULLY_DRAWN = 2;
    private static final int LOG_ENUM_TRIGGER_ANR = 3;
    private static final int LOG_ENUM_TRIGGER_KILL_FORCE_STOP = 5;
    private static final int LOG_ENUM_TRIGGER_OOM = 8;
    private static final int LOG_ENUM_TRIGGER_ANOMALY = 9;
    private static final int LOG_ENUM_TRIGGER_APP_COMPAT = 12;

    private static final int LOG_ENUM_ERROR_FAILED_RATE_LIMIT_SYSTEM = 2;

    @Before
    public void setUp() {
        // Ensure ProfilingStatsLog.write does nothing when called.
        doNothing().when(() -> ProfilingStatsLog.write(anyInt(), anyInt()));
        doNothing().when(() -> ProfilingStatsLog.write(anyInt(), anyInt(), anyInt(), anyBoolean()));
        doNothing().when(() -> ProfilingStatsLog.write(anyInt(), anyInt(), anyInt(), anyInt()));
        doNothing()
                .when(
                        () ->
                                ProfilingStatsLog.write(
                                        anyInt(), anyInt(), anyInt(), anyInt(), anyInt()));
        doNothing()
                .when(
                        () ->
                                ProfilingStatsLog.write(
                                        anyInt(),
                                        anyInt(),
                                        anyInt(),
                                        anyBoolean(),
                                        anyInt(),
                                        anyBoolean()));
        doNothing()
                .when(
                        () ->
                                ProfilingStatsLog.write(
                                        anyInt(), anyInt(), anyInt(), anyInt(), anyInt(),
                                        anyLong()));
    }

    @Test
    public void testLogProfilingRequest() {
        Bundle params = new Bundle();
        params.putString("key", "value");
        LoggingHelper.logProfilingRequest(
                TEST_UID,
                PROFILING_TYPE_JAVA_HEAP_DUMP,
                params,
                REQUEST_RESULT_PROFILING_STARTED,
                /* isRateLimiterEnabled= */ false);

        verify(
                () ->
                        ProfilingStatsLog.write(
                                ProfilingStatsLog.PROFILING_REQUEST,
                                TEST_UID,
                                LOG_ENUM_JAVA_HEAP_DUMP,
                                true, // params is not empty
                                REQUEST_RESULT_PROFILING_STARTED,
                                false));
    }

    @Test
    public void testLogProfilingRequest_nullParams() {
        LoggingHelper.logProfilingRequest(
                TEST_UID,
                PROFILING_TYPE_HEAP_PROFILE,
                /* params= */ null,
                REQUEST_RESULT_ERROR,
                /* isRateLimiterEnabled= */ true);

        verify(
                () ->
                        ProfilingStatsLog.write(
                                ProfilingStatsLog.PROFILING_REQUEST,
                                TEST_UID,
                                LOG_ENUM_HEAP_PROFILE,
                                false, // params is null
                                REQUEST_RESULT_ERROR,
                                true));
    }

    @Test
    public void testLogProfilingStopped() {
        LoggingHelper.logProfilingStopped(
                TEST_UID,
                PROFILING_TYPE_STACK_SAMPLING,
                TRIGGER_TYPE_ANR,
                PROFILING_STOPPED_REASON_TIMED_OUT);

        verify(
                () ->
                        ProfilingStatsLog.write(
                                ProfilingStatsLog.PROFILING_STOPPED,
                                TEST_UID,
                                LOG_ENUM_STACK_SAMPLING,
                                LOG_ENUM_TRIGGER_ANR,
                                PROFILING_STOPPED_REASON_TIMED_OUT));
    }

    @Test
    public void testLogProfilingResultCallbackSent() {
        LoggingHelper.logProfilingResultCallbackSent(
                TEST_UID,
                PROFILING_TYPE_SYSTEM_TRACE,
                TRIGGER_TYPE_OOM,
                ERROR_FAILED_RATE_LIMIT_SYSTEM,
                /* ProfilingRequestTimeMs= */ -1L);

        verify(
                () ->
                        ProfilingStatsLog.write(
                                ProfilingStatsLog.PROFILING_RESULT_CALLBACK_SENT,
                                TEST_UID,
                                LOG_ENUM_SYSTEM_TRACE,
                                LOG_ENUM_TRIGGER_OOM,
                                LOG_ENUM_ERROR_FAILED_RATE_LIMIT_SYSTEM,
                                -1L));
    }

    @Test
    public void testLogProfilingTriggerRegister() {
        LoggingHelper.logProfilingTriggerRegister(TEST_UID, TRIGGER_TYPE_APP_FULLY_DRAWN, null);

        verify(
                () ->
                        ProfilingStatsLog.write(
                                ProfilingStatsLog.PROFILING_TRIGGER_REGISTER,
                                TEST_UID,
                                LOG_ENUM_TRIGGER_APP_FULLY_DRAWN,
                                false));
    }

    @Test
    public void testLogProfilingTriggerSent() {
        LoggingHelper.logProfilingTriggerSent(
                TEST_UID, TRIGGER_TYPE_ANOMALY, TRIGGER_STATUS_FULFILLED, ANOMALY_MEMORY_LIMIT_TAG);

        verify(
                () ->
                        ProfilingStatsLog.write(
                                ProfilingStatsLog.PROFILING_TRIGGER_SENT,
                                TEST_UID,
                                LOG_ENUM_TRIGGER_ANOMALY,
                                TRIGGER_STATUS_FULFILLED,
                                PROFILING_TAG_ANOMALY_MEMORY_LIMIT));
    }

    @Test
    public void testLogProfilingTriggerSent_userSpecified() {
        LoggingHelper.logProfilingTriggerSent(
                TEST_UID,
                TRIGGER_TYPE_APP_COMPAT,
                TRIGGER_STATUS_RATE_LIMIT_APP,
                /* tag= */ "USER_TAG");

        verify(
                () ->
                        ProfilingStatsLog.write(
                                ProfilingStatsLog.PROFILING_TRIGGER_SENT,
                                TEST_UID,
                                LOG_ENUM_TRIGGER_APP_COMPAT,
                                TRIGGER_STATUS_RATE_LIMIT_APP,
                                PROFILING_TAG_USER_SPECIFIED));
    }

    @Test
    public void testLogProfilingBackgroundTraceState() {
        LoggingHelper.logProfilingBackgroundTraceState(BACKGROUND_TRACE_STATE_STARTED);

        verify(
                () ->
                        ProfilingStatsLog.write(
                                ProfilingStatsLog.PROFILING_BACKGROUND_TRACE_STATE,
                                BACKGROUND_TRACE_STATE_STARTED));
    }

    @Test
    public void testLogGlobalListenerRegister() {
        LoggingHelper.logGlobalListenerRegister(TEST_UID);

        verify(
                () ->
                        ProfilingStatsLog.write(
                                ProfilingStatsLog.PROFILING_GLOBAL_LISTENER_REGISTER, TEST_UID));
    }

    @Test
    public void testLogProfilingTriggerCallbackStatus() {
        LoggingHelper.logProfilingTriggerCallbackStatus(
                TEST_UID, TRIGGER_TYPE_KILL_FORCE_STOP, TRIGGER_CALLBACK_STATUS_SUCCESS);

        verify(
                () ->
                        ProfilingStatsLog.write(
                                ProfilingStatsLog.PROFILING_TRIGGER_CALLBACK_STATUS,
                                TEST_UID,
                                LOG_ENUM_TRIGGER_KILL_FORCE_STOP,
                                TRIGGER_CALLBACK_STATUS_SUCCESS));
    }
}
