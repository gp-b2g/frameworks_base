/*
 * Copyright (C) 2012 Code Aurora Forum. All rights reserved.
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.server.sm;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.util.Slog;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;

/** {@hide} */
public class PowerSaver {
    private static final String TAG = "PowerSaver";

    public static final int LOW_BATTERY_MODE = 0;
    public static final int LIGHT_COMPUTING_MODE = 1;
    public static final int HIGH_TEMPERATURE_MODE = 2;
    public static final int SCREEN_OFF_MODE = 3;
    public static final int SCREEN_ON_MODE = 4;

    InputStream mIn;

    OutputStream mOut;

    LocalSocket mSocket;

    byte buf[] = new byte[1024];

    int buflen = 0;

    private boolean connect() {
        if (mSocket != null) {
            return true;
        }
        Slog.e(TAG, "connecting...");
        try {
            mSocket = new LocalSocket();

            LocalSocketAddress address = new LocalSocketAddress("powerSaverService",
                    LocalSocketAddress.Namespace.RESERVED);

            mSocket.connect(address);

            mIn = mSocket.getInputStream();
            mOut = mSocket.getOutputStream();
        } catch (IOException ex) {
            disconnect();
            return false;
        }
        return true;
    }

    private void disconnect() {
        Slog.i(TAG, "disconnecting...");
        try {
            if (mSocket != null)
                mSocket.close();
        } catch (IOException ex) {
        }
        try {
            if (mIn != null)
                mIn.close();
        } catch (IOException ex) {
        }
        try {
            if (mOut != null)
                mOut.close();
        } catch (IOException ex) {
        }
        mSocket = null;
        mIn = null;
        mOut = null;
    }

    private boolean readBytes(byte buffer[], int len) {
        int off = 0, count;
        if (len < 0)
            return false;
        while (off != len) {
            try {
                count = mIn.read(buffer, off, len - off);
                if (count <= 0) {
                    Slog.e(TAG, "read error " + count);
                    break;
                }
                off += count;
            } catch (IOException ex) {
                Slog.e(TAG, "read exception");
                break;
            }
        }

        if (off == len)
            return true;
        disconnect();
        return false;
    }

    private boolean readReply() {
        int len;
        buflen = 0;
        if (!readBytes(buf, 2))
            return false;
        len = (((int) buf[0]) & 0xff) | ((((int) buf[1]) & 0xff) << 8);
        if ((len < 1) || (len > 1024)) {
            Slog.e(TAG, "invalid reply length (" + len + ")");
            disconnect();
            return false;
        }
        if (!readBytes(buf, len))
            return false;
        buflen = len;
        return true;
    }

    private boolean writeCommand(String _cmd) {
        byte[] cmd = _cmd.getBytes();
        int len = cmd.length;
        if ((len < 1) || (len > 1024))
            return false;
        buf[0] = (byte) (len & 0xff);
        buf[1] = (byte) ((len >> 8) & 0xff);
        try {
            mOut.write(buf, 0, 2);
            mOut.write(cmd, 0, len);
        } catch (IOException ex) {
            Slog.e(TAG, "write error");
            disconnect();
            return false;
        }
        return true;
    }

    private synchronized String transaction(String cmd) {
        if (!connect()) {
            Slog.e(TAG, "connection failed");
            return "-1";
        }

        if (!writeCommand(cmd)) {
            Slog.e(TAG, "write command failed? reconnect!");
            if (!connect() || !writeCommand(cmd)) {
                return "-1";
            }
        }

        if (readReply()) {
            String s = new String(buf, 0, buflen);
            return s;
        } else {
            return "-1";
        }
    }

    private int execute(String cmd) {
        String res = transaction(cmd);
        try {
            return Integer.parseInt(res);
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    public int setCpuMode(int mode) {
        StringBuilder builder = new StringBuilder("setcpumode");
        builder.append(' ');
        builder.append(mode);
        return execute(builder.toString());
    }
}
