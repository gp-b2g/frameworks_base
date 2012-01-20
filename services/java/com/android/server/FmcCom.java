/* Copyright (c) 2011 Code Aurora Forum. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *     * Redistributions of source code must retain the above copyright
 *       notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above copyright
 *       notice, this list of conditions and the following disclaimer in the
 *       documentation and/or other materials provided with the distribution.
 *     * Neither the name of Code Aurora nor
 *       the names of its contributors may be used to endorse or promote
 *       products derived from this software without specific prior written
 *       permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NON-INFRINGEMENT ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS;
 * OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR
 * OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF
 * ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *
 */

package com.android.server;

import static com.android.server.FmcStateMachine.*;

import java.io.*;
import java.net.*;
import java.util.concurrent.BlockingQueue;

import android.util.Log;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;

public abstract class FmcCom implements Runnable {

	/* Private */
    private final static String TAG = "FmcCom";
    private final static String SOCKET_PATH = "/data/radio/ds_fmc_app_call_mgr_sock";

    /* Protected */
    protected static final boolean DBG = true;

    protected final static int FMC_TRIGGER_RESP_LEN = 132;

    protected FmcCom instance = null;
    protected FmcStateMachine fsm = null;
    protected BlockingQueue<Integer> receiveQueue = null;

    /* Protected shared objects */
    protected static LocalSocket ds_sock = null;
    protected static class fmc_trigger_resp {
        int ds_fmc_app_fmc_bearer_status; /* FMC bearer status */
        sockaddr_in tunnel_dest_ip; /* Tunnel destination IP address */
    };

    protected static class sockaddr_in {
        short sin_family;
        short sin_port;
        byte  sin_addr[];
        char  sin_zero[];
    };

    /* Constructor */
    protected FmcCom() {
        if (DBG) Log.d(TAG, "constructor");
    }

    @Override
    public void run() {
        if (DBG) Log.d(TAG, "run");
    }

    private boolean openSocket() {
        if (DBG) Log.d(TAG, "openSocket");

        try {
            ds_sock = new LocalSocket();
            if (ds_sock == null) {
                Log.e(TAG, "openSocket could not create LocalSocket");
                return false;
            }
            ds_sock.connect(new LocalSocketAddress(SOCKET_PATH, LocalSocketAddress.Namespace.FILESYSTEM));
            return ds_sock.isConnected();
        } catch (IOException e) {
            Log.e(TAG, "openSocket IOException=" + e.getMessage());
            return false;
        }
    }

    boolean establishSocket() {
        if (DBG) Log.d(TAG, "establishSocket");

        if (ds_sock == null) {
            if (DBG) Log.d(TAG, "readFromDS ds_sock is null");
            if (!openSocket()) {
                Log.e(TAG, "readFromDS failed to open socket");
                return false;
            }
        } else if (!ds_sock.isConnected()) {
            if (DBG) Log.d(TAG, "readFromDS ds_sock is not connected");
            if (!openSocket()) {
                Log.e(TAG, "establishSocket failed to open socket");
                return false;
            }
        }

        if (DBG) Log.d(TAG, "establishSocket ok");
        return true;
    }
};

class FmcComSend extends FmcCom {

    private static final String TAG = "FmcComSend";

    protected FmcComSend(
        FmcStateMachine callback,
        BlockingQueue<Integer> sendQueue
    )
    {
        if (DBG) Log.d(TAG, "constructor");

        fsm = callback;
        receiveQueue = sendQueue;
    }

    @Override
    public void run() {
        if (DBG) Log.d(TAG, "run");

        int command = 0;
        while (true) {
            try {
                 command = receiveQueue.take();
                 if (DBG) Log.d(TAG, "run received command=" + command);
                 sendToDS(command);
            } catch (InterruptedException e) {
                if (DBG) Log.d(TAG, "run InterruptedException=" + e.getMessage());
            } catch (Exception e) {
                if (DBG) Log.d(TAG, "run Exception=" + e.getMessage());
            }
        }
    }

