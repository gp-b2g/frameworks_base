/* Copyright (c) 2011-2012 Code Aurora Forum. All rights reserved.
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

import static android.net.FmcNotifier.*;
import static android.net.ConnectivityManager.*;

import com.android.internal.util.*;
import com.android.internal.telephony.ITelephony;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.IFmcEventListener;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.NetworkInfo;
import android.net.RouteInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteException;
import android.os.INetworkManagementService;
import android.os.ServiceManager;
import android.util.Log;

import java.net.InetAddress;
import java.util.Collection;
import java.util.TimerTask;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;


public class FmcStateMachine extends StateMachine {

    /* Private */
    private static final String TAG = "FmcStateMachine";

    private static int mFmcStatus = FMC_STATUS_CLOSED;

    private static BroadcastReceiver mBroadcastReceiver = null;

    private static byte[] mDestIp = null;

    private static WifiManager mWifiManager = null;
    private static NetworkInfo mNetworkInfo = null;
    private static ConnectivityManager mConnManager = null;

    private static boolean mUserShutDown = true;
    private static boolean mAsyncBearerDown = true;

    private FmcState mFmcStateInactive   = null;
    private FmcState mFmcStateStart      = null;
    private FmcState mFmcStateBearerUp   = null;
    private FmcState mFmcStateRegistered = null;
    private FmcState mFmcStateActive     = null;
    private FmcState mFmcStateShutDown   = null;
    private FmcState mFmcStateBearerDown = null;
    private FmcState mFmcStateDataDown   = null;
    private FmcState mFmcStateDSNotAvail = null;
    private FmcState mFmcStateFailure    = null;

    /* Protected */
    protected static final boolean DBG = true;

    protected static Context mContext = null;
    protected static IFmcEventListener mListener = null;
    protected static ConnectivityService mConnSvc = null;
    protected static FmcStateMachine instance = null;

    protected static final int FMC_DEFAULT_TIMEOUT = 20000;
    protected static final int FMC_BEARER_ENABLE_TIMEOUT = 120000;
    protected static final int FMC_DATA_ENABLE_TIMEOUT = 45000;

    protected static RouteInfo wlanDefault = null;

    protected static FmcCom fmcComSend = null;
    protected static FmcCom fmcComRead = null;
    protected static Thread fmcComSendThread = null;
    protected static Thread fmcComReadThread = null;
    protected static BlockingQueue<Integer> sendQueue = null;

    protected static FmcTimer fmcTimer = null;

    /* Protected shared definitions */
    protected static final int DS_FMC_APP_FMC_BEARER_INVALID  = -1;
    protected static final int DS_FMC_APP_FMC_BEARER_DISABLED = 0;
    protected static final int DS_FMC_APP_FMC_BEARER_ENABLED  = 1;
    protected static final int DS_FMC_APP_FMC_BEARER_MAX      = 2;

    protected static final int FMC_MSG_FAILURE       = 0;
    protected static final int FMC_MSG_START         = 1;
    protected static final int FMC_MSG_STOP          = 2;
    protected static final int FMC_MSG_BEARER_UP     = 3;
    protected static final int FMC_MSG_BEARER_DOWN   = 4;
    protected static final int FMC_MSG_DATA_ENABLED  = 5;
    protected static final int FMC_MSG_DATA_DISABLED = 6;
    protected static final int FMC_MSG_WIFI_UP       = 7;
    protected static final int FMC_MSG_WIFI_DOWN     = 8;
    protected static final int FMC_MSG_TIMEOUT       = 9;

    protected class FmcTimerCallback extends TimerTask {

        public final static String TAG = "FmcTimerCallback";

        FmcTimerCallback() {
            if (DBG) Log.d(TAG, "constructor");
        };

        public void run() {
            if (DBG) Log.d(TAG, "run");

            fmcTimer.clearTimer();
            sendMessage(FMC_MSG_TIMEOUT);
        }
    }

    /* Constructor */
    private FmcStateMachine(
        Context context,
        IFmcEventListener listener,
        ConnectivityService connSvc
    )
    {
        super(TAG);
        if (DBG) Log.d(TAG, "constructor");

        mContext     = context;
        mListener    = listener;
        mConnSvc     = connSvc;
        mWifiManager = (WifiManager) mContext.getSystemService(Context.WIFI_SERVICE);
        mConnManager = (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        mNetworkInfo = mConnManager.getNetworkInfo(ConnectivityManager.TYPE_MOBILE);

        /* Command queue and server communication threads */
        sendQueue = new LinkedBlockingQueue<Integer>();

        fmcComSend = new FmcComSend(this, sendQueue);
        fmcComSendThread = new Thread(fmcComSend);

        fmcComRead = new FmcComRead(this);
        fmcComReadThread = new Thread(fmcComRead);

        /* Ctate transition timer */
        fmcTimer = FmcTimer.create();

        /* Add FMC state objects to state machine */
        mFmcStateInactive   = new FmcStateInactive();
        mFmcStateStart      = new FmcStateStart();
        mFmcStateBearerUp   = new FmcStateBearerUp();
        mFmcStateRegistered = new FmcStateRegistered();
        mFmcStateActive     = new FmcStateActive();
        mFmcStateShutDown   = new FmcStateShutDown();
        mFmcStateBearerDown = new FmcStateBearerDown();
        mFmcStateDataDown   = new FmcStateDataDown();
        mFmcStateDSNotAvail = new FmcStateDSNotAvail();
        mFmcStateFailure    = new FmcStateFailure();

        addState(mFmcStateInactive);
        addState(mFmcStateStart);
        addState(mFmcStateBearerUp);
        addState(mFmcStateRegistered);
        addState(mFmcStateActive);
        addState(mFmcStateShutDown);
        addState(mFmcStateBearerDown);
        addState(mFmcStateDataDown);
        addState(mFmcStateDSNotAvail);
        addState(mFmcStateFailure);

        /* Start in Inactive state */
        setInitialState(mFmcStateInactive);

        /* Add actions to intent filter */
        IntentFilter filter = new IntentFilter();
        filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        filter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        filter.addAction(Intent.ACTION_SHUTDOWN);

        mContext.registerReceiver(mIntentReceiver, filter);
    }

    /* Create singleton */
    static FmcStateMachine create(
        Context context,
        IFmcEventListener listener,
        ConnectivityService connSvc
    )
    {
        if (DBG) Log.d(TAG, "create");

        if (instance == null) {
            if (DBG) Log.d(TAG, "create instance object is null");
            instance = new FmcStateMachine(context, listener, connSvc);
            instance.start();
        } else {
            if (DBG) Log.d(TAG, "create instance object is not null");
            mListener = listener; /* Upadte listener if app creates new instance onResume */
        }

        return instance;
    }

    /* FMC API */
    final boolean startFmc() {
        if (DBG) Log.d(TAG, "startFmc");

        try {
            if (!fmcComSendThread.isAlive()) {
                fmcComSendThread.start();
            }
            if (!fmcComReadThread.isAlive()) {
                fmcComReadThread.start();
            }
        } catch (IllegalThreadStateException e) {
            if (DBG) Log.d(TAG, "startFmc IllegalThreadStateException=" + e.getMessage());
        }

        sendMessage(FMC_MSG_START);

        return true;
    }

    /* FMC API */
    final boolean stopFmc() {
        if (DBG) Log.d(TAG, "stopFmc");

        sendMessage(FMC_MSG_STOP);

        return true;
    }

    /* FMC API */
    final int getStatus() {
        if (DBG) Log.d(TAG, "getStatus status=" + mFmcStatus);

        return mFmcStatus;
    }

    BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();

            if (DBG) Log.d(TAG, "onReceive action=" + action);

            /* Android shutting down */
            if (action.equals(Intent.ACTION_SHUTDOWN)) {
                stopFmc();
            /* Wifi */
            } else if (action.equals(WifiManager.NETWORK_STATE_CHANGED_ACTION) ||
                       action.equals(WifiManager.WIFI_STATE_CHANGED_ACTION)) {
                if (DBG) Log.d(TAG, "onReceive " + mWifiManager.getWifiState());
                switch (mWifiManager.getWifiState()) {
                    case WifiManager.WIFI_STATE_ENABLED:
                        if (DBG) Log.d(TAG, "onReceive WIFI_STATE_ENABLED");
                        sendMessage(FMC_MSG_WIFI_UP);
                        break;
                    case WifiManager.WIFI_STATE_DISABLED:
                    case WifiManager.WIFI_STATE_DISABLING:
                        if (DBG) Log.d(TAG, "onReceive WIFI_STATE_DISABLED");
                        sendMessage(FMC_MSG_WIFI_DOWN);
                        break;
                    default:
                        break;
                }
            /* Mobile data */
            } else if (action.equals(ConnectivityManager.CONNECTIVITY_ACTION)) {
                if (DBG) Log.d(TAG, "onReceive " + mNetworkInfo.getState().name());
                switch(mNetworkInfo.getState()) {
                    case CONNECTED:
                        sendMessage(FMC_MSG_DATA_ENABLED);
                        break;
                    case DISCONNECTED:
                    case DISCONNECTING:
                    case SUSPENDED:
                    //case UNKNOWN:
                        sendMessage(FMC_MSG_DATA_DISABLED);
                        break;
                    default:
                        break;
                }
            }
        }
    };

    protected void setDestIp(byte[] destIp) {
        if (DBG) Log.d(this.getName(), "setDestIp");

        mDestIp = destIp;

        if (DBG) {
            String debug = (short) (mDestIp[0] & 0xff) + 
                     "." + (short) (mDestIp[1] & 0xff) +
                     "." + (short) (mDestIp[2] & 0xff) +
                     "." + (short) (mDestIp[3] & 0xff);
            Log.d(this.getName(), "setDestIp (exit) mDestIp=" + debug);
        }
    }

    abstract class FmcState extends State {

        protected final String TAG = "FmcState";

        protected int timeout = FMC_DEFAULT_TIMEOUT;

        protected FmcState() {
            if (DBG) Log.d(this.getName(), "constructor");
        }

        protected  final void startStateTimer(String prop, int defaultTimeout) {
            if (DBG) Log.d(this.getName(), "startStateTimer prop=" + prop);

            String sTimeout = System.getProperty(prop);
            if (sTimeout != null) {
                try {
                    timeout = Integer.parseInt(sTimeout);
                } catch (NumberFormatException e) {
                    if (DBG) Log.d(TAG, "startStateTimer NumberFormatException=" + e.getMessage());
                    timeout = defaultTimeout;
                }
            } else {
                if (DBG) Log.d(this.getName(), "startStateTimer default timeout");
                timeout = defaultTimeout;
            }
            if (DBG) Log.d(this.getName(), "startStateTimer timeout=" + timeout);
            fmcTimer.startTimer(new FmcTimerCallback(), timeout);
        }

        /* Wrapper clears timer at every state transition */
        protected final void transitionToState(IState destState) {
            if (DBG) Log.d(this.getName(), "transitionToState " + destState.getName());
            fmcTimer.clearTimer();
            transitionTo(destState);
        }

        protected void setStatus(final int status) {
            if (DBG) Log.d(this.getName(), "setStatus status=" + status);

            mFmcStatus = status;
            if (mListener != null) {
                try {
                    mListener.onFmcStatus(status);
                } catch (RemoteException e) {
                    if (DBG) Log.e(this.getName(), "setStatus RemoteException=" + e.getMessage());
                }
            } else {
                if (DBG) Log.e(this.getName(), "setStatus mListener is null");
            }
        }

        protected final void sendEnableFmc() {
            if (DBG) Log.d(this.getName(), "sendEnableFmc");

            sendFmc(DS_FMC_APP_FMC_BEARER_ENABLED);

            if (DBG) Log.d(TAG, "sendEnableFmc (exit)");
        }

        protected final void sendDisableFmc() {
            if (DBG) Log.d(this.getName(), "sendDisableFmc");

            sendFmc(DS_FMC_APP_FMC_BEARER_DISABLED);

            if (DBG) Log.d(TAG, "sendDisableFmc (exit)");
        }

        protected final void sendFmc(int command) {
            if (DBG) Log.d(this.getName(), "sendFmc");

            if (!fmcComSendThread.isAlive()) {
                if (DBG) Log.d(this.getName(), "sendFmc fmcComSendThread not active");
                return;
            }
            try {
                sendQueue.add(command);
            } catch (IllegalStateException e) {
                if (DBG) Log.d(this.getName(), "sendFmc IllegalStateException=" + e.getMessage());
            }

            if (DBG) Log.d(TAG, "sendFmc (exit)");
        }

        protected final void sendEnableData() {
            if (DBG) Log.d(this.getName(), "sendEnableData");
            setDataReadiness(true);
            if (mConnSvc.bringUpRat(ConnectivityManager.TYPE_MOBILE)) { /* Wait for broadcast */
                if (DBG) Log.d(this.getName(), "sendEnableData bringUpRat true");
            } else { /* Data disabled or reconnect failed */
                if (DBG) Log.d(this.getName(), "sendEnableData bringUpRat false");

                sendMessage(FMC_MSG_DATA_DISABLED);
            }
        }

        protected final void sendDisableData() {
            if (DBG) Log.d(this.getName(), "sendDisableData");
            setDataReadiness(false);
            if (mConnSvc.bringDownRat(ConnectivityManager.TYPE_MOBILE)) {
                if (DBG) Log.d(this.getName(), "sendDisableData bringDownRat true");

                sendMessage(FMC_MSG_DATA_DISABLED);
            } else { /* Wait for broadcast */
                if (DBG) Log.d(this.getName(), "sendDisableData bringDownRat false");
            }
        }

        protected final void setDataReadiness(boolean bypass) {
            if (DBG) Log.d(this.getName(), "setDataReadiness");

            ITelephony phone =
                    ITelephony.Stub.asInterface(ServiceManager.getService("phone"));
            try {
                if (bypass) {
                    // bypass connectivity and subscription
                    // checking, and bring up data call
                    phone.setDataReadinessChecks(false, false, true);
                } else {
                    phone.setDataReadinessChecks(true, true, false);
                }
            } catch (RemoteException e) {
                Log.w(this.getName(), "remoteException while calling setDataReadinessChecks");
            }
        }

        protected final void handleActiveRouting(byte[] hostRoutingIpAddr) {
            try {
                if (DBG) Log.d(this.getName(), "handleActiveRouting hostRoutingIpAddr=" + InetAddress.getByAddress(hostRoutingIpAddr).getHostAddress());
            } catch (Exception e) {
                if (DBG) Log.d(this.getName(), "handleActiveRouting Exception=" + e.getMessage());
            }

            try {
                /* Create route info for host address and active gateway */
                LinkProperties lp = mConnSvc.getLinkProperties(ConnectivityManager.TYPE_WIFI);
                if (lp == null) {
                    Log.e(this.getName(), "handleActiveRouting LinkProperties is null");
                    return;
                }
                String iface = lp.getInterfaceName();
                InetAddress gateway = lp.getRoutes().iterator().next().getGateway();
                InetAddress ipAddr = InetAddress.getByAddress(hostRoutingIpAddr);
                RouteInfo rInfo = RouteInfo.makeHostRoute(ipAddr, gateway);

                /* Add host route to wlan0 */
                IBinder b = ServiceManager.getService(Context.NETWORKMANAGEMENT_SERVICE);
                INetworkManagementService nms = INetworkManagementService.Stub.asInterface(b);
                if (nms == null) {
                    Log.e(this.getName(), "handleActiveRouting INetworkManagementService is null");
                    return;
                }

                if (DBG) Log.d(this.getName(), "handleActiveRouting addRoute iface=" + iface + " gateway=" + gateway);
                nms.addRoute(iface, rInfo);

                /* Set rmnet0 as default interface */
                lp = mConnSvc.getLinkProperties(ConnectivityManager.TYPE_MOBILE);
                iface = lp.getInterfaceName();
                String defGateway = null;
                Collection<RouteInfo> routes = lp.getRoutes();
                for (RouteInfo r : routes) {
                    if (r.isDefaultRoute()) {
                        defGateway = r.getGateway().getHostAddress();
                    }
                }
                if (defGateway == null) {
                    if (DBG) Log.d(this.getName(), "handleActiveRouting defGateway is null");
                    return;
                }

                if (DBG) Log.d(this.getName(), "handleActiveRouting replaceV4DefaultRoute iface=" + iface + " gateway=" + defGateway);
                nms.replaceV4DefaultRoute(iface, defGateway);
            } catch (Exception e) {
                if (DBG) Log.d(this.getName(), "handleActiveRouting Exception=" + e.getMessage());
            }
        }

        protected final void handleRegisteredRouting() {
            if (DBG) Log.d(this.getName(), "handleRegisteredRouting");

            try {
                /* Attempt to remove and retain wlan0 default route */
                LinkProperties lp = mConnSvc.getLinkProperties(ConnectivityManager.TYPE_WIFI);
                if (lp == null) {
                    Log.e(this.getName(), "handleRegisteredRouting LinkProperties is null");
                    return;
                }
                String iface = lp.getInterfaceName();
                Collection<RouteInfo> routes = lp.getRoutes();
                for (RouteInfo r : routes) {
                    Log.d(this.getName(), "loop handleRegisteredRouting rInfo=" + r);
                    if (r.isDefaultRoute()) {
                        /* Retain default wlan0 route to restore later */
                        wlanDefault = r;
                        break;
                    }
                }
                if (wlanDefault == null) {
                    if (DBG) Log.d(this.getName(), "handleActiveRouting wlanDefault is null");
                    return;
                }

                IBinder b = ServiceManager.getService(Context.NETWORKMANAGEMENT_SERVICE);
                INetworkManagementService nms = INetworkManagementService.Stub.asInterface(b);
                if (nms == null) {
                    Log.e(this.getName(), "handleRegisteredRouting INetworkManagementService is null");
                    return;
                }

                if (DBG) Log.d(this.getName(), "handleRegisteredRouting removeRoute iface=" + iface + " rInfo=" + wlanDefault);
                nms.removeRoute(iface, wlanDefault);
            } catch (Exception e) {
                if (DBG) Log.d(TAG, "handleRegisteredRouting Exception=" + e.getMessage());
            }
        }

        protected final void handleCleanUpRouting(byte[] hostRoutingIpAddr) {
            try {
                if (DBG) Log.d(this.getName(), "handleCleanUpRouting hostRoutingIpAddr=" + InetAddress.getByAddress(hostRoutingIpAddr).getHostAddress());
            } catch (Exception e) {
                if (DBG) Log.d(this.getName(), "handleCleanUpRouting Exception=" + e.getMessage());
            }

            /* Indicates FMC cycle has not started or no IP address from FMC server */
            if (hostRoutingIpAddr == null) {
                return;
                }

            try {
                /* Remove FMC host route from wlan0 and replace wlan0 default route */
                LinkProperties lp = mConnSvc.getLinkProperties(ConnectivityManager.TYPE_WIFI);
                if (lp == null) {
                    Log.e(this.getName(), "handleCleanUpRouting LinkProperties is null");
                    return;
                }
                String iface = lp.getInterfaceName();
                InetAddress gateway = lp.getRoutes().iterator().next().getGateway();
                RouteInfo rInfo = new RouteInfo(new LinkAddress(InetAddress.getByAddress(hostRoutingIpAddr), 32), 
                                                gateway);

                IBinder b = ServiceManager.getService(Context.NETWORKMANAGEMENT_SERVICE);
                INetworkManagementService nms = INetworkManagementService.Stub.asInterface(b);
                if (nms == null) {
                    Log.e(this.getName(), "handleCleanUpRouting INetworkManagementService is null");
                    return;
                }
                try {
                    if (DBG) Log.d(this.getName(), "handleCleanUpRouting removeRoute iface=" + iface + " gateway=" + gateway);
                    nms.removeRoute(iface, rInfo);
                } catch (Exception e) {
                    if (DBG) Log.d(TAG, "handleCleanUpRouting Exception=" + e.getMessage());
                }

                if (DBG) Log.d(this.getName(), "handleCleanUpRouting addRoute iface=" + iface + " gateway=" + gateway);
                nms.addRoute(iface, wlanDefault);
            } catch (Exception e) {
                if (DBG) Log.d(TAG, "handleCleanUpRouting Exception=" + e.getMessage());
            }
        }
    }

    class FmcStateInactive extends FmcState {

        protected FmcStateInactive() {
            super();
            if (DBG) Log.d(this.getName(), "constructor");
        }

        @Override
        public void enter() {
            if (DBG) Log.d(this.getName(), "enter");
            /* Do not override status if tearing down from DsNotAvail or Failure states */
            if (mUserShutDown) {
                setStatus(FMC_STATUS_CLOSED);
            } else {
                mUserShutDown = true;
            }
            handleCleanUpRouting(mDestIp);
        }

        @Override
        public boolean processMessage(Message msg) {
            if (DBG) Log.d(this.getName(), "processMessage message=" + msg.what);

            switch(msg.what) {
                case FMC_MSG_START:
                    transitionToState(mFmcStateStart);
                    break;
                case FMC_MSG_STOP:
                    sendMessage(FMC_STATUS_NOT_YET_STARTED);
                    break;
                default:
                    if (DBG) Log.d(this.getName(), "processMessage not handled");
                    break;
            }
            return HANDLED;
        }
    }

    class FmcStateStart extends FmcState {

        protected FmcStateStart() {
            super();
            if (DBG) Log.d(this.getName(), "constructor");
        }

        @Override
        public void enter() {
            if (DBG) Log.d(this.getName(), "enter");

            setStatus(FMC_STATUS_INITIALIZED);
            startStateTimer("fmc.bearer.enable.timeout", FMC_BEARER_ENABLE_TIMEOUT);
            sendDisableData();
            sendEnableFmc();
        }

        @Override
        public boolean processMessage(Message msg) {
            if (DBG) Log.d(this.getName(), "processMessage message=" + msg.what);

            switch(msg.what) {
                case FMC_MSG_STOP:
                    transitionToState(mFmcStateShutDown);
                    break;
                case FMC_MSG_BEARER_UP:
                    transitionToState(mFmcStateBearerUp);
                    break;
                case FMC_MSG_BEARER_DOWN:
                    mAsyncBearerDown = true;
                    transitionToState(mFmcStateDSNotAvail);
                    break;
                case FMC_MSG_TIMEOUT:
                    mUserShutDown = false;
                    setStatus(FMC_STATUS_FAILURE);
                    transitionToState(mFmcStateInactive);
                    break;
                case FMC_MSG_FAILURE:
                    mUserShutDown = false;
                    transitionToState(mFmcStateFailure);
                    break;
                default:
                    if (DBG) Log.d(this.getName(), "processMessage not handled");
                    break;
            }
            return HANDLED;
        }
    }

    class FmcStateBearerUp extends FmcState {

        protected FmcStateBearerUp() {
            super();
            if (DBG) Log.d(this.getName(), "constructor");
        }

        @Override
        public void enter() {
            if (DBG) Log.d(this.getName(), "enter");

            setStatus(FMC_STATUS_REGISTRATION_SUCCESS);
            startStateTimer("fmc.data.enable.timeout", FMC_DATA_ENABLE_TIMEOUT);
            sendEnableData();
        }

        @Override
        public boolean processMessage(Message msg) {
            if (DBG) Log.d(this.getName(), "processMessage message=" + msg.what);

            switch(msg.what) {
                case FMC_MSG_STOP:
                    transitionToState(mFmcStateShutDown);
                    break;
                case FMC_MSG_BEARER_DOWN:
                    mAsyncBearerDown = true;
                    transitionToState(mFmcStateDSNotAvail);
                    break;
                case FMC_MSG_DATA_ENABLED:
                    transitionToState(mFmcStateActive);
                    break;
                case FMC_MSG_DATA_DISABLED:
                    transitionToState(mFmcStateRegistered);
                    break;
                case FMC_MSG_WIFI_DOWN:
                    transitionToState(mFmcStateBearerDown);
                    break;
                case FMC_MSG_TIMEOUT:
                case FMC_MSG_FAILURE:
                    mUserShutDown = false;
                    transitionToState(mFmcStateFailure);
                    break;
                default:
                    if (DBG) Log.d(this.getName(), "processMessage not handled");
                    break;
            }
            return HANDLED;
        }
    }

    class FmcStateRegistered extends FmcState {

        protected FmcStateRegistered() {
            super();
            if (DBG) Log.d(this.getName(), "constructor");
        }

        @Override
        public void enter() {
            if (DBG) Log.d(this.getName(), "enter");

            setStatus(FMC_STATUS_REGISTRATION_SUCCESS);
        }

        @Override
        public boolean processMessage(Message msg) {
            if (DBG) Log.d(this.getName(), "processMessage message=" + msg.what);

            switch(msg.what) {
                case FMC_MSG_STOP:
                    transitionToState(mFmcStateShutDown);
                    break;
                case FMC_MSG_BEARER_DOWN:
                    mAsyncBearerDown = true;
                    transitionToState(mFmcStateDSNotAvail);
                    break;
                case FMC_MSG_DATA_ENABLED:
                    transitionToState(mFmcStateActive);
                    break;
                case FMC_MSG_WIFI_DOWN:
                    transitionToState(mFmcStateDSNotAvail);
                    break;
                case FMC_MSG_FAILURE:
                    mUserShutDown = false;
                    transitionToState(mFmcStateFailure);
                    break;
                default:
                    if (DBG) Log.d(this.getName(), "processMessage not handled");
                    break;
            }
            return HANDLED;
        }
    }

    class FmcStateActive extends FmcState {

        protected FmcStateActive() {
            super();
            if (DBG) Log.d(this.getName(), "constructor");
        }

        @Override
        public void enter() {
            if (DBG) Log.d(this.getName(), "enter");

            setStatus(FMC_STATUS_ENABLED);
            handleRegisteredRouting();
            handleActiveRouting(mDestIp);
        }

        @Override
        public boolean processMessage(Message msg) {
            if (DBG) Log.d(this.getName(), "processMessage message=" + msg.what);

            switch(msg.what) {
                case FMC_MSG_STOP:
                    transitionToState(mFmcStateShutDown);
                    break;
                case FMC_MSG_BEARER_DOWN:
                    mAsyncBearerDown = true;
                    transitionToState(mFmcStateDSNotAvail);
                    break;
                case FMC_MSG_DATA_ENABLED:
                    transitionToState(mFmcStateActive);
                    break;
                case FMC_MSG_DATA_DISABLED:
                    handleCleanUpRouting(mDestIp);
                    transitionToState(mFmcStateRegistered);
                    break;
                case FMC_MSG_WIFI_DOWN:
                    transitionToState(mFmcStateDSNotAvail);
                    break;
                case FMC_MSG_FAILURE:
                    mUserShutDown = false;
                    transitionToState(mFmcStateFailure);
                    break;
                default:
                    if (DBG) Log.d(this.getName(), "processMessage not handled");
                    break;
            }
            return HANDLED;
        }
    }

    class FmcStateShutDown extends FmcState {

        protected FmcStateShutDown() {
            super();
            if (DBG) Log.d(this.getName(), "constructor");
        }

        @Override
        public void enter() {
            if (DBG) Log.d(this.getName(), "enter");

            setStatus(FMC_STATUS_SHUTTING_DOWN);
            startStateTimer("fmc.shut.down.timeout", FMC_DEFAULT_TIMEOUT);
            mConnSvc.setFmcDisabled();
            sendDisableFmc();
            sendDisableData();
        }

        @Override
        public boolean processMessage(Message msg) {
            if (DBG) Log.d(this.getName(), "processMessage message=" + msg.what);

            switch(msg.what) {
                case FMC_MSG_BEARER_DOWN:
                    transitionToState(mFmcStateBearerDown);
                    break;
                case FMC_MSG_DATA_DISABLED:
                    transitionToState(mFmcStateDataDown);
                    break;
                case FMC_MSG_TIMEOUT:
                    transitionToState(mFmcStateInactive);
                    break;
                case FMC_MSG_FAILURE:
                    transitionToState(mFmcStateFailure);
                    break;
                default:
                    if (DBG) Log.d(this.getName(), "processMessage not handled");
                    break;
            }
            return HANDLED;
        }
    }

    class FmcStateBearerDown extends FmcState {

        protected FmcStateBearerDown() {
            super();
            if (DBG) Log.d(this.getName(), "constructor");
        }

        @Override
        public void enter() {
            if (DBG) Log.d(this.getName(), "enter");

            startStateTimer("fmc.data.disable.timeout", FMC_DEFAULT_TIMEOUT);
        }

        @Override
        public boolean processMessage(Message msg) {
            if (DBG) Log.d(this.getName(), "processMessage message=" + msg.what);

            switch(msg.what) {
                case FMC_MSG_DATA_DISABLED:
                    transitionToState(mFmcStateInactive);
                    break;
                case FMC_MSG_TIMEOUT:
                    transitionToState(mFmcStateInactive);
                    break;
                default:
                    if (DBG) Log.d(this.getName(), "processMessage not handled");
                    break;
            }
            return HANDLED;
        }
    }

    class FmcStateDataDown extends FmcState {

        protected FmcStateDataDown() {
            super();
            if (DBG) Log.d(this.getName(), "constructor");
        }

        public void enter() {
            if (DBG) Log.d(this.getName(), "enter");

            startStateTimer("fmc.bearer.disable.timeout", FMC_DEFAULT_TIMEOUT);
        }

        @Override
        public boolean processMessage(Message msg) {
            if (DBG) Log.d(this.getName(), "processMessage message=" + msg.what);

            switch(msg.what) {
                case FMC_MSG_BEARER_DOWN:
                    transitionToState(mFmcStateInactive);
                    break;
                case FMC_MSG_TIMEOUT:
                    transitionToState(mFmcStateInactive);
                    break;
                default:
                    if (DBG) Log.d(this.getName(), "processMessage not handled");
                    break;
            }
            return HANDLED;
        }
    }

    class FmcStateDSNotAvail extends FmcStateBearerDown {

        protected FmcStateDSNotAvail() {
            super();
            if (DBG) Log.d(this.getName(), "constructor");
        }

        @Override
        public void enter() {
            if (DBG) Log.d(this.getName(), "enter");
            mConnSvc.setFmcDisabled();
            setStatus(FMC_STATUS_DS_NOT_AVAIL);
            mUserShutDown = false;
            /* Do not send disable fmc if already received asynchronous bearer down indication */
            if (!mAsyncBearerDown) {
                sendDisableFmc();
            } else {
                mAsyncBearerDown = false;
            }
            sendDisableData();
        }
    }

    class FmcStateFailure extends FmcStateShutDown {

        protected FmcStateFailure() {
            super();
            if (DBG) Log.d(this.getName(), "constructor");
        }

        @Override
        public void enter() {
            if (DBG) Log.d(this.getName(), "enter");
            mConnSvc.setFmcDisabled();
            setStatus(FMC_STATUS_FAILURE);
            mUserShutDown = false;
            sendDisableFmc();
            sendDisableData();
        }
    }
};
