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

import android.os.profiling.anomaly.Rule;

import java.util.Set;

/**
 * Controller for the anomaly detector.
 *
 * @hide
 */
public interface AnomalyDetectorController {
    /**
     * Sets the rules to be used for anomaly detection.
     *
     * @param rules A set of rules to be used for anomaly detection.
     */
    void setRules(Set<Rule> rules);

    /**
     * Called when the system services are ready.
     *
     * <p>This triggers the initial loading of rules from storage.
     */
    void onSystemServicesReady();
}
