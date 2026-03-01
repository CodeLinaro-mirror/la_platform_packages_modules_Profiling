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

package com.android.os.profiling.anomaly.handler;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import android.util.StatsEvent;
import android.util.StatsEventTestUtils;
import android.util.StatsLog;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.modules.utils.testing.ExtendedMockitoRule;
import com.android.os.AtomsProto;
import com.android.os.profiling.anomaly.handler.AnomalyStatsAtomsLog;
import com.android.os.profiling.anomaly.attribute.BinderSpamDetailsAttribute;
import com.android.os.profiling.anomaly.attribute.UidAttribute;
import com.android.os.profiling.anomaly.core.AnomalyReport;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;

import java.time.Duration;

/**
 * Unit tests for {@link AnomalyStatsHandler}.
 *
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class AnomalyStatsHandlerTest {
    private static final int ANOMALY_STATS_BINDER_SPAM_ATOM_ID = 1286;

    @Rule
    public final ExtendedMockitoRule mExtendedMockitoRule =
            new ExtendedMockitoRule.Builder(this).mockStatic(StatsLog.class).build();

    @Captor private ArgumentCaptor<StatsEvent> mStatsEventCaptor;

    @Mock private AnomalyReport mMockReport;

    private AnomalyStatsHandler mHandler;

    @Before
    public void setUp() {
        mHandler = new AnomalyStatsHandler();
    }

    private static StatsEvent buildAnomalyStatsBinderSpamEvent(int uid, String binderInterface,
            String binderMethod, int callCount, long timespanMs) {
        return StatsEvent.newBuilder()
                .setAtomId(ANOMALY_STATS_BINDER_SPAM_ATOM_ID)
                .writeInt(uid)
                .writeString(binderInterface)
                .writeString(binderMethod)
                .writeInt(callCount)
                .writeLong(timespanMs)
                .build();
    }

    @Test
    public void execute_withBinderSpamDetails_writesAtom() throws Exception {
        final int uid = 1001;
        final String interfaceName = "com.foo.IBar";
        final String methodName = "baz";
        final int callCount = 100;
        final long intervalMs = 1000;

        UidAttribute uidAttribute = new UidAttribute(uid);
        BinderSpamDetailsAttribute binderSpamDetails = new BinderSpamDetailsAttribute(
                interfaceName, methodName, callCount, Duration.ofMillis(intervalMs),
                -1, Duration.ZERO);

        when(mMockReport.get(UidAttribute.class)).thenReturn(uidAttribute);
        when(mMockReport.get(BinderSpamDetailsAttribute.class)).thenReturn(binderSpamDetails);

        mHandler.execute(mMockReport);

        verify(() -> StatsLog.write(mStatsEventCaptor.capture()));
        StatsEvent actualEvent = mStatsEventCaptor.getValue();
        StatsEvent expectedEvent = buildAnomalyStatsBinderSpamEvent(
                uid, interfaceName, methodName, callCount, intervalMs);

        AtomsProto.Atom actualAtom = StatsEventTestUtils.convertToAtom(actualEvent);
        AtomsProto.Atom expectedAtom = StatsEventTestUtils.convertToAtom(expectedEvent);

        assertEquals(expectedAtom, actualAtom);
        assertEquals(ANOMALY_STATS_BINDER_SPAM_ATOM_ID,
                AnomalyStatsAtomsLog.ANOMALY_STATS_BINDER_SPAM);
    }

    @Test
    public void execute_nullUid_doesNotWriteAtom() {
        final String interfaceName = "com.foo.IBar";
        final String methodName = "baz";
        final int callCount = 100;
        final long intervalMs = 1000;

        BinderSpamDetailsAttribute binderSpamDetails = new BinderSpamDetailsAttribute(
                interfaceName, methodName, callCount, Duration.ofMillis(intervalMs),
                -1, Duration.ZERO);

        when(mMockReport.get(UidAttribute.class)).thenReturn(null);
        when(mMockReport.get(BinderSpamDetailsAttribute.class)).thenReturn(binderSpamDetails);

        mHandler.execute(mMockReport);

        verify(() -> StatsLog.write(mStatsEventCaptor.capture()), never());
    }

    @Test
    public void execute_notBinderSpam_doesNotWriteAtom() {
        final int uid = 1001;

        UidAttribute uidAttribute = new UidAttribute(uid);

        when(mMockReport.get(UidAttribute.class)).thenReturn(uidAttribute);
        when(mMockReport.get(BinderSpamDetailsAttribute.class)).thenReturn(null);

        mHandler.execute(mMockReport);

        verify(() -> StatsLog.write(mStatsEventCaptor.capture()), never());
    }
}
