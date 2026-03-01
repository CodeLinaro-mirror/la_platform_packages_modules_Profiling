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

import static android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import android.os.Process;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.time.Duration;

/** Tests for the {@link BinderSpamData} value object. */
@RunWith(AndroidJUnit4.class)
public final class BinderSpamDataTests {

    private static final int CALLING_UID = 1000;
    private static final int SERVER_UID = 1001;
    private static final int CALL_COUNT = 100;
    private static final String INTERFACE_NAME = "android.app.IActivityManager";
    private static final String METHOD_NAME = "startService";
    private static final Duration TIMESPAN = Duration.ofSeconds(3);
    private static final int CALLER_IMPORTANCE = IMPORTANCE_VISIBLE;

    private static final BinderSpamData BINDER_SPAM_SIGNAL =
            new BinderSpamData.Builder()
                    .setCallingUid(CALLING_UID)
                    .setServerUid(SERVER_UID)
                    .setCallCount(CALL_COUNT)
                    .setInterfaceName(INTERFACE_NAME)
                    .setMethodName(METHOD_NAME)
                    .setTimespan(TIMESPAN)
                    .setCallerImportance(CALLER_IMPORTANCE)
                    .build();

    @Test
    public void builder_withInvalidCallingUid_throwException() {
        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                new BinderSpamData.Builder()
                                        .setCallingUid(-1)
                                        .setServerUid(SERVER_UID)
                                        .setCallCount(CALL_COUNT)
                                        .setInterfaceName(INTERFACE_NAME)
                                        .setMethodName(METHOD_NAME)
                                        .setTimespan(TIMESPAN)
                                        .build());
        assertThat(exception).hasMessageThat().contains("Invalid calling UID!");
    }

    @Test
    public void builder_withoutCallingUid_throwException() {
        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                new BinderSpamData.Builder()
                                        .setServerUid(SERVER_UID)
                                        .setCallCount(CALL_COUNT)
                                        .setInterfaceName(INTERFACE_NAME)
                                        .setMethodName(METHOD_NAME)
                                        .setTimespan(TIMESPAN)
                                        .build());

        assertThat(exception).hasMessageThat().contains("Calling UID must be set!");
    }

    @Test
    public void builder_withRootCallingUid_noException() {
        assertEquals(
                new BinderSpamData.Builder()
                        .setCallingUid(Process.ROOT_UID)
                        .setCallCount(CALL_COUNT)
                        .setServerUid(SERVER_UID)
                        .setInterfaceName(INTERFACE_NAME)
                        .setMethodName(METHOD_NAME)
                        .setTimespan(TIMESPAN)
                        .build()
                        .getCallingUid(),
                Process.ROOT_UID);
    }

    @Test
    public void builder_withInvalidServerUid_throwException() {
        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                new BinderSpamData.Builder()
                                        .setCallingUid(CALLING_UID)
                                        .setServerUid(-1)
                                        .setCallCount(CALL_COUNT)
                                        .setInterfaceName(INTERFACE_NAME)
                                        .setMethodName(METHOD_NAME)
                                        .setTimespan(TIMESPAN)
                                        .build());
        assertThat(exception).hasMessageThat().contains("Invalid server UID!");
    }

    @Test
    public void builder_withoutServerUid_throwException() {
        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                new BinderSpamData.Builder()
                                        .setCallingUid(CALLING_UID)
                                        .setCallCount(CALL_COUNT)
                                        .setInterfaceName(INTERFACE_NAME)
                                        .setMethodName(METHOD_NAME)
                                        .setTimespan(TIMESPAN)
                                        .build());
        assertThat(exception).hasMessageThat().contains("Server UID must be set!");
    }

    @Test
    public void builder_withRootServerUid_noException() {
        assertEquals(
                new BinderSpamData.Builder()
                        .setCallingUid(CALLING_UID)
                        .setCallCount(CALL_COUNT)
                        .setServerUid(Process.ROOT_UID)
                        .setInterfaceName(INTERFACE_NAME)
                        .setMethodName(METHOD_NAME)
                        .setTimespan(TIMESPAN)
                        .build()
                        .getServerUid(),
                Process.ROOT_UID);
    }

    @Test
    public void builder_withInvalidCallCount_throwException() {
        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                new BinderSpamData.Builder()
                                        .setCallingUid(CALLING_UID)
                                        .setServerUid(SERVER_UID)
                                        .setCallCount(0)
                                        .setInterfaceName(INTERFACE_NAME)
                                        .setMethodName(METHOD_NAME)
                                        .setTimespan(TIMESPAN)
                                        .build());
        assertThat(exception).hasMessageThat().contains("Invalid call count!");
    }

    @Test
    public void builder_withoutCallCount_throwException() {
        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                new BinderSpamData.Builder()
                                        .setCallingUid(CALLING_UID)
                                        .setServerUid(SERVER_UID)
                                        .setInterfaceName(INTERFACE_NAME)
                                        .setMethodName(METHOD_NAME)
                                        .setTimespan(TIMESPAN)
                                        .build());
        assertThat(exception).hasMessageThat().contains("Call count must be set!");
    }

    @Test
    public void builder_withEmptyInterfaceName_noException() {
        new BinderSpamData.Builder()
                .setCallingUid(CALLING_UID)
                .setServerUid(SERVER_UID)
                .setCallCount(CALL_COUNT)
                .setInterfaceName("")
                .setMethodName(METHOD_NAME)
                .setTimespan(TIMESPAN)
                .build();
    }

    @Test
    public void builder_withoutInterfaceName_throwException() {
        assertThrows(
                NullPointerException.class,
                () ->
                        new BinderSpamData.Builder()
                                .setCallingUid(CALLING_UID)
                                .setServerUid(SERVER_UID)
                                .setCallCount(CALL_COUNT)
                                .setMethodName(METHOD_NAME)
                                .setTimespan(TIMESPAN)
                                .build());
    }

    @Test
    public void builder_withEmptyMethodName_noException() {
        new BinderSpamData.Builder()
                .setCallingUid(CALLING_UID)
                .setServerUid(SERVER_UID)
                .setCallCount(CALL_COUNT)
                .setInterfaceName(INTERFACE_NAME)
                .setMethodName("")
                .setTimespan(TIMESPAN)
                .build();
    }

    @Test
    public void builder_withoutMethodName_throwException() {
        assertThrows(
                NullPointerException.class,
                () ->
                        new BinderSpamData.Builder()
                                .setCallingUid(CALLING_UID)
                                .setServerUid(SERVER_UID)
                                .setCallCount(CALL_COUNT)
                                .setInterfaceName(INTERFACE_NAME)
                                .setTimespan(TIMESPAN)
                                .build());
    }

    @Test
    public void builder_withZeroTimespan_throwException() {
        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                new BinderSpamData.Builder()
                                        .setCallingUid(CALLING_UID)
                                        .setCallCount(CALL_COUNT)
                                        .setInterfaceName(INTERFACE_NAME)
                                        .setMethodName(METHOD_NAME)
                                        .setTimespan(Duration.ZERO)
                                        .build());
        assertThat(exception).hasMessageThat().contains("Timespan must be positive!");
    }

    @Test
    public void builder_withNegativeTimespan_throwException() {
        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                new BinderSpamData.Builder()
                                        .setCallingUid(CALLING_UID)
                                        .setCallCount(CALL_COUNT)
                                        .setInterfaceName(INTERFACE_NAME)
                                        .setMethodName(METHOD_NAME)
                                        .setTimespan(Duration.ofSeconds(-1))
                                        .build());
        assertThat(exception).hasMessageThat().contains("Timespan must be positive!");
    }

    @Test
    public void builder_withoutTimespan_returnDefaultTimespan() {
        assertThat(
                        new BinderSpamData.Builder()
                                .setCallingUid(CALLING_UID)
                                .setServerUid(SERVER_UID)
                                .setCallCount(CALL_COUNT)
                                .setInterfaceName(INTERFACE_NAME)
                                .setMethodName(METHOD_NAME)
                                .build()
                                .getTimespan())
                .isEqualTo(BinderSpamData.Builder.DEFAULT_TIMESPAN);
    }

    @Test
    public void getCallingUid_returnCallingUid() {
        assertThat(BINDER_SPAM_SIGNAL.getCallingUid()).isEqualTo(CALLING_UID);
    }

    @Test
    public void getServerUid_returnServerUid() {
        assertThat(BINDER_SPAM_SIGNAL.getServerUid()).isEqualTo(SERVER_UID);
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

    @Test
    public void getTimespan_returnTimespan() {
        assertThat(BINDER_SPAM_SIGNAL.getTimespan()).isEqualTo(TIMESPAN);
    }

    @Test
    public void getCallerImportance_returnCallerImportance() {
        assertThat(BINDER_SPAM_SIGNAL.getCallerImportance()).isEqualTo(CALLER_IMPORTANCE);
    }

    @Test
    public void testEquals_sameObject_returnsTrue() {
        assertThat(BINDER_SPAM_SIGNAL.equals(BINDER_SPAM_SIGNAL)).isTrue();
    }

    @Test
    public void testEquals_nullObject_returnsFalse() {
        assertThat(BINDER_SPAM_SIGNAL.equals(null)).isFalse();
    }

    @Test
    public void testEquals_equalObjects_returnsTrue() {
        BinderSpamData data =
                new BinderSpamData.Builder()
                        .setCallingUid(CALLING_UID)
                        .setServerUid(SERVER_UID)
                        .setCallCount(CALL_COUNT)
                        .setInterfaceName(INTERFACE_NAME)
                        .setMethodName(METHOD_NAME)
                        .setTimespan(TIMESPAN)
                        .setCallerImportance(CALLER_IMPORTANCE)
                        .build();
        assertThat(BINDER_SPAM_SIGNAL).isEqualTo(data);
    }

    @Test
    public void testEquals_differentCallingUid_returnsFalse() {
        BinderSpamData data =
                new BinderSpamData.Builder()
                        .setCallingUid(CALLING_UID + 1)
                        .setServerUid(SERVER_UID)
                        .setCallCount(CALL_COUNT)
                        .setInterfaceName(INTERFACE_NAME)
                        .setMethodName(METHOD_NAME)
                        .setTimespan(TIMESPAN)
                        .setCallerImportance(CALLER_IMPORTANCE)
                        .build();
        assertThat(BINDER_SPAM_SIGNAL).isNotEqualTo(data);
    }

    @Test
    public void testEquals_differentServerUid_returnsFalse() {
        BinderSpamData data =
                new BinderSpamData.Builder()
                        .setCallingUid(CALLING_UID)
                        .setServerUid(SERVER_UID + 1)
                        .setCallCount(CALL_COUNT)
                        .setInterfaceName(INTERFACE_NAME)
                        .setMethodName(METHOD_NAME)
                        .setTimespan(TIMESPAN)
                        .setCallerImportance(CALLER_IMPORTANCE)
                        .build();
        assertThat(BINDER_SPAM_SIGNAL).isNotEqualTo(data);
    }

    @Test
    public void testEquals_differentCallCount_returnsFalse() {
        BinderSpamData data =
                new BinderSpamData.Builder()
                        .setCallingUid(CALLING_UID)
                        .setServerUid(SERVER_UID)
                        .setCallCount(CALL_COUNT + 1)
                        .setInterfaceName(INTERFACE_NAME)
                        .setMethodName(METHOD_NAME)
                        .setTimespan(TIMESPAN)
                        .setCallerImportance(CALLER_IMPORTANCE)
                        .build();
        assertThat(BINDER_SPAM_SIGNAL).isNotEqualTo(data);
    }

    @Test
    public void testEquals_differentInterfaceName_returnsFalse() {
        BinderSpamData data =
                new BinderSpamData.Builder()
                        .setCallingUid(CALLING_UID)
                        .setServerUid(SERVER_UID)
                        .setCallCount(CALL_COUNT)
                        .setInterfaceName(INTERFACE_NAME + "a")
                        .setMethodName(METHOD_NAME)
                        .setTimespan(TIMESPAN)
                        .setCallerImportance(CALLER_IMPORTANCE)
                        .build();
        assertThat(BINDER_SPAM_SIGNAL).isNotEqualTo(data);
    }

    @Test
    public void testEquals_differentMethodName_returnsFalse() {
        BinderSpamData data =
                new BinderSpamData.Builder()
                        .setCallingUid(CALLING_UID)
                        .setServerUid(SERVER_UID)
                        .setCallCount(CALL_COUNT)
                        .setInterfaceName(INTERFACE_NAME)
                        .setMethodName(METHOD_NAME + "a")
                        .setTimespan(TIMESPAN)
                        .setCallerImportance(CALLER_IMPORTANCE)
                        .build();
        assertThat(BINDER_SPAM_SIGNAL).isNotEqualTo(data);
    }

    @Test
    public void testEquals_differentTimespan_returnsFalse() {
        BinderSpamData data =
                new BinderSpamData.Builder()
                        .setCallingUid(CALLING_UID)
                        .setServerUid(SERVER_UID)
                        .setCallCount(CALL_COUNT)
                        .setInterfaceName(INTERFACE_NAME)
                        .setMethodName(METHOD_NAME)
                        .setTimespan(TIMESPAN.plusSeconds(1))
                        .setCallerImportance(CALLER_IMPORTANCE)
                        .build();
        assertThat(BINDER_SPAM_SIGNAL).isNotEqualTo(data);
    }

    @Test
    public void testEquals_differentCallerImportance_returnsFalse() {
        BinderSpamData data =
                new BinderSpamData.Builder()
                        .setCallingUid(CALLING_UID)
                        .setServerUid(SERVER_UID)
                        .setCallCount(CALL_COUNT)
                        .setInterfaceName(INTERFACE_NAME)
                        .setMethodName(METHOD_NAME)
                        .setTimespan(TIMESPAN)
                        .setCallerImportance(CALLER_IMPORTANCE + 1)
                        .build();
        assertThat(BINDER_SPAM_SIGNAL).isNotEqualTo(data);
    }

    @Test
    public void testHashCode_equalObjects_returnsSameHashCode() {
        BinderSpamData data =
                new BinderSpamData.Builder()
                        .setCallingUid(CALLING_UID)
                        .setServerUid(SERVER_UID)
                        .setCallCount(CALL_COUNT)
                        .setInterfaceName(INTERFACE_NAME)
                        .setMethodName(METHOD_NAME)
                        .setTimespan(TIMESPAN)
                        .setCallerImportance(CALLER_IMPORTANCE)
                        .build();
        assertThat(BINDER_SPAM_SIGNAL.hashCode()).isEqualTo(data.hashCode());
    }
}
