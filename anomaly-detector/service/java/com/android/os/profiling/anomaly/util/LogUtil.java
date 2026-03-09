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

import android.os.Build;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;

/**
 * Utility class for logging. Only messages that are loggable for the given tag/log level are
 * effectively logged when using a release build.
 *
 * @hide
 */
public final class LogUtil {
    private final Logger mLogger;
    private final boolean mIsDebuggable;
    private final String mTag;

    @VisibleForTesting
    interface LoggableChecker {
        boolean isLoggable(String tag, int level);
    }

    private final LoggableChecker mLoggableChecker;

    public LogUtil(String tag) {
        this(new SlogLogger(), Build.isDebuggable(), tag, (t, level) -> Log.isLoggable(t, level));
    }

    @VisibleForTesting
    LogUtil(Logger logger, boolean isDebuggable, String tag, LoggableChecker loggableChecker) {
        mLogger = logger;
        mIsDebuggable = isDebuggable;
        mTag = tag;
        mLoggableChecker = loggableChecker;
    }

    private boolean isLoggable(int level) {
        return mIsDebuggable || mLoggableChecker.isLoggable(mTag, level);
    }

    /** Log the message as DEBUG. */
    public int d(String msg) {
        if (isLoggable(Log.DEBUG)) {
            return mLogger.d(mTag, msg);
        }
        return 0;
    }

    /** Log the message as DEBUG. */
    public int d(String msg, Throwable tr) {
        if (isLoggable(Log.DEBUG)) {
            return mLogger.d(mTag, msg, tr);
        }
        return 0;
    }

    /** Log the message as ERROR. */
    public int e(String msg) {
        if (isLoggable(Log.ERROR)) {
            return mLogger.e(mTag, msg);
        }
        return 0;
    }

    /** Log the message as ERROR. */
    public int e(String msg, Throwable tr) {
        if (isLoggable(Log.ERROR)) {
            return mLogger.e(mTag, msg, tr);
        }
        return 0;
    }

    /** Log the message as INFO. */
    public int i(String msg) {
        if (isLoggable(Log.INFO)) {
            return mLogger.i(mTag, msg);
        }
        return 0;
    }

    /** Log the message as INFO. */
    public int i(String msg, Throwable tr) {
        if (isLoggable(Log.INFO)) {
            return mLogger.i(mTag, msg, tr);
        }
        return 0;
    }

    /** Log the message as VERBOSE. */
    public int v(String msg) {
        if (isLoggable(Log.VERBOSE)) {
            return mLogger.v(mTag, msg);
        }
        return 0;
    }

    /** Log the message as VERBOSE. */
    public int v(String msg, Throwable tr) {
        if (isLoggable(Log.VERBOSE)) {
            return mLogger.v(mTag, msg, tr);
        }
        return 0;
    }

    /** Log the message as WARN. */
    public int w(String msg) {
        if (isLoggable(Log.WARN)) {
            return mLogger.w(mTag, msg);
        }
        return 0;
    }

    /** Log the message as WARN. */
    public int w(String msg, Throwable tr) {
        if (isLoggable(Log.WARN)) {
            return mLogger.w(mTag, msg, tr);
        }
        return 0;
    }

    /** Log the message as WARN. */
    public int w(Throwable tr) {
        if (isLoggable(Log.WARN)) {
            return mLogger.w(mTag, tr);
        }
        return 0;
    }
}
