/**
 * Copyright (C) 2012, Code Aurora Forum. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *     * Redistributions of source code must retain the above copyright
 *       notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above
 *       copyright notice, this list of conditions and the following
 *       disclaimer in the documentation and/or other materials provided
 *       with the distribution.
 *     * Neither the name of Code Aurora Forum, Inc. nor the names of its
 *       contributors may be used to endorse or promote products derived
 *       from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED "AS IS" AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NON-INFRINGEMENT
 * ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS
 * BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR
 * BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
 * OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN
 * IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.android.server.sm;

import com.android.server.Watchdog;
import com.android.server.NativeDaemonConnector;
import com.android.server.INativeDaemonConnectorCallbacks;
import android.util.Log;
import android.util.Slog;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteCallbackList;
import android.os.RemoteException;

import android.security.ICallToken;
import android.security.IMessageToken;
import android.security.ISecurityCallback;
import android.security.ISecurityManager;
import android.security.SecurityManagerNative;
import android.security.SecurityRecord;
import android.security.SecurityResult;

import java.util.concurrent.CountDownLatch;

/** {@hide} */
public final class RadioSecurityController implements Watchdog.Monitor {

    private static final String TAG = "RadioSecurityController";
    private static final boolean DBG = true;
    private static final String SECD_TAG = "SecdConnector";
    /**
     * connector object for communicating with secd
     */
    private NativeDaemonConnector mConnector;

    private Thread mThread;
    private final CountDownLatch mConnectedSignal = new CountDownLatch(1);

    /**
     * Let us know the daemon is connected
     */
    protected void onDaemonConnected() {
        if (DBG)
            Slog.d(TAG, "onConnected");
        mConnectedSignal.countDown();
    }

    /** {@inheritDoc} */
    public void monitor() {
        if (mConnector != null) {
            mConnector.monitor();
        }
    }

    private RadioSecurityController() {
        mConnector = new NativeDaemonConnector(
                new SecdCallbackReceiver(), "secd", 10, SECD_TAG);
        mThread = new Thread(mConnector, SECD_TAG);

        // Add ourself to the Watchdog monitors.
        Watchdog.getInstance().addMonitor(this);
    }

    private static SecurityManagerService mService;

    public static RadioSecurityController create(final SecurityManagerService service)
            throws InterruptedException {
        mService = service;
        RadioSecurityController controller = new RadioSecurityController();
        if (DBG)
            Slog.d(TAG, "Creating RadioSecurityController");
        controller.mThread.start();
        if (DBG)
            Slog.d(TAG, "Awaiting socket connection");
        controller.mConnectedSignal.await();
        if (DBG)
            Slog.d(TAG, "Connected");
        return controller;
    }

    //
    // Secd Callback handling
    //

    class SecdCallbackReceiver implements INativeDaemonConnectorCallbacks {
        /** {@inheritDoc} */
        public void onDaemonConnected() {
            RadioSecurityController.this.onDaemonConnected();
        }

        /** {@inheritDoc} */
        public boolean onEvent(int code, String raw, String[] cooked) {
            return false;
        }
    }

    public void setFirewallPolicy(int uid, boolean enable, int type) {
        LOG("setFirewallPolicy", "uid: " + uid + "/enable: " + enable + "/type: " + type);
        String status = enable ? "enable" : "disable";
        mConnector.doCommand("netctrl " + status + " " + uid + " " + type);
    }

    final RemoteCallbackList<ISecurityCallback> mSendingMessageCallbacks = new RemoteCallbackList<ISecurityCallback>();
    final RemoteCallbackList<ISecurityCallback> mReceivedMesageCallbacks = new RemoteCallbackList<ISecurityCallback>();

    private IMessageToken mMessageToken;

    // register message watcher
    public void monitorMessage(SecurityRecord r) {
        LOG("monitorMessage", "pkg");
        ISecurityCallback callback = ISecurityCallback.Stub.asInterface(r.getCallback());
        if (r.isSendingWatcher()) {
            mSendingMessageCallbacks.register(callback);
            try {
                mMessageToken.onEnableSendingMessageIntercept();
            } catch (RemoteException e) {
            }
        }
        if (r.isReceivedWatcher()) {
            mReceivedMesageCallbacks.register(callback);
            try {
                mMessageToken.onEnableReceivedMessageIntercept();
            } catch (RemoteException e) {
            }
        }
    }


    public void unMonitorMessage (SecurityRecord r) {
        LOG("unMonitorMessage", "pkg");
        ISecurityCallback callback = ISecurityCallback.Stub.asInterface(r.getCallback());
        mSendingMessageCallbacks.unregister(callback);
        mReceivedMesageCallbacks.unregister(callback);
        // TODO: send disable message to mMessageToken
    }

    // apply message token
    public void applyMessageToken(IMessageToken token) {
        mMessageToken = token;
    }

