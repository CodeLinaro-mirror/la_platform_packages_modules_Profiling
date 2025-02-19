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

package android.profiling.cts;

import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;

import android.os.ProfilingFrameworkInitializer;
import android.os.ProfilingServiceManager;
import android.os.profiling.Flags;
import android.platform.test.annotations.RequiresFlagsEnabled;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Tests defined in this class are expected to test the implementation of the
 * ProfilingFrameworkInitializer APIs.
 */
@RunWith(AndroidJUnit4.class)
public class ProfilingFrameworkInitializerTests {

    /**
     * ProfilingFrameworkInitializer.setProfilingServiceManager() should only be called by during
     * system initialization. Calling this API at any other time should throw an exception.
     */
    @Test
    @RequiresFlagsEnabled(Flags.FLAG_TELEMETRY_APIS)
    public void testSetProfilingServiceManager() {
        assertThrows(IllegalStateException.class,
                () -> ProfilingFrameworkInitializer.setProfilingServiceManager(
                        mock(ProfilingServiceManager.class)));
    }

    /**
     * ProfilingFrameworkInitializer.registerServiceWrappers() should only be called by
     * SystemServiceRegistry during boot up. Calling this API at any other time should throw an
     * exception.
     */
    @Test
    @RequiresFlagsEnabled(Flags.FLAG_TELEMETRY_APIS)
    public void testRegisterServiceWrappers() {
        assertThrows(
                IllegalStateException.class,
                () -> ProfilingFrameworkInitializer.registerServiceWrappers());
    }
}
