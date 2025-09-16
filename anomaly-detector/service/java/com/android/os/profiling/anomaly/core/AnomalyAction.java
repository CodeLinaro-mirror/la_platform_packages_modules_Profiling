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

package com.android.os.profiling.anomaly.core;

import android.annotation.IntDef;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Defines the set of actions that can be performed when an anomaly is detected.
 *
 * @hide
 */
public final class AnomalyAction {
    private AnomalyAction() {}

    /** Action to write a detailed report of the anomaly to the system log. */
    public static final int ACTION_LOG = 1;

    /** Action to kill the offending application process. */
    public static final int ACTION_KILL = 2;

    @IntDef(
            prefix = {"ACTION_"},
            value = {
                ACTION_LOG,
                ACTION_KILL,
            })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Action {}
}
