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

import android.os.Bundle;

import java.util.concurrent.Executor;

public final class ProfilingTestUtils {

    private static final String KEY_DURATION_MS = "KEY_DURATION_MS";

    static class ImmediateExecutor implements Executor {
        public void execute(Runnable r) {
            r.run();
        }
    }

    static Bundle getOneSecondDurationParamBundle() {
        Bundle params = new Bundle();
        params.putInt(KEY_DURATION_MS, 1000);
        return params;
    }
}

