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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.os.Binder;
import android.os.IProfilingService;
import android.os.OutcomeReceiver;
import android.os.ProfilingRequest;
import android.os.ProfilingResult;
import android.os.IProfilingResultCallback;
import android.os.RemoteException;
import android.util.Log;
import android.util.SparseArray;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.SystemService;

import java.io.IOException;
import java.lang.Process;
import java.lang.Exception;
import java.lang.IllegalArgumentException;
import java.lang.ProcessBuilder;
import java.lang.RuntimeException;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executor;

public class ProfilingService extends IProfilingService.Stub {
    private static final String TAG = ProfilingService.class.getSimpleName();
    private static final boolean DEBUG = false;

    private static final String TEMP_TRACE_DIR_KEY = "TMPDIR";
    private static final String TEMP_TRACE_DIR =
            "/data/local/traces/.profiling-trace-in-progress.trace";
    private static final String PERFETTO_TAG = "profiling";

    private final Context mContext;

    // uid indexed collecion of JNI callbacks for results.
    private @Nullable SparseArray<IProfilingResultCallback> mResultCallbacks = new SparseArray<>();

    @VisibleForTesting
    public ProfilingService(Context context) {
        mContext = context;
        RateLimiter.loadFromDisk();
    }

    /**
     * This method validates the request, arguments, whether the app is allowed to profile now,
     * and if so, starts the profiling.
     */
    public void requestProfiling(byte[] profilingRequestBytes, String tag,
            long keyMostSigBits, long keyLeastSigBits) {
        int uid = Binder.getCallingUid();

        // Check if we're running another trace so we don't run multiple at once.
        try {
            if (isTraceRunning()) {
                processResultCallback(uid, keyMostSigBits, keyLeastSigBits,
                    ProfilingResult.ERROR_FAILED_PROFILING_IN_PROGRESS, null, tag,
                    null);
                return;
            }
        } catch (RuntimeException e) {
            if (DEBUG) Log.d(TAG, "Perfetto error", e);
            processResultCallback(uid, keyMostSigBits, keyLeastSigBits,
                    ProfilingResult.ERROR_UNKNOWN, null, tag, "Perfetto error");
            return;
        }

        // Process the request from the byte array it was provided as.
        ProfilingRequest request;
        try {
            request = ProfilingRequest.parseFrom(profilingRequestBytes);
        } catch (Exception e) {
            if (DEBUG) Log.d(TAG, "Exception parsing request", e);
            processResultCallback(uid, keyMostSigBits, keyLeastSigBits,
                    ProfilingResult.ERROR_FAILED_INVALID_REQUEST, null, tag,
                    "Request parsing failed");
            return;
        }

        // Get package name for requesting process. We can't request the trace without it.
        String packageName = mContext.getPackageManager().getNameForUid(uid);
        if (packageName == null) {
            if (DEBUG) Log.d(TAG, "Could not get package name for UID: " + uid);
            processResultCallback(uid, keyMostSigBits, keyLeastSigBits,
                    ProfilingResult.ERROR_UNKNOWN, null, tag, "Couldn't determine package name");
            return;
        }

        // Check with rate limiter if this request is allowed.
        final int status = RateLimiter.isProfilingRequestAllowed(Binder.getCallingUid(), request);
        if (status == RateLimiter.RATE_LIMIT_RESULT_ALLOWED) {
            // Rate limiter approved, try to start the request.
            try {
                startProfiling(Configs.generateConfigForRequest(request, packageName), uid,
                        packageName, keyMostSigBits, keyLeastSigBits, tag);
            } catch (IllegalArgumentException e) {
                // Issue with the request. Apps fault.
                if (DEBUG) Log.d(TAG, "Invalid request", e);
                processResultCallback(uid, keyMostSigBits, keyLeastSigBits,
                        ProfilingResult.ERROR_FAILED_INVALID_REQUEST, null, tag, e.getMessage());
                return;
            } catch (RuntimeException e) {
                // Perfetto error. Systems fault.
                if (DEBUG) Log.d(TAG, "Perfetto error", e);
                processResultCallback(uid, keyMostSigBits, keyLeastSigBits,
                        ProfilingResult.ERROR_UNKNOWN, null, tag, "Perfetto error");
                return;
            }
        } else {
            // Rate limiter denied, notify caller.
            if (DEBUG) Log.d(TAG, "Request denied with status: " + status);
            processResultCallback(uid, keyMostSigBits, keyLeastSigBits,
                    RateLimiter.statusToResult(status), null, tag, null);
        }
    }

