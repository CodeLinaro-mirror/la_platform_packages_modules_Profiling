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

import android.os.Bundle;
import android.os.profiling.anomaly.Rule;
import android.os.profiling.anomaly.flags.Flags;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.compatibility.common.util.ApiTest;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

/** Cts tests for {@link Rule}. */
@RunWith(AndroidJUnit4.class)
@RequiresFlagsEnabled(Flags.FLAG_ANOMALY_DETECTOR_CORE)
public final class RuleTest {
    @org.junit.Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    private static final String TEST_BINDER_SPAM_RULE_NAME = "test_binder_spam_rule";
    private static final String TEST_GENERIC_RULE_NAME = "test_rule";
    private static final String TEST_GENERIC_RULE_NAME_1 = "test_rule_1";
    private static final String TEST_GENERIC_RULE_NAME_2 = "test_rule_2";
    private static final String TEST_CONDITION_NEW_TYPE_NAME =
            "android.os.profiling.anomaly.Rule.rss_anon";

    private Bundle createBinderSpamBundle() {
        Bundle bundle = new Bundle();
        bundle.putString(
                Rule.BUNDLE_KEY_CONDITION_BINDER_SPAM_INTERFACE_NAME,
                "android.app.IActivityManager");
        bundle.putString(Rule.BUNDLE_KEY_CONDITION_BINDER_SPAM_METHOD_NAME, "startService");
        bundle.putInt(Rule.BUNDLE_KEY_CONDITION_BINDER_SPAM_CALL_LIMIT, 100);
        bundle.putLong(Rule.BUNDLE_KEY_CONDITION_BINDER_SPAM_BINDER_CALL_INTERVAL_MILLIS, 60000L);
        return bundle;
    }

    private void assertBundlesEqual(Bundle expected, Bundle actual) {
        assertThat(actual.keySet()).isEqualTo(expected.keySet());
        for (String key : expected.keySet()) {
            assertThat(actual.get(key)).isEqualTo(expected.get(key));
        }
    }

    @Test
    @ApiTest(
            apis = {
                "android.os.profiling.anomaly.Rule.Builder#setName",
                "android.os.profiling.anomaly.Rule.Builder#setConditionType",
                "android.os.profiling.anomaly.Rule.Builder#setRuleCondition",
                "android.os.profiling.anomaly.Rule.Builder#addAnomalyAction",
                "android.os.profiling.anomaly.Rule.Builder#build",
                "android.os.profiling.anomaly.Rule#getName",
                "android.os.profiling.anomaly.Rule#getConditionType",
                "android.os.profiling.anomaly.Rule#getRuleCondition",
                "android.os.profiling.anomaly.Rule#getAnomalyActions",
                "android.os.profiling.anomaly.Rule.CONDITION_TYPE_BINDER_SPAM",
                "android.os.profiling.anomaly.Rule.ACTION_TYPE_LOG"
            })
    public void buildRule_withBinderSpam_buildsSuccessfully() {
        Bundle binderSpamConditionBundle = createBinderSpamBundle();
        Rule rule =
                new Rule.Builder()
                        .setName(TEST_BINDER_SPAM_RULE_NAME)
                        .setConditionType(Rule.CONDITION_TYPE_BINDER_SPAM)
                        .setRuleCondition(binderSpamConditionBundle)
                        .addAnomalyAction(Rule.ACTION_TYPE_LOG)
                        .build();

        assertThat(rule).isNotNull();
        assertThat(rule.getName()).isEqualTo(TEST_BINDER_SPAM_RULE_NAME);
        assertThat(rule.getConditionType()).isEqualTo(Rule.CONDITION_TYPE_BINDER_SPAM);
        assertBundlesEqual(binderSpamConditionBundle, rule.getRuleCondition());
        assertThat(rule.getAnomalyActions()).containsExactly(Rule.ACTION_TYPE_LOG);
    }

