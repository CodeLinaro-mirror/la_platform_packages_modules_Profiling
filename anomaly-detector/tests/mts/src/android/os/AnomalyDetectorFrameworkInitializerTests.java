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

package android.os;

import static org.junit.Assert.assertThrows;

import android.os.profiling.anomaly.flags.Flags;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests the implementation of the {@link AnomalyDetectorFrameworkInitializer} APIs. */
@RunWith(AndroidJUnit4.class)
@RequiresFlagsEnabled(Flags.FLAG_ANOMALY_DETECTOR_CORE)
public final class AnomalyDetectorFrameworkInitializerTests {

    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    /**
     * Verifies that calling {@link AnomalyDetectorFrameworkInitializer#registerServiceWrappers}
     * from a non-system context throws an exception, as it should only be called by
     * SystemServiceRegistry during boot.
     */
    @Test
    public void registerServiceWrappers_fromNonSystemContext_throwsException() {
        assertThrows(
                IllegalStateException.class,
                () -> AnomalyDetectorFrameworkInitializer.registerServiceWrappers());
    }
}
