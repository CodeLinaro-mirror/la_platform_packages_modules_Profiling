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

/** Tests for the {@link BinderSpamConfig} value object. */
@RunWith(AndroidJUnit4.class)
public class BinderSpamConfigTests {
    private static final String TEST_INTERFACE_NAME = "com.test.service";
    private static final String TEST_METHOD_NAME = "method";
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
                                .setUids(TEST_UIDS)
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