    @Test
    @ApiTest(
            apis = {
                "android.os.profiling.anomaly.Rule.Builder#setName",
                "android.os.profiling.anomaly.Rule.Builder#setConditionType",
                "android.os.profiling.anomaly.Rule.Builder#setRuleCondition",
                "android.os.profiling.anomaly.Rule.Builder#build",
                "android.os.profiling.anomaly.Rule#getAnomalyActions",
                "android.os.profiling.anomaly.Rule.CONDITION_TYPE_BINDER_SPAM"
            })
    public void buildRule_withNewActions_buildsSuccessfully() {
        Rule rule =
                new Rule.Builder()
                        .setName(TEST_GENERIC_RULE_NAME)
                        .setConditionType(Rule.CONDITION_TYPE_BINDER_SPAM)
                        .setRuleCondition(createBinderSpamBundle())
                        .addAnomalyAction(/* anomalyAction= */ Integer.MAX_VALUE)
                        .build();

        assertThat(rule.getAnomalyActions()).isNotNull();
        assertThat(rule.getAnomalyActions()).hasSize(1);
        assertThat(rule.getAnomalyActions().getFirst()).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    @ApiTest(
            apis = {
                "android.os.profiling.anomaly.Rule.Builder#setName",
                "android.os.profiling.anomaly.Rule.Builder#setConditionType",
                "android.os.profiling.anomaly.Rule.Builder#setRuleCondition",
                "android.os.profiling.anomaly.Rule.Builder#build",
                "android.os.profiling.anomaly.Rule#getAnomalyActions",
                "android.os.profiling.anomaly.Rule.CONDITION_TYPE_BINDER_SPAM"
            })
    public void buildRule_withDuplicateActions_duplicatesIgnored() {
        Bundle binderSpamConditionBundle = createBinderSpamBundle();
        Rule rule =
                new Rule.Builder()
                        .setName(TEST_GENERIC_RULE_NAME)
                        .setConditionType(Rule.CONDITION_TYPE_BINDER_SPAM)
                        .setRuleCondition(binderSpamConditionBundle)
                        .addAnomalyAction(Rule.ACTION_TYPE_LOG)
                        .addAnomalyAction(Rule.ACTION_TYPE_LOG)
                        .build();

        assertThat(rule.getAnomalyActions()).containsExactly(Rule.ACTION_TYPE_LOG);
    }

    @Test
    @ApiTest(apis = {"android.os.profiling.anomaly.Rule.Builder#build"})
    public void buildRule_missingConditionType_throwsIllegalStateException() {
        assertThrows(
                IllegalStateException.class,
                () ->
                        new Rule.Builder()
                                .setName(TEST_GENERIC_RULE_NAME)
                                .setRuleCondition(createBinderSpamBundle())
                                .addAnomalyAction(Rule.ACTION_TYPE_LOG)
                                .build());
    }

    @Test
    @ApiTest(apis = {"android.os.profiling.anomaly.Rule.Builder#build"})
    public void buildRule_missingRuleCondition_throwsIllegalStateException() {
        assertThrows(
                IllegalStateException.class,
                () ->
                        new Rule.Builder()
                                .setName(TEST_GENERIC_RULE_NAME)
                                .setConditionType(Rule.CONDITION_TYPE_BINDER_SPAM)
                                .addAnomalyAction(Rule.ACTION_TYPE_LOG)
                                .build());
    }

    @Test
    @ApiTest(
            apis = {
                "android.os.profiling.anomaly.Rule.Builder#setName",
                "android.os.profiling.anomaly.Rule.Builder#setConditionType",
                "android.os.profiling.anomaly.Rule.Builder#setRuleCondition",
                "android.os.profiling.anomaly.Rule.Builder#build",
                "android.os.profiling.anomaly.Rule#getAnomalyActions",
                "android.os.profiling.anomaly.Rule.CONDITION_TYPE_BINDER_SPAM"
            })
    public void buildRule_missingActions_throwsIllegalStateException() {
        assertThrows(
                IllegalStateException.class,
                () ->
                        new Rule.Builder()
                                .setName(TEST_GENERIC_RULE_NAME)
                                .setConditionType(Rule.CONDITION_TYPE_BINDER_SPAM)
                                .setRuleCondition(createBinderSpamBundle())
                                .build());
    }

    @Test
    @ApiTest(
            apis = {
                "android.os.profiling.anomaly.Rule.Builder",
                "android.os.profiling.anomaly.Rule.Builder#setName",
                "android.os.profiling.anomaly.Rule.Builder#setConditionType",
                "android.os.profiling.anomaly.Rule.Builder#setRuleCondition",
                "android.os.profiling.anomaly.Rule.Builder#build",
                "android.os.profiling.anomaly.Rule#getAnomalyActions",
                "android.os.profiling.anomaly.Rule.CONDITION_TYPE_BINDER_SPAM"
            })
    public void buildRule_binderSpamWithNewBundleKey_buildsSuccessfully() {
        Bundle binderSpamConditionBundle = createBinderSpamBundle();
        binderSpamConditionBundle.putString("some_invalid_key", "some_value");

        Rule rule =
                new Rule.Builder()
                        .setName(TEST_GENERIC_RULE_NAME)
                        .setConditionType(Rule.CONDITION_TYPE_BINDER_SPAM)
                        .setRuleCondition(binderSpamConditionBundle)
                        .addAnomalyAction(Rule.ACTION_TYPE_LOG)
                        .build();

        assertThat(rule).isNotNull();
        assertBundlesEqual(binderSpamConditionBundle, rule.getRuleCondition());
    }

    @Test
    @ApiTest(
            apis = {
                "android.os.profiling.anomaly.Rule.Builder#setRuleCondition",
                "android.os.profiling.anomaly.Rule.CONDITION_TYPE_BINDER_SPAM",
                "android.os.profiling.anomaly.Rule.BUNDLE_KEY_CONDITION_BINDER_SPAM_CALL_LIMIT"
            })
    public void buildRule_binderSpamMissingBundleKey_throwsIllegalArgumentException() {
        Bundle binderSpamConditionBundle = createBinderSpamBundle();
        binderSpamConditionBundle.remove(Rule.BUNDLE_KEY_CONDITION_BINDER_SPAM_CALL_LIMIT);

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new Rule.Builder()
                                .setName(TEST_GENERIC_RULE_NAME)
                                .setConditionType(Rule.CONDITION_TYPE_BINDER_SPAM)
                                .setRuleCondition(binderSpamConditionBundle)
                                .addAnomalyAction(Rule.ACTION_TYPE_LOG)
                                .build());
    }

