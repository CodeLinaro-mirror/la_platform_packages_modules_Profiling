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

/** Tests for the {@link BinderSpamData} value object. */
@RunWith(AndroidJUnit4.class)
@RequiresFlagsEnabled(Flags.FLAG_ANOMALY_DETECTOR_CORE)
public final class BinderSpamDataTests {

    private static final int CALLING_UID = 1000;
    private static final int CALL_COUNT = 100;
    private static final String INTERFACE_NAME = "android.app.IActivityManager";
    private static final String METHOD_NAME = "startService";
    private static final long TIMESPAN_MILLIS = 3 * 1000;

    private static final BinderSpamData BINDER_SPAM_SIGNAL =
            new BinderSpamData.Builder()
                    .setCallingUid(CALLING_UID)
                    .setCallCount(CALL_COUNT)
                    .setInterfaceName(INTERFACE_NAME)
                    .setMethodName(METHOD_NAME)
                    .setTimespanMillis(TIMESPAN_MILLIS)
                    .build();

    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    @Test
    public void builder_withInvalidCallingUid_throwException() {
        assertThrows(
                "Calling UID must be set to valid UID!",
                IllegalArgumentException.class,
                () ->
                        new BinderSpamData.Builder()
                                .setCallingUid(-1)
                                .setCallCount(CALL_COUNT)
                                .setInterfaceName(INTERFACE_NAME)
                                .setMethodName(METHOD_NAME)
                                .setTimespanMillis(TIMESPAN_MILLIS)
                                .build());
    }

    @Test
    public void builder_withInvalidCallCount_throwException() {
        assertThrows(
                "Call count must be greater than 0!",
                IllegalArgumentException.class,
                () ->
                        new BinderSpamData.Builder()
                                .setCallingUid(CALLING_UID)
                                .setCallCount(0)
                                .setInterfaceName(INTERFACE_NAME)
                                .setMethodName(METHOD_NAME)
                                .setTimespanMillis(TIMESPAN_MILLIS)
                                .build());
    }

    @Test
    public void builder_withInvalidInterfaceName_throwException() {
        assertThrows(
                "Interface and method names must be set!",
                IllegalArgumentException.class,
                () ->
                        new BinderSpamData.Builder()
                                .setCallingUid(CALLING_UID)
                                .setCallCount(CALL_COUNT)
                                .setInterfaceName("")
                                .setMethodName(METHOD_NAME)
                                .setTimespanMillis(TIMESPAN_MILLIS)
                                .build());
    }

    @Test
    public void builder_withInvalidMethodName_throwException() {
        assertThrows(
                "Interface and method names must be set!",
                IllegalArgumentException.class,
                () ->
                        new BinderSpamData.Builder()
                                .setCallingUid(CALLING_UID)
                                .setCallCount(CALL_COUNT)
                                .setInterfaceName(INTERFACE_NAME)
                                .setMethodName("")
                                .setTimespanMillis(TIMESPAN_MILLIS)
                                .build());
    }

    @Test
    public void builder_withInvalidTimespanMillisecond_throwException() {
        assertThrows(
                "Timespan must be greater than 0!",
                IllegalArgumentException.class,
                () ->
                        new BinderSpamData.Builder()
                                .setCallingUid(CALLING_UID)
                                .setCallCount(CALL_COUNT)
                                .setInterfaceName(INTERFACE_NAME)
                                .setMethodName(METHOD_NAME)
                                .setTimespanMillis(0)
                                .build());
    }

    @Test
    public void getCallingUid_returnCallingUid() {
        assertThat(BINDER_SPAM_SIGNAL.getCallingUid()).isEqualTo(CALLING_UID);
    }

    @Test
    public void getCallCount_returnCallCount() {
        assertThat(BINDER_SPAM_SIGNAL.getCallCount()).isEqualTo(CALL_COUNT);
    }

    @Test
    public void getInterfaceName_returnInterfaceName() {
        assertThat(BINDER_SPAM_SIGNAL.getInterfaceName()).isEqualTo(INTERFACE_NAME);
    }

    @Test
    public void getMethodName_returnMethodName() {
        assertThat(BINDER_SPAM_SIGNAL.getMethodName()).isEqualTo(METHOD_NAME);
    }
}
