/*
 * Copyright (C) 2024 The Android Open Source Project
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

import static android.os.profiling.ProfilingService.TracingState;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Bundle;
import android.os.QueuedResultsWrapper;
import android.util.Log;

import java.util.UUID;

/**
 * Represents a single in progress tracing session and all necessary data to manage and process it.
 */
public final class TracingSession {
    private static final String TAG = TracingSession.class.getSimpleName();

    // LINT.IfChange(persisted_params)
    // Persisted params
    private final int mProfilingType;
    private final int mTriggerType;
    private final int mUid;
    @NonNull private final String mPackageName;
    @Nullable private final String mTag;
    private final long mKeyMostSigBits;
    private final long mKeyLeastSigBits;
    @Nullable private String mFileName = null;
    @Nullable private String mRedactedFileName = null;
    @NonNull private TracingState mState;
    private int mRetryCount = 0;
    @Nullable private String mErrorMessage = null;
    // Expected to be populated with ProfilingResult.ERROR_* values.
    private int mErrorStatus = -1; // Default to invalid value.
    // LINT.ThenChange(:from_proto)

    // Non-persisted params
    @Nullable private final Bundle mParams;
    @Nullable private Process mActiveTrace;
    @Nullable private Process mActiveRedaction;
    @Nullable private Runnable mProcessResultRunnable;
    @Nullable private String mKey = null;
    @Nullable private String mDestinationFileName = null;
    private long mRedactionStartTimeMs;
    private long mProfilingStartTimeMs;
    private int mMaxProfilingTimeAllowedMs = 0;

    public TracingSession(int profilingType,  int uid, String packageName, int triggerType) {
        this(
                profilingType,
                null,
                uid,
                packageName,
                null,
                0L,
                0L,
                triggerType);
    }

    public TracingSession(int profilingType, Bundle params, int uid, String packageName, String tag,
            long keyMostSigBits, long keyLeastSigBits, int triggerType) {
        mProfilingType = profilingType;
        mTriggerType = triggerType;
        mParams = params;
        mUid = uid;
        mPackageName = packageName;
        mTag = tag;
        mKeyMostSigBits = keyMostSigBits;
        mKeyLeastSigBits = keyLeastSigBits;
        mState = TracingState.REQUESTED;
    }

    // LINT.IfChange(from_proto)
    public TracingSession(QueuedResultsWrapper.TracingSession sessionProto) {
        mProfilingType = sessionProto.getProfilingType();
        mUid = sessionProto.getUid();
        mPackageName = sessionProto.getPackageName();
        mTag = sessionProto.getTag();
        mKeyMostSigBits = sessionProto.getKeyMostSigBits();
        mKeyLeastSigBits = sessionProto.getKeyLeastSigBits();
        if (sessionProto.hasFileName()) {
            mFileName = sessionProto.getFileName();
        }
        if (sessionProto.hasRedactedFileName()) {
            mRedactedFileName = sessionProto.getRedactedFileName();
        }
        mState = TracingState.of(sessionProto.getTracingState());
        mRetryCount = sessionProto.getRetryCount();
        if (sessionProto.hasErrorMessage()) {
            mErrorMessage = sessionProto.getErrorMessage();
        }
        mErrorStatus = sessionProto.getErrorStatus();
        mTriggerType = sessionProto.getTriggerType();

        // params is not persisted because we cannot guarantee that it does not contain some large
        // store of data, and because we don't need it anymore once the request has gotten to the
        // point of being persisted.
        mParams = null;

        if (mState == null || mState.getValue() < TracingState.PROFILING_FINISHED.getValue()) {
            // This should never happen. If state is null, then we can't know what to do next. If
            // the state is earlier than PROFILING_FINISHED then it should not have been in the
            // queue and therefore should not have been persisted. Either way, update the state to
            // indicate that the caller was already notified (because we can't know what to notify),
            // this will ensure that all that's remaining is cleanup.
            mState = TracingState.NOTIFIED_REQUESTER;
            Log.e(TAG, "Attempting to load a queued session with an invalid state.");
        }
    }
    // LINT.ThenChange(:to_proto)

    public byte[] getConfigBytes() throws IllegalArgumentException {
        return Configs.generateConfigForRequest(mProfilingType, mParams, mPackageName);
    }

    public int getPostProcessingScheduleDelayMs() throws IllegalArgumentException {
        return Configs.getInitialProfilingTimeMs(mProfilingType, mParams);
    }

    /**
     * Gets the maximum profiling time allowed for this TracingSession.
     * @return maximum profiling time allowed in ms.
     */
    public int getMaxProfilingTimeAllowedMs() {
        if (mMaxProfilingTimeAllowedMs != 0) {
            return mMaxProfilingTimeAllowedMs;
        }
        mMaxProfilingTimeAllowedMs =
                Configs.getMaxProfilingTimeAllowedMs(mProfilingType, mParams);
        return mMaxProfilingTimeAllowedMs;
    }

    @Nullable
    public String getKey() {
        if (mKey == null) {
            mKey = (new UUID(mKeyMostSigBits, mKeyLeastSigBits)).toString();
        }
        return mKey;
    }