    // send checking message
    public Bundle processCheckingMessage(Bundle message) {
        Bundle result = new Bundle();
        LOG("processCheckingMessage", "message");
        int ident = System.identityHashCode(message);
        Bundle data = new Bundle(message);
        data.putInt(ISecurityManager.MESSAGE_IDENT_KEY, ident);
        result.putInt(ISecurityManager.MESSAGE_IDENT_KEY, ident);
        RemoteCallbackList<ISecurityCallback> callbacks = null;
        String direction = data.getString(ISecurityManager.MESSAGE_DIRECTION_KEY);
        if (ISecurityManager.MESSAGE_SENDING.equals(direction)) {
            callbacks = mSendingMessageCallbacks;
        } else if (ISecurityManager.MESSAGE_RECEIVED.equals(direction)) {
            callbacks = mReceivedMesageCallbacks;
        }

        ISecurityCallback callback = null;
        int exception = SecurityResult.REMOTE_NO_ERROR;
        if (callbacks != null) {
            final int N = callbacks.beginBroadcast();
            for (int i = 0; i < N; i++) {
                try {
                    callback = callbacks.getBroadcastItem(i);
                    mService.getSecurityRecord(callback.asBinder()).insertMessage(data);
                    callback.messageToBeChecked(data);
                } catch (RemoteException e) {
                    exception |= SecurityResult.REMOTE_ERROR;
                }
            }
            callbacks.finishBroadcast();
        }
        result.putInt(ISecurityManager.BROADCAST_RESULT, exception);
        return result;
    }

    public void interceptMessage(int ident, int action) {
        LOG("interceptMessage", "ident: " + ident + "/action: " + action);
        try {
            mMessageToken.onInterceptMessage(ident, action);
        } catch (RemoteException e) {
        }
    }

    public void systemReady() {
        LOG("systemReady", "make the rule");
        mConnector.doCommand("netctrl radio enable");
    }

    private RemoteCallbackList<ISecurityCallback> mCallBlackListCallbacks;

    private ICallToken mCallToken;

    // register call watcher
    public void monitorCall(SecurityRecord r) {
        LOG("monitorCall", "pkg");
        if (mCallBlackListCallbacks == null) {
            mCallBlackListCallbacks = new RemoteCallbackList<ISecurityCallback>();
        }
        mCallBlackListCallbacks.register(ISecurityCallback.Stub.asInterface(r.getCallback()));
        try {
            mCallToken.onEnableCallIntercept();
        } catch (RemoteException e) {

        }
    }

    public void unMonitorCall(SecurityRecord r) {
        LOG("unMonitorCall", "pkg");
        mCallBlackListCallbacks.unregister(ISecurityCallback.Stub.asInterface(r.getCallback()));
        // TODO: send disable message to mCallToken
    }

    // apply call token
    public void applyCallToken(ICallToken token) {
        mCallToken = token;
    }

    // send checking call
    public Bundle processCheckingCall(Bundle call) {
        Bundle result = new Bundle();
        LOG("processCheckingCall", "call");
        int ident = System.identityHashCode(call);
        Bundle data = new Bundle(call);
        data.putInt(ISecurityManager.CALL_IDENT_KEY, ident);
        result.putInt(ISecurityManager.CALL_IDENT_KEY, ident);
        final RemoteCallbackList<ISecurityCallback> callbacks = mCallBlackListCallbacks;

        ISecurityCallback callback = null;
        SecurityRecord r;
        int exception = SecurityResult.REMOTE_NO_ERROR;
        if (callbacks != null) {
            final int N = callbacks.beginBroadcast();
            for (int i = 0; i < N; i++) {
                callback = callbacks.getBroadcastItem(i);
                r = mService.getSecurityRecord(callback.asBinder());
                r.insertCallCheckItem(data);
                if (r.isInAppCallMode()) {
                    //in app mode. Third-party should check the item
                    LOG("processCheckingCall", "in app mode");
                    try {
                        callback.callToBeChecked(data);
                    } catch (RemoteException e) {
                        exception |= SecurityResult.REMOTE_ERROR;
                    }
                } else {
                    //in sys mode. controller will check itself
                    if (r.isCallInBlackList(ident)) {
                        interceptCall(ident, ISecurityManager.CALL_BLOCK);
                        try {
                            data.putLong(ISecurityManager.CALL_DATE_KEY, r.getCallDate(ident));
                            callback.callBlocked(data);
                        } catch (RemoteException e) {
                            exception |= SecurityResult.REMOTE_ERROR;
                        }
                    } else {
                        interceptCall(ident, ISecurityManager.CALL_PASS);
                    }
                }
            }
            callbacks.finishBroadcast();
        }
        result.putInt(ISecurityManager.BROADCAST_RESULT, exception);
        return result;
    }

    public void interceptCall(int ident, int action) {
        LOG("interceptCall", "ident: " + ident + "/action: " + action);
        try {
            mCallToken.onInterceptCall(ident, action);
        } catch (RemoteException e) {
        }
    }

    private void LOG(String function, String msg) {
        if(DBG)
            Slog.d(TAG, "(" + function + ")" + ": " + msg);
    }
}
