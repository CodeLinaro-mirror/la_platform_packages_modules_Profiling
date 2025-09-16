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

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.verify;

import android.os.OutcomeReceiver;
import android.os.profiling.anomaly.flags.Flags;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.Executor;

/** Tests for {@link RuleStorageImpl}. */
@RunWith(AndroidJUnit4.class)
@RequiresFlagsEnabled(Flags.FLAG_ANOMALY_DETECTOR_CORE)
public final class RuleStorageImplTests {

    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    @Rule public MockitoRule mMockitoRule = MockitoJUnit.rule();

    @Mock
    private OutcomeReceiver<Set<com.android.os.profiling.anomaly.core.Rule<?>>, Throwable>
            mLoadCallback;

    @Mock private OutcomeReceiver<Void, Throwable> mSaveCallback;

    @Captor
    private ArgumentCaptor<Set<com.android.os.profiling.anomaly.core.Rule<?>>> mRuleSetCaptor;

    private RuleStorageImpl mRuleStorage;
    private Executor mExecutor;

    @Before
    public void setUp() {
        mRuleStorage = new RuleStorageImpl();
        mExecutor = Runnable::run; // Direct executor
    }

    @Test
    public void load_returnsEmptySet() {
        mRuleStorage.load(mExecutor, mLoadCallback);

        verify(mLoadCallback).onResult(mRuleSetCaptor.capture());
        assertThat(mRuleSetCaptor.getValue()).isNotNull();
        assertThat(mRuleSetCaptor.getValue()).isEmpty();
    }

    @Test
    public void save_completesSuccessfully() {
        Set<com.android.os.profiling.anomaly.core.Rule<?>> rulesToSave = Collections.emptySet();
        mRuleStorage.save(rulesToSave, mExecutor, mSaveCallback);

        verify(mSaveCallback).onResult(null);
    }
}
