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

import android.app.ActivityManager;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.time.Duration;

/** Tests for the {@link BinderSpamConfig} value object. */
@RunWith(AndroidJUnit4.class)
public class BinderSpamConfigTests {
    private static final String TEST_INTERFACE_NAME = "com.test.service";
    private static final String TEST_METHOD_NAME = "method";
    private static final int TEST_CALL_COUNT_THRESHOLD = 100;
    private static final Duration TEST_WINDOW_SIZE = Duration.ofSeconds(60);
    private static final int[] TEST_UIDS = new int[] {1000, 1001};
    private static final int[] TEST_IMPORTANCES =
            new int[] {
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND,
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
            };

    private static final BinderSpamConfig BINDER_SPAM_CONFIG =
            new BinderSpamConfig.Builder()
                    .setInterfaceName(TEST_INTERFACE_NAME)
                    .setMethodName(TEST_METHOD_NAME)
                    .setCallCountThreshold(TEST_CALL_COUNT_THRESHOLD)
                    .setWindowSize(TEST_WINDOW_SIZE)
                    .setUids(TEST_UIDS)
                    .setCallerImportanceList(TEST_IMPORTANCES)
                    .build();

    @Test
    public void builder_withInvalidInterfaceName_throwException() {
        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> new BinderSpamConfig.Builder().setInterfaceName(""));
        assertThat(exception).hasMessageThat().contains("Interface name must not be empty!");
    }

    @Test
    public void builder_withInvalidMethodName_throwException() {
        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> new BinderSpamConfig.Builder().setMethodName(""));
        assertThat(exception).hasMessageThat().contains("Method name must not be empty!");
    }

    @Test
    public void builder_witNegativeThreshold_throwException() {
        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> new BinderSpamConfig.Builder().setCallCountThreshold(-1));
        assertThat(exception)
                .hasMessageThat()
                .contains("Call count threshold must be greater than 0!");
    }

    @Test
    public void builder_witZeroThreshold_throwException() {
        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> new BinderSpamConfig.Builder().setCallCountThreshold(0));
        assertThat(exception)
                .hasMessageThat()
                .contains("Call count threshold must be greater than 0!");
    }

    @Test
    public void builder_withTooShortWindowSize_throwException() {
        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> new BinderSpamConfig.Builder().setWindowSize(Duration.ofMillis(999)));
        assertThat(exception)
                .hasMessageThat()
                .contains(
                        "Window size must not be less than "
                                + BinderSpamConfig.Builder.MINIMUM_WINDOW_SIZE);
    }

    @Test
    public void builder_withNullUids_throwException() {
        assertThrows(
                NullPointerException.class, () -> new BinderSpamConfig.Builder().setUids(null));
    }

    @Test
    public void builder_withNullImportanceList_throwException() {
        assertThrows(
                NullPointerException.class,
                () -> new BinderSpamConfig.Builder().setCallerImportanceList(null));
    }

    @Test
    public void builder_withoutInterfaceName_throwException() {
        assertThrows(
                NullPointerException.class,
                () ->
                        new BinderSpamConfig.Builder()
                                .setMethodName(TEST_METHOD_NAME)
                                .setWindowSize(TEST_WINDOW_SIZE)
                                .setUids(TEST_UIDS)
                                .setCallCountThreshold(TEST_CALL_COUNT_THRESHOLD)
                                .setCallerImportanceList(TEST_IMPORTANCES)
                                .build());
    }

    @Test
    public void builder_withoutMethodName_throwException() {
        assertThrows(
                NullPointerException.class,
                () ->
                        new BinderSpamConfig.Builder()
                                .setInterfaceName(TEST_INTERFACE_NAME)
                                .setWindowSize(TEST_WINDOW_SIZE)
                                .setUids(TEST_UIDS)
                                .setCallCountThreshold(TEST_CALL_COUNT_THRESHOLD)
                                .setCallerImportanceList(TEST_IMPORTANCES)
                                .build());
    }

    @Test
    public void builder_withoutCallCountThreshold_throwException() {
        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                new BinderSpamConfig.Builder()
                                        .setInterfaceName(TEST_INTERFACE_NAME)
                                        .setMethodName(TEST_METHOD_NAME)
                                        .setWindowSize(TEST_WINDOW_SIZE)
                                        .setUids(TEST_UIDS)
                                        .setCallerImportanceList(TEST_IMPORTANCES)
                                        .build());
        assertThat(exception).hasMessageThat().contains("Call count threshold must be set!");
    }

    @Test
    public void builder_withoutWindowSize_throwException() {
        assertThrows(
                NullPointerException.class,
                () ->
                        new BinderSpamConfig.Builder()
                                .setInterfaceName(TEST_INTERFACE_NAME)
                                .setMethodName(TEST_METHOD_NAME)
                                .setCallCountThreshold(TEST_CALL_COUNT_THRESHOLD)
                                .setUids(TEST_UIDS)
                                .setCallerImportanceList(TEST_IMPORTANCES)
                                .build());
    }

    @Test
    public void builder_withoutUids_useEmptyList() {
        assertThat(
                        new BinderSpamConfig.Builder()
                                .setInterfaceName(TEST_INTERFACE_NAME)
                                .setMethodName(TEST_METHOD_NAME)
                                .setCallCountThreshold(TEST_CALL_COUNT_THRESHOLD)
                                .setWindowSize(TEST_WINDOW_SIZE)
                                .setCallerImportanceList(TEST_IMPORTANCES)
                                .build()
                                .getUids())
                .isEmpty();
    }

    @Test
    public void builder_withoutImportanceList_useEmptyList() {
        assertThat(
                        new BinderSpamConfig.Builder()
                                .setInterfaceName(TEST_INTERFACE_NAME)
                                .setMethodName(TEST_METHOD_NAME)
                                .setCallCountThreshold(TEST_CALL_COUNT_THRESHOLD)
                                .setWindowSize(TEST_WINDOW_SIZE)
                                .setUids(TEST_UIDS)
                                .build()
                                .getCallerImportanceList())
                .isEmpty();
    }

    @Test
    public void getAidlInterface_returnAidlInterface() {
        assertThat(BINDER_SPAM_CONFIG.getInterfaceName()).isEqualTo(TEST_INTERFACE_NAME);
    }

    @Test
    public void getAidlMethod_returnAidlMethod() {
        assertThat(BINDER_SPAM_CONFIG.getMethodName()).isEqualTo(TEST_METHOD_NAME);
    }

    @Test
    public void getCallCountThreshold_returnCallCountThreshold() {
        assertThat(BINDER_SPAM_CONFIG.getCallCountThreshold()).isEqualTo(TEST_CALL_COUNT_THRESHOLD);
    }

    @Test
    public void getWindowSize_returnWindowSize() {
        assertThat(BINDER_SPAM_CONFIG.getWindowSize()).isEqualTo(TEST_WINDOW_SIZE);
    }

    @Test
    public void getUids_returnUids() {
        assertThat(BINDER_SPAM_CONFIG.getUids()).isEqualTo(TEST_UIDS);
        assertThat(BINDER_SPAM_CONFIG.getUids()).isNotSameInstanceAs(TEST_UIDS);
    }

    @Test
    public void getImportanceList_returnImportanceList() {
        assertThat(BINDER_SPAM_CONFIG.getCallerImportanceList()).isEqualTo(TEST_IMPORTANCES);
        assertThat(BINDER_SPAM_CONFIG.getCallerImportanceList())
                .isNotSameInstanceAs(TEST_IMPORTANCES);
    }
}
