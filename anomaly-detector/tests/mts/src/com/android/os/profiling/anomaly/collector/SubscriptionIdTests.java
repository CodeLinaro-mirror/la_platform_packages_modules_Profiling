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

package com.android.os.profiling.anomaly.collector;

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

import java.util.UUID;

/**
 * Tests for the {@link SubscriptionId} value object.
 */
@RunWith(AndroidJUnit4.class)
@RequiresFlagsEnabled(Flags.FLAG_ANOMALY_DETECTOR_CORE)
public class SubscriptionIdTests {

    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    @Test
    public void generateNew_returnsNonNull() {
        assertThat(SubscriptionId.generateNew()).isNotNull();
    }

    @Test
    public void generateNew_createsUniqueIds() {
        SubscriptionId id1 = SubscriptionId.generateNew();
        SubscriptionId id2 = SubscriptionId.generateNew();

        assertThat(id1).isNotEqualTo(id2);
    }

    @Test
    public void equals_isReflexive() {
        UUID uuid = UUID.randomUUID();
        SubscriptionId id1 = new SubscriptionId(uuid);

        assertThat(id1.equals(id1)).isTrue();
    }

    @Test
    public void equals_isSymmetric() {
        UUID uuid = UUID.randomUUID();
        SubscriptionId id1 = new SubscriptionId(uuid);
        SubscriptionId id2 = new SubscriptionId(uuid);

        assertThat(id1).isEqualTo(id2);
        assertThat(id2).isEqualTo(id1);
    }

    @Test
    public void equals_handlesInequality() {
        SubscriptionId id1 = new SubscriptionId(UUID.randomUUID());
        SubscriptionId id2 = new SubscriptionId(UUID.randomUUID());

        assertThat(id1).isNotEqualTo(id2);
    }

    @Test
    public void equals_handlesNull() {
        SubscriptionId id1 = new SubscriptionId(UUID.randomUUID());

        assertThat(id1).isNotEqualTo(null);
    }

    @Test
    public void equals_handlesDifferentClass() {
        SubscriptionId id1 = new SubscriptionId(UUID.randomUUID());
        Object otherObject = new Object();

        assertThat(id1).isNotEqualTo(otherObject);
    }

    @Test
    public void hashCode_isConsistentForEqualObjects() {
        UUID uuid = UUID.randomUUID();
        SubscriptionId id1 = new SubscriptionId(uuid);
        SubscriptionId id2 = new SubscriptionId(uuid);

        assertThat(id1.hashCode()).isEqualTo(id2.hashCode());
    }

    @Test
    public void toString_matchesUuidToString() {
        UUID uuid = UUID.randomUUID();
        SubscriptionId id = new SubscriptionId(uuid);

        assertThat(id.toString()).isEqualTo(uuid.toString());
    }

    @Test
    public void constructor_nullUuid_throwsException() {
        assertThrows("Constructor should throw NullPointerException for a null UUID",
                NullPointerException.class,
                () -> new SubscriptionId(null));
    }
}
