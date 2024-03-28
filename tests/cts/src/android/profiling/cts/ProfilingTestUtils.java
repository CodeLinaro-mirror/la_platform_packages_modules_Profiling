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

import android.os.IBinder;
import android.os.IProfilingResultCallback;
import android.os.OutcomeReceiver;
import android.os.ParcelUuid;
import android.profiling.cts.ClientProfilingRequest;
import android.profiling.cts.ClientProfilingRequestExtraFields;
import android.profiling.cts.ClientProfilingRequestFakeType;
import android.profiling.cts.ClientProfilingRequestMisnamedTypes;
import android.profiling.cts.ClientProfilingRequestMissingFields;
import android.profiling.cts.ClientProfilingRequestMissingTypes;
import android.os.ProfilingResult;
import android.os.RemoteException;

import java.util.concurrent.Executor;
import java.util.function.Consumer;

public final class ProfilingTestUtils {

    static class ImmediateExecutor implements Executor {
        public void execute(Runnable r) {
            r.run();
        }
    }

    public static byte[] getSystemTraceProfilingRequest() {
        // Create system trace proto object.
        ClientProfilingRequest.SystemTrace systemTrace
                = ClientProfilingRequest.SystemTrace.newBuilder().build();

        // Create config proto object with only system trace type set.
        ClientProfilingRequest.Config config
                = ClientProfilingRequest.Config.newBuilder().setSystemTrace(systemTrace).build();

        // Create profiling request object with config and convert to byte array.
        return ClientProfilingRequest.newBuilder().setConfig(config).build().toByteArray();
    }

    public static byte[] getJavaHeapDumpProfilingRequest() {
        // Create jave heap dump proto object.
        ClientProfilingRequest.JavaHeapDump javaHeapDump
                = ClientProfilingRequest.JavaHeapDump.newBuilder().build();

        // Create config proto object with only java heap dump type set.
        ClientProfilingRequest.Config config
                = ClientProfilingRequest.Config.newBuilder().setJavaHeapDump(javaHeapDump).build();

        // Create profiling request object with config and convert to byte array.
        return ClientProfilingRequest.newBuilder().setConfig(config).build().toByteArray();
    }

    public static byte[] getHeapProfileProfilingRequest() {
        // Create heap profile proto object.
        ClientProfilingRequest.HeapProfile heapProfile
                = ClientProfilingRequest.HeapProfile.newBuilder().build();


        // Create config proto object with only heap profile type set.
        ClientProfilingRequest.Config config
                = ClientProfilingRequest.Config.newBuilder().setHeapProfile(heapProfile).build();

        // Create profiling request object with config and convert to byte array.
        return ClientProfilingRequest.newBuilder().setConfig(config).build().toByteArray();
    }

    public static byte[] getStackSamplingProfilingRequest() {
        // Create stack sampling proto object.
        ClientProfilingRequest.StackSampling stackSampling
                = ClientProfilingRequest.StackSampling.newBuilder().build();

        // Create config proto object with only stack sampling type set.
        ClientProfilingRequest.Config config = ClientProfilingRequest.Config
                .newBuilder().setStackSampling(stackSampling).build();

        // Create profiling request object with config and convert to byte array.
        return ClientProfilingRequest.newBuilder().setConfig(config).build().toByteArray();
    }

    public static byte[] getJavaHeapDumpMissingTypesProfilingRequest() {
        // Create jave heap dump proto object.
        ClientProfilingRequestMissingTypes.JavaHeapDump javaHeapDump
                = ClientProfilingRequestMissingTypes.JavaHeapDump.newBuilder().build();

        // Create config proto object with only java heap dump type set.
        ClientProfilingRequestMissingTypes.Config config = ClientProfilingRequestMissingTypes
                .Config.newBuilder().setJavaHeapDump(javaHeapDump).build();

        // Create profiling request object with config and convert to byte array.
        return ClientProfilingRequestMissingTypes.newBuilder().setConfig(config)
                .build().toByteArray();
    }

    public static byte[] getJavaHeapDumpMisnamedProfilingRequest() {
        // Create misnamed jave heap sample proto object.
        ClientProfilingRequestMisnamedTypes.JavaHeapSample javaHeapSample
                = ClientProfilingRequestMisnamedTypes.JavaHeapSample.newBuilder().build();

        // Create config proto object with only java heap sample type set.
        ClientProfilingRequestMisnamedTypes.Configuration config
                = ClientProfilingRequestMisnamedTypes.Configuration.newBuilder()
                .setJavaHeapSample(javaHeapSample).build();

        // Create profiling request object with config and convert to byte array.
        return ClientProfilingRequestMisnamedTypes.newBuilder().setConfiguration(config)
                .build().toByteArray();
    }

    public static byte[] getFakeTypeProfilingRequest(boolean useFakeType) {
        ClientProfilingRequestFakeType.Config config;

        if (useFakeType) {
            // Create fake stack dump proto object.
            ClientProfilingRequestFakeType.StackDump stackDump
                    = ClientProfilingRequestFakeType.StackDump.newBuilder().build();

            // Create config proto object with only stack dump type set.
            config = ClientProfilingRequestFakeType.Config.newBuilder()
                    .setStackDump(stackDump).build();
        } else {
            // Create real java heap dump proto object.
            ClientProfilingRequestFakeType.JavaHeapDump javaHeapDump
                    = ClientProfilingRequestFakeType.JavaHeapDump.newBuilder().build();

            // Create config proto object with only java heap dump type set.
            config = ClientProfilingRequestFakeType.Config.newBuilder()
                    .setJavaHeapDump(javaHeapDump).build();
        }

        // Create profiling request object with config and convert to byte array.
        return ClientProfilingRequestFakeType.newBuilder().setConfig(config)
                .build().toByteArray();
    }

    public static byte[] getHeapProfileMissingFieldsProfilingRequest() {
        // Create heap profile proto object.
        ClientProfilingRequestMissingFields.HeapProfile heapProfile
                = ClientProfilingRequestMissingFields.HeapProfile.newBuilder().build();

        // Create config proto object with only heap profile type set.
        ClientProfilingRequestMissingFields.Config config = ClientProfilingRequestMissingFields
                .Config.newBuilder().setHeapProfile(heapProfile).build();

        // Create profiling request object with config and convert to byte array.
        return ClientProfilingRequestMissingFields.newBuilder().setConfig(config)
                .build().toByteArray();
    }

    public static byte[] getJavaHeapDumpExtraFieldsProfilingRequest() {
        // Create java heap dump proto object with extra meaningless fields.
        ClientProfilingRequestExtraFields.JavaHeapDump javaHeapDump
                = ClientProfilingRequestExtraFields.JavaHeapDump.newBuilder()
                .setJava(false).setHeapiness(5743).setDump(true).build();

        // Create config proto object with only java heap dump type set.
        ClientProfilingRequestExtraFields.Config config = ClientProfilingRequestExtraFields
                .Config.newBuilder().setJavaHeapDump(javaHeapDump).build();

        // Create profiling request object with config and convert to byte array.
        return ClientProfilingRequestExtraFields.newBuilder().setConfig(config)
                .build().toByteArray();
    }
}