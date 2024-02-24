/*
 * Copyright (C) 2024 The Android Open Source Project
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

import static com.google.common.truth.Truth.assertThat;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.runner.AndroidJUnit4;

import android.os.profiling.ProfilingService;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;


import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Tests in this class are for testing the ProfilingService directly without the need to get a
 * reference to the service via the call to getSystemService().
 */

@RunWith(AndroidJUnit4.class)
public final class ProfilingServiceTests {
    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();
    private ProfilingService mProfilingService =
            new ProfilingService(ApplicationProvider.getApplicationContext());

    @Test
    public void createProfilingServiceTest() {
    assertThat(mProfilingService).isNotNull();
  }

    @Test
    public void profilingNotRunningTests() {
      try {
        boolean isRunning = mProfilingService.areAnyTracesRunning();
        assertThat(isRunning).isFalse();
      } catch (Exception exception) {
        assertThat(exception).isInstanceOf(RuntimeException.class);
      }
    }
}


