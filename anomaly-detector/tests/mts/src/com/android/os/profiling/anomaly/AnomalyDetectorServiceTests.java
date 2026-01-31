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

package com.android.os.profiling.anomaly;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.Bundle;
import android.os.profiling.anomaly.RuleInternal;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.runner.AndroidJUnit4;

import com.android.os.profiling.anomaly.collector.SignalCollector;
import com.android.os.profiling.anomaly.collector.SignalCollectorConfig;
import com.android.os.profiling.anomaly.collector.SignalCollectorData;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Collections;

/**
 * Tests for {@link AnomalyDetectorService}.
 *
 * <p>Verifies the behavior of the signal collector registration within the {@link
 * AnomalyDetectorService}, including the handling of invalid arguments and duplicate registrations.
 * This test class instantiates the service directly and requires platform-level visibility to
 * access its local manager.
 */
@RunWith(AndroidJUnit4.class)
public final class AnomalyDetectorServiceTests {
    @Rule public MockitoRule mMockitoRule = MockitoJUnit.rule();

    @Mock private SignalCollector<TestConfig, TestData> mTestCollector;
    @Mock private SignalCollector<TestConfig, TestData> mAnotherTestCollector;

    private Context mContext;
    private AnomalyDetectorService mService;
    private AnomalyDetectorManagerLocal mLocalManager;

    // Stub classes for strong typing in tests
    private static class TestConfig implements SignalCollectorConfig {}

    private static class TestData implements SignalCollectorData {}

    private static class AnotherConfig implements SignalCollectorConfig {}

    private static class AnotherData implements SignalCollectorData {}

    @Before
    public void setUp() {
        mContext = ApplicationProvider.getApplicationContext();
        mService = new AnomalyDetectorService(mContext);
        mLocalManager = mService.mLocalManager;
    }

    @Test
    public void registerSignalCollector_duplicate_throwsException() {
        registerCollector(TestConfig.class, TestData.class, mTestCollector);
        // Try to register another collector with the same config type, which should fail.
        assertThrows(
                IllegalArgumentException.class,
                () -> registerCollector(TestConfig.class, TestData.class, mAnotherTestCollector));
    }

    @Test
    public void registerSignalCollector_nullArgs_throwsException() {
        assertThrows(
                NullPointerException.class,
                () -> registerCollector(/* configType= */ null, TestData.class, mTestCollector));
        assertThrows(
                NullPointerException.class,
                () -> registerCollector(TestConfig.class, /* dataType= */ null, mTestCollector));
        assertThrows(
                NullPointerException.class,
                () -> registerCollector(TestConfig.class, TestData.class, /* collector= */ null));
    }

    @Test
    public void unregisterSignalCollector_removesCollector() {
        registerCollector(TestConfig.class, TestData.class, mTestCollector);
        // Unregister the collector.
        mLocalManager.unregisterSignalCollector(TestConfig.class, TestData.class);
        // Try to register again, it should not throw.
        registerCollector(TestConfig.class, TestData.class, mAnotherTestCollector);
    }

    @Test
    public void unregisterSignalCollector_withDifferentDataType_doesNotRemoveCollector() {
        registerCollector(TestConfig.class, TestData.class, mTestCollector);

        // Attempt to unregister with a different data type. This should not affect the
        // originally registered collector.
        mLocalManager.unregisterSignalCollector(TestConfig.class, AnotherData.class);

        // Verify that the original collector is still registered by trying to register a new
        // collector with the same types, which should fail.
        assertThrows(
                "A collector with the same config and data type should still be registered.",
                IllegalArgumentException.class,
                () -> registerCollector(TestConfig.class, TestData.class, mAnotherTestCollector));
    }

    @Test
    public void unregisterSignalCollector_withDifferentConfigType_doesNotRemoveCollector() {
        registerCollector(TestConfig.class, TestData.class, mTestCollector);

        // Attempt to unregister with a different config type. This should not affect the
        // originally registered collector.
        mLocalManager.unregisterSignalCollector(AnotherConfig.class, TestData.class);

        // Verify that the original collector is still registered by trying to register a new
        // collector with the same types, which should fail.
        assertThrows(
                "A collector with the same config and data type should still be registered.",
                IllegalArgumentException.class,
                () -> registerCollector(TestConfig.class, TestData.class, mAnotherTestCollector));
    }

    @Test
    public void unregisterSignalCollector_notFound_doesNotThrow() {
        // Unregistering a non-existent collector should not throw an exception.
        mLocalManager.unregisterSignalCollector(TestConfig.class, TestData.class);
    }

    @Test
    public void unregisterSignalCollector_nullArgs_throwsException() {
        assertThrows(
                NullPointerException.class,
                () ->
                        mLocalManager.unregisterSignalCollector(
                                /* configType= */ null, TestData.class));
        assertThrows(
                NullPointerException.class,
                () ->
                        mLocalManager.unregisterSignalCollector(
                                TestConfig.class, /* dataType= */ null));
    }

    private Bundle createBinderSpamBundle() {
        Bundle bundle = new Bundle();
        bundle.putString(
                RuleInternal.BUNDLE_KEY_CONDITION_BINDER_SPAM_INTERFACE_NAME,
                "android.app.IActivityManager");
        bundle.putString(RuleInternal.BUNDLE_KEY_CONDITION_BINDER_SPAM_METHOD_NAME, "startService");
        bundle.putInt(RuleInternal.BUNDLE_KEY_CONDITION_BINDER_SPAM_CALL_LIMIT, 100);
        bundle.putLong(
                RuleInternal.BUNDLE_KEY_CONDITION_BINDER_SPAM_BINDER_CALL_INTERVAL_MILLIS, 60000L);
        return bundle;
    }

    @Test
    public void dumpsys_succeeds() {
        Bundle bundle = createBinderSpamBundle();
        bundle.putString(
                RuleInternal.BUNDLE_KEY_CONDITION_BINDER_SPAM_INTERFACE_NAME,
                "android.app.IActivityManager");
        RuleInternal rule =
                new RuleInternal.Builder()
                        .setName("test_dumpsys")
                        .setConditionType(RuleInternal.CONDITION_TYPE_BINDER_SPAM)
                        .setRuleCondition(bundle)
                        .addAnomalyAction(RuleInternal.ACTION_TYPE_LOG)
                        .build();
        mService.mController.setRules(Collections.singleton(rule));
        final StringWriter stringWriter = new StringWriter();
        final PrintWriter pw = new PrintWriter(stringWriter);

        assertTrue(mService.mController.getRules().size() == 1);

        // The dump method checks DUMP permissions - which the test dosn't have.
        mService.mBinderService.dump(pw, new String[0]);
        pw.flush();
        final String dumpOutput = stringWriter.toString();

        assertFalse(dumpOutput.isEmpty());
        assertTrue(dumpOutput.contains("android.app.IActivityManager"));
    }

    /**
     * Helper method to register a collector using the local manager interface, which is exposed for
     * testing. This mimics how other system services would register collectors.
     */
    private <T extends SignalCollectorConfig, U extends SignalCollectorData> void registerCollector(
            Class<T> configType, Class<U> dataType, SignalCollector<T, U> collector) {
        mLocalManager.registerSignalCollector(configType, dataType, collector);
    }
}