    public void registerResultsCallback(IProfilingResultCallback callback) {
        mResultCallbacks.put(Binder.getCallingUid(), callback);
    }

    public void requestCancel(long keyMostSigBits, long keyLeastSigBits) {
        // TODO: b/293957254
    }

    private void processResultCallback(int uid, long keyMostSigBits, long keyLeastSigBits,
            int status, @Nullable String filePath, @Nullable String tag, @Nullable String error) {
        if (!mResultCallbacks.contains(uid)) {
            // No callback, nowhere to notify with result or this failure.
            if (DEBUG) Log.d(TAG, "No callback to ProfilingManager, callback dropped.");
            return;
        }
        try {
            mResultCallbacks.get(uid).sendResult(keyMostSigBits, keyLeastSigBits, status, filePath,
                    tag, error);
        } catch (RemoteException e) {
            // Failed to send result. Ignore.
            if (DEBUG) Log.d(TAG, "Exception processing result callback", e);
        }
    }

    private void startProfiling(String config, int uid, String packageName, long keyMostSigBits,
            long keyLeastSigBits, @Nullable String tag) throws RuntimeException {
        try {
            ProcessBuilder pb = new ProcessBuilder("/system/bin/perfetto", "--detach="
                    + PERFETTO_TAG  + " -o " + TEMP_TRACE_DIR_KEY
                    + " -c - --txt <<  PERFETTO_ARGUMENTS\n" + config + "\nPERFETTO_ARGUMENTS");
            // Set temp directory for result
            pb.environment().put(TEMP_TRACE_DIR_KEY, TEMP_TRACE_DIR);
            Process process = pb.start();
            if (!process.waitFor(10000 /* TODO b/324885858 make configureable */,
                    TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                processResultCallback(uid, keyMostSigBits, keyLeastSigBits,
                        ProfilingResult.ERROR_FAILED_EXECUTING, null, tag, "perfetto timeout");
                return;
            }
            if (process.exitValue() != 0) {
                processResultCallback(uid, keyMostSigBits, keyLeastSigBits,
                        ProfilingResult.ERROR_FAILED_EXECUTING, null, tag,
                        "perfetto exitvalue: " + process.exitValue());
                return;
            }
        } catch (Exception e) {
            processResultCallback(uid, keyMostSigBits, keyLeastSigBits,
                    ProfilingResult.ERROR_FAILED_EXECUTING, null, tag, null);
            throw new RuntimeException(e);
        }
        // If we got here then the trace has been started successfully.
        spawnRedactionProcess(uid, packageName, keyMostSigBits, keyLeastSigBits, tag);
    }

    public void stopProfiling() throws RuntimeException {
        if (!isTraceRunning()) {
            // No trace running, nothing to stop.
            if (DEBUG) Log.d(TAG, "Exited stopTrace without stopping due to no trace running.");
           return;
        }

        try {
            Process process = new ProcessBuilder("/system/bin/perfetto", "--stop", "--attach="
                    + PERFETTO_TAG).start();
            if (process.waitFor() != 0) {
                // failed to stop
                if (DEBUG) Log.d(TAG, "Failed to stop trace");
            }
            // TODO: b/293957254 spawn redaction if results available
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public boolean isTraceRunning() throws RuntimeException {
        try {
            Process process = new ProcessBuilder("/system/bin/perfetto", "--is_detached="
                    + PERFETTO_TAG).start();
            int result = process.waitFor();
            if (result == 0) {
                // running
                if (DEBUG) Log.d(TAG, "A trace is currently running");
                return true;
            } else if (result == 2) {
                // not running
                if (DEBUG) Log.d(TAG, "A trace is not currently running");
                return false;
            } else {
                throw new RuntimeException("Perfetto error: " + result);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void spawnRedactionProcess(int uid, String packageName, long keyMostSigBits,
            long keyLeastSigBits, String tag) {
        // todo: start redaction in its own process.
        processResultCallback(uid, keyMostSigBits, keyLeastSigBits, ProfilingResult.ERROR_NONE,
                null, tag, null);
    }

    public static final class Lifecycle extends SystemService {
        final ProfilingService mService;

        public Lifecycle(Context context) {
            this(context, new ProfilingService(context));
        }

        @VisibleForTesting
        public Lifecycle(Context context, ProfilingService service) {
            super(context);
            mService = service;
        }

        @Override
        public void onStart() {
            try {
                publishBinderService("profiling_service", mService);
            } catch (Exception e) {
                if (DEBUG) Log.d(TAG, "Failed to publish service", e);
            }
        }

        @Override
        public void onBootPhase(int phase) {
            super.onBootPhase(phase);
        }
    }
}
