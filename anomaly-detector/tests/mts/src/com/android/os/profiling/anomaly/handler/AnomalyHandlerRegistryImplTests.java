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

import android.content.Context;

import androidx.test.runner.AndroidJUnit4;

import com.android.os.profiling.anomaly.core.AnomalyAction;
import com.android.os.profiling.anomaly.internal.AnomalyHandlerRegistryImpl;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/** Tests for {@link AnomalyHandlerRegistryImpl}. */
@RunWith(AndroidJUnit4.class)
public final class AnomalyHandlerRegistryImplTests {
    private static final int UNREGISTERED_ACTION = 999;

    @Rule public MockitoRule mMockitoRule = MockitoJUnit.rule();

    @Mock private Context mMockContext;

    private AnomalyHandlerRegistryImpl mRegistry;

    @Before
    public void setUp() {
        mRegistry = new AnomalyHandlerRegistryImpl(mMockContext);
    }

    @Test
    public void constructor_registersDefaultHandlers() {
        assertThat(mRegistry.getHandler(AnomalyAction.ACTION_LOG))
                .isInstanceOf(LogAnomalyHandler.class);
        assertThat(mRegistry.getHandler(AnomalyAction.ACTION_KILL))
                .isInstanceOf(KillAnomalyHandler.class);
    }

    @Test
    public void getHandler_unregisteredAction_returnsNull() {
        assertThat(mRegistry.getHandler(UNREGISTERED_ACTION)).isNull();
    }
}
