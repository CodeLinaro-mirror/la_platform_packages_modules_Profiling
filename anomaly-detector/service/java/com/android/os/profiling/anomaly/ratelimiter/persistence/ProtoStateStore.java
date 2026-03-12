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

import android.os.OutcomeReceiver;
import android.util.AtomicFile;

import com.android.os.profiling.anomaly.ratelimiter.proto.RateLimiterStateProto;
import com.android.os.profiling.anomaly.util.LogUtil;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.Executor;

/**
 * A Protobuf-based implementation of {@link RateLimiterStateStore} that persists the state to disk
 * using {@link AtomicFile} for safe, atomic writes.
 *
 * @hide
 */
public final class ProtoStateStore implements RateLimiterStateStore {
    private static final LogUtil sLog = new LogUtil("ProtoStateStore");

    private final File mStateFile;
    private final Executor mIoExecutor;

    public ProtoStateStore(File stateFile, Executor ioExecutor) {
        mStateFile = stateFile;
        mIoExecutor = ioExecutor;
    }

    @Override
    public void readState(OutcomeReceiver<RateLimiterState, Throwable> callback) {
        mIoExecutor.execute(
                () -> {
                    if (!mStateFile.exists()) {
                        callback.onResult(RateLimiterState.createEmpty());
                        return;
                    }

                    AtomicFile atomicFile = new AtomicFile(mStateFile);
                    try (FileInputStream fis = atomicFile.openRead()) {
                        RateLimiterStateProto proto = RateLimiterStateProto.parseFrom(fis);
                        callback.onResult(convertProtoToState(proto));
                    } catch (IOException e) {
                        sLog.e("Failed to read rate limiter state, delegating failure.", e);
                        // If the file can't be read, delegate the error to the receiver, which
                        // should "fail-closed".
                        callback.onError(e);
                    }
                });
    }

    @Override
    public void writeState(RateLimiterState state) {
        mIoExecutor.execute(
                () -> {
                    RateLimiterStateProto proto = convertStateToProto(state);

                    AtomicFile atomicFile = new AtomicFile(mStateFile);
                    FileOutputStream fos = null;
                    try {
                        fos = atomicFile.startWrite();
                        proto.writeTo(fos);
                        atomicFile.finishWrite(fos);
                    } catch (IOException e) {
                        if (fos != null) {
                            atomicFile.failWrite(fos);
                        }
                        sLog.e("Failed to write rate limiter state", e);
                    }
                });
    }

    private RateLimiterState convertProtoToState(RateLimiterStateProto proto) {
        return new RateLimiterState(
                new ArrayList<>(proto.getDeviceTimestampsList()),
                new HashMap<>(proto.getUidTimestampsMap()),
                new HashMap<>(proto.getSignatureTimestampsMap()));
    }

    private RateLimiterStateProto convertStateToProto(RateLimiterState state) {
        return RateLimiterStateProto.newBuilder()
                .addAllDeviceTimestamps(state.getDeviceTimestamps())
                .putAllUidTimestamps(state.getUidTimestamps())
                .putAllSignatureTimestamps(state.getSignatureTimestamps())
                .build();
    }
}
