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
package android.os.profiling;

import android.annotation.IntDef;
import android.annotation.Nullable;
import android.os.Bundle;
import android.os.ProfilingManager;
import android.os.ProfilingResult;
import android.os.ProfilingTrigger;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/** Helper methods for logging. */
public final class LoggingHelper {

    public static final int REQUEST_RESULT_UNSPECIFIED = 0;
    public static final int REQUEST_RESULT_ERROR = 1;
    public static final int REQUEST_RESULT_RATE_LIMIT_SYSTEM = 2;
    public static final int REQUEST_RESULT_RATE_LIMIT_PROCESS = 3;
    public static final int REQUEST_RESULT_INVALID = 4;
    public static final int REQUEST_RESULT_PROFILING_IN_PROGRESS = 5;
    public static final int REQUEST_RESULT_PROFILING_STARTED = 6;

    public static final int PROFILING_STOPPED_REASON_UNSPECIFIED = 0;
    public static final int PROFILING_STOPPED_REASON_APP_REQUESTED = 1;
    public static final int PROFILING_STOPPED_REASON_APP_DIED = 2;
    public static final int PROFILING_STOPPED_REASON_TIMED_OUT = 3;
    public static final int PROFILING_STOPPED_REASON_ERROR = 4;
    public static final int PROFILING_STOPPED_REASON_SYSTEM_REQUESTED = 5;

    public static final int TRIGGER_STATUS_UNSPECIFIED = 0;
    public static final int TRIGGER_STATUS_ERROR = 1;
    public static final int TRIGGER_STATUS_RATE_LIMIT_SYSTEM = 2;
    public static final int TRIGGER_STATUS_RATE_LIMIT_PROCESS = 3;
    public static final int TRIGGER_STATUS_RATE_LIMIT_APP = 4;
    public static final int TRIGGER_STATUS_NOT_REGISTERED = 5;
    public static final int TRIGGER_STATUS_NOT_RUNNING = 6;
    public static final int TRIGGER_STATUS_FULFILLED = 7;
    public static final int TRIGGER_STATUS_MISSING_NAME = 8;

    public static final int BACKGROUND_TRACE_STATE_UNSPECIFIED = 0;
    public static final int BACKGROUND_TRACE_STATE_STARTED = 1;
    public static final int BACKGROUND_TRACE_STATE_NOT_STARTED_NO_TRIGGERS_REGISTERED = 2;
    public static final int BACKGROUND_TRACE_STATE_NOT_STARTED_TRIGGERS_NOT_LOADED = 3;
    public static final int BACKGROUND_TRACE_STATE_NOT_STARTED_ALREADY_RUNNING = 4;
    public static final int BACKGROUND_TRACE_STATE_STOPPED = 5;

    @IntDef(
            prefix = {"REQUEST_RESULT_"},
            value = {
                REQUEST_RESULT_UNSPECIFIED,
                REQUEST_RESULT_ERROR,
                REQUEST_RESULT_RATE_LIMIT_SYSTEM,
                REQUEST_RESULT_RATE_LIMIT_PROCESS,
                REQUEST_RESULT_INVALID,
                REQUEST_RESULT_PROFILING_IN_PROGRESS,
                REQUEST_RESULT_PROFILING_STARTED,
            })
    @Retention(RetentionPolicy.SOURCE)
    public @interface RequestResult {}

    @IntDef(
            prefix = {"PROFILING_STOPPED_REASON_"},
            value = {
                PROFILING_STOPPED_REASON_UNSPECIFIED,
                PROFILING_STOPPED_REASON_APP_REQUESTED,
                PROFILING_STOPPED_REASON_APP_DIED,
                PROFILING_STOPPED_REASON_TIMED_OUT,
                PROFILING_STOPPED_REASON_ERROR,
            })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ProfilingStoppedReason {}

    @IntDef(
            prefix = {"TRIGGER_STATUS_"},
            value = {
                TRIGGER_STATUS_UNSPECIFIED,
                TRIGGER_STATUS_ERROR,
                TRIGGER_STATUS_RATE_LIMIT_SYSTEM,
                TRIGGER_STATUS_RATE_LIMIT_PROCESS,
                TRIGGER_STATUS_RATE_LIMIT_APP,
                TRIGGER_STATUS_NOT_REGISTERED,
                TRIGGER_STATUS_NOT_RUNNING,
                TRIGGER_STATUS_FULFILLED,
                TRIGGER_STATUS_MISSING_NAME
            })
    @Retention(RetentionPolicy.SOURCE)
    public @interface TriggerStatus {}

    @IntDef(
            prefix = {"BACKGROUND_TRACE_STATE_"},
            value = {
                BACKGROUND_TRACE_STATE_UNSPECIFIED,
                BACKGROUND_TRACE_STATE_STARTED,
                BACKGROUND_TRACE_STATE_NOT_STARTED_NO_TRIGGERS_REGISTERED,
                BACKGROUND_TRACE_STATE_NOT_STARTED_TRIGGERS_NOT_LOADED,
                BACKGROUND_TRACE_STATE_NOT_STARTED_ALREADY_RUNNING,
                BACKGROUND_TRACE_STATE_STOPPED,
            })
    @Retention(RetentionPolicy.SOURCE)
    public @interface BackgroundTraceState {}

    /** Log that a profiling request was made. */
    public static void logProfilingRequest(
            int uid,
            int profilingType,
            @Nullable Bundle params,
            @RequestResult int requestResult,
            boolean isRateLimiterDisabled) {
        ProfilingStatsLog.write(
                ProfilingStatsLog.PROFILING_REQUEST,
                uid,
                profilingTypeToEnumValue(profilingType),
                params != null && !params.isEmpty(),
                requestResult,
                isRateLimiterDisabled);
    }

