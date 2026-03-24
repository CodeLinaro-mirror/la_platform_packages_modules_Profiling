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

package android.os.profiling.anomaly;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;

import android.os.Bundle;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

/** Tests for {@link RuleInternal}. */
@RunWith(AndroidJUnit4.class)
public final class RuleInternalTests {

    private static final String TEST_NAME = "test_rule_name";
    private static final long TEST_PROFILING_DURATION_MS = 5000L;

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

    @SuppressWarnings("deprecation") // See comment below for this deprecation.
    private void assertBundlesEqual(Bundle expected, Bundle actual) {
        assertThat(actual.keySet()).isEqualTo(expected.keySet());
        for (String key : expected.keySet()) {
            // Bundle.get() is used here because this is a generic comparison function
            // where the types of the values are not known in advance. The type-safe
            // getters (e.g., getString, getInt) cannot be used without knowing the type
            // for each key.
            assertThat(actual.get(key)).isEqualTo(expected.get(key));
        }
    }

    @Test
    public void buildRule_withBinderSpam_buildsSuccessfully() {
        Bundle binderSpamConditionBundle = createBinderSpamBundle();
        RuleInternal rule =
                new RuleInternal.Builder()
                        .setName(TEST_NAME)
                        .setConditionType(RuleInternal.CONDITION_TYPE_BINDER_SPAM)
                        .setRuleCondition(binderSpamConditionBundle)
                        .addAnomalyAction(RuleInternal.ACTION_TYPE_LOG)
                        .build();

        assertThat(rule).isNotNull();
        assertThat(rule.getRuleCondition().keySet()).isEqualTo(binderSpamConditionBundle.keySet());
        assertThat(rule.getConditionType()).isEqualTo(RuleInternal.CONDITION_TYPE_BINDER_SPAM);
        assertBundlesEqual(binderSpamConditionBundle, rule.getRuleCondition());
        assertThat(rule.getAnomalyActions()).containsExactly(RuleInternal.ACTION_TYPE_LOG);
    }

    @Test
    public void buildRule_withNewActions_buildsSuccessfully() {
        RuleInternal rule =
                new RuleInternal.Builder()
                        .setName(TEST_NAME)
                        .setConditionType(RuleInternal.CONDITION_TYPE_BINDER_SPAM)
                        .setRuleCondition(createBinderSpamBundle())
                        .addAnomalyAction(Integer.MAX_VALUE)
                        .build();

        assertThat(rule.getAnomalyActions()).isNotNull();
        assertThat(rule.getAnomalyActions()).hasSize(1);
        assertThat(rule.getAnomalyActions().getFirst()).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    public void buildRule_withDuplicateActions_duplicatesIgnored() {
        Bundle binderSpamConditionBundle = createBinderSpamBundle();
        RuleInternal rule =
                new RuleInternal.Builder()
                        .setName(TEST_NAME)
                        .setConditionType(RuleInternal.CONDITION_TYPE_BINDER_SPAM)
                        .setRuleCondition(binderSpamConditionBundle)
                        .addAnomalyAction(RuleInternal.ACTION_TYPE_LOG)
                        .addAnomalyAction(RuleInternal.ACTION_TYPE_LOG)
                        .build();

        assertThat(rule.getAnomalyActions()).containsExactly(RuleInternal.ACTION_TYPE_LOG);
    }

    @Test
    public void buildRule_missingConditionType_throwsIllegalStateException() {
        assertThrows(
                IllegalStateException.class,
                () ->
                        new RuleInternal.Builder()
                                .setName(TEST_NAME)
                                .setRuleCondition(createBinderSpamBundle())
                                .addAnomalyAction(RuleInternal.ACTION_TYPE_LOG)
                                .build());
    }

    @Test
    public void buildRule_missingRuleCondition_throwsIllegalStateException() {
        assertThrows(
                IllegalStateException.class,
                () ->
                        new RuleInternal.Builder()
                                .setName(TEST_NAME)
                                .setConditionType(RuleInternal.CONDITION_TYPE_BINDER_SPAM)
                                .addAnomalyAction(RuleInternal.ACTION_TYPE_LOG)
                                .build());
    }

    @Test
    public void buildRule_missingActions_throwsIllegalStateException() {
        assertThrows(
                IllegalStateException.class,
                () ->
                        new RuleInternal.Builder()
                                .setName(TEST_NAME)
                                .setConditionType(RuleInternal.CONDITION_TYPE_BINDER_SPAM)
                                .setRuleCondition(createBinderSpamBundle())
                                .build());
    }

    @Test
    public void buildRule_setConditionType_nullInput_throwsNullPointerException() {
        RuleInternal.Builder builder = new RuleInternal.Builder();
        assertThrows(NullPointerException.class, () -> builder.setConditionType(null));
    }

    @Test
    public void buildRule_setRuleCondition_nullInput_throwsNullPointerException() {
        RuleInternal.Builder builder = new RuleInternal.Builder();
        assertThrows(NullPointerException.class, () -> builder.setRuleCondition(null));
    }

    @Test
    public void buildRule_binderSpamMissingBundleKey_throwsIllegalArgumentException() {
        Bundle binderSpamConditionBundle = createBinderSpamBundle();
        binderSpamConditionBundle.remove(RuleInternal.BUNDLE_KEY_CONDITION_BINDER_SPAM_CALL_LIMIT);

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new RuleInternal.Builder()
                                .setName(TEST_NAME)
                                .setConditionType(RuleInternal.CONDITION_TYPE_BINDER_SPAM)
                                .setRuleCondition(binderSpamConditionBundle)
                                .addAnomalyAction(RuleInternal.ACTION_TYPE_LOG)
                                .build());
    }

