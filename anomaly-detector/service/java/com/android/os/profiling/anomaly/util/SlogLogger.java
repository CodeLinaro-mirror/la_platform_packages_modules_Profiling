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
import android.util.Slog;

/**
 * A real implementation of the Logger interface that uses Slog.
 *
 * @hide
 */
class SlogLogger implements Logger {
    @Override
    public int d(@NonNull String tag, @NonNull String msg) {
        return Slog.d(tag, msg);
    }

    @Override
    public int d(@NonNull String tag, @NonNull String msg, @Nullable Throwable tr) {
        return Slog.d(tag, msg, tr);
    }

    @Override
    public int w(@NonNull String tag, @NonNull String msg) {
        return Slog.w(tag, msg);
    }

    @Override
    public int w(@NonNull String tag, @NonNull String msg, @Nullable Throwable tr) {
        return Slog.w(tag, msg, tr);
    }

    @Override
    public int w(@NonNull String tag, @Nullable Throwable tr) {
        return Slog.w(tag, tr);
    }

    @Override
    public int e(@NonNull String tag, @NonNull String msg) {
        return Slog.e(tag, msg);
    }

    @Override
    public int e(@NonNull String tag, @NonNull String msg, @Nullable Throwable tr) {
        return Slog.e(tag, msg, tr);
    }

    @Override
    public int i(@NonNull String tag, @NonNull String msg) {
        return Slog.i(tag, msg);
    }

    @Override
    public int i(@NonNull String tag, @NonNull String msg, @Nullable Throwable tr) {
        return Slog.i(tag, msg, tr);
    }

    @Override
    public int v(@NonNull String tag, @NonNull String msg) {
        return Slog.v(tag, msg);
    }

    @Override
    public int v(@NonNull String tag, @NonNull String msg, @Nullable Throwable tr) {
        return Slog.v(tag, msg, tr);
    }
}