    void sendToDS(int command) {
        if (DBG) Log.d(TAG, "sendToDS");

        if (!establishSocket()) {
            if (DBG) Log.d(TAG, "sendToDS could not establish socket");
            fsm.sendMessage(FMC_MSG_FAILURE);
            return;
        }

        try {
            if (DBG) Log.d(TAG, "sendToDS writing to OutputStream");

            byte[] b = new byte[2];
            b[0] = (byte) command;
            ds_sock.getOutputStream().write(b);

            if (DBG) Log.d(TAG, "sendToDS (exit)");

            return;
        } catch (IOException e) {
            if (DBG) Log.d(TAG, "sendToDS IOException=" + e.getMessage());
            fsm.sendMessage(FMC_MSG_FAILURE);
            return;
        }
    }
};

class FmcComRead extends FmcCom {
    private static final String TAG = "FmcComRead";

    protected FmcComRead(FmcStateMachine callback) {
        if (DBG) Log.d(TAG, "constructor");

        fsm = callback;
    }

    @Override
    public void run() {
        if (DBG) Log.d(TAG, "run");

        fmc_trigger_resp resp = new fmc_trigger_resp();
        while (true) {
            try {
                resp = readFromDS();
                if (resp != null) {
                    if (resp.tunnel_dest_ip != null && resp.tunnel_dest_ip.sin_addr != null) {
                        fsm.setDestIp(resp.tunnel_dest_ip.sin_addr);
                    }
                    switch (resp.ds_fmc_app_fmc_bearer_status) {
                        case DS_FMC_APP_FMC_BEARER_ENABLED:
                            fsm.sendMessage(FMC_MSG_BEARER_UP);
                            break;
                        case DS_FMC_APP_FMC_BEARER_DISABLED:
                            fsm.sendMessage(FMC_MSG_BEARER_DOWN);
                            break;
                        default:
                            Log.e(TAG, "run unhandled resp from DS=" + resp.ds_fmc_app_fmc_bearer_status);
                            fsm.sendMessage(FMC_MSG_FAILURE);
                            break;
                    }
                } else {
                    if (DBG) Log.d(TAG, "run resp is null");

                    fsm.sendMessage(FMC_MSG_FAILURE);
                    //TODO: something more intelligent while we constantly receive -1 from read()
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        if (DBG) Log.d(TAG, "run InterruptedException=" + e.getMessage());
                    }
                }
            } catch (Exception e) {
                if (DBG) Log.d(TAG, "run Exception=" + e.getMessage());
            }
        }
    }

    fmc_trigger_resp readFromDS() {
        if (DBG) Log.d(TAG, "readFromDS");

        if (!establishSocket()) {
            if (DBG) Log.d(TAG, "readFromDS could not establish socket");
            return null;
        }

        byte[] buffer = new byte[FMC_TRIGGER_RESP_LEN];
        fmc_trigger_resp resp = new fmc_trigger_resp();

        try {
            if (DBG) Log.d(TAG, "readFromDS waiting on InputStream ");

            int len = ds_sock.getInputStream().read(buffer, 0, FMC_TRIGGER_RESP_LEN);
            if (DBG) Log.d(TAG, "readFromDS bytes=" + len);

            if (len < FMC_TRIGGER_RESP_LEN) {
                if (DBG) Log.d(TAG, "readFromDS closing socket");
                ds_sock.close();
                ds_sock = null;
                return null;
            } else {
                resp.ds_fmc_app_fmc_bearer_status = (int) buffer[0];
                resp.tunnel_dest_ip = new sockaddr_in();
                resp.tunnel_dest_ip.sin_addr = new byte[4];
                for (int i = 0; i < 4; i++) {
                    resp.tunnel_dest_ip.sin_addr[i] = buffer[i + 8];
                }
                //for (int i = 0; i < len; i++) {
                //    if (DBG) Log.d(TAG, "buffer[" + i + "]\t" + (int) buffer[i]);
                return resp;
            }
        } catch (IOException e) {
            if (DBG) Log.d(TAG, "readFromDS IOException=" + e.getMessage());

            ds_sock = null;
            return null;
        } catch (NullPointerException e) {
            if (DBG) Log.d(TAG, "readFromDS NullPointerException=" + e.getMessage());

            return null;
        } catch (Exception e) {
            if (DBG) Log.d(TAG, "readFromDS Exception=" + e.getMessage());

            return null;
        }
    }
}
