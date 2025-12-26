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

package android.os.cts;

import static org.junit.Assert.assertThrows;

import android.content.Context;
import android.os.Bundle;
import android.os.profiling.anomaly.AnomalyDetectorManager;
import android.os.profiling.anomaly.Rule;
import android.os.profiling.anomaly.flags.Flags;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.compatibility.common.util.ApiTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.Collections;
import java.util.Set;

/** Cts tests for {@link AnomalyDetectorManager}. */
@RunWith(AndroidJUnit4.class)
@RequiresFlagsEnabled(Flags.FLAG_ANOMALY_DETECTOR_CORE)
public final class AnomalyDetectorManagerTest {
    @org.junit.Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    @org.junit.Rule public MockitoRule mMockitoRule = MockitoJUnit.rule();

    private AnomalyDetectorManager mManager;

    @Before
    public void setUp() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        mManager = context.getSystemService(AnomalyDetectorManager.class);
    }

    @Test
    @ApiTest(apis = "android.os.profiling.anomaly.AnomalyDetectorManager#setAnomalyDetectorRules")
    public void setAnomalyDetectorRules_nullRules_throwsException() {
        assertThrows(NullPointerException.class, () -> mManager.setAnomalyDetectorRules(null));
    }

    @Test
    @ApiTest(apis = "android.os.profiling.anomaly.AnomalyDetectorManager#setAnomalyDetectorRules")
    public void setAnomalyDetectorRules_serviceThrowsRemoteException_rethrows() throws Exception {
        Set<Rule> rules = Collections.singleton(createRule(createBinderSpamBundle()));
        // RemoteException.rethrowFromSystemServer() wraps the exception in a RuntimeException
        assertThrows(RuntimeException.class, () -> mManager.setAnomalyDetectorRules(rules));
    }

    @Test
    @ApiTest(apis = "android.os.profiling.anomaly.AnomalyDetectorManager#setAnomalyDetectorRules")
    public void setAnomalyDetectorRules_withoutPermission_throwsSecurityException() {
        InstrumentationRegistry.getInstrumentation()
                .getUiAutomation()
                .dropShellPermissionIdentity();

        try {
            assertThrows(
                    SecurityException.class,
                    () -> mManager.setAnomalyDetectorRules(Collections.emptySet()));
        } finally {
            InstrumentationRegistry.getInstrumentation()
                    .getUiAutomation()
                    .adoptShellPermissionIdentity();
        }
    }

    @Test
    @ApiTest(apis = "android.os.profiling.anomaly.AnomalyDetectorManager#setAnomalyDetectorRules")
    public void setAnomalyDetectorRules_withPermission_doesNotThrowException() {
        InstrumentationRegistry.getInstrumentation()
                .getUiAutomation()
                .adoptShellPermissionIdentity();

        try {
            mManager.setAnomalyDetectorRules(Collections.emptySet());
        } finally {
            InstrumentationRegistry.getInstrumentation()
                    .getUiAutomation()
                    .dropShellPermissionIdentity();
        }
    }

    private Bundle createBinderSpamBundle() {
        Bundle bundle = new Bundle();
        bundle.putString(Rule.BUNDLE_KEY_CONDITION_BINDER_SPAM_INTERFACE_NAME, "test.interface");
        bundle.putString(Rule.BUNDLE_KEY_CONDITION_BINDER_SPAM_METHOD_NAME, "testMethod");
        bundle.putInt(Rule.BUNDLE_KEY_CONDITION_BINDER_SPAM_CALL_LIMIT, 10);
        bundle.putLong(Rule.BUNDLE_KEY_CONDITION_BINDER_SPAM_BINDER_CALL_INTERVAL_MILLIS, 1000L);
        return bundle;
    }

    private Rule createRule(Bundle condition) {
        return new Rule.Builder()
                .setConditionType(Rule.CONDITION_TYPE_BINDER_SPAM)
                .setRuleCondition(condition)
                .addAnomalyAction(Rule.ACTION_TYPE_LOG)
                .build();
    }
}