    public void setActiveTrace(Process activeTrace) {
        mActiveTrace = activeTrace;
    }

    public void setActiveRedaction(Process activeRedaction) {
        mActiveRedaction = activeRedaction;
    }

    public void setProcessResultRunnable(Runnable processResultRunnable) {
        mProcessResultRunnable = processResultRunnable;
    }

    // The file set here will be the name of the file that perfetto creates regardless of the
    // type of profiling that is being done.
    public void setFileName(String fileName) {
        mFileName = fileName;
    }

    public void setRedactedFileName(String fileName) {
        mRedactedFileName = fileName;
    }

    public void setRedactionStartTimeMs(long startTime) {
        mRedactionStartTimeMs = startTime;
    }

    public void setRetryCount(int retryCount) {
        mRetryCount = retryCount;
    }

    /**
     * Do not call directly!
     * State should only be updated with {@link ProfilingService#advanceStateAndContinue}.
     */
    public void setState(TracingState state) {
        mState = state;
    }

    /** Increase retry count by 1 */
    public void incrementRetryCount() {
        mRetryCount += 1;
    }

    public void setProfilingStartTimeMs(long startTime)  {
        mProfilingStartTimeMs = startTime;
    }

    /**
     * Update error status. Also overrides error message to null as the two fields must be set
     * together to ensure they make sense.
     */
    public void setError(int status) {
        setError(status, null);
    }

    /** Update error status and message. */
    public void setError(int status, String message) {
        mErrorStatus = status;
        mErrorMessage = message;
    }

    @Nullable
    public Process getActiveTrace() {
        return mActiveTrace;
    }

    @Nullable
    public Process getActiveRedaction() {
        return mActiveRedaction;
    }

    @Nullable
    public Runnable getProcessResultRunnable() {
        return mProcessResultRunnable;
    }

    public int getProfilingType() {
        return mProfilingType;
    }

    public int getUid() {
        return mUid;
    }

    @NonNull
    public String getPackageName() {
        return mPackageName;
    }

    @Nullable
    public String getTag() {
        return mTag;
    }

    public long getKeyMostSigBits() {
        return mKeyMostSigBits;
    }

    public long getKeyLeastSigBits() {
        return mKeyLeastSigBits;
    }

    // This returns the name of the file that perfetto created during profiling. If the profiling
    // type was a trace collection it will return the unredacted trace file name.
    @Nullable
    public String getFileName() {
        return mFileName;
    }

    @Nullable
    public String getRedactedFileName() {
        return mRedactedFileName;
    }

    public long getRedactionStartTimeMs() {
        return mRedactionStartTimeMs;
    }

    public long getProfilingStartTimeMs() {
        return mProfilingStartTimeMs;
    }

    /**
     * Returns the relative path starting from apps storage dir including name of the file being
     * returned to the client.
     * @param appRelativePath relative path to app storage.
     * @return relative file path and name of file.
     */
    @Nullable
    public String getDestinationFileName(String appRelativePath) {
        if (mFileName == null) {
            return null;
        }
        if (mDestinationFileName == null) {
            mDestinationFileName = appRelativePath
                    + ((this.getRedactedFileName() == null) ? mFileName : mRedactedFileName);
        }
        return mDestinationFileName;
    }

    @NonNull
    public TracingState getState() {
        return mState;
    }

    public int getRetryCount() {
        return mRetryCount;
    }

    @Nullable
    public String getErrorMessage() {
        return mErrorMessage;
    }

    public int getErrorStatus() {
        return mErrorStatus;
    }

    public int getTriggerType() {
        return mTriggerType;
    }

    // LINT.IfChange(to_proto)
    /** Convert this session to a proto for persisting. */
    public QueuedResultsWrapper.TracingSession toProto() {
        QueuedResultsWrapper.TracingSession.Builder tracingSessionBuilder =
                QueuedResultsWrapper.TracingSession.newBuilder();

        tracingSessionBuilder.setProfilingType(mProfilingType);
        tracingSessionBuilder.setUid(mUid);
        tracingSessionBuilder.setPackageName(mPackageName);
        if (mTag != null) {
            tracingSessionBuilder.setTag(mTag);
        }
        tracingSessionBuilder.setKeyMostSigBits(mKeyMostSigBits);
        tracingSessionBuilder.setKeyLeastSigBits(mKeyLeastSigBits);
        if (mFileName != null) {
            tracingSessionBuilder.setFileName(mFileName);
        }
        if (mRedactedFileName != null) {
            tracingSessionBuilder.setRedactedFileName(mRedactedFileName);
        }
        tracingSessionBuilder.setTracingState(mState.getValue());
        tracingSessionBuilder.setRetryCount(mRetryCount);
        if (mErrorMessage != null) {
            tracingSessionBuilder.setErrorMessage(mErrorMessage);
        }
        tracingSessionBuilder.setErrorStatus(mErrorStatus);
        tracingSessionBuilder.setTriggerType(mTriggerType);

        return tracingSessionBuilder.build();
    }
    // LINT.ThenChange(/tests/cts/src/android/profiling/cts/ProfilingServiceTests.java:equals)
}
