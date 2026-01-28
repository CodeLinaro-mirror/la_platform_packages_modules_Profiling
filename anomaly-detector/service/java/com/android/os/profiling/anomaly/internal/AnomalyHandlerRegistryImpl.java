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

import android.os.profiling.anomaly.RuleInternal;
import android.os.profiling.anomaly.RuleInternal.AnomalyActionTypeInternal;
import android.util.SparseArray;

import com.android.os.profiling.anomaly.core.AnomalyHandler;
import com.android.os.profiling.anomaly.core.AnomalyHandlerRegistry;
import com.android.os.profiling.anomaly.handler.LogAnomalyHandler;

/**
 * A registry for mapping action types to their handlers.
 *
 * @hide
 */
public final class AnomalyHandlerRegistryImpl implements AnomalyHandlerRegistry {
    private final SparseArray<AnomalyHandler> mHandlers = new SparseArray<>();

    public AnomalyHandlerRegistryImpl() {
        register(RuleInternal.ACTION_TYPE_LOG, new LogAnomalyHandler());
    }

    /** Registers a handler for a given action type. */
    private void register(@AnomalyActionTypeInternal int action, AnomalyHandler handler) {
        mHandlers.put(action, handler);
    }

    /** Returns the handler for a given action type. */
    @Override
    public AnomalyHandler getHandler(@AnomalyActionTypeInternal int action) {
        return mHandlers.get(action);
    }
}
