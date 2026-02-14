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

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@RunWith(JUnit4.class)
public final class BinderSpamConfigListTest {

    @Test
    public void testGetConfigs() {
        BinderSpamConfig config1 =
                new BinderSpamConfig.Builder()
                        .setInterfaceName("test.interface1")
                        .setMethodName("testMethod1")
                        .build();
        BinderSpamConfig config2 =
                new BinderSpamConfig.Builder()
                        .setInterfaceName("test.interface2")
                        .setMethodName("testMethod2")
                        .build();
        List<BinderSpamConfig> configs = Arrays.asList(config1, config2);
        BinderSpamConfigList configList = new BinderSpamConfigList(configs);
        assertThat(configList.getConfigs()).isEqualTo(configs);
        assertThat(configList.getConfigs()).isNotSameInstanceAs(configs);
    }

    @Test
    public void testConstructor_emptyList() {
        IllegalArgumentException e =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> new BinderSpamConfigList(Collections.emptyList()));
        assertThat(e).hasMessageThat().isEqualTo("Must provide at least one config.");
    }

    @Test
    public void testConstructor_nullList() {
        assertThrows(NullPointerException.class, () -> new BinderSpamConfigList(null));
    }
}
