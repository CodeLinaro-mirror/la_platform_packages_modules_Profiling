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

package com.android.os.profiling.anomaly;

import static com.android.os.profiling.anomaly.AnomalyDetectorService.FULL_BINDER_SPAM_DETECTION_CONFIG_FILE_NAME;
import static com.android.os.profiling.anomaly.AnomalyDetectorShellCommandHandler.FULL_BINDER_SPAM_DETECTION;
import static com.android.os.profiling.anomaly.AnomalyDetectorShellCommandHandler.STOP_ENABLING;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.TruthJUnit.assume;

import android.os.Binder;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.io.FileDescriptor;
import java.nio.file.Files;

/** Tests for {@link AnomalyDetectorShellCommandHandler}. */
@RunWith(AndroidJUnit4.class)
public final class AnomalyDetectorShellCommandHandlerTest {
    @Rule public final TemporaryFolder mTemporaryFolder = new TemporaryFolder();
    @Rule public final MockitoRule mMockitoRule = MockitoJUnit.rule();

    @Mock Binder mBinder;
    @Mock FileDescriptor mMockIn;
    @Mock FileDescriptor mMockOut;
    @Mock FileDescriptor mMockErr;

    private AnomalyDetectorShellCommandHandler mShellCommandHandler;

    @Before
    public void setUp() {
        mShellCommandHandler =
                new AnomalyDetectorShellCommandHandler(mTemporaryFolder.getRoot().toPath());
    }

    @Test
    public void enableFullBinderSpamDetection_configFileInPlace() {
        assertThat(executeCommand(new String[] {FULL_BINDER_SPAM_DETECTION})).isEqualTo(0);
        assertThat(
                        Files.exists(
                                mTemporaryFolder
                                        .getRoot()
                                        .toPath()
                                        .resolve(FULL_BINDER_SPAM_DETECTION_CONFIG_FILE_NAME)))
                .isTrue();
    }

    @Test
    public void enableFullBinderSpamDetection_twice_configFileInPlace() {
        assume().that(executeCommand(new String[] {FULL_BINDER_SPAM_DETECTION})).isEqualTo(0);

        assertThat(executeCommand(new String[] {FULL_BINDER_SPAM_DETECTION})).isEqualTo(0);
        assertThat(
                        Files.exists(
                                mTemporaryFolder
                                        .getRoot()
                                        .toPath()
                                        .resolve(FULL_BINDER_SPAM_DETECTION_CONFIG_FILE_NAME)))
                .isTrue();
    }

    @Test
    public void stopEnablingFullBinderSpamDetection_configFileIsRemoved() {
        assume().that(executeCommand(new String[] {FULL_BINDER_SPAM_DETECTION})).isEqualTo(0);

        assertThat(executeCommand(new String[] {FULL_BINDER_SPAM_DETECTION, STOP_ENABLING}))
                .isEqualTo(0);
        assertThat(
                        Files.exists(
                                mTemporaryFolder
                                        .getRoot()
                                        .toPath()
                                        .resolve(FULL_BINDER_SPAM_DETECTION_CONFIG_FILE_NAME)))
                .isFalse();
    }

    @Test
    public void stopEnablingFullBinderSpamDetection_configFileNotExists() {
        assume().that(
                        Files.exists(
                                mTemporaryFolder
                                        .getRoot()
                                        .toPath()
                                        .resolve(FULL_BINDER_SPAM_DETECTION_CONFIG_FILE_NAME)))
                .isFalse();

        assertThat(executeCommand(new String[] {FULL_BINDER_SPAM_DETECTION, STOP_ENABLING}))
                .isEqualTo(0);
    }

    private int executeCommand(String[] args) {
        return mShellCommandHandler.exec(mBinder, mMockIn, mMockOut, mMockErr, args);
    }
}
