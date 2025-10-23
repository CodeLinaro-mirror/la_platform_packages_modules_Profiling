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

import android.os.Bundle;
import android.os.OutcomeReceiver;
import android.os.Rule;
import android.util.AtomicFile;
import android.util.Slog;

import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import com.android.os.profiling.anomaly.core.RuleStorage;
import com.android.server.anomaly.proto.BundleValue;
import com.android.server.anomaly.proto.RuleProto;
import com.android.server.anomaly.proto.RuleSetProto;

import com.google.protobuf.InvalidProtocolBufferException;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

/**
 * An implementation of {@link RuleStorage} that persists {@link Rule} objects to disk using Java
 * Proto Lite and {@link AtomicFile} for safe, atomic writes.
 *
 * @hide
 */
public final class RuleStorageImpl implements RuleStorage {

    private static final String TAG = "RuleStorageImpl";

    private final File mFile;
    private final Executor mIoExecutor;
    private final Object mLock = new Object();

    public RuleStorageImpl(File file, Executor ioExecutor) {
        this.mFile = file;
        this.mIoExecutor = ioExecutor;
    }

    /** {@inheritDoc} */
    @Override
    public void load(Executor executor, OutcomeReceiver<Set<Rule>, Throwable> callback) {
        mIoExecutor.execute(
                () -> {
                    try {
                        Set<Rule> rules = readRulesFromDisk();
                        executor.execute(() -> callback.onResult(rules));
                    } catch (Exception e) {
                        Slog.e(TAG, "Failed to load rules from proto", e);
                        executor.execute(() -> callback.onError(e));
                    }
                });
    }

    /** {@inheritDoc} */
    @Override
    public void save(
            Set<Rule> rules, Executor executor, OutcomeReceiver<Void, Throwable> callback) {
        mIoExecutor.execute(
                () -> {
                    try {
                        writeRulesToDisk(rules);
                        executor.execute(() -> callback.onResult(null));
                    } catch (Exception e) {
                        Slog.e(TAG, "Failed to save rules to proto", e);
                        executor.execute(() -> callback.onError(e));
                    }
                });
    }

    @WorkerThread
    private Set<Rule> readRulesFromDisk() throws IOException, InvalidProtocolBufferException {
        synchronized (mLock) {
            if (!mFile.exists()) {
                return new HashSet<>();
            }
            AtomicFile atomicFile = new AtomicFile(mFile);
            byte[] bytes;
            try (FileInputStream fis = atomicFile.openRead()) {
                bytes = fis.readAllBytes();
            }

            if (bytes == null || bytes.length == 0) {
                return new HashSet<>();
            }

            RuleSetProto ruleSetProto = RuleSetProto.parseFrom(bytes);
            return ruleSetProto.getRulesList().stream()
                    .map(this::convertProtoToRule)
                    .filter(java.util.Objects::nonNull)
                    .collect(Collectors.toSet());
        }
    }

    @WorkerThread
    private void writeRulesToDisk(Set<Rule> rules) throws IOException {
        RuleSetProto.Builder ruleSetBuilder = RuleSetProto.newBuilder();
        for (Rule rule : rules) {
            RuleProto ruleProto = convertRuleToProto(rule);
            if (ruleProto != null) {
                ruleSetBuilder.addRules(ruleProto);
            }
        }
        byte[] bytes = ruleSetBuilder.build().toByteArray();

        synchronized (mLock) {
            AtomicFile atomicFile = new AtomicFile(mFile);
            FileOutputStream fos = null;
            try {
                fos = atomicFile.startWrite();
                fos.write(bytes);
                atomicFile.finishWrite(fos);
            } catch (IOException e) {
                if (fos != null) {
                    atomicFile.failWrite(fos);
                }
                throw e;
            }
        }
    }

    /**
     * Converts an {@link android.os.Rule} object into its {@link RuleProto} equivalent.
     *
     * @return The converted {@link RuleProto}, or {@code null} if the rule contains an unsupported
     *     value type in its condition bundle.
     */
    @Nullable
    private RuleProto convertRuleToProto(Rule rule) {
        RuleProto.Builder ruleBuilder =
                RuleProto.newBuilder()
                        .addAllAnomalyActions(rule.getAnomalyActions())
                        .setConditionType(rule.getConditionType());

        Bundle bundle = rule.getRuleCondition();
        for (String key : bundle.keySet()) {
            Object value = bundle.get(key);
            BundleValue.Builder valueBuilder = BundleValue.newBuilder();

            if (value instanceof String) {
                valueBuilder.setStringValue((String) value);
            } else if (value instanceof Integer) {
                valueBuilder.setIntValue((Integer) value);
            } else if (value instanceof Long) {
                valueBuilder.setLongValue((Long) value);
            } else if (value instanceof Boolean) {
                valueBuilder.setBoolValue((Boolean) value);
            } else if (value instanceof Double) {
                valueBuilder.setDoubleValue((Double) value);
            } else if (value instanceof Float) {
                valueBuilder.setFloatValue((Float) value);
            } else {
                Slog.w(
                        TAG,
                        "Unsupported value type in Bundle for key: "
                                + key
                                + ". Skipping rule: "
                                + rule);
                return null;
            }
            ruleBuilder.putRuleCondition(key, valueBuilder.build());
        }

        return ruleBuilder.build();
    }

    /**
     * Converts a {@link RuleProto} object back into its {@link android.os.Rule} equivalent.
     *
     * @return The converted {@link Rule}, or {@code null} if the proto is malformed (e.g. missing
     *     condition type, unrecognized value type).
     */
    @Nullable
    private Rule convertProtoToRule(RuleProto proto) {
        if (!proto.hasConditionType()) {
            Slog.w(
                    TAG,
                    "Skipping rule with missing condition type. Actions: "
                            + proto.getAnomalyActionsList());
            return null;
        }

        Bundle bundle = new Bundle();
        for (String key : proto.getRuleConditionMap().keySet()) {
            BundleValue bundleValue = proto.getRuleConditionMap().get(key);

            switch (bundleValue.getValueTypeCase()) {
                case STRING_VALUE:
                    bundle.putString(key, bundleValue.getStringValue());
                    break;
                case INT_VALUE:
                    bundle.putInt(key, bundleValue.getIntValue());
                    break;
                case LONG_VALUE:
                    bundle.putLong(key, bundleValue.getLongValue());
                    break;
                case BOOL_VALUE:
                    bundle.putBoolean(key, bundleValue.getBoolValue());
                    break;
                case DOUBLE_VALUE:
                    bundle.putDouble(key, bundleValue.getDoubleValue());
                    break;
                case FLOAT_VALUE:
                    bundle.putFloat(key, bundleValue.getFloatValue());
                    break;
                case VALUETYPE_NOT_SET:
                default:
                    Slog.w(
                            TAG,
                            "Unrecognized value type in proto for key: "
                                    + key
                                    + ". Skipping rule with condition type: "
                                    + proto.getConditionType());
                    return null;
            }
        }

        Rule.Builder builder =
                new Rule.Builder()
                        .setConditionType(proto.getConditionType())
                        .setRuleCondition(bundle);

        for (int action : proto.getAnomalyActionsList()) {
            builder.addAnomalyAction(action);
        }

        return builder.build();
    }
}
