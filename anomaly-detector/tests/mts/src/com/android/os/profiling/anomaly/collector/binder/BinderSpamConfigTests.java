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

package com.android.os.profiling.anomaly.collector.binder;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;

import android.os.profiling.anomaly.flags.Flags;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Tests for the {@link BinderSpamConfig} value object.
 */
@RunWith(AndroidJUnit4.class)
@RequiresFlagsEnabled(Flags.FLAG_ANOMALY_DETECTOR_CORE)
public class BinderSpamConfigTests {
    private static final String TEST_INTERFACE_NAME = "com.test.service";
    private static final String TEST_METHOD_NAME = "method";

    private static final BinderSpamConfig BINDER_SPAM_CONFIG = new BinderSpamConfig.Builder()
                    .setInterfaceName(TEST_INTERFACE_NAME)
                    .setMethodName(TEST_METHOD_NAME)
                    .build();
    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    @Test
    public void builder_withInvalidInterfaceName_throwException() {
        assertThrows("Interface and method name must be set!", IllegalArgumentException.class,
                () -> new BinderSpamConfig.Builder()
                        .setInterfaceName("")
                        .setMethodName(TEST_METHOD_NAME)
                        .build());
    }

    @Test
    public void builder_withInvalidMethodName_throwException() {
        assertThrows("Interface and method name must be set!", IllegalArgumentException.class,
                () -> new BinderSpamConfig.Builder()
                        .setInterfaceName(TEST_INTERFACE_NAME)
                        .setMethodName("")
                        .build());
    }

    @Test
    public void getAidlInterface_returnAidlInterface() {
        assertThat(BINDER_SPAM_CONFIG.getInterfaceName()).isEqualTo(TEST_INTERFACE_NAME);
    }

    @Test
    public void getAidlMethod_returnAidlMethod() {
        assertThat(BINDER_SPAM_CONFIG.getMethodName()).isEqualTo(TEST_METHOD_NAME);
    }
}
