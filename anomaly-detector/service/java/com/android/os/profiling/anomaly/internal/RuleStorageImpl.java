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

package com.android.os.profiling.anomaly.internal;

import android.os.OutcomeReceiver;

import com.android.os.profiling.anomaly.core.Rule;
import com.android.os.profiling.anomaly.core.RuleStorage;

import java.util.Set;
import java.util.concurrent.Executor;

/**
 * A stub implementation of {@link RuleStorage}.
 *
 * <p>This implementation does not perform any actual disk I/O. It immediately returns an empty set
 * for load operations and an immediate success for save operations.
 *
 * <p>TODO(b/435257657) Replace with real implementation.
 *
 * @hide
 */
public final class RuleStorageImpl implements RuleStorage {
    /** {@inheritDoc} */
    @Override
    public void load(Executor executor, OutcomeReceiver<Set<Rule<?>>, Throwable> callback) {
        executor.execute(() -> callback.onResult(Set.of()));
    }

    /** {@inheritDoc} */
    @Override
    public void save(
            Set<Rule<?>> rules, Executor executor, OutcomeReceiver<Void, Throwable> callback) {
        executor.execute(() -> callback.onResult(null));
    }
}
