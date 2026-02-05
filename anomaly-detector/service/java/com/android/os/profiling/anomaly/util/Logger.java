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

package com.android.os.profiling.anomaly.util;

import android.annotation.NonNull;
import android.annotation.Nullable;

/**
 * An interface to abstract the action of logging.
 *
 * @hide
 */
interface Logger {
    /** Send a {@link android.util.Log#DEBUG} log message. */
    int d(@NonNull String tag, @NonNull String msg);

    /** Send a {@link android.util.Log#DEBUG} log message and log the exception. */
    int d(@NonNull String tag, @NonNull String msg, @Nullable Throwable tr);

    /** Send a {@link android.util.Log#WARN} log message. */
    int w(@NonNull String tag, @NonNull String msg);

    /** Send a {@link android.util.Log#WARN} log message and log the exception. */
    int w(@NonNull String tag, @NonNull String msg, @Nullable Throwable tr);

    /** Send a {@link android.util.Log#WARN} log message and log the exception. */
    int w(@NonNull String tag, @Nullable Throwable tr);

    /** Send an {@link android.util.Log#ERROR} log message. */
    int e(@NonNull String tag, @NonNull String msg);

    /** Send an {@link android.util.Log#ERROR} log message and log the exception. */
    int e(@NonNull String tag, @NonNull String msg, @Nullable Throwable tr);

    /** Send an {@link android.util.Log#INFO} log message. */
    int i(@NonNull String tag, @NonNull String msg);

    /** Send an {@link android.util.Log#INFO} log message and log the exception. */
    int i(@NonNull String tag, @NonNull String msg, @Nullable Throwable tr);

    /** Send a {@link android.util.Log#VERBOSE} log message. */
    int v(@NonNull String tag, @NonNull String msg);

    /** Send a {@link android.util.Log#VERBOSE} log message and log the exception. */
    int v(@NonNull String tag, @NonNull String msg, @Nullable Throwable tr);
}
