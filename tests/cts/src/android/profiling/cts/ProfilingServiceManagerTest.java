/*
 * Copyright (C) 2022 The Android Open Source Project
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

import android.os.IBinder;
import android.os.Flags;
import android.os.ProfilingFrameworkInitializer;
import android.os.ProfilingServiceManager.ServiceNotFoundException;
import android.os.ProfilingServiceManager.ServiceRegisterer;
import android.os.ProfilingServiceManager;
import android.platform.test.annotations.RequiresFlagsEnabled;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 *
 * Tests defined in this class are expected to test the implementation of the
 * ProfilingServiceManager APIs.
 *
 */
@RunWith(AndroidJUnit4.class)
public class ProfilingServiceManagerTest {

    /**
     * Tests that the ProfilingServiceManager.getProfilingServiceRegisterer()
     * and the ServiceRegisterer's .get and .getOrThrow methods return results.
     */
    @Test
    @RequiresFlagsEnabled(Flags.FLAG_TELEMETRY_APIS_FRAMEWORK_INITIALIZATION)
    public void testProfilingServiceRegisterer() {
        ProfilingServiceManager serviceManager =
                ProfilingFrameworkInitializer.getProfilingServiceManager();

        ServiceRegisterer serviceRegisterer =
                serviceManager.getProfilingServiceRegisterer();
        assertNotNull(serviceRegisterer);

        IBinder serviceBinder = serviceRegisterer.get();
        assertNotNull(serviceBinder);

        serviceBinder = null;
        try {
            serviceBinder = serviceRegisterer.getOrThrow();
            assertNotNull(serviceBinder);
        } catch (ServiceNotFoundException exception) {
            fail("ServiceNotFoundException should not be thrown "
                + "since the service should exist in this test");
        }
    }

    /** Tests that the ProfilingServiceManagerServiceNotFoundException works as expected. */
    @Test
    @RequiresFlagsEnabled(Flags.FLAG_TELEMETRY_APIS_FRAMEWORK_INITIALIZATION)
    public void testProfilingServiceNotFoundException() {
        String name = "test-profiling-service";
        ServiceNotFoundException testException =
                new ServiceNotFoundException(name);

        try {
            throw testException;
        } catch (ServiceNotFoundException exception) {
            assertEquals("No service published for: " + name,
                exception.getMessage());
        }
    }
}
