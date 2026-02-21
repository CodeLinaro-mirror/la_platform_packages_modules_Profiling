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

import android.annotation.Nullable;
import android.os.profiling.anomaly.RuleInternal;

import com.android.os.profiling.anomaly.core.AnomalyAttribute;
import com.android.os.profiling.anomaly.core.AnomalyReport;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A data object representing a single detected anomaly.
 *
 * <p>This object acts as a type-safe container for different attributes that hold the details of
 * the report.
 *
 * @hide
 */
public final class AnomalyReportImpl implements AnomalyReport {
    private final RuleInternal mRule;
    private final Map<Class<?>, AnomalyAttribute> mAttributes;

    private AnomalyReportImpl(Builder builder) {
        mRule = builder.mRule;
        mAttributes = Collections.unmodifiableMap(new HashMap<>(builder.mAttributes));
    }

    @Override
    public RuleInternal getRule() {
        return mRule;
    }

    @Override
    @Nullable
    public <T> T get(Class<T> attributeType) {
        return attributeType.cast(mAttributes.get(attributeType));
    }

    /** Builder for creating an AnomalyReport. */
    public static final class Builder {
        private final RuleInternal mRule;
        private final Map<Class<?>, AnomalyAttribute> mAttributes = new HashMap<>();

        public Builder(RuleInternal rule) {
            mRule = Objects.requireNonNull(rule);
        }

        /**
         * Adds an attribute to the report.
         *
         * @param attribute The attribute instance to add.
         * @return This builder for method chaining.
         */
        public Builder addAttribute(AnomalyAttribute attribute) {
            Objects.requireNonNull(attribute);
            mAttributes.put(attribute.getClass(), attribute);
            return this;
        }

        /**
         * Builds the {@link AnomalyReport} instance.
         *
         * @return The new, immutable {@link AnomalyReport}.
         */
        public AnomalyReport build() {
            return new AnomalyReportImpl(this);
        }
    }
}
