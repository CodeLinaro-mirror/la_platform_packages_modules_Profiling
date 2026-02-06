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

package com.android.os.profiling.anomaly.util;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.util.Log;

import androidx.test.runner.AndroidJUnit4;

import com.android.os.profiling.anomaly.util.LogUtil.LoggableChecker;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@RunWith(AndroidJUnit4.class)
public class LogUtilTests {

    @Rule public MockitoRule mMockitoRule = MockitoJUnit.rule();

    private static final String TEST_TAG = "MyTag";
    private static final String TEST_MSG_D = "Debug";
    private static final String TEST_MSG_E = "Error";
    private static final String TEST_MSG_V = "Verbose";
    private static final String TEST_MSG_I = "Info";
    private static final String TEST_MSG_W = "Warning";
    private static final String TEST_THROWABLE_MSG = "test";

    @Mock private Logger mMockLogger;
    @Mock private LoggableChecker mMockLoggableChecker;

    @Test
    public void d_inDebugBuild_logsMessage() {
        LogUtil logUtil =
                new LogUtil(mMockLogger, /* isDebuggable= */ true, TEST_TAG, mMockLoggableChecker);
        logUtil.d(TEST_MSG_D);
        verify(mMockLogger, times(1)).d(TEST_TAG, TEST_MSG_D);
    }

    @Test
    public void d_withThrowable_inDebugBuild_logsMessage() {
        LogUtil logUtil =
                new LogUtil(mMockLogger, /* isDebuggable= */ true, TEST_TAG, mMockLoggableChecker);
        Throwable tr = new Throwable(TEST_THROWABLE_MSG);
        logUtil.d(TEST_MSG_D, tr);
        verify(mMockLogger, times(1)).d(TEST_TAG, TEST_MSG_D, tr);
    }

    @Test
    public void d_inReleaseBuild_doesNotLogMessage() {
        when(mMockLoggableChecker.isLoggable(TEST_TAG, Log.DEBUG)).thenReturn(false);
        LogUtil logUtil =
                new LogUtil(mMockLogger, /* isDebuggable= */ false, TEST_TAG, mMockLoggableChecker);
        logUtil.d(TEST_MSG_D);
        verify(mMockLogger, never()).d(anyString(), anyString());
    }

    @Test
    public void e_inDebugBuild_logsMessage() {
        LogUtil logUtil =
                new LogUtil(mMockLogger, /* isDebuggable= */ true, TEST_TAG, mMockLoggableChecker);
        logUtil.e(TEST_MSG_E);
        verify(mMockLogger, times(1)).e(TEST_TAG, TEST_MSG_E);
    }

    @Test
    public void e_inReleaseBuild_logsMessage() {
        when(mMockLoggableChecker.isLoggable(TEST_TAG, Log.ERROR)).thenReturn(true);
        LogUtil logUtil =
                new LogUtil(mMockLogger, /* isDebuggable= */ false, TEST_TAG, mMockLoggableChecker);
        logUtil.e(TEST_MSG_E);
        verify(mMockLogger, times(1)).e(TEST_TAG, TEST_MSG_E);
    }

    @Test
    public void v_inDebugBuild_logsMessage() {
        LogUtil logUtil =
                new LogUtil(mMockLogger, /* isDebuggable= */ true, TEST_TAG, mMockLoggableChecker);
        logUtil.v(TEST_MSG_V);
        verify(mMockLogger, times(1)).v(TEST_TAG, TEST_MSG_V);
    }

    @Test
    public void v_inReleaseBuild_doesNotLogMessage() {
        when(mMockLoggableChecker.isLoggable(TEST_TAG, Log.VERBOSE)).thenReturn(false);
        LogUtil logUtil =
                new LogUtil(mMockLogger, /* isDebuggable= */ false, TEST_TAG, mMockLoggableChecker);
        logUtil.v(TEST_MSG_V);
        verify(mMockLogger, never()).v(anyString(), anyString());
    }

    @Test
    public void i_inDebugBuild_logsMessage() {
        LogUtil logUtil =
                new LogUtil(mMockLogger, /* isDebuggable= */ true, TEST_TAG, mMockLoggableChecker);
        logUtil.i(TEST_MSG_I);
        verify(mMockLogger, times(1)).i(TEST_TAG, TEST_MSG_I);
    }

    @Test
    public void i_inReleaseBuild_logsMessage() {
        when(mMockLoggableChecker.isLoggable(TEST_TAG, Log.INFO)).thenReturn(true);
        LogUtil logUtil =
                new LogUtil(mMockLogger, /* isDebuggable= */ false, TEST_TAG, mMockLoggableChecker);
        logUtil.i(TEST_MSG_I);
        verify(mMockLogger, times(1)).i(TEST_TAG, TEST_MSG_I);
    }

    @Test
    public void w_inDebugBuild_logsMessage() {
        LogUtil logUtil =
                new LogUtil(mMockLogger, /* isDebuggable= */ true, TEST_TAG, mMockLoggableChecker);
        logUtil.w(TEST_MSG_W);
        verify(mMockLogger, times(1)).w(TEST_TAG, TEST_MSG_W);
    }

    @Test
    public void w_inReleaseBuild_logsMessage() {
        when(mMockLoggableChecker.isLoggable(TEST_TAG, Log.WARN)).thenReturn(true);
        LogUtil logUtil =
                new LogUtil(mMockLogger, /* isDebuggable= */ false, TEST_TAG, mMockLoggableChecker);
        logUtil.w(TEST_MSG_W);
        verify(mMockLogger, times(1)).w(TEST_TAG, TEST_MSG_W);
    }

    @Test
    public void w_withThrowable_inDebugBuild_logsMessage() {
        LogUtil logUtil =
                new LogUtil(mMockLogger, /* isDebuggable= */ true, TEST_TAG, mMockLoggableChecker);
        Throwable tr = new Throwable(TEST_THROWABLE_MSG);
        logUtil.w(TEST_MSG_W, tr);
        verify(mMockLogger, times(1)).w(TEST_TAG, TEST_MSG_W, tr);
    }

    @Test
    public void w_withThrowable_inReleaseBuild_logsMessage() {
        when(mMockLoggableChecker.isLoggable(TEST_TAG, Log.WARN)).thenReturn(true);
        LogUtil logUtil =
                new LogUtil(mMockLogger, /* isDebuggable= */ false, TEST_TAG, mMockLoggableChecker);
        Throwable tr = new Throwable(TEST_THROWABLE_MSG);
        logUtil.w(TEST_MSG_W, tr);
        verify(mMockLogger, times(1)).w(TEST_TAG, TEST_MSG_W, tr);
    }

    @Test
    public void w_justThrowable_inDebugBuild_logsMessage() {
        LogUtil logUtil =
                new LogUtil(mMockLogger, /* isDebuggable= */ true, TEST_TAG, mMockLoggableChecker);
        Throwable tr = new Throwable(TEST_THROWABLE_MSG);
        logUtil.w(tr);
        verify(mMockLogger, times(1)).w(TEST_TAG, tr);
    }

    @Test
    public void w_justThrowable_inReleaseBuild_logsMessage() {
        when(mMockLoggableChecker.isLoggable(TEST_TAG, Log.WARN)).thenReturn(true);
        LogUtil logUtil =
                new LogUtil(mMockLogger, /* isDebuggable= */ false, TEST_TAG, mMockLoggableChecker);
        Throwable tr = new Throwable(TEST_THROWABLE_MSG);
        logUtil.w(tr);
        verify(mMockLogger, times(1)).w(TEST_TAG, tr);
    }
}
