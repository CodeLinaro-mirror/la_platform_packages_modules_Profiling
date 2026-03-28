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

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;
import static org.junit.Assume.assumeTrue;

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
import com.android.modules.utils.build.SdkLevel;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.Collections;

/** Cts tests for {@link AnomalyDetectorManager}. */
@RunWith(AndroidJUnit4.class)
@RequiresFlagsEnabled(Flags.FLAG_ANOMALY_DETECTOR_CORE)
public final class AnomalyDetectorManagerTest {
    @org.junit.Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    @org.junit.Rule public MockitoRule mMockitoRule = MockitoJUnit.rule();

    private AnomalyDetectorManager mManager;

    private static final int TEST_BINDER_CALL_LIMIT = 100;
    private static final long TEST_BINDER_CALL_INTERVAL_MILLIS = 60000L;

    @Before
    public void setUp() {
        assumeTrue(SdkLevel.isAtLeastC());
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        mManager = context.getSystemService(AnomalyDetectorManager.class);
    }

    private Bundle createBinderSpamBundle() {
        Bundle bundle = new Bundle();
        bundle.putString(
                Rule.BUNDLE_KEY_CONDITION_BINDER_SPAM_INTERFACE_NAME,
                "android.app.IActivityManager");
        bundle.putString(Rule.BUNDLE_KEY_CONDITION_BINDER_SPAM_METHOD_NAME, "startService");
        bundle.putInt(Rule.BUNDLE_KEY_CONDITION_BINDER_SPAM_CALL_LIMIT, TEST_BINDER_CALL_LIMIT);
        bundle.putLong(
                Rule.BUNDLE_KEY_CONDITION_BINDER_SPAM_BINDER_CALL_INTERVAL_MILLIS,
                TEST_BINDER_CALL_INTERVAL_MILLIS);
        return bundle;
    }

    @Test
    @ApiTest(apis = "android.os.profiling.anomaly.AnomalyDetectorManager#setAnomalyDetectorRules")
    public void setAnomalyDetectorRules_nullRules_throwsException() {
        assertThrows(NullPointerException.class, () -> mManager.setAnomalyDetectorRules(null));
    }

    @Test
    @ApiTest(apis = "android.os.profiling.anomaly.AnomalyDetectorManager#setAnomalyDetectorRules")
    public void setAnomalyDetectorRules_withoutPermission_throwsSecurityException() {
        // By not adopting shell permission identity, this test is run without the
        // required permission and should throw a SecurityException.
        assertThrows(
                SecurityException.class,
                () -> mManager.setAnomalyDetectorRules(Collections.emptySet()));
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

    @Test
    @ApiTest(apis = "android.os.profiling.anomaly.AnomalyDetectorManager#setAnomalyDetectorRules")
    public void setAnomalyDetectorRules_ruleWithoutName_doesNotThrow() {
        InstrumentationRegistry.getInstrumentation()
                .getUiAutomation()
                .adoptShellPermissionIdentity();
        try {
            Rule rule =
                    new Rule.Builder()
                            .setConditionType(Rule.CONDITION_TYPE_BINDER_SPAM)
                            .setRuleCondition(createBinderSpamBundle())
                            .addAnomalyAction(Rule.ACTION_TYPE_LOG)
                            .build();
            assertThat(rule.getName()).isEmpty();

            // This should not throw an exception.
            mManager.setAnomalyDetectorRules(Collections.singleton(rule));
        } finally {
            InstrumentationRegistry.getInstrumentation()
                    .getUiAutomation()
                    .dropShellPermissionIdentity();
        }
    }
}
