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

package android.profiling.cts.profilingapp;

import static android.profiling.cts.ProfilingTestConstants.ACTION_INIT_AND_ADD_ANOMALY_TRIGGER;
import static android.profiling.cts.ProfilingTestConstants.ACTION_INIT_AND_ADD_APP_FULLY_DRAWN_TRIGGER;
import static android.profiling.cts.ProfilingTestConstants.ACTION_KEY;
import static android.profiling.cts.ProfilingTestConstants.ACTION_REGISTER_AND_ALLOCATE_MEMORY;
import static android.profiling.cts.ProfilingTestConstants.ACTION_REGISTER_AND_REPORT_FULLY_DRAWN;
import static android.profiling.cts.ProfilingTestConstants.ACTION_REGISTER_ANR_CALLBACK;
import static android.profiling.cts.ProfilingTestConstants.ACTION_SETUP_PROFILING_TRIGGER_AND_TRIGGER_ANR;
import static android.profiling.cts.ProfilingTestConstants.FILE_VALIDATION_RESULT_FILE_DOES_NOT_EXIST;
import static android.profiling.cts.ProfilingTestConstants.FILE_VALIDATION_RESULT_FILE_EMPTY;
import static android.profiling.cts.ProfilingTestConstants.FILE_VALIDATION_RESULT_FILE_PATH_EMPTY;
import static android.profiling.cts.ProfilingTestConstants.FILE_VALIDATION_RESULT_NONE;
import static android.profiling.cts.ProfilingTestConstants.FILE_VALIDATION_RESULT_SUCCESS;
import static android.profiling.cts.profilingapp.ProfilingAppUtils.reply;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.ProfilingManager;
import android.os.ProfilingResult;
import android.os.ProfilingTrigger;
import android.os.SharedMemory;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.util.Log;
import android.view.WindowManager;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public class ProfilingTriggerTestActivity extends Activity {
    private static final String TAG = ProfilingTriggerTestActivity.class.getSimpleName();
    private static final String ACTION_ANR = "action_anr";

    private final List<ByteBuffer> mAllocations = new ArrayList<>();
    private volatile boolean mStopAllocation = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        int action = getIntent().getIntExtra(ACTION_KEY, -1);
        if (action == -1) {
            Log.e(TAG, "Action is null, finishing onCreate");
            return;
        }

        switch (action) {
            case ACTION_INIT_AND_ADD_APP_FULLY_DRAWN_TRIGGER -> initAndAddAppFullyDrawnTrigger();
            case ACTION_REGISTER_AND_REPORT_FULLY_DRAWN -> registerAndReportFullyDrawn();
            case ACTION_INIT_AND_ADD_ANOMALY_TRIGGER -> initAndAddAnomalyTrigger();
            case ACTION_REGISTER_AND_ALLOCATE_MEMORY -> registerAndAllocateMemory();
            case ACTION_SETUP_PROFILING_TRIGGER_AND_TRIGGER_ANR ->
                    setupProfilingTriggersAndTriggerAnr();
            case ACTION_REGISTER_ANR_CALLBACK -> registerAnrCallback();
            default -> {
                Log.e(TAG, "Unknown action: " + action);
                finish();
            }
        }
    }

    /** Clears all profiling triggers and adds an anomaly profiling trigger. */
    private void initAndAddAnomalyTrigger() {
        ProfilingManager profilingManager = getSystemService(ProfilingManager.class);
        profilingManager.clearProfilingTriggers();
        ProfilingTrigger trigger =
                new ProfilingTrigger.Builder(ProfilingTrigger.TRIGGER_TYPE_ANOMALY).build();
        profilingManager.addProfilingTriggers(Collections.singletonList(trigger));
    }

    /** Registers for profiling results and allocates memory. */
    private void registerAndAllocateMemory() {
        Log.i(TAG, "registerAndAllocateMemory starting");
        ProfilingManager profilingManager = getSystemService(ProfilingManager.class);

        profilingManager.registerForAllProfilingResults(
                Executors.newSingleThreadExecutor(), new AppCallback(this));

        new Thread(
                        () -> {
                            // Fetch total memory to calculate a safe allocation target.
                            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
                            ActivityManager am = getSystemService(ActivityManager.class);
                            am.getMemoryInfo(mi);

                            // The test sets a 5% limit via 'am memory-limiter manual'.
                            // We allocate 10% of total RAM to ensure we exceed that limit.
                            long targetAllocation = mi.totalMem / 10;
                            int chunkSize = 50 * 1024 * 1024; // 50MB chunks

                            Log.i(
                                    TAG,
                                    "Total RAM: "
                                            + mi.totalMem
                                            + ", Target allocation: "
                                            + targetAllocation);

                            long allocated = 0;
                            try {
                                while (allocated < targetAllocation && !mStopAllocation) {
                                    Log.i(TAG, "Allocating chunk " + (allocated / chunkSize + 1));
                                    try {
                                        SharedMemory mem = SharedMemory.create(null, chunkSize);
                                        ByteBuffer chunk = mem.mapReadWrite();
                                        // Make sure the memory is actually allocated and not just
                                        // lazy-paged.
                                        for (int i = 0; i < chunkSize; i += 4096) {
                                            chunk.put(i, (byte) 1);
                                        }
                                        mAllocations.add(chunk);
                                    } catch (ErrnoException e) {
                                        Log.e(TAG, "SharedMemory allocation failed", e);
                                        break;
                                    }

                                    allocated += chunkSize;
                                    Log.i(TAG, "Allocated total: " + allocated);

                                    // Wait a bit to allow the system to detect and callback
                                    try {
                                        Thread.sleep(1000);
                                    } catch (InterruptedException e) {
                                        Thread.currentThread().interrupt();
                                        break;
                                    }
                                }
                                Log.i(
                                        TAG,
                                        "Allocation loop finished. StopAllocation: "
                                                + mStopAllocation);
                            } catch (OutOfMemoryError e) {
                                Log.e(
                                        TAG,
                                        "Caught OutOfMemoryError after allocating " + allocated,
                                        e);
                            }
                        })
                .start();
    }

    /** Clears all profiling triggers and adds an app fully drawn profiling trigger. */
    private void initAndAddAppFullyDrawnTrigger() {
        ProfilingManager profilingManager = getSystemService(ProfilingManager.class);
        profilingManager.clearProfilingTriggers();
        ProfilingTrigger trigger =
                new ProfilingTrigger.Builder(ProfilingTrigger.TRIGGER_TYPE_APP_FULLY_DRAWN).build();
        profilingManager.addProfilingTriggers(Collections.singletonList(trigger));
    }

    /** Registers for profiling results and reports that the app is fully drawn. */
    private void registerAndReportFullyDrawn() {
        ProfilingManager profilingManager = getSystemService(ProfilingManager.class);
        profilingManager.registerForAllProfilingResults(
                Executors.newSingleThreadExecutor(), new AppCallback(this));
        reportFullyDrawn();
    }

    /** Triggers an ANR by registering a broadcast receiver that enters an infinite loop. */
    private void triggerAnr() {
        registerReceiver(
                new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        Log.d(TAG, "Received broadcast: " + intent.getAction());
                        while (true) {
                            SystemClock.sleep(2);
                        }
                    }
                },
                new IntentFilter(ACTION_ANR),
                Context.RECEIVER_EXPORTED);

        getWindow()
                .addFlags(
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
    }

    /**
     * Sets up ANR profiling triggers, registers for profiling results, and then triggers an ANR.
     */
    private void setupProfilingTriggersAndTriggerAnr() {
        Log.d(TAG, "setupProfilingTriggersAndTriggerAnr");
        ProfilingManager profilingManager = getSystemService(ProfilingManager.class);
        profilingManager.clearProfilingTriggers();
        ProfilingTrigger trigger =
                new ProfilingTrigger.Builder(ProfilingTrigger.TRIGGER_TYPE_ANR).build();
        profilingManager.addProfilingTriggers(Collections.singletonList(trigger));

        triggerAnr();
    }

    /** Registers for ANR profiling trigger results. */
    private void registerAnrCallback() {
        ProfilingManager profilingManager = getSystemService(ProfilingManager.class);
        profilingManager.registerForAllProfilingResults(
                Executors.newSingleThreadExecutor(), new AppCallback(this));
    }

    private class AppCallback implements Consumer<ProfilingResult> {
        private final Context mContext;

        AppCallback(Context context) {
            mContext = context;
        }

        @Override
        public void accept(ProfilingResult result) {
            Log.d(TAG, "Profiling result received: " + result.toString());
            mStopAllocation = true;

            // Validates the result file in the test app context. The result file is stored under
            // test app's file directory and can't be accessed by host app directly. We validate the
            // file and reply the file validation result to host app using broadcast.
            int fileValidationResult = validateResultFile(result);

            reply(mContext, result, fileValidationResult);
        }

        /**
         * Validates the result file from a {@link ProfilingResult}.
         *
         * @param result The profiling result to validate.
         * @return A file validation result code.
         */
        private int validateResultFile(ProfilingResult result) {
            if (result.getErrorCode() != ProfilingResult.ERROR_NONE) {
                return FILE_VALIDATION_RESULT_NONE;
            }

            String filePath = result.getResultFilePath();
            if (filePath == null || filePath.isEmpty()) {
                return FILE_VALIDATION_RESULT_FILE_PATH_EMPTY;
            }

            File resultFile = new File(filePath);
            if (!resultFile.exists()) {
                return FILE_VALIDATION_RESULT_FILE_DOES_NOT_EXIST;
            }

            if (resultFile.length() == 0) {
                return FILE_VALIDATION_RESULT_FILE_EMPTY;
            }

            return FILE_VALIDATION_RESULT_SUCCESS;
        }
    }
}
