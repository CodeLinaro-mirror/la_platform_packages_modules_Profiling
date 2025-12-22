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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.os.Bundle;
import android.os.OutcomeReceiver;
import android.os.profiling.anomaly.RuleInternal;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.anomaly.proto.BundleValue;
import com.android.server.anomaly.proto.RuleProto;
import com.android.server.anomaly.proto.RuleSetProto;

import com.google.protobuf.InvalidProtocolBufferException;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.Executor;

/** Tests for {@link RuleStorageImpl}. */
@RunWith(AndroidJUnit4.class)
public final class RuleStorageImplTests {
    private static final String TEST_FILE_NAME = "test_anomaly_rules.pb";

    @org.junit.Rule public MockitoRule mMockitoRule = MockitoJUnit.rule();

    @Mock private OutcomeReceiver<Set<RuleInternal>, Throwable> mLoadCallback;
    @Mock private OutcomeReceiver<Void, Throwable> mSaveCallback;
    @Captor private ArgumentCaptor<Set<RuleInternal>> mRuleSetCaptor;

    private RuleStorageImpl mRuleStorage;
    private Executor mDirectExecutor;
    private File mTestFile;

    @Before
    public void setUp() {
        Context context = InstrumentationRegistry.getInstrumentation().getContext();
        mTestFile = new File(context.getFilesDir(), TEST_FILE_NAME);
        mTestFile.delete();

        mDirectExecutor = Runnable::run;
        mRuleStorage = new RuleStorageImpl(mTestFile, mDirectExecutor);
    }

    @After
    public void tearDown() {
        mTestFile.delete();
    }

    @Test
    public void load_nonExistentFile_returnsEmptySet() {
        assertThat(mTestFile.exists()).isFalse();

        mRuleStorage.load(mDirectExecutor, mLoadCallback);

        verify(mLoadCallback).onResult(mRuleSetCaptor.capture());
        assertThat(mRuleSetCaptor.getValue()).isNotNull();
        assertThat(mRuleSetCaptor.getValue()).isEmpty();
    }

    @Test
    public void saveAndLoad_emptySet() {
        mRuleStorage.save(Collections.emptySet(), mDirectExecutor, mSaveCallback);
        verify(mSaveCallback).onResult(null);
        assertThat(mTestFile.exists()).isTrue();

        mRuleStorage.load(mDirectExecutor, mLoadCallback);
        verify(mLoadCallback).onResult(mRuleSetCaptor.capture());
        assertThat(mRuleSetCaptor.getValue()).isEmpty();
    }

    @Test
    public void saveAndLoad_rulesArePreserved() {
        Bundle bundle1 = new Bundle();
        bundle1.putString("stringKey", "stringValue");
        bundle1.putInt("intKey", 123);
        bundle1.putBoolean("boolKey", true);
        RuleInternal rule1 =
                new RuleInternal.Builder()
                        .setConditionType("TYPE_1")
                        .setRuleCondition(bundle1)
                        .addAnomalyAction(1)
                        .build();

        Bundle bundle2 = new Bundle();
        bundle2.putLong("longKey", 456L);
        bundle2.putDouble("doubleKey", 123.456);
        bundle2.putFloat("floatKey", 789.0f);
        RuleInternal rule2 =
                new RuleInternal.Builder()
                        .setConditionType("TYPE_2")
                        .setRuleCondition(bundle2)
                        .addAnomalyAction(2)
                        .addAnomalyAction(3)
                        .build();

        Set<RuleInternal> originalRules = Set.of(rule1, rule2);

        mRuleStorage.save(originalRules, mDirectExecutor, mSaveCallback);
        verify(mSaveCallback).onResult(null);

        mRuleStorage.load(mDirectExecutor, mLoadCallback);
        verify(mLoadCallback).onResult(mRuleSetCaptor.capture());

        Set<RuleInternal> loadedRules = mRuleSetCaptor.getValue();
        assertThat(loadedRules).isNotNull();
        assertThat(loadedRules).hasSize(2);

        // Verify rules content individually as Bundle does not implement equals().
        RuleInternal loadedRule1 =
                loadedRules.stream()
                        .filter(r -> r.getConditionType().equals("TYPE_1"))
                        .findFirst()
                        .get();
        RuleInternal loadedRule2 =
                loadedRules.stream()
                        .filter(r -> r.getConditionType().equals("TYPE_2"))
                        .findFirst()
                        .get();

        assertThat(loadedRule1.getAnomalyActions()).containsExactly(1);
        assertThat(loadedRule1.getRuleCondition().getString("stringKey")).isEqualTo("stringValue");
        assertThat(loadedRule1.getRuleCondition().getInt("intKey")).isEqualTo(123);
        assertThat(loadedRule1.getRuleCondition().getBoolean("boolKey")).isTrue();

        assertThat(loadedRule2.getAnomalyActions()).containsExactly(2, 3).inOrder();
        assertThat(loadedRule2.getRuleCondition().getLong("longKey")).isEqualTo(456L);
        assertThat(loadedRule2.getRuleCondition().getDouble("doubleKey")).isEqualTo(123.456);
        assertThat(loadedRule2.getRuleCondition().getFloat("floatKey")).isEqualTo(789.0f);
    }