    @Test
    public void buildRule_binderSpamEmptyBundle_throwsIllegalArgumentException() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new RuleInternal.Builder()
                                .setName(TEST_NAME)
                                .setConditionType(RuleInternal.CONDITION_TYPE_BINDER_SPAM)
                                .setRuleCondition(new Bundle())
                                .addAnomalyAction(RuleInternal.ACTION_TYPE_LOG)
                                .build());
    }

    @Test
    public void buildRule_binderSpamWrongTypeInterfaceName_throwsIllegalArgumentException() {
        Bundle binderSpamConditionBundle = createBinderSpamBundle();
        binderSpamConditionBundle.putInt(
                RuleInternal.BUNDLE_KEY_CONDITION_BINDER_SPAM_INTERFACE_NAME, 123); // Wrong type

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new RuleInternal.Builder()
                                .setName(TEST_NAME)
                                .setConditionType(RuleInternal.CONDITION_TYPE_BINDER_SPAM)
                                .setRuleCondition(binderSpamConditionBundle)
                                .addAnomalyAction(RuleInternal.ACTION_TYPE_LOG)
                                .build());
    }

    @Test
    public void buildRule_binderSpamWrongTypeMethodName_throwsIllegalArgumentException() {
        Bundle binderSpamConditionBundle = createBinderSpamBundle();
        binderSpamConditionBundle.putBoolean(
                RuleInternal.BUNDLE_KEY_CONDITION_BINDER_SPAM_METHOD_NAME, true);

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new RuleInternal.Builder()
                                .setName(TEST_NAME)
                                .setConditionType(RuleInternal.CONDITION_TYPE_BINDER_SPAM)
                                .setRuleCondition(binderSpamConditionBundle)
                                .addAnomalyAction(RuleInternal.ACTION_TYPE_LOG)
                                .build());
    }

    @Test
    public void buildRule_binderSpamWrongTypeCallLimit_throwsIllegalArgumentException() {
        Bundle binderSpamConditionBundle = createBinderSpamBundle();
        binderSpamConditionBundle.putString(
                RuleInternal.BUNDLE_KEY_CONDITION_BINDER_SPAM_CALL_LIMIT, "100");

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new RuleInternal.Builder()
                                .setConditionType(RuleInternal.CONDITION_TYPE_BINDER_SPAM)
                                .setRuleCondition(binderSpamConditionBundle)
                                .addAnomalyAction(RuleInternal.ACTION_TYPE_LOG)
                                .build());
    }

    @Test
    public void buildRule_binderSpamWrongTypeIntervalMillis_throwsIllegalArgumentException() {
        Bundle binderSpamConditionBundle = createBinderSpamBundle();
        binderSpamConditionBundle.putInt(
                RuleInternal.BUNDLE_KEY_CONDITION_BINDER_SPAM_BINDER_CALL_INTERVAL_MILLIS,
                60000); // Wrong type

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new RuleInternal.Builder()
                                .setConditionType(RuleInternal.CONDITION_TYPE_BINDER_SPAM)
                                .setRuleCondition(binderSpamConditionBundle)
                                .addAnomalyAction(RuleInternal.ACTION_TYPE_LOG)
                                .build());
    }

    @Test
    public void getAnomalyActions_returnsCorrectActions() {
        Bundle binderSpamConditionBundle = createBinderSpamBundle();
        RuleInternal rule =
                new RuleInternal.Builder()
                        .setName(TEST_NAME)
                        .setConditionType(RuleInternal.CONDITION_TYPE_BINDER_SPAM)
                        .setRuleCondition(binderSpamConditionBundle)
                        .addAnomalyAction(RuleInternal.ACTION_TYPE_LOG)
                        .build();

        assertThat(rule.getAnomalyActions()).containsExactly(RuleInternal.ACTION_TYPE_LOG);
    }

    @Test
    public void getAnomalyActions_returnsACopy() {
        RuleInternal rule =
                new RuleInternal.Builder()
                        .setName(TEST_NAME)
                        .setConditionType(RuleInternal.CONDITION_TYPE_BINDER_SPAM)
                        .setRuleCondition(createBinderSpamBundle())
                        .addAnomalyAction(RuleInternal.ACTION_TYPE_LOG)
                        .build();

        List<Integer> actions = rule.getAnomalyActions();
        actions.add(123);

        assertThat(rule.getAnomalyActions()).containsExactly(RuleInternal.ACTION_TYPE_LOG);
    }

    @Test
    public void getConditionType_returnsCorrectType() {
        Bundle binderSpamConditionBundle = createBinderSpamBundle();
        RuleInternal rule =
                new RuleInternal.Builder()
                        .setName(TEST_NAME)
                        .setConditionType(RuleInternal.CONDITION_TYPE_BINDER_SPAM)
                        .setRuleCondition(binderSpamConditionBundle)
                        .addAnomalyAction(RuleInternal.ACTION_TYPE_LOG)
                        .build();

        assertThat(rule.getConditionType()).isEqualTo(RuleInternal.CONDITION_TYPE_BINDER_SPAM);
    }

    @Test
    public void getRuleCondition_returnsCorrectBundle() {
        Bundle expectedConditionBundle = createBinderSpamBundle();
        RuleInternal rule =
                new RuleInternal.Builder()
                        .setName(TEST_NAME)
                        .setConditionType(RuleInternal.CONDITION_TYPE_BINDER_SPAM)
                        .setRuleCondition(expectedConditionBundle)
                        .addAnomalyAction(RuleInternal.ACTION_TYPE_LOG)
                        .build();

        assertBundlesEqual(expectedConditionBundle, rule.getRuleCondition());
    }

    @Test
    public void getRuleCondition_returnsACopy() {
        Bundle binderSpamConditionBundle = createBinderSpamBundle();
        RuleInternal rule =
                new RuleInternal.Builder()
                        .setName(TEST_NAME)
                        .setConditionType(RuleInternal.CONDITION_TYPE_BINDER_SPAM)
                        .setRuleCondition(binderSpamConditionBundle)
                        .addAnomalyAction(RuleInternal.ACTION_TYPE_LOG)
                        .build();

        Bundle condition = rule.getRuleCondition();
        condition.putString("new_key", "new_value");

        assertBundlesEqual(binderSpamConditionBundle, rule.getRuleCondition());
        assertThat(rule.getRuleCondition().containsKey("new_key")).isFalse();
    }

    @Test
    public void equalsAndHashCode_identicalObjects_areEqual() {
        RuleInternal rule1 =
                new RuleInternal.Builder()
                        .setName(TEST_NAME)
                        .setConditionType(RuleInternal.CONDITION_TYPE_BINDER_SPAM)
                        .setRuleCondition(createBinderSpamBundle())
                        .addAnomalyAction(RuleInternal.ACTION_TYPE_LOG)
                        .build();

        RuleInternal rule2 =
                new RuleInternal.Builder()
                        .setName(TEST_NAME)
                        .setConditionType(RuleInternal.CONDITION_TYPE_BINDER_SPAM)
                        .setRuleCondition(createBinderSpamBundle())
                        .addAnomalyAction(RuleInternal.ACTION_TYPE_LOG)
                        .build();

        assertThat(rule1).isEqualTo(rule2);
        assertThat(rule1.hashCode()).isEqualTo(rule2.hashCode());
    }

    @Test
    public void equals_differentActions_areNotEqual() {
        RuleInternal rule1 =
                new RuleInternal.Builder()
                        .setName(TEST_NAME)
                        .setConditionType(RuleInternal.CONDITION_TYPE_BINDER_SPAM)
                        .setRuleCondition(createBinderSpamBundle())
                        .addAnomalyAction(RuleInternal.ACTION_TYPE_LOG)
                        .build();

        RuleInternal rule2 =
                new RuleInternal.Builder()
                        .setName(TEST_NAME)
                        .setConditionType(RuleInternal.CONDITION_TYPE_BINDER_SPAM)
                        .setRuleCondition(createBinderSpamBundle())
                        .addAnomalyAction(RuleInternal.ACTION_TYPE_LOG)
                        .addAnomalyAction(2)
                        .build();

        assertThat(rule1).isNotEqualTo(rule2);
    }

    @Test
    public void equals_differentRuleConditionValue_areNotEqual() {
        RuleInternal rule1 =
                new RuleInternal.Builder()
                        .setName(TEST_NAME)
                        .setConditionType(RuleInternal.CONDITION_TYPE_BINDER_SPAM)
                        .addAnomalyAction(RuleInternal.ACTION_TYPE_LOG)
                        .setRuleCondition(createBinderSpamBundle())
                        .build();

        Bundle bundle2 = createBinderSpamBundle();
        bundle2.putInt(RuleInternal.BUNDLE_KEY_CONDITION_BINDER_SPAM_CALL_LIMIT, 200);
        RuleInternal rule2 =
                new RuleInternal.Builder()
                        .setName(TEST_NAME)
                        .setConditionType(RuleInternal.CONDITION_TYPE_BINDER_SPAM)
                        .addAnomalyAction(RuleInternal.ACTION_TYPE_LOG)
                        .setRuleCondition(bundle2)
                        .build();

        assertThat(rule1).isNotEqualTo(rule2);
    }

    @Test
    public void equals_differentConditionType_areNotEqual() {
        RuleInternal rule1 =
                new RuleInternal.Builder()
                        .setConditionType(RuleInternal.CONDITION_TYPE_BINDER_SPAM)
                        .addAnomalyAction(RuleInternal.ACTION_TYPE_LOG)
                        .setRuleCondition(createBinderSpamBundle())
                        .build();

        RuleInternal rule2 =
                new RuleInternal.Builder()
                        .setConditionType("another_condition_type")
                        .addAnomalyAction(RuleInternal.ACTION_TYPE_LOG)
                        .setRuleCondition(createBinderSpamBundle())
                        .build();

        assertThat(rule1).isNotEqualTo(rule2);
    }

    @Test
    public void getName_returnsCorrectName() {
        RuleInternal rule =
                new RuleInternal.Builder()
                        .setName(TEST_NAME)
                        .setConditionType(RuleInternal.CONDITION_TYPE_BINDER_SPAM)
                        .setRuleCondition(createBinderSpamBundle())
                        .addAnomalyAction(RuleInternal.ACTION_TYPE_LOG)
                        .build();

        assertThat(rule.getName()).isEqualTo(TEST_NAME);
    }

    @Test
    public void setName_empty_doesNotThrow() {
        RuleInternal rule =
                new RuleInternal.Builder()
                        .setName("")
                        .setConditionType(RuleInternal.CONDITION_TYPE_BINDER_SPAM)
                        .setRuleCondition(createBinderSpamBundle())
                        .addAnomalyAction(RuleInternal.ACTION_TYPE_LOG)
                        .build();
        assertThat(rule.getName()).isEmpty();
    }

    @Test
    public void setName_blank_doesNotThrow() {
        RuleInternal rule =
                new RuleInternal.Builder()
                        .setName("   ")
                        .setConditionType(RuleInternal.CONDITION_TYPE_BINDER_SPAM)
                        .setRuleCondition(createBinderSpamBundle())
                        .addAnomalyAction(RuleInternal.ACTION_TYPE_LOG)
                        .build();
        assertThat(rule.getName()).isEqualTo("   ");
    }

    @Test
    public void buildRule_withoutName_usesDefaultEmptyName() {
        RuleInternal rule =
                new RuleInternal.Builder()
                        .setConditionType(RuleInternal.CONDITION_TYPE_BINDER_SPAM)
                        .setRuleCondition(createBinderSpamBundle())
                        .addAnomalyAction(RuleInternal.ACTION_TYPE_LOG)
                        .build();

        assertThat(rule.getName()).isEmpty();
    }

    @Test
    public void buildRule_withOptionalProfileDuration_buildsSuccessfully() {
        Bundle conditionBundle = createBinderSpamBundle();
        conditionBundle.putLong(
                RuleInternal.BUNDLE_KEY_PROFILING_SESSION_DURATION_MILLIS,
                TEST_PROFILING_DURATION_MS);

        RuleInternal rule =
                new RuleInternal.Builder()
                        .setName(TEST_NAME)
                        .setConditionType(RuleInternal.CONDITION_TYPE_BINDER_SPAM)
                        .setRuleCondition(conditionBundle)
                        .addAnomalyAction(RuleInternal.ACTION_TYPE_COLLECT_PROFILE)
                        .build();

        assertThat(rule).isNotNull();
        assertBundlesEqual(conditionBundle, rule.getRuleCondition());
    }

    @Test
    public void buildRule_binderSpamWrongTypeProfileDuration_throwsIllegalArgumentException() {
        Bundle conditionBundle = createBinderSpamBundle();
        conditionBundle.putInt(
                RuleInternal.BUNDLE_KEY_PROFILING_SESSION_DURATION_MILLIS,
                (int) TEST_PROFILING_DURATION_MS); // Wrong type (should be Long)

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new RuleInternal.Builder()
                                .setConditionType(RuleInternal.CONDITION_TYPE_BINDER_SPAM)
                                .setRuleCondition(conditionBundle)
                                .addAnomalyAction(RuleInternal.ACTION_TYPE_COLLECT_PROFILE)
                                .build());
    }

    @Test
    public void buildRule_withProfileDurationWithoutProfileAction_throwsIllegalArgumentException() {
        Bundle conditionBundle = createBinderSpamBundle();
        conditionBundle.putLong(
                RuleInternal.BUNDLE_KEY_PROFILING_SESSION_DURATION_MILLIS,
                TEST_PROFILING_DURATION_MS);

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new RuleInternal.Builder()
                                .setConditionType(RuleInternal.CONDITION_TYPE_BINDER_SPAM)
                                .setRuleCondition(conditionBundle)
                                .addAnomalyAction(RuleInternal.ACTION_TYPE_LOG)
                                .build());
    }
}
