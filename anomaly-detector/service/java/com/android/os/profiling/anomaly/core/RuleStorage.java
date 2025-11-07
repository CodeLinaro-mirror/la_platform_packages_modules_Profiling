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

import android.os.OutcomeReceiver;
import android.os.profiling.anomaly.Rule;

import java.util.Set;
import java.util.concurrent.Executor;

/**
 * Interface for asynchronous local disk storage of rules.
 *
 * @hide
 */
public interface RuleStorage {

    /**
     * Loads a set of rules from storage.
     *
     * @param executor The executor to use for executing the callback.
     * @param callback The callback to receive the loaded rules or an error.
     */
    void load(Executor executor, OutcomeReceiver<Set<Rule>, Throwable> callback);

    /**
     * Saves a set of rules to storage.
     *
     * @param rules The set of rules to save.
     * @param executor The executor to use for executing the callback.
     * @param callback The callback to be notified when the save operation is complete or if an
     *     error occurs.
     */
    void save(Set<Rule> rules, Executor executor, OutcomeReceiver<Void, Throwable> callback);
}
