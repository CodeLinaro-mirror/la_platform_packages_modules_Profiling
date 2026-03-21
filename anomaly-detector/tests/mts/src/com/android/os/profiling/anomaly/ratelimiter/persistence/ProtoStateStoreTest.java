/*
 * Copyright (C) 2026 The Android Open Source Project
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

package com.android.os.profiling.anomaly.ratelimiter.persistence;

import static com.google.common.truth.Truth.assertThat;

import android.os.OutcomeReceiver;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(AndroidJUnit4.class)
public class ProtoStateStoreTest {

    @Rule public TemporaryFolder mTempFolder = new TemporaryFolder();

    private static final String TEST_FILE_NAME = "test_ratelimiter_state.pb";
    private static final int ASYNC_TIMEOUT_SECONDS = 10;
    private static final long TIMESTAMP_1 = 100L;
    private static final long TIMESTAMP_2 = 200L;
    private static final int UID_1 = 1001;
    private static final long UID_TIMESTAMP_1 = 300L;
    private static final String SIGNATURE_KEY = "key1";
    private static final long SIGNATURE_TIMESTAMP = 400L;
    private static final long INITIAL_TIMESTAMP = 1L;
    private static final int UID_2 = 9999;
    private static final long UID_TIMESTAMP_2 = 2L;
    private static final byte[] GARBAGE_DATA = new byte[] {0x1, 0x2, 0x3, 0x4};

    private File mTestFile;
    private ProtoStateStore mStateStore;

    @Before
    public void setUp() throws IOException {
        mTestFile = mTempFolder.newFile(TEST_FILE_NAME);
        Executor directExecutor = Runnable::run;
        mStateStore = new ProtoStateStore(mTestFile, directExecutor);
    }

    @Test
    public void readState_noFileExists_returnsEmptyState() throws InterruptedException {
        AtomicReference<RateLimiterState> resultRef = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        mStateStore.readState(
                new OutcomeReceiver<>() {
                    @Override
                    public void onResult(RateLimiterState result) {
                        resultRef.set(result);
                        latch.countDown();
                    }

                    @Override
                    public void onError(Throwable error) {
                        latch.countDown();
                    }
                });

        assertThat(latch.await(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        RateLimiterState state = resultRef.get();

        assertThat(state).isNotNull();

        assertThat(state.getDeviceTimestamps()).isNotNull();
        assertThat(state.getDeviceTimestamps()).isEmpty();
        assertThat(state.getUidTimestamps()).isNotNull();
        assertThat(state.getUidTimestamps()).isEmpty();
        assertThat(state.getSignatureTimestamps()).isNotNull();
        assertThat(state.getSignatureTimestamps()).isEmpty();
    }

    @Test
    public void writeAndReadState_roundTrip_preservesData() throws InterruptedException {
        // 1. Create a state object with some data.
        RateLimiterState originalState = RateLimiterState.createEmpty();
        originalState.getDeviceTimestamps().add(TIMESTAMP_1);
        originalState.getDeviceTimestamps().add(TIMESTAMP_2);
        originalState.getUidTimestamps().put(UID_1, UID_TIMESTAMP_1);
        originalState.getSignatureTimestamps().put(SIGNATURE_KEY, SIGNATURE_TIMESTAMP);

        // 2. Write it to disk.
        mStateStore.writeState(originalState);

        // 3. Read it back.
        AtomicReference<RateLimiterState> resultRef = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        mStateStore.readState(
                new OutcomeReceiver<>() {
                    @Override
                    public void onResult(RateLimiterState result) {
                        resultRef.set(result);
                        latch.countDown();
                    }

                    @Override
                    public void onError(Throwable error) {
                        latch.countDown();
                    }
                });

        assertThat(latch.await(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        RateLimiterState loadedState = resultRef.get();

        // 4. Verify the data is identical.

        assertThat(loadedState.getDeviceTimestamps())
                .containsExactly(TIMESTAMP_1, TIMESTAMP_2)
                .inOrder();
        assertThat(loadedState.getUidTimestamps()).containsExactly(UID_1, UID_TIMESTAMP_1);
        assertThat(loadedState.getSignatureTimestamps())
                .containsExactly(SIGNATURE_KEY, SIGNATURE_TIMESTAMP);
    }

    @Test
    public void writeState_overwritesExistingFile() throws InterruptedException {
        // Write an initial state.
        RateLimiterState initialState = RateLimiterState.createEmpty();
        initialState.getDeviceTimestamps().add(INITIAL_TIMESTAMP);
        mStateStore.writeState(initialState);

        // Write a new state.
        RateLimiterState newState = RateLimiterState.createEmpty();
        newState.getUidTimestamps().put(UID_2, UID_TIMESTAMP_2);
        mStateStore.writeState(newState);

        // Read it back and verify it's the new state.
        AtomicReference<RateLimiterState> resultRef = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        mStateStore.readState(
                new OutcomeReceiver<>() {
                    @Override
                    public void onResult(RateLimiterState result) {
                        resultRef.set(result);
                        latch.countDown();
                    }

                    @Override
                    public void onError(Throwable error) {
                        latch.countDown();
                    }
                });

        assertThat(latch.await(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        RateLimiterState loadedState = resultRef.get();

        assertThat(loadedState.getDeviceTimestamps()).isEmpty();

        assertThat(loadedState.getUidTimestamps()).containsExactly(UID_2, UID_TIMESTAMP_2);
    }

    @Test
    public void readState_corruptedFile_callsOnError() throws Exception {
        // Write garbage to the file.
        try (FileOutputStream fos = new FileOutputStream(mTestFile)) {
            fos.write(GARBAGE_DATA);
        }

        AtomicReference<Throwable> errorRef = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        mStateStore.readState(
                new OutcomeReceiver<>() {
                    @Override
                    public void onResult(RateLimiterState result) {
                        latch.countDown();
                    }

                    @Override
                    public void onError(Throwable error) {
                        errorRef.set(error);
                        latch.countDown();
                    }
                });

        assertThat(latch.await(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(errorRef.get()).isInstanceOf(IOException.class);
    }
}
