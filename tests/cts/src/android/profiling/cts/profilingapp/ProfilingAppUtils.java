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

import static android.profiling.cts.ProfilingTestConstants.REPLY_ACTION_COMPLETE;
import static android.profiling.cts.ProfilingTestConstants.REPLY_EXTRA_FILE_VALIDATION_RESULT;
import static android.profiling.cts.ProfilingTestConstants.REPLY_EXTRA_PROFILING_RESULT;

import android.content.Context;
import android.content.Intent;
import android.os.ProfilingResult;
import android.util.Log;

public final class ProfilingAppUtils {
    private static final String TAG = ProfilingAppUtils.class.getSimpleName();

    /** Replies to the caller with the given profiling result. */
    public static void reply(Context context, ProfilingResult result, int fileValidationResult) {
        Intent intent = new Intent();
        intent.setAction(REPLY_ACTION_COMPLETE);
        intent.putExtra(REPLY_EXTRA_PROFILING_RESULT, result);
        intent.putExtra(REPLY_EXTRA_FILE_VALIDATION_RESULT, fileValidationResult);
        context.sendBroadcast(intent);
        Log.d(TAG, "Sent broadcast: " + REPLY_ACTION_COMPLETE);
    }
}
