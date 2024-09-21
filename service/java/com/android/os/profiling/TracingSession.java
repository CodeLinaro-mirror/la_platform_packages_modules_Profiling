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

import android.os.Bundle;

import java.util.UUID;

/**
 * Represents a single in progress tracing session and all necessary data to manage and process it.
 */
public final class TracingSession {

    private Process mActiveTrace;
    private Process mActiveRedaction;
    private Runnable mProcessResultRunnable;
    private final int mProfilingType;
    private final Bundle mParams;
    private final String mAppFilePath;
    private final int mUid;
    private final String mPackageName;
    private final String mTag;
    private final long mKeyMostSigBits;
    private final long mKeyLeastSigBits;
    private String mKey = null;
    private String mFileName;
    private String mDestinationFileName = null;
    private String mRedactedFileName = null;
    private long mRedactionStartTimeMs;
    private TracingState mState;
    private int mRetryCount = 0;
    private long mProfilingStartTimeMs;
    private int mMaxProfilingTimeAllowedMs = 0;
    private String mErrorMessage = null;

    // Expected to be populated with ProfilingResult.ERROR_* values.
    private int mErrorStatus = -1; // Default to invalid value.

    public TracingSession(int profilingType, Bundle params, String appFilePath, int uid,
                String packageName, String tag, long keyMostSigBits, long keyLeastSigBits) {
        mProfilingType = profilingType;
        mParams = params;
        mAppFilePath = appFilePath;
        mUid = uid;
        mPackageName = packageName;
        mTag = tag;
        mKeyMostSigBits = keyMostSigBits;
        mKeyLeastSigBits = keyLeastSigBits;
        mState = TracingState.REQUESTED;
    }

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

    public Process getActiveTrace() {
        return mActiveTrace;
    }

    public Process getActiveRedaction() {
        return mActiveRedaction;
    }

    public Runnable getProcessResultRunnable() {
        return mProcessResultRunnable;
    }

    public int getProfilingType() {
        return mProfilingType;
    }

    public String getAppFilePath() {
        return mAppFilePath;
    }

    public int getUid() {
        return mUid;
    }

    public String getPackageName() {
        return mPackageName;
    }

    public String getTag() {
        return mTag;
    }

    public long getKeyMostSigBits() {
        return mKeyMostSigBits;
    }

    public long getKeyLeastSigBits() {
        return mKeyLeastSigBits;
    }

    // This returns the name of the file that perfetto created during profiling.  If the profling
    // type was a trace collection it will return the unredacted trace file name.
    public String getFileName() {
        return mFileName;
    }

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
     * Returns the full path including name of the file being returned to the client.
     * @param appRelativePath relative path to app storage.
     * @return full file path and name of file.
     */
    public String getDestinationFileName(String appRelativePath) {
        if (mFileName == null) {
            return null;
        }
        if (mDestinationFileName == null) {
            mDestinationFileName = mAppFilePath + appRelativePath
                    + ((this.getRedactedFileName() == null) ? mFileName : mRedactedFileName);
        }
        return mDestinationFileName;
    }

    public TracingState getState() {
        return mState;
    }

    public int getRetryCount() {
        return mRetryCount;
    }

    public String getErrorMessage() {
        return mErrorMessage;
    }

    public int getErrorStatus() {
        return mErrorStatus;
    }
}
