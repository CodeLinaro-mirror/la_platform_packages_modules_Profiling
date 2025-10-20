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

import static android.profiling.cts.ProfilingTestConstants.ACTION_INIT_AND_ADD_APP_FULLY_DRAWN_TRIGGER;
import static android.profiling.cts.ProfilingTestConstants.ACTION_KEY;
import static android.profiling.cts.ProfilingTestConstants.ACTION_REGISTER_AND_REPORT_FULLY_DRAWN;
import static android.profiling.cts.ProfilingTestConstants.FILE_VALIDATION_RESULT_FILE_DOES_NOT_EXIST;
import static android.profiling.cts.ProfilingTestConstants.FILE_VALIDATION_RESULT_FILE_EMPTY;
import static android.profiling.cts.ProfilingTestConstants.FILE_VALIDATION_RESULT_FILE_PATH_EMPTY;
import static android.profiling.cts.ProfilingTestConstants.FILE_VALIDATION_RESULT_NONE;
import static android.profiling.cts.ProfilingTestConstants.FILE_VALIDATION_RESULT_SUCCESS;
import static android.profiling.cts.profilingapp.ProfilingAppUtils.reply;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.os.ProfilingManager;
import android.os.ProfilingResult;
import android.os.ProfilingTrigger;
import android.util.Log;

import java.io.File;
import java.util.Collections;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public class ProfilingTriggerTestActivity extends Activity {
    private static final String TAG = ProfilingTriggerTestActivity.class.getSimpleName();

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
            default -> {
                Log.e(TAG, "Unknown action: " + action);
                finish();
            }
        }
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

    private static class AppCallback implements Consumer<ProfilingResult> {
        private final Context mContext;

        AppCallback(Context context) {
            mContext = context;
        }

        @Override
        public void accept(ProfilingResult result) {
            Log.d(TAG, "Profiling result received: " + result.toString());

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