    /** Log that a profiling session was stopped. */
    public static void logProfilingStopped(
            int uid,
            int profilingType,
            int triggerType,
            @ProfilingStoppedReason int profilingStoppedReason) {
        ProfilingStatsLog.write(
                ProfilingStatsLog.PROFILING_STOPPED,
                uid,
                profilingTypeToEnumValue(profilingType),
                triggerTypeToEnumValue(triggerType),
                profilingStoppedReason);
    }

    /** Log that a result callback was sent to an app. */
    public static void logProfilingResultCallbackSent(
            int uid, int profilingType, int triggerType, int errorCode) {
        ProfilingStatsLog.write(
                ProfilingStatsLog.PROFILING_RESULT_CALLBACK_SENT,
                uid,
                profilingTypeToEnumValue(profilingType),
                triggerTypeToEnumValue(triggerType),
                errorCodeToEnumValue(errorCode));
    }

    /** Log that a trigger was registered. */
    public static void logProfilingTriggerRegister(
            int uid, int triggerType, @Nullable Bundle params) {
        ProfilingStatsLog.write(
                ProfilingStatsLog.PROFILING_TRIGGER_REGISTER,
                uid,
                triggerTypeToEnumValue(triggerType),
                params != null && !params.isEmpty());
    }

    /** Log that a trigger was sent. */
    public static void logProfilingTriggerSent(
            int uid, int triggerType, @TriggerStatus int triggerStatus) {
        ProfilingStatsLog.write(
                ProfilingStatsLog.PROFILING_TRIGGER_SENT,
                uid,
                triggerTypeToEnumValue(triggerType),
                triggerStatus);
    }

    /** Log a background trace state change. */
    public static void logProfilingBackgroundTraceState(
            @BackgroundTraceState int backgroundTraceState) {
        ProfilingStatsLog.write(
                ProfilingStatsLog.PROFILING_BACKGROUND_TRACE_STATE, backgroundTraceState);
    }

    /** Log a global listener registration. */
    public static void logGlobalListenerRegister(int uid) {
        ProfilingStatsLog.write(ProfilingStatsLog.PROFILING_GLOBAL_LISTENER_REGISTER, uid);
    }

    /**
     * Convert API profiling type value to logging enum value.
     *
     * <p>Constants come from:
     * frameworks/proto_logging/stats/enums/profiling/enums.proto:ProfilingType
     */
    private static int profilingTypeToEnumValue(int profilingType) {
        return switch (profilingType) {
            case ProfilingManager.PROFILING_TYPE_JAVA_HEAP_DUMP -> 1;
            case ProfilingManager.PROFILING_TYPE_HEAP_PROFILE -> 2;
            case ProfilingManager.PROFILING_TYPE_STACK_SAMPLING -> 3;
            case ProfilingManager.PROFILING_TYPE_SYSTEM_TRACE -> 4;
            default -> 0;
        };
    }

    /**
     * Convert API trigger type value to logging enum value.
     *
     * <p>Constants come from:
     * frameworks/proto_logging/stats/enums/profiling/enums.proto:TriggerType
     */
    private static int triggerTypeToEnumValue(int triggerType) {
        // LINT.IfChange(trigger_types)
        return switch (triggerType) {
            case ProfilingTrigger.TRIGGER_TYPE_NONE -> 1;
            case ProfilingTrigger.TRIGGER_TYPE_APP_FULLY_DRAWN -> 2;
            case ProfilingTrigger.TRIGGER_TYPE_ANR -> 3;
            case ProfilingTrigger.TRIGGER_TYPE_APP_REQUEST_RUNNING_TRACE -> 4;
            case ProfilingTrigger.TRIGGER_TYPE_KILL_FORCE_STOP -> 5;
            case ProfilingTrigger.TRIGGER_TYPE_KILL_RECENTS -> 6;
            case ProfilingTrigger.TRIGGER_TYPE_KILL_TASK_MANAGER -> 7;
            case ProfilingTrigger.TRIGGER_TYPE_OOM -> 8;
            case ProfilingTrigger.TRIGGER_TYPE_ANOMALY -> 9;
            case ProfilingTrigger.TRIGGER_TYPE_KILL_EXCESSIVE_CPU_USAGE -> 10;
            case ProfilingTrigger.TRIGGER_TYPE_COLD_START -> 11;
            case ProfilingTrigger.TRIGGER_TYPE_APP_COMPAT -> 12;
            default -> 0;
        };
        // LINT.ThenChange(/framework/java/android/os/ProfilingTrigger.java:trigger_types)
    }

    /**
     * Convert API error code value to logging enum value.
     *
     * <p>Constants come from:
     * frameworks/proto_logging/stats/enums/profiling/enums.proto:ResultErrorCode
     */
    private static int errorCodeToEnumValue(int errorCode) {
        return switch (errorCode) {
            case ProfilingResult.ERROR_NONE -> 1;
            case ProfilingResult.ERROR_FAILED_RATE_LIMIT_SYSTEM -> 2;
            case ProfilingResult.ERROR_FAILED_RATE_LIMIT_PROCESS -> 3;
            case ProfilingResult.ERROR_FAILED_PROFILING_IN_PROGRESS -> 4;
            case ProfilingResult.ERROR_FAILED_EXECUTING -> 5;
            case ProfilingResult.ERROR_FAILED_POST_PROCESSING -> 6;
            case ProfilingResult.ERROR_FAILED_NO_DISK_SPACE -> 7;
            case ProfilingResult.ERROR_FAILED_INVALID_REQUEST -> 8;
            case ProfilingResult.ERROR_UNKNOWN -> 9;
            default -> 0;
        };
    }
}
