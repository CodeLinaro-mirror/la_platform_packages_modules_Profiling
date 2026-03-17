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

import com.android.internal.annotations.VisibleForTesting;
import com.android.modules.utils.BasicShellCommandHandler;
import com.android.os.profiling.anomaly.util.LogUtil;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

final class AnomalyDetectorShellCommandHandler extends BasicShellCommandHandler {
    private static final LogUtil sLog = new LogUtil("AnomalyDetectorShellCommandHandler");

    @VisibleForTesting
    static final String FULL_BINDER_SPAM_DETECTION = "full-binder-spam-detection";

    public static final String STOP_ENABLING = "--stopEnabling";
    private final Path mAnomalyServiceDir;

    AnomalyDetectorShellCommandHandler(Path anomalyServiceDir) {
        mAnomalyServiceDir = anomalyServiceDir;
    }

    @Override
    public int onCommand(String cmd) {
        if (cmd == null) {
            return handleDefaultCommands(cmd);
        }
        if (cmd.equals(FULL_BINDER_SPAM_DETECTION)) {
            return onFullBinderSpamDetection();
        }
        return handleDefaultCommands(cmd);
    }

    private int onFullBinderSpamDetection() {
        if (mAnomalyServiceDir == null) {
            getErrPrintWriter().println("Command failed. Could not find the directory for config!");
            return -1;
        }
        Path alwaysMonitorBinderToSystemServerConfig =
                mAnomalyServiceDir.resolve(
                        AnomalyDetectorService.FULL_BINDER_SPAM_DETECTION_CONFIG_FILE_NAME);

        if (STOP_ENABLING.equals(getNextOption())) {
            try {
                Files.delete(alwaysMonitorBinderToSystemServerConfig);
                getOutPrintWriter()
                        .println(
                                "Success! Full binder spam detection will not be enabled after"
                                        + " reboot.");
            } catch (NoSuchFileException e) {
                getOutPrintWriter().println("No need to stop enabling full binder spam detection.");
            } catch (IOException e) {
                sLog.e("Failed to delete the config file!", e);
                getErrPrintWriter()
                        .println("Failed! Could not stop enabling full binder spam detection.");
                return -1;
            }
            return 0;
        }

        try {
            Files.createFile(alwaysMonitorBinderToSystemServerConfig);
        } catch (FileAlreadyExistsException e) {
            // File already exists, we are good.
        } catch (IOException e) {
            sLog.e("Failed to create the config file!", e);
            getErrPrintWriter().println("Failed!. Could not create the config file");
            return -1;
        }
        getOutPrintWriter()
                .println(
                        "Success! Reboot your device to detect binder spam for all transactions"
                                + " made to system server.");
        return 0;
    }

    @Override
    public void onHelp() {
        PrintWriter pw = getOutPrintWriter();
        pw.println("Anomaly Detector (anomaly_detector) commands:");
        pw.println("  help or -h");
        pw.println("    Print this help text");
        pw.println("  full-binder-spam-detection [--stopEnabling]");
        pw.println("    After reboot, enable full ability to detect binder spam anomaly.");
        pw.println("    Re-run this command for successive reboots.");
        pw.println("    Use the optional argument to stop enabling after reboot.");
    }
}
