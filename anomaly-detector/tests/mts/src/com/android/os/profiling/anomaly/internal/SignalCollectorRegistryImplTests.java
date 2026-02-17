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

package com.android.os.profiling.anomaly.internal;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import androidx.test.runner.AndroidJUnit4;

import com.android.os.profiling.anomaly.collector.SignalCollector;
import com.android.os.profiling.anomaly.collector.SignalCollectorConfig;
import com.android.os.profiling.anomaly.collector.SignalCollectorData;
import com.android.os.profiling.anomaly.core.SignalTypeId;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** Tests for {@link SignalCollectorRegistryImpl}. */
@RunWith(AndroidJUnit4.class)
public final class SignalCollectorRegistryImplTests {
    private static final int TIMEOUT_MS = 1000;
    @Rule public MockitoRule mMockitoRule = MockitoJUnit.rule();

    @Mock private SignalCollector<TestConfig, TestData> mTestCollector;
    @Mock private SignalCollector<AnotherConfig, AnotherData> mAnotherTestCollector;
    @Mock private Consumer<SignalTypeId> mCallback;

    private SignalCollectorRegistryImpl mRegistry;
    private Executor mExecutor;

    // Stub classes for strong typing in tests
    private static final class TestConfig implements SignalCollectorConfig {}

    private static final class TestData implements SignalCollectorData {}

    private static final class AnotherConfig implements SignalCollectorConfig {}

    private static final class AnotherData implements SignalCollectorData {}

    @Before
    public void setUp() {
        mRegistry = new SignalCollectorRegistryImpl();
        mExecutor = Runnable::run; // Direct executor
    }

    @Test
    public void registerSignalCollector_success() {
        mRegistry.registerSignalCollector(TestConfig.class, TestData.class, mTestCollector);
        SignalCollector<TestConfig, TestData> retrieved =
                mRegistry.getSignalCollector(TestConfig.class, TestData.class);
        assertThat(retrieved).isEqualTo(mTestCollector);
    }

    @Test
    public void registerSignalCollector_duplicate_throwsException() {
        mRegistry.registerSignalCollector(TestConfig.class, TestData.class, mTestCollector);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        mRegistry.registerSignalCollector(
                                TestConfig.class, TestData.class, mTestCollector));
    }

    @Test
    public void registerSignalCollector_nullArgs_throwsException() {
        assertThrows(
                NullPointerException.class,
                () -> mRegistry.registerSignalCollector(null, TestData.class, mTestCollector));
        assertThrows(
                NullPointerException.class,
                () -> mRegistry.registerSignalCollector(TestConfig.class, null, mTestCollector));
        assertThrows(
                NullPointerException.class,
                () -> mRegistry.registerSignalCollector(TestConfig.class, TestData.class, null));
    }

    @Test
    public void unregisterSignalCollector_success() {
        mRegistry.registerSignalCollector(TestConfig.class, TestData.class, mTestCollector);
        mRegistry.unregisterSignalCollector(TestConfig.class, TestData.class);
        SignalCollector<TestConfig, TestData> retrieved =
                mRegistry.getSignalCollector(TestConfig.class, TestData.class);
        assertThat(retrieved).isNull();
    }

    @Test
    public void unregisterSignalCollector_notFound_doesNotThrow() {
        // Unregistering a non-existent collector should not throw an exception.
        mRegistry.unregisterSignalCollector(TestConfig.class, TestData.class);
    }

    @Test
    public void unregisterSignalCollector_nullArgs_throwsException() {
        assertThrows(
                NullPointerException.class,
                () -> mRegistry.unregisterSignalCollector(null, TestData.class));
        assertThrows(
                NullPointerException.class,
                () -> mRegistry.unregisterSignalCollector(TestConfig.class, null));
    }

    @Test
    public void getSignalCollector_notFound_returnsNull() {
        SignalCollector<TestConfig, TestData> retrieved =
                mRegistry.getSignalCollector(TestConfig.class, TestData.class);
        assertThat(retrieved).isNull();
    }

    @Test
    public void getSignalCollector_dataTypeMismatch_returnsNull() {
        mRegistry.registerSignalCollector(TestConfig.class, TestData.class, mTestCollector);
        // Request with a different data type
        SignalCollector<TestConfig, AnotherData> retrieved =
                mRegistry.getSignalCollector(TestConfig.class, AnotherData.class);
        assertThat(retrieved).isNull();
    }

    @Test
    public void addCollectorRegisteredCallback_invokedForExistingCollectors() {
        mRegistry.registerSignalCollector(TestConfig.class, TestData.class, mTestCollector);
        mRegistry.registerSignalCollector(
                AnotherConfig.class, AnotherData.class, mAnotherTestCollector);

        mRegistry.addCollectorRegisteredCallback(mExecutor, mCallback);

        verify(mCallback, timeout(TIMEOUT_MS))
                .accept(new SignalTypeId(TestConfig.class, TestData.class));
        verify(mCallback, timeout(TIMEOUT_MS))
                .accept(new SignalTypeId(AnotherConfig.class, AnotherData.class));
    }

    @Test
    public void addCollectorRegisteredCallback_invokedForNewCollectors() {
        mRegistry.addCollectorRegisteredCallback(mExecutor, mCallback);

        mRegistry.registerSignalCollector(TestConfig.class, TestData.class, mTestCollector);
        verify(mCallback, timeout(TIMEOUT_MS))
                .accept(new SignalTypeId(TestConfig.class, TestData.class));

        mRegistry.registerSignalCollector(
                AnotherConfig.class, AnotherData.class, mAnotherTestCollector);
        verify(mCallback, timeout(TIMEOUT_MS))
                .accept(new SignalTypeId(AnotherConfig.class, AnotherData.class));
    }

    @Test
    public void removeCollectorRegisteredCallback_notInvoked() {
        mRegistry.addCollectorRegisteredCallback(mExecutor, mCallback);
        mRegistry.removeCollectorRegisteredCallback(mCallback);

        mRegistry.registerSignalCollector(TestConfig.class, TestData.class, mTestCollector);

        verify(mCallback, never()).accept(any(SignalTypeId.class));
    }

    @Test
    public void addCollectorUnregisteredCallback_invoked() {
        mRegistry.registerSignalCollector(TestConfig.class, TestData.class, mTestCollector);
        mRegistry.addCollectorUnregisteredCallback(mExecutor, mCallback);

        mRegistry.unregisterSignalCollector(TestConfig.class, TestData.class);
        verify(mCallback, timeout(TIMEOUT_MS))
                .accept(new SignalTypeId(TestConfig.class, TestData.class));
    }

    @Test
    public void removeCollectorUnregisteredCallback_notInvoked() {
        mRegistry.registerSignalCollector(TestConfig.class, TestData.class, mTestCollector);
        mRegistry.addCollectorUnregisteredCallback(mExecutor, mCallback);
        mRegistry.removeCollectorUnregisteredCallback(mCallback);

        mRegistry.unregisterSignalCollector(TestConfig.class, TestData.class);
        verify(mCallback, never()).accept(any());
    }

    @Test
    public void removeCollectorUnregisteredCallback_twice_doesNotThrow() {
        mRegistry.addCollectorUnregisteredCallback(mExecutor, mCallback);
        mRegistry.removeCollectorUnregisteredCallback(mCallback);
        mRegistry.removeCollectorUnregisteredCallback(mCallback); // Remove a second time
        mRegistry.registerSignalCollector(TestConfig.class, TestData.class, mTestCollector);
        mRegistry.unregisterSignalCollector(TestConfig.class, TestData.class);

        verify(mCallback, never()).accept(any());
    }
}