    @Test
    @ApiTest(
            apis = {
                "android.os.profiling.anomaly.Rule.Builder#setRuleCondition",
                "android.os.profiling.anomaly.Rule.CONDITION_TYPE_BINDER_SPAM"
            })
    public void buildRule_binderSpamEmptyBundle_throwsIllegalArgumentException() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new Rule.Builder()
                                .setName(TEST_GENERIC_RULE_NAME)
                                .setConditionType(Rule.CONDITION_TYPE_BINDER_SPAM)
                                .setRuleCondition(new Bundle())
                                .addAnomalyAction(Rule.ACTION_TYPE_LOG)
                                .build());
    }

    @Test
    @ApiTest(
            apis = {
                "android.os.profiling.anomaly.Rule.Builder#setRuleCondition",
                "android.os.profiling.anomaly.Rule.Builder#setConditionType"
            })
    public void buildRule_newConditions_buildsSuccessfully() {
        Bundle newConditionTypeBundle = new Bundle();
        newConditionTypeBundle.putString("testKey", "testValue");
        newConditionTypeBundle.putInt("testKey2", 123);
        String newCondition = TEST_CONDITION_NEW_TYPE_NAME;

        Rule rule =
                new Rule.Builder()
                        .setName(TEST_GENERIC_RULE_NAME)
                        .setConditionType(newCondition)
                        .setRuleCondition(newConditionTypeBundle)
                        .addAnomalyAction(Rule.ACTION_TYPE_LOG)
                        .build();

        assertThat(rule).isNotNull();
        assertBundlesEqual(newConditionTypeBundle, rule.getRuleCondition());
        assertThat(rule.getConditionType()).isEqualTo(newCondition);
    }

    // TODO(b/440140585): Update test to validate binder interface and method name.
    @Test
    @ApiTest(
            apis = {
                "android.os.profiling.anomaly.Rule.Builder#setRuleCondition",
                "android.os.profiling.anomaly.Rule.BUNDLE_KEY_CONDITION_BINDER_SPAM_INTERFACE_NAME"
            })
    public void buildRule_binderSpamWrongTypeInterfaceName_throwsIllegalArgumentException() {
        Bundle binderSpamConditionBundle = createBinderSpamBundle();
        binderSpamConditionBundle.putInt(
                Rule.BUNDLE_KEY_CONDITION_BINDER_SPAM_INTERFACE_NAME, 123); // Wrong type

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new Rule.Builder()
                                .setName(TEST_GENERIC_RULE_NAME)
                                .setConditionType(Rule.CONDITION_TYPE_BINDER_SPAM)
                                .setRuleCondition(binderSpamConditionBundle)
                                .addAnomalyAction(Rule.ACTION_TYPE_LOG)
                                .build());
    }

    @Test
    @ApiTest(
            apis = {
                "android.os.profiling.anomaly.Rule.Builder#setRuleCondition",
                "android.os.profiling.anomaly.Rule.BUNDLE_KEY_CONDITION_BINDER_SPAM_METHOD_NAME"
            })
    public void buildRule_binderSpamWrongTypeMethodName_throwsIllegalArgumentException() {
        Bundle binderSpamConditionBundle = createBinderSpamBundle();
        binderSpamConditionBundle.putBoolean(
                Rule.BUNDLE_KEY_CONDITION_BINDER_SPAM_METHOD_NAME, true);

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new Rule.Builder()
                                .setName(TEST_GENERIC_RULE_NAME)
                                .setConditionType(Rule.CONDITION_TYPE_BINDER_SPAM)
                                .setRuleCondition(binderSpamConditionBundle)
                                .addAnomalyAction(Rule.ACTION_TYPE_LOG)
                                .build());
    }

    @Test
    @ApiTest(
            apis = {
                "android.os.profiling.anomaly.Rule.Builder#setRuleCondition",
                "android.os.profiling.anomaly.Rule.BUNDLE_KEY_CONDITION_BINDER_SPAM_CALL_LIMIT"
            })
    public void buildRule_binderSpamWrongTypeCallLimit_throwsIllegalArgumentException() {
        Bundle binderSpamConditionBundle = createBinderSpamBundle();
        binderSpamConditionBundle.putString(
                Rule.BUNDLE_KEY_CONDITION_BINDER_SPAM_CALL_LIMIT, "100");

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new Rule.Builder()
                                .setName(TEST_GENERIC_RULE_NAME)
                                .setConditionType(Rule.CONDITION_TYPE_BINDER_SPAM)
                                .setRuleCondition(binderSpamConditionBundle)
                                .addAnomalyAction(Rule.ACTION_TYPE_LOG)
                                .build());
    }

    @Test
    @ApiTest(
            apis = {
                "android.os.profiling.anomaly.Rule.Builder#setRuleCondition",
                "android.os.profiling.anomaly.Rule"
                        + ".BUNDLE_KEY_CONDITION_BINDER_SPAM_BINDER_CALL_INTERVAL_MILLIS"
            })
    public void buildRule_binderSpamWrongTypeIntervalMillis_throwsIllegalArgumentException() {
        Bundle binderSpamConditionBundle = createBinderSpamBundle();
        binderSpamConditionBundle.putInt(
                Rule.BUNDLE_KEY_CONDITION_BINDER_SPAM_BINDER_CALL_INTERVAL_MILLIS, 60000);

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new Rule.Builder()
                                .setName(TEST_GENERIC_RULE_NAME)
                                .setConditionType(Rule.CONDITION_TYPE_BINDER_SPAM)
                                .setRuleCondition(binderSpamConditionBundle)
                                .addAnomalyAction(Rule.ACTION_TYPE_LOG)
                                .build());
    }

    @Test
    @ApiTest(apis = {"android.os.profiling.anomaly.Rule#getAnomalyActions"})
    public void getAnomalyActions_returnsCorrectActions() {
        Bundle binderSpamConditionBundle = createBinderSpamBundle();
        Rule rule =
                new Rule.Builder()
                        .setName(TEST_GENERIC_RULE_NAME)
                        .setConditionType(Rule.CONDITION_TYPE_BINDER_SPAM)
                        .setRuleCondition(binderSpamConditionBundle)
                        .addAnomalyAction(Rule.ACTION_TYPE_LOG)
                        .build();

        assertThat(rule.getAnomalyActions()).containsExactly(Rule.ACTION_TYPE_LOG);
    }

    @Test
    @ApiTest(apis = {"android.os.profiling.anomaly.Rule#getAnomalyActions"})
    public void getAnomalyActions_returnsACopy() {
        Rule rule =
                new Rule.Builder()
                        .setName(TEST_GENERIC_RULE_NAME)
                        .setConditionType(Rule.CONDITION_TYPE_BINDER_SPAM)
                        .setRuleCondition(createBinderSpamBundle())
                        .addAnomalyAction(Rule.ACTION_TYPE_LOG)
                        .build();

        List<Integer> actions = rule.getAnomalyActions();
        actions.add(123);

        assertThat(rule.getAnomalyActions()).containsExactly(Rule.ACTION_TYPE_LOG);
    }

    @Test
    @ApiTest(apis = {"android.os.profiling.anomaly.Rule#getConditionType"})
    public void getConditionType_returnsCorrectType() {
        Bundle binderSpamConditionBundle = createBinderSpamBundle();
        Rule rule =
                new Rule.Builder()
                        .setName(TEST_GENERIC_RULE_NAME)
                        .setConditionType(Rule.CONDITION_TYPE_BINDER_SPAM)
                        .setRuleCondition(binderSpamConditionBundle)
                        .addAnomalyAction(Rule.ACTION_TYPE_LOG)
                        .build();

        assertThat(rule.getConditionType()).isEqualTo(Rule.CONDITION_TYPE_BINDER_SPAM);
    }

    @Test
    @ApiTest(apis = {"android.os.profiling.anomaly.Rule#getRuleCondition"})
    public void getRuleCondition_returnsCorrectBundle() {
        Bundle binderSpamConditionBundle = createBinderSpamBundle();
        Rule rule =
                new Rule.Builder()
                        .setName(TEST_GENERIC_RULE_NAME)
                        .setConditionType(Rule.CONDITION_TYPE_BINDER_SPAM)
                        .setRuleCondition(binderSpamConditionBundle)
                        .addAnomalyAction(Rule.ACTION_TYPE_LOG)
                        .build();

        assertBundlesEqual(binderSpamConditionBundle, rule.getRuleCondition());
    }

    @Test
    @ApiTest(apis = {"android.os.profiling.anomaly.Rule#getRuleCondition"})
    public void getRuleCondition_returnsACopy() {
        Bundle binderSpamConditionBundle = createBinderSpamBundle();
        Rule rule =
                new Rule.Builder()
                        .setName(TEST_GENERIC_RULE_NAME)
                        .setConditionType(Rule.CONDITION_TYPE_BINDER_SPAM)
                        .setRuleCondition(binderSpamConditionBundle)
                        .addAnomalyAction(Rule.ACTION_TYPE_LOG)
                        .build();

        Bundle condition = rule.getRuleCondition();
        condition.putString("new_key", "new_value");

        assertBundlesEqual(binderSpamConditionBundle, rule.getRuleCondition());
        assertThat(rule.getRuleCondition().containsKey("new_key")).isFalse();
    }

    @Test
    @ApiTest(apis = {"android.os.profiling.anomaly.Rule.Builder#setRuleCondition"})
    public void setRuleCondition_nullInput_throwsNullPointerException() {
        Rule.Builder builder = new Rule.Builder();

        assertThrows(NullPointerException.class, () -> builder.setRuleCondition(null));
    }

    @Test
    public void equalsAndHashCode_identicalObjects_areEqual() {
        Rule rule1 =
                new Rule.Builder()
                        .setName(TEST_GENERIC_RULE_NAME)
                        .setConditionType(Rule.CONDITION_TYPE_BINDER_SPAM)
                        .setRuleCondition(createBinderSpamBundle())
                        .addAnomalyAction(Rule.ACTION_TYPE_LOG)
                        .build();

        Rule rule2 =
                new Rule.Builder()
                        .setName(TEST_GENERIC_RULE_NAME)
                        .setConditionType(Rule.CONDITION_TYPE_BINDER_SPAM)
                        .setRuleCondition(createBinderSpamBundle())
                        .addAnomalyAction(Rule.ACTION_TYPE_LOG)
                        .build();

        assertThat(rule1).isEqualTo(rule2);
        assertThat(rule1.hashCode()).isEqualTo(rule2.hashCode());
    }

    @Test
    public void equals_differentName_areNotEqual() {
        Rule rule1 =
                new Rule.Builder()
                        .setName(TEST_GENERIC_RULE_NAME_1)
                        .setConditionType(Rule.CONDITION_TYPE_BINDER_SPAM)
                        .setRuleCondition(createBinderSpamBundle())
                        .addAnomalyAction(Rule.ACTION_TYPE_LOG)
                        .build();
        Rule rule2 =
                new Rule.Builder()
                        .setName(TEST_GENERIC_RULE_NAME_2)
                        .setConditionType(Rule.CONDITION_TYPE_BINDER_SPAM)
                        .setRuleCondition(createBinderSpamBundle())
                        .addAnomalyAction(Rule.ACTION_TYPE_LOG)
                        .build();
        assertThat(rule1).isNotEqualTo(rule2);
    }

    @Test
    public void equals_differentActions_areNotEqual() {
        Rule rule1 =
                new Rule.Builder()
                        .setName(TEST_GENERIC_RULE_NAME)
                        .setConditionType(Rule.CONDITION_TYPE_BINDER_SPAM)
                        .setRuleCondition(createBinderSpamBundle())
                        .addAnomalyAction(Rule.ACTION_TYPE_LOG)
                        .build();

        Rule rule2 =
                new Rule.Builder()
                        .setName(TEST_GENERIC_RULE_NAME)
                        .setConditionType(Rule.CONDITION_TYPE_BINDER_SPAM)
                        .setRuleCondition(createBinderSpamBundle())
                        .addAnomalyAction(Rule.ACTION_TYPE_LOG)
                        .addAnomalyAction(2)
                        .build(); // No actions

        assertThat(rule1).isNotEqualTo(rule2);
    }

    @Test
    public void equals_differentRuleConditionValue_areNotEqual() {
        Rule rule1 =
                new Rule.Builder()
                        .setName(TEST_GENERIC_RULE_NAME)
                        .setConditionType(Rule.CONDITION_TYPE_BINDER_SPAM)
                        .addAnomalyAction(Rule.ACTION_TYPE_LOG)
                        .setRuleCondition(createBinderSpamBundle())
                        .build();

        Bundle bundle2 = createBinderSpamBundle();
        bundle2.putInt(Rule.BUNDLE_KEY_CONDITION_BINDER_SPAM_CALL_LIMIT, 200);
        Rule rule2 =
                new Rule.Builder()
                        .setName(TEST_GENERIC_RULE_NAME)
                        .setConditionType(Rule.CONDITION_TYPE_BINDER_SPAM)
                        .addAnomalyAction(Rule.ACTION_TYPE_LOG)
                        .setRuleCondition(bundle2)
                        .build();

        assertThat(rule1).isNotEqualTo(rule2);
    }

    @Test
    @ApiTest(apis = {"android.os.profiling.anomaly.Rule.Builder#setName"})
    public void setName_null_throws() {
        assertThrows(NullPointerException.class, () -> new Rule.Builder().setName(null));
    }

    @Test
    @ApiTest(apis = {"android.os.profiling.anomaly.Rule.Builder#setName"})
    public void setName_empty_throws() {
        assertThrows(IllegalArgumentException.class, () -> new Rule.Builder().setName(""));
    }

    @Test
    @ApiTest(apis = {"android.os.profiling.anomaly.Rule.Builder#setName"})
    public void setName_blank_throws() {
        assertThrows(IllegalArgumentException.class, () -> new Rule.Builder().setName("   "));
    }

    @Test
    @ApiTest(
            apis = {
                "android.os.profiling.anomaly.Rule.Builder#build",
                "android.os.profiling.anomaly.Rule.Builder#setConditionType",
                "android.os.profiling.anomaly.Rule.Builder#setRuleCondition",
                "android.os.profiling.anomaly.Rule.Builder#addAnomalyAction"
            })
    public void buildRule_withoutName_usesDefaultEmptyName() {
        Rule rule =
                new Rule.Builder()
                        .setConditionType(Rule.CONDITION_TYPE_BINDER_SPAM)
                        .setRuleCondition(createBinderSpamBundle())
                        .addAnomalyAction(Rule.ACTION_TYPE_LOG)
                        .build();

        assertThat(rule.getName()).isEmpty();
    }

    @Test
    @ApiTest(apis = {"android.os.profiling.anomaly.Rule#getName"})
    public void getName_returnsCorrectName() {
        String name = "MyRuleName";
        Rule rule =
                new Rule.Builder()
                        .setName(name)
                        .setConditionType(Rule.CONDITION_TYPE_BINDER_SPAM)
                        .setRuleCondition(createBinderSpamBundle())
                        .addAnomalyAction(Rule.ACTION_TYPE_LOG)
                        .build();

        assertThat(rule.getName()).isEqualTo(name);
    }
}
