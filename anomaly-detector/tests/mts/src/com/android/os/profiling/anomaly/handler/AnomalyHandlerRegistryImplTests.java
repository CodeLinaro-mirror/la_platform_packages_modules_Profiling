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

package com.android.os.profiling.anomaly.handler;

import static com.google.common.truth.Truth.assertThat;

import android.os.profiling.anomaly.RuleInternal;
import android.os.profiling.anomaly.RuleInternal.AnomalyActionType;

import androidx.test.runner.AndroidJUnit4;

import com.android.os.profiling.anomaly.internal.AnomalyHandlerRegistryImpl;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests for {@link AnomalyHandlerRegistryImpl}. */
@RunWith(AndroidJUnit4.class)
public final class AnomalyHandlerRegistryImplTests {
    @AnomalyActionType private static final int UNREGISTERED_ACTION = 999;

    private AnomalyHandlerRegistryImpl mRegistry;

    @Before
    public void setUp() {
        mRegistry = new AnomalyHandlerRegistryImpl();
    }

    @Test
    public void constructor_registersDefaultHandlers() {
        assertThat(mRegistry.getHandler(RuleInternal.ACTION_TYPE_LOG))
                .isInstanceOf(LogAnomalyHandler.class);
    }

    @Test
    public void getHandler_unregisteredAction_returnsNull() {
        assertThat(mRegistry.getHandler(UNREGISTERED_ACTION)).isNull();
    }
}