    @Test
    public void load_corruptedFile_callsOnError() throws IOException {
        try (FileOutputStream fos = new FileOutputStream(mTestFile)) {
            fos.write(new byte[] {0x1, 0x2, 0x3});
        }
        assertThat(mTestFile.exists()).isTrue();

        mRuleStorage.load(mDirectExecutor, mLoadCallback);

        verify(mLoadCallback).onError(any(InvalidProtocolBufferException.class));
    }

    @Test
    public void save_skipsRuleWithUnsupportedBundleType() {
        Bundle validBundle = new Bundle();
        validBundle.putString("key", "value");
        RuleInternal validRule =
                new RuleInternal.Builder()
                        .setConditionType("VALID_TYPE")
                        .setRuleCondition(validBundle)
                        .addAnomalyAction(1)
                        .build();

        Bundle invalidBundle = new Bundle();
        invalidBundle.putByteArray(
                "unsupported", new byte[] {1, 2, 3}); // byte array is not supported
        RuleInternal invalidRule =
                new RuleInternal.Builder()
                        .setConditionType("INVALID_TYPE")
                        .setRuleCondition(invalidBundle)
                        .addAnomalyAction(2)
                        .build();

        mRuleStorage.save(Set.of(validRule, invalidRule), mDirectExecutor, mSaveCallback);
        verify(mSaveCallback).onResult(null);

        mRuleStorage.load(mDirectExecutor, mLoadCallback);
        verify(mLoadCallback).onResult(mRuleSetCaptor.capture());

        Set<RuleInternal> loadedRules = mRuleSetCaptor.getValue();
        assertThat(loadedRules).isNotNull();
        assertThat(loadedRules).hasSize(1);
        assertThat(loadedRules.iterator().next()).isEqualTo(validRule);
    }

    @Test
    public void load_skipsRuleWithMissingConditionType() throws IOException {
        RuleProto validProto =
                RuleProto.newBuilder().setConditionType("VALID_TYPE").addAnomalyActions(1).build();
        RuleProto invalidProto =
                RuleProto.newBuilder().addAnomalyActions(2).build(); // No condition type

        RuleSetProto ruleSetProto =
                RuleSetProto.newBuilder().addRules(validProto).addRules(invalidProto).build();

        try (FileOutputStream fos = new FileOutputStream(mTestFile)) {
            fos.write(ruleSetProto.toByteArray());
        }

        mRuleStorage.load(mDirectExecutor, mLoadCallback);
        verify(mLoadCallback).onResult(mRuleSetCaptor.capture());

        Set<RuleInternal> loadedRules = mRuleSetCaptor.getValue();
        assertThat(loadedRules).hasSize(1);
        assertThat(loadedRules.iterator().next().getConditionType()).isEqualTo("VALID_TYPE");
    }

    @Test
    public void load_skipsRuleWithUnsupportedBundleValue() throws IOException {
        RuleProto validProto =
                RuleProto.newBuilder().setConditionType("VALID_TYPE").addAnomalyActions(1).build();
        RuleProto invalidProto =
                RuleProto.newBuilder()
                        .setConditionType("INVALID_TYPE")
                        .addAnomalyActions(2)
                        .putRuleCondition(
                                "key", BundleValue.newBuilder().build()) // VALUETYPE_NOT_SET
                        .build();

        RuleSetProto ruleSetProto =
                RuleSetProto.newBuilder().addRules(validProto).addRules(invalidProto).build();

        try (FileOutputStream fos = new FileOutputStream(mTestFile)) {
            fos.write(ruleSetProto.toByteArray());
        }

        mRuleStorage.load(mDirectExecutor, mLoadCallback);
        verify(mLoadCallback).onResult(mRuleSetCaptor.capture());

        Set<RuleInternal> loadedRules = mRuleSetCaptor.getValue();
        assertThat(loadedRules).hasSize(1);
        assertThat(loadedRules.iterator().next().getConditionType()).isEqualTo("VALID_TYPE");
    }
}
