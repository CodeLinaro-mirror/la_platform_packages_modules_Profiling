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

public final class ProfilingTestConstants {
    public static final String REPLY_ACTION_COMPLETE =
            "com.android.cts.profilingapp.ACTION_COMPLETE";
    public static final String REPLY_EXTRA_PROFILING_RESULT = "profiling_result";
    public static final String REPLY_EXTRA_FILE_VALIDATION_RESULT = "file_validation_result";

    public static final String ACTION_KEY = "action";
    public static final int ACTION_INIT_AND_ADD_APP_FULLY_DRAWN_TRIGGER = 1;
    public static final int ACTION_REGISTER_AND_REPORT_FULLY_DRAWN = 2;
    public static final int ACTION_INIT_AND_ADD_ANOMALY_TRIGGER = 3;
    public static final int ACTION_REGISTER_AND_ALLOCATE_MEMORY = 4;

    public static final int FILE_VALIDATION_RESULT_NONE = -1;
    public static final int FILE_VALIDATION_RESULT_SUCCESS = 0;
    public static final int FILE_VALIDATION_RESULT_FILE_PATH_EMPTY = 1;
    public static final int FILE_VALIDATION_RESULT_FILE_DOES_NOT_EXIST = 2;
    public static final int FILE_VALIDATION_RESULT_FILE_EMPTY = 3;
}
