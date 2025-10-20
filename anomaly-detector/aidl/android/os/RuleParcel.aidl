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

package android.os;

import android.os.Bundle;

/**
 * Parcelable to represent a {@link android.os.Rule} for the anomaly detector.
 *
 * @hide
 */
parcelable RuleParcel {
   /**
    * The actions to take when the anomaly condition is met.
    * {@see android.os.Rule#mAnomalyActions}
    */
   int[] anomalyActions;

   /**
    * The type of condition that this rule contains.
    * {@see android.os.Rule#mConditionType}
    */
   String conditionType;

   /**
    * A Bundle containing the specific parameters for the rule condition.
    * The contents of this Bundle depend on the {@code conditionType}.
    * {@see android.os.Rule#mRuleCondition}.
    */
   Bundle ruleCondition;
}
