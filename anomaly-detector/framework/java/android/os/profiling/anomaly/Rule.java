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

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.StringDef;
import android.annotation.SystemApi;
import android.os.Bundle;
import android.os.profiling.anomaly.flags.Flags;
import android.util.ArraySet;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Defines a rule for detecting system anomalies.
 *
 * <p>Each rule consists of a condition and a set of actions to be taken if the condition is met.
 * These rules are set through {@link
 * android.os.profiling.anomaly.AnomalyDetectorManager#setAnomalyDetectorRules}.
 *
 * <p>Use the {@link Builder} to construct {@link Rule} instances.
 *
 * @hide
 */
@SystemApi(client = SystemApi.Client.PRIVILEGED_APPS)
@FlaggedApi(Flags.FLAG_ANOMALY_DETECTOR_CORE)
public final class Rule {
    /**
     * Action to write a detailed report of the anomaly to the system log.
     *
     * @hide
     */
    @SystemApi(client = SystemApi.Client.PRIVILEGED_APPS)
    public static final int ACTION_TYPE_LOG = 1;

    /**
     * Condition type for monitoring excessive Binder Inter-Process Calls (IPCs), also known as
     * 'Binder Spam'.
     *
     * <p>Binder Spam is characterized by an application making an unusually high number of IPCs to
     * a specific Binder interface and method within a defined time window.
     *
     * <p>When using this condition type, the associated {@link #getRuleCondition()} bundle must
     * only contain the following keys {@link #BUNDLE_KEY_CONDITION_BINDER_SPAM_INTERFACE_NAME},
     * {@link #BUNDLE_KEY_CONDITION_BINDER_SPAM_METHOD_NAME}, {@link
     * #BUNDLE_KEY_CONDITION_BINDER_SPAM_CALL_LIMIT}, and {@link
     * #BUNDLE_KEY_CONDITION_BINDER_SPAM_BINDER_CALL_INTERVAL_MILLIS}.
     *
     * @hide
     */
    @SystemApi(client = SystemApi.Client.PRIVILEGED_APPS)
    public static final String CONDITION_TYPE_BINDER_SPAM =
            "android.os.profiling.anomaly.Rule.binder_spam";

    /**
     * {@link Bundle} key for the fully qualified name of the AIDL interface to monitor.
     *
     * <p>Example: {@code "android.app.IActivityManager"}
     *
     * <p>Used with {@link #CONDITION_TYPE_BINDER_SPAM}.
     *
     * @hide
     */
    @SystemApi(client = SystemApi.Client.PRIVILEGED_APPS)
    public static final String BUNDLE_KEY_CONDITION_BINDER_SPAM_INTERFACE_NAME =
            "android.os.profiling.anomaly.Rule.binder_interface_name";

    /**
     * {@link Bundle} key for the name of the method within the AIDL interface to monitor.
     *
     * <p>Example: {@code "startService"}
     *
     * <p>Used with {@link #CONDITION_TYPE_BINDER_SPAM}.
     *
     * @hide
     */
    @SystemApi(client = SystemApi.Client.PRIVILEGED_APPS)
    public static final String BUNDLE_KEY_CONDITION_BINDER_SPAM_METHOD_NAME =
            "android.os.profiling.anomaly.Rule.binder_method_name";

    /**
     * {@link Bundle} key for the maximum number of allowed calls to the specified interface and
     * method within the defined interval. This value is an integer.
     *
     * <p>Used with {@link #CONDITION_TYPE_BINDER_SPAM}.
     *
     * @hide
     */
    @SystemApi(client = SystemApi.Client.PRIVILEGED_APPS)
    public static final String BUNDLE_KEY_CONDITION_BINDER_SPAM_CALL_LIMIT =
            "android.os.profiling.anomaly.Rule.binder_call_limit";

    /**
     * {@link Bundle} key for the duration of the sliding time window in milliseconds used to count
     * Binder calls. This value is a long.
     *
     * <p>Exceeding the {@link #BUNDLE_KEY_CONDITION_BINDER_SPAM_CALL_LIMIT} within this interval
     * triggers the associated anomaly actions.
     *
     * <p>Used with {@link #CONDITION_TYPE_BINDER_SPAM}.
     *
     * @hide
     */
    @SystemApi(client = SystemApi.Client.PRIVILEGED_APPS)
    public static final String BUNDLE_KEY_CONDITION_BINDER_SPAM_BINDER_CALL_INTERVAL_MILLIS =
            "android.os.profiling.anomaly.Rule.binder_call_interval_millis";

    private static final Set<String> BINDER_SPAM_CONDITION_KEYS =
            Set.of(
                    BUNDLE_KEY_CONDITION_BINDER_SPAM_INTERFACE_NAME,
                    BUNDLE_KEY_CONDITION_BINDER_SPAM_METHOD_NAME,
                    BUNDLE_KEY_CONDITION_BINDER_SPAM_CALL_LIMIT,
                    BUNDLE_KEY_CONDITION_BINDER_SPAM_BINDER_CALL_INTERVAL_MILLIS);

    /**
     * The list of actions to execute when the rule's condition (see {@link #getConditionType()}) is
     * met. Each element must be a value from {@link AnomalyActionType}.
     */
    private final List<@AnomalyActionType Integer> mAnomalyActions;

    /** The type of condition this rule monitors. Must be a value from {@link ConditionType}. */
    private final @ConditionType String mConditionType;

    /**
     * A {@link Bundle} containing the specific parameters for the rule's condition.
     *
     * <p>The system enforces this condition. Non-compliance triggers the execution of the actions
     * specified in {@link #getAnomalyActions()}.
     */
    private final Bundle mRuleCondition;

    // Private constructor used by the Builder.
    private Rule(Builder builder) {
        this.mAnomalyActions = new ArrayList<>(builder.mAnomalyActions);
        this.mConditionType = builder.mConditionType;
        this.mRuleCondition = builder.mRuleCondition;
    }

    /**
     * Returns the list of actions to be executed by the anomaly detection service when the {@code
     * mRuleCondition} defined by this rule is met.
     *
     * <p>Each integer in the list corresponds to a constant defined in {@link AnomalyActionType},
     * representing a specific action.
     *
     * @return A non-null list of {@link AnomalyActionType} integers.
     * @hide
     */
    @NonNull
    @SystemApi(client = SystemApi.Client.PRIVILEGED_APPS)
    public List<@AnomalyActionType Integer> getAnomalyActions() {
        return new ArrayList<>(mAnomalyActions);
    }

    /**
     * Returns a string indicating the type of condition this rule monitors.
     *
     * <p>The condition type determines how the parameters in the {@link #getRuleCondition()} Bundle
     * are interpreted and what system behavior is being observed.
     *
     * @return One of the string constants defined in {@link ConditionType}, for example, {@link
     *     #CONDITION_TYPE_BINDER_SPAM}.
     * @hide
     */
    @NonNull
    @SystemApi(client = SystemApi.Client.PRIVILEGED_APPS)
    public @ConditionType String getConditionType() {
        return mConditionType;
    }

    /**
     * Returns the {@link Bundle} containing the specific parameters that define the condition for
     * this rule.
     *
     * <p>The expected keys and value types within this Bundle are strictly dependent on the {@link
     * ConditionType} returned by {@link #getConditionType()}. For instance, if the type is {@link
     * #CONDITION_TYPE_BINDER_SPAM}, the Bundle should contain the following keys: {@link
     * #BUNDLE_KEY_CONDITION_BINDER_SPAM_INTERFACE_NAME}, {@link
     * #BUNDLE_KEY_CONDITION_BINDER_SPAM_METHOD_NAME}, {@link
     * #BUNDLE_KEY_CONDITION_BINDER_SPAM_CALL_LIMIT}, and {@link
     * #BUNDLE_KEY_CONDITION_BINDER_SPAM_BINDER_CALL_INTERVAL_MILLIS}.
     *
     * @return A non-null {@link Bundle} instance. The Bundle may be empty if the condition type
     *     requires no parameters.
     * @hide
     */
    @NonNull
    @SystemApi(client = SystemApi.Client.PRIVILEGED_APPS)
    public Bundle getRuleCondition() {
        return new Bundle(mRuleCondition);
    }

    @Override
    @SuppressWarnings("deprecation")
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Rule rule = (Rule) o;

        if (!mConditionType.equals(rule.mConditionType)) {
            return false;
        }

        // Compare mAnomalyActions. Order doesn't matter.
        if (mAnomalyActions.size() != rule.mAnomalyActions.size()
                || !new ArraySet<>(mAnomalyActions).equals(new ArraySet<>(rule.mAnomalyActions))) {
            return false;
        }

        // Compare mRuleCondition
        if (mRuleCondition.size() != rule.mRuleCondition.size()) {
            return false;
        }
        for (String key : mRuleCondition.keySet()) {
            if (!rule.mRuleCondition.containsKey(key)) {
                return false;
            }
            Object value1 = mRuleCondition.get(key);
            Object value2 = rule.mRuleCondition.get(key);
            if (!Objects.equals(value1, value2)) {
                return false;
            }
        }

        return true;
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(mConditionType);

        // Hash for mAnomalyActions, order-independent
        result = 31 * result + new ArraySet<>(mAnomalyActions).hashCode();

        // Hash for mRuleCondition, order-independent for keys
        int bundleHash = 0;
        for (String key : mRuleCondition.keySet()) {
            Object value = mRuleCondition.get(key);
            bundleHash += Objects.hash(key, value);
        }
        result = 31 * result + bundleHash;

        return result;
    }

    /**
     * Defines the types of actions to be executed by the {@code
     * com.android.os.profiling.anomaly.AnomalyDetectorService} when an anomaly is detected based on
     * the {@link Rule}.
     *
     * @hide
     */
    @Target(ElementType.TYPE_USE)
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
        ACTION_TYPE_LOG,
    })
    // TODO(b/416804300): Add default and other action once finalized.
    public @interface AnomalyActionType {}

    /**
     * Defines the possible types of conditions a {@link Rule} can represent.
     *
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @StringDef({
        CONDITION_TYPE_BINDER_SPAM,
    })
    public @interface ConditionType {}

    /**
     * Defines the valid {@link Bundle} keys for the {@link #CONDITION_TYPE_BINDER_SPAM} condition
     * type.
     *
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @StringDef({
        BUNDLE_KEY_CONDITION_BINDER_SPAM_INTERFACE_NAME,
        BUNDLE_KEY_CONDITION_BINDER_SPAM_METHOD_NAME,
        BUNDLE_KEY_CONDITION_BINDER_SPAM_CALL_LIMIT,
        BUNDLE_KEY_CONDITION_BINDER_SPAM_BINDER_CALL_INTERVAL_MILLIS
    })
    public @interface ConditionTypeBinderSpamBundleParams {}

    /**
     * Builder class for creating {@link Rule} instances.
     *
     * @hide
     */
    @SystemApi(client = SystemApi.Client.PRIVILEGED_APPS)
    public static final class Builder {
        private final Set<@AnomalyActionType Integer> mAnomalyActions = new ArraySet<>();
        private @ConditionType String mConditionType;
        private Bundle mRuleCondition;

        /**
         * Adds a action to be taken when the rule's condition is met. Duplicate actions will be
         * ignored.
         *
         * @param anomalyAction An integer representing one of the constants defined in {@link
         *     AnomalyActionType}.
         * @return This Builder instance for chaining.
         * @hide
         */
        @NonNull
        @SystemApi(client = SystemApi.Client.PRIVILEGED_APPS)
        public Builder addAnomalyAction(@AnomalyActionType int anomalyAction) {
            this.mAnomalyActions.add(anomalyAction);
            return this;
        }

        /**
         * Sets the type of condition this rule monitors.
         *
         * @param conditionType One of the string constants defined in {@link ConditionType}.
         * @return This Builder instance for chaining.
         * @hide
         */
        @NonNull
        @SystemApi(client = SystemApi.Client.PRIVILEGED_APPS)
        public Builder setConditionType(@NonNull @ConditionType String conditionType) {
            Objects.requireNonNull(conditionType, "conditionType cannot be null");
            this.mConditionType = conditionType;
            return this;
        }

        /**
         * Sets the {@link Bundle} containing the specific parameters for this rule's condition.
         *
         * <p>The keys and values expected in the Bundle depend on the {@link
         * #setConditionType(String) ConditionType}.
         *
         * @param ruleCondition A Bundle containing the condition parameters.
         * @return This Builder instance for chaining.
         * @hide
         */
        @NonNull
        @SystemApi(client = SystemApi.Client.PRIVILEGED_APPS)
        public Builder setRuleCondition(@NonNull Bundle ruleCondition) {
            Objects.requireNonNull(ruleCondition, "ruleCondition cannot be null");
            this.mRuleCondition = ruleCondition;
            return this;
        }

        /**
         * Builds the {@link Rule} instance.
         *
         * @return The configured {@link Rule} object.
         * @throws IllegalStateException if required fields (ConditionType or RuleCondition) are not
         *     set.
         * @throws IllegalArgumentException if any values are invalid (e.g., unknown action type,
         *     unknown condition type, or invalid Bundle keys for the given condition type).
         * @hide
         */
        @NonNull
        @SystemApi(client = SystemApi.Client.PRIVILEGED_APPS)
        public Rule build() {
            if (mConditionType == null) {
                throw new IllegalStateException("ConditionType must be set.");
            }
            if (mRuleCondition == null) {
                throw new IllegalStateException("RuleCondition Bundle must be set.");
            }
            if (mAnomalyActions.isEmpty()) {
                throw new IllegalStateException("AnomalyActions must be set.");
            }

            validateRuleConditionBundle();

            return new Rule(this);
        }

        private void validateRuleConditionBundle() {
            Set<String> requiredKeys = new ArraySet<>();
            Set<String> providedKeys = mRuleCondition.keySet();

            switch (mConditionType) {
                case CONDITION_TYPE_BINDER_SPAM -> {
                    requiredKeys = new ArraySet<>(BINDER_SPAM_CONDITION_KEYS);
                }
            }

            if (!providedKeys.containsAll(requiredKeys)) {
                Set<String> missingKeys = new ArraySet<>(requiredKeys);
                missingKeys.removeAll(providedKeys);

                throw new IllegalArgumentException(
                        "mRuleCondition is missing keys. Missing keys: " + missingKeys);
            }

            // if all the keys are present, we validate value types
            switch (mConditionType) {
                case CONDITION_TYPE_BINDER_SPAM -> validateBinderSpamBundleValuesType();
                    // add validation for other types.
            }
        }

        @SuppressWarnings("deprecation") // Using Bundle.get() for strict runtime type checking.
        private void validateBinderSpamBundleValueType(String key, Class<?> expectedType) {
            Object value = mRuleCondition.get(key);

            if (!expectedType.isInstance(value)) {
                throw new IllegalArgumentException(
                        String.format(
                                "Invalid value type for key: %s. Expected: %s, Actual: %s",
                                key,
                                expectedType.getSimpleName(),
                                (value == null ? "null" : value.getClass().getSimpleName())));
            }
        }

        // TODO(b/440140585): Validate the format of the interface name and method.
        private void validateBinderSpamBundleValuesType() {
            validateBinderSpamBundleValueType(
                    BUNDLE_KEY_CONDITION_BINDER_SPAM_INTERFACE_NAME, String.class);
            validateBinderSpamBundleValueType(
                    BUNDLE_KEY_CONDITION_BINDER_SPAM_METHOD_NAME, String.class);
            validateBinderSpamBundleValueType(
                    BUNDLE_KEY_CONDITION_BINDER_SPAM_CALL_LIMIT, Integer.class);
            validateBinderSpamBundleValueType(
                    BUNDLE_KEY_CONDITION_BINDER_SPAM_BINDER_CALL_INTERVAL_MILLIS, Long.class);
        }
    }
}
