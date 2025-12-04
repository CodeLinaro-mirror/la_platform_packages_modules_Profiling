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

package com.android.os.profiling.anomaly.core;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;

import android.os.profiling.anomaly.flags.Flags;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;

import androidx.test.runner.AndroidJUnit4;

import com.android.os.profiling.anomaly.collector.SignalCollectorConfig;
import com.android.os.profiling.anomaly.collector.SignalCollectorData;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests for the {@link SignalTypeId} value object. */
@RunWith(AndroidJUnit4.class)
@RequiresFlagsEnabled(Flags.FLAG_ANOMALY_DETECTOR_CORE)
public final class SignalTypeIdTests {

    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    private static class TestConfig1 implements SignalCollectorConfig {}

    private static class TestData1 implements SignalCollectorData {}

    private static class TestConfig2 implements SignalCollectorConfig {}

    private static class TestData2 implements SignalCollectorData {}

    @Test
    public void constructor_nullArgs_throwsException() {
        assertThrows(NullPointerException.class, () -> new SignalTypeId(null, TestData1.class));
        assertThrows(NullPointerException.class, () -> new SignalTypeId(TestConfig1.class, null));
    }

    @Test
    public void equals_isReflexive() {
        SignalTypeId id1 = new SignalTypeId(TestConfig1.class, TestData1.class);
        assertThat(id1.equals(id1)).isTrue();
    }

    @Test
    public void equals_isSymmetric() {
        SignalTypeId id1 = new SignalTypeId(TestConfig1.class, TestData1.class);
        SignalTypeId id2 = new SignalTypeId(TestConfig1.class, TestData1.class);
        assertThat(id1).isEqualTo(id2);
        assertThat(id2).isEqualTo(id1);
    }

    @Test
    public void equals_handlesInequality() {
        SignalTypeId id1 = new SignalTypeId(TestConfig1.class, TestData1.class);
        SignalTypeId id2 = new SignalTypeId(TestConfig2.class, TestData1.class);
        SignalTypeId id3 = new SignalTypeId(TestConfig1.class, TestData2.class);
        SignalTypeId id4 = new SignalTypeId(TestConfig2.class, TestData2.class);
        assertThat(id1).isNotEqualTo(id2);
        assertThat(id1).isNotEqualTo(id3);
        assertThat(id1).isNotEqualTo(id4);
    }

    @Test
    public void equals_handlesNull() {
        SignalTypeId id1 = new SignalTypeId(TestConfig1.class, TestData1.class);
        assertThat(id1).isNotEqualTo(null);
    }

    @Test
    public void equals_handlesDifferentClass() {
        SignalTypeId id1 = new SignalTypeId(TestConfig1.class, TestData1.class);
        Object otherObject = new Object();
        assertThat(id1).isNotEqualTo(otherObject);
    }

    @Test
    public void hashCode_isConsistentForEqualObjects() {
        SignalTypeId id1 = new SignalTypeId(TestConfig1.class, TestData1.class);
        SignalTypeId id2 = new SignalTypeId(TestConfig1.class, TestData1.class);
        assertThat(id1.hashCode()).isEqualTo(id2.hashCode());
    }

    @Test
    public void toString_containsClassNames() {
        SignalTypeId id = new SignalTypeId(TestConfig1.class, TestData1.class);
        String str = id.toString();
        assertThat(str).contains("TestConfig1");
        assertThat(str).contains("TestData1");
    }
}
