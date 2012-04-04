/*
 * Copyright (C) 2006 The Android Open Source Project
 * Copyright (c) 2011-12 Code Aurora Forum. All rights reserved.
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

package com.android.internal.telephony;


import com.android.internal.telephony.DataCallState.SetupResult;
import com.android.internal.telephony.DataProfile;
import com.android.internal.telephony.QosSpec;
import com.android.internal.telephony.QosIndication;
import com.android.internal.util.AsyncChannel;
import com.android.internal.util.Protocol;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;

import android.app.PendingIntent;
import android.net.LinkAddress;
import android.net.LinkCapabilities;
import android.net.LinkProperties;
import android.net.ProxyProperties;
import android.os.AsyncResult;
import android.os.Message;
import android.os.SystemProperties;
import android.text.TextUtils;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

/**
 * {@hide}
 *
 * DataConnection StateMachine.
 *
 * This is an abstract base class for representing a single data connection.
 * Instances of this class such as <code>CdmaDataConnection</code> and
 * <code>GsmDataConnection</code>, * represent a connection via the cellular network.
 * There may be multiple data connections and all of them are managed by the
 * <code>DataConnectionTracker</code>.
 *
 * Instances are asynchronous state machines and have two primary entry points
 * <code>connect()</code> and <code>disconnect</code>. The message a parameter will be returned
 * hen the operation completes. The <code>msg.obj</code> will contain an AsyncResult
 * object and <code>AsyncResult.userObj</code> is the original <code>msg.obj</code>. if successful
 * with the <code>AsyncResult.result == null</code> and <code>AsyncResult.exception == null</code>.
 * If an error <code>AsyncResult.result = FailCause</code> and
 * <code>AsyncResult.exception = new Exception()</code>.
 *
 * The other public methods are provided for debugging.
 */
public abstract class DataConnection extends StateMachine {
    protected static final boolean DBG = true;
    protected static final boolean VDBG = false;

    protected static Object mCountLock = new Object();
    protected static int mCount;
    protected AsyncChannel mAc;

    private List<ApnContext> mApnList = null;
    PendingIntent mReconnectIntent = null;

    /* Used for data call retry for partial failures of dual-ip calls */
    private boolean mPartialSuccess = false;
    // Stores the protocol to be used if network returns
    // v4-only or v6-only error codes.
    private String mPendingProtocol = null;
    private boolean mInPartialRetry = false;
    private DataConnectionTracker mDataConnectionTracker = null;

    /**
     * Used internally for saving connecting parameters.
     */
    protected static class ConnectionParams {
        public ConnectionParams(DataProfile apn, Message onCompletedMsg) {
            this.apn = apn;
            this.onCompletedMsg = onCompletedMsg;
        }

        public int tag;
        public DataProfile apn;
        public Message onCompletedMsg;
    }

    /**
     * Used internally for saving disconnecting parameters.
     */
    protected static class DisconnectParams {
        public DisconnectParams(String reason, Message onCompletedMsg) {
            this.reason = reason;
            this.onCompletedMsg = onCompletedMsg;
        }
        public int tag;
        public String reason;
        public Message onCompletedMsg;
    }

    /**
     * Used internally for saving disconnecting parameters.

     * Returned as the reason for a connection failure as defined
     * by RIL_DataCallFailCause in ril.h and some local errors.
     */
    public enum FailCause {
        NONE(0),

        // This series of errors as specified by the standards
        // specified in ril.h
        OPERATOR_BARRED(0x08),
        INSUFFICIENT_RESOURCES(0x1A),
        MISSING_UNKNOWN_APN(0x1B),
        UNKNOWN_PDP_ADDRESS_TYPE(0x1C),
        USER_AUTHENTICATION(0x1D),
        ACTIVATION_REJECT_GGSN(0x1E),
        ACTIVATION_REJECT_UNSPECIFIED(0x1F),
        SERVICE_OPTION_NOT_SUPPORTED(0x20),
        SERVICE_OPTION_NOT_SUBSCRIBED(0x21),
        SERVICE_OPTION_OUT_OF_ORDER(0x22),
        NSAPI_IN_USE(0x23),
        ONLY_IPV4_ALLOWED(0x32),
        ONLY_IPV6_ALLOWED(0x33),
        ONLY_SINGLE_BEARER_ALLOWED(0x34),
        PROTOCOL_ERRORS(0x6F),

        // Local errors generated by Vendor RIL
        // specified in ril.h
        REGISTRATION_FAIL(-1),
        GPRS_REGISTRATION_FAIL(-2),
        SIGNAL_LOST(-3),
        PREF_RADIO_TECH_CHANGED(-4),
        RADIO_POWER_OFF(-5),
        TETHERED_CALL_ACTIVE(-6),
        ERROR_UNSPECIFIED(0xFFFF),

        // Errors generated by the Framework
        // specified here
        UNKNOWN(0x10000),
        RADIO_NOT_AVAILABLE(0x10001),
        UNACCEPTABLE_NETWORK_PARAMETER(0x10002),
        CONNECTION_TO_DATACONNECTIONAC_BROKEN(0x10003);

        private final int mErrorCode;
        private static final HashMap<Integer, FailCause> sErrorCodeToFailCauseMap;
        static {
            sErrorCodeToFailCauseMap = new HashMap<Integer, FailCause>();
            for (FailCause fc : values()) {
                sErrorCodeToFailCauseMap.put(fc.getErrorCode(), fc);
            }
        }

        FailCause(int errorCode) {
            mErrorCode = errorCode;
        }

        int getErrorCode() {
            return mErrorCode;
        }

        public boolean isPermanentFail() {
            return (this == OPERATOR_BARRED) || (this == MISSING_UNKNOWN_APN) ||
                   (this == UNKNOWN_PDP_ADDRESS_TYPE) || (this == USER_AUTHENTICATION) ||
                   (this == SERVICE_OPTION_NOT_SUPPORTED) ||
                   (this == SERVICE_OPTION_NOT_SUBSCRIBED) || (this == NSAPI_IN_USE) ||
                   (this == PROTOCOL_ERRORS);
        }

        public boolean isEventLoggable() {
            return (this == OPERATOR_BARRED) || (this == INSUFFICIENT_RESOURCES) ||
                    (this == UNKNOWN_PDP_ADDRESS_TYPE) || (this == USER_AUTHENTICATION) ||
                    (this == ACTIVATION_REJECT_GGSN) || (this == ACTIVATION_REJECT_UNSPECIFIED) ||
                    (this == SERVICE_OPTION_NOT_SUBSCRIBED) ||
                    (this == SERVICE_OPTION_NOT_SUPPORTED) ||
                    (this == SERVICE_OPTION_OUT_OF_ORDER) || (this == NSAPI_IN_USE) ||
                    (this == PROTOCOL_ERRORS) ||
                    (this == UNACCEPTABLE_NETWORK_PARAMETER);
        }

        public static FailCause fromInt(int errorCode) {
            FailCause fc = sErrorCodeToFailCauseMap.get(errorCode);
            if (fc == null) {
                fc = UNKNOWN;
            }
            return fc;
        }
    }

    public static class CallSetupException extends Exception {
        private int mRetryOverride = -1;

        CallSetupException (int retryOverride) {
            mRetryOverride = retryOverride;
        }

        public int getRetryOverride() {
            return mRetryOverride;
        }
    }

    // ***** Event codes for driving the state machine
    protected static final int BASE = Protocol.BASE_DATA_CONNECTION;
    protected static final int EVENT_CONNECT = BASE + 0;
    protected static final int EVENT_SETUP_DATA_CONNECTION_DONE = BASE + 1;
    protected static final int EVENT_GET_LAST_FAIL_DONE = BASE + 2;
    protected static final int EVENT_DEACTIVATE_DONE = BASE + 3;
    protected static final int EVENT_DISCONNECT = BASE + 4;
    protected static final int EVENT_RIL_CONNECTED = BASE + 5;
    protected static final int EVENT_DISCONNECT_ALL = BASE + 6;

    protected static final int EVENT_QOS_ENABLE = BASE + 30;
    protected static final int EVENT_QOS_ENABLE_DONE = BASE + 31;
    protected static final int EVENT_QOS_DISABLE = BASE + 32;
    protected static final int EVENT_QOS_DISABLE_DONE = BASE + 33;
    protected static final int EVENT_QOS_MODIFY = BASE + 34;
    protected static final int EVENT_QOS_MODIFY_DONE = BASE + 35;
    protected static final int EVENT_QOS_SUSPEND = BASE + 36;
    protected static final int EVENT_QOS_SUSPEND_DONE = BASE + 37;
    protected static final int EVENT_QOS_RESUME = BASE + 38;
    protected static final int EVENT_QOS_RESUME_DONE = BASE + 39;
    protected static final int EVENT_QOS_GET_STATUS = BASE + 40;
    protected static final int EVENT_QOS_GET_STATUS_DONE = BASE + 41;
    protected static final int EVENT_QOS_IND = BASE + 42;

    // ***** Tag IDs for EventLog
    protected static final int EVENT_LOG_BAD_DNS_ADDRESS = 50100;

    //***** Member Variables
    protected DataProfile mApn;
    protected int mTag;
    protected PhoneBase phone;
    protected int mRilVersion = -1;
    protected int cid;
    protected LinkProperties mLinkProperties = new LinkProperties();
    protected LinkCapabilities mCapabilities = new LinkCapabilities();
    protected ArrayList<Integer> mQosFlowIds = new ArrayList<Integer>();
    protected long createTime;
    protected long lastFailTime;
    protected FailCause lastFailCause;
    protected int mRetryOverride = -1;
    protected static final String NULL_IP = "0.0.0.0";
    private int mRefCount;
    Object userData;

    //***** Abstract methods
    @Override
    public abstract String toString();

    protected abstract void onConnect(ConnectionParams cp);

    protected abstract boolean isDnsOk(String[] domainNameServers);

    protected abstract void log(String s);

   //***** Constructor
    protected DataConnection(PhoneBase phone, String name, int id, RetryManager rm,
            DataConnectionTracker dct) {
        super(name);
        if (DBG) log("DataConnection constructor E");
        this.phone = phone;
        this.mDataConnectionTracker = dct;
        mId = id;
        mRetryMgr = rm;
        this.cid = -1;

        setDbg(false);
        addState(mDefaultState);
            addState(mInactiveState, mDefaultState);
            addState(mActivatingState, mDefaultState);
            addState(mActiveState, mDefaultState);
                addState(mQosActiveState, mActiveState);
            addState(mDisconnectingState, mDefaultState);
            addState(mDisconnectingErrorCreatingConnection, mDefaultState);
        setInitialState(mInactiveState);

        mApnList = new ArrayList<ApnContext>();
        if (DBG) log("DataConnection constructor X");
    }

    /**
     * TearDown the data connection.
     *
     * @param o will be returned in AsyncResult.userObj
     *          and is either a DisconnectParams or ConnectionParams.
     */
    private void tearDownData(Object o) {
        int discReason = RILConstants.DEACTIVATE_REASON_NONE;
        if ((o != null) && (o instanceof DisconnectParams)) {
            DisconnectParams dp = (DisconnectParams)o;
            Message m = dp.onCompletedMsg;
            if (TextUtils.equals(dp.reason, Phone.REASON_RADIO_TURNED_OFF)) {
                discReason = RILConstants.DEACTIVATE_REASON_RADIO_OFF;
            } else if (TextUtils.equals(dp.reason, Phone.REASON_PDP_RESET)) {
                discReason = RILConstants.DEACTIVATE_REASON_PDP_RESET;
            }
        }
        if (phone.mCM.getRadioState().isOn()) {
            if (DBG) log("tearDownData radio is on, call deactivateDataCall");
            phone.mCM.deactivateDataCall(cid, discReason, obtainMessage(EVENT_DEACTIVATE_DONE, o));
        } else {
            if (DBG) log("tearDownData radio is off sendMessage EVENT_DEACTIVATE_DONE immediately");
            AsyncResult ar = new AsyncResult(o, null, null);
            sendMessage(obtainMessage(EVENT_DEACTIVATE_DONE, ar));
        }
    }

    /**
     * Tear down all the QoS flows that has been setup
     */
    private void tearDownQos() {
        for (int id: (Integer[])mQosFlowIds.toArray(new Integer[0])) {
            qosRelease(id);
        }
    }

    /**
     * Send the connectionCompletedMsg.
     *
     * @param cp is the ConnectionParams
     * @param cause
     */
    private void notifyConnectCompleted(ConnectionParams cp, FailCause cause) {
        Message connectionCompletedMsg = cp.onCompletedMsg;
        if (connectionCompletedMsg == null) {
            return;
        }

        long timeStamp = System.currentTimeMillis();
        connectionCompletedMsg.arg1 = cid;

        if (cause == FailCause.NONE) {
            createTime = timeStamp;
            AsyncResult.forMessage(connectionCompletedMsg);
        } else {
            lastFailCause = cause;
            lastFailTime = timeStamp;
            AsyncResult.forMessage(connectionCompletedMsg, cause,
                                   new CallSetupException(mRetryOverride));
        }
        if (DBG) log("notifyConnectionCompleted at " + timeStamp + " cause=" + cause);

        connectionCompletedMsg.sendToTarget();
    }

    /**
     * Send ar.userObj if its a message, which is should be back to originator.
     *
     * @param dp is the DisconnectParams.
     */
    private void notifyDisconnectCompleted(DisconnectParams dp, boolean sendAll) {
        if (VDBG) log("NotifyDisconnectCompleted");

        ApnContext alreadySent = null;
        String reason = null;

        if (dp.onCompletedMsg != null) {
            // Get ApnContext, but only valid on GSM devices this is a string on CDMA devices.
            Message msg = dp.onCompletedMsg;
            if (msg.obj instanceof ApnContext) {
                alreadySent = (ApnContext)msg.obj;
            }
            reason = dp.reason;
            if (VDBG) {
                log(String.format("msg=%s msg.obj=%s", msg.toString(),
                    ((msg.obj instanceof String) ? (String) msg.obj : "<no-reason>")));
            }
            AsyncResult.forMessage(msg);
            msg.sendToTarget();
        }
        if (sendAll) {
            for (ApnContext a : mApnList) {
                if (a == alreadySent) continue;
                if (reason != null) a.setReason(reason);
                Message msg = mDataConnectionTracker.obtainMessage(
                        DataConnectionTracker.EVENT_DISCONNECT_DONE, a);
                AsyncResult.forMessage(msg);
                msg.sendToTarget();
            }
        }

        if (DBG) log("NotifyDisconnectCompleted DisconnectParams=" + dp);
    }

    protected int getRadioTechnology(int defaultRadioTechnology) {
        int radioTechnology;
        if (mRilVersion < 6) {
            radioTechnology = defaultRadioTechnology;
        } else {
            radioTechnology = phone.getServiceState().getRadioTechnology() + 2;
        }
        return radioTechnology;
    }

    /*
     * **************************************************************************
     * Begin Members and methods owned by DataConnectionTracker but stored
     * in a DataConnection because there is one per connection.
     * **************************************************************************
     */

    /*
     * The id is owned by DataConnectionTracker.
     */
    private int mId;

    /**
     * Get the DataConnection ID
     */
    public int getDataConnectionId() {
        return mId;
    }

    /*
     * The retry manager is currently owned by the DataConnectionTracker but is stored
     * in the DataConnection because there is one per connection. These methods
     * should only be used by the DataConnectionTracker although someday the retrying
     * maybe managed by the DataConnection itself and these methods could disappear.
     */
    private RetryManager mRetryMgr;

    /**
     * @return retry manager retryCount
     */
    public int getRetryCount() {
        return mRetryMgr.getRetryCount();
    }

    /**
     * @return retry manager retryTimer
     */
    public int getRetryTimer() {
        return mRetryMgr.getRetryTimer();
    }

    /**
     * increaseRetryCount of retry manager
     */
    public void increaseRetryCount() {
        mRetryMgr.increaseRetryCount();
    }

    /**
     * @return retry manager isRetryNeeded
     */
    public boolean isRetryNeeded() {
        return mRetryMgr.isRetryNeeded();
    }

    /**
     * resetRetryCount of retry manager
     */
    public void resetRetryCount() {
        mRetryMgr.resetRetryCount();
    }

    /**
     * set retryForeverUsingLasttimeout of retry manager
     */
    public void retryForeverUsingLastTimeout() {
        mRetryMgr.retryForeverUsingLastTimeout();
    }

    /**
     * @return retry manager isRetryForever
     */
    public boolean isRetryForever() {
        return mRetryMgr.isRetryForever();
    }

    /**
     * @return whether the retry config is set successfully or not
     */
    public boolean configureRetry(int maxRetryCount, int retryTime, int randomizationTime) {
        return mRetryMgr.configure(maxRetryCount, retryTime, randomizationTime);
    }

    /**
     * @return whether the retry config is set successfully or not
     */
    public boolean configureRetry(String configStr) {
        return mRetryMgr.configure(configStr);
    }

    /*
     * **************************************************************************
     * End members owned by DataConnectionTracker
     * **************************************************************************
     */

    /**
     * Clear all settings called when entering mInactiveState.
     */
    protected void clearSettings() {
        if (DBG) log("clearSettings");

        createTime = -1;
        lastFailTime = -1;
        lastFailCause = FailCause.NONE;
        mRetryOverride = -1;
        mRefCount = 0;
        cid = -1;

        mLinkProperties = new LinkProperties();
        mInPartialRetry = false;
        mPartialSuccess = false;
        mPendingProtocol = null;
        mApn = null;
    }

    /**
     * Process setup completion.
     *
     * @param ar is the result
     * @return SetupResult.
     */
    private DataCallState.SetupResult onSetupConnectionCompleted(AsyncResult ar) {
        DataCallState response = (DataCallState) ar.result;
        ConnectionParams cp = (ConnectionParams) ar.userObj;
        DataCallState.SetupResult result;

        if (ar.exception != null) {
            if (DBG) {
                log("onSetupConnectionCompleted failed, ar.exception=" + ar.exception +
                    " response=" + response);
            }

            if (ar.exception instanceof CommandException
                    && ((CommandException) (ar.exception)).getCommandError()
                    == CommandException.Error.RADIO_NOT_AVAILABLE) {
                result = DataCallState.SetupResult.ERR_BadCommand;
                result.mFailCause = FailCause.RADIO_NOT_AVAILABLE;
            } else if ((response == null) || (response.version < 4)) {
                result = DataCallState.SetupResult.ERR_GetLastErrorFromRil;
            } else {
                result = DataCallState.SetupResult.ERR_RilError;
                result.mFailCause = FailCause.fromInt(response.status);
            }
        } else if (cp.tag != mTag) {
            if (DBG) {
                log("BUG: onSetupConnectionCompleted is stale cp.tag=" + cp.tag + ", mtag=" + mTag);
            }
            result = DataCallState.SetupResult.ERR_Stale;
        } else if (response.status != 0) {
            result = DataCallState.SetupResult.ERR_RilError;
            result.mFailCause = FailCause.fromInt(response.status);
            handleErrorCodes(response);
        } else {
            if (DBG) log("onSetupConnectionCompleted received DataCallState: " + response);
            cid = response.cid;
            result = updateLinkProperty(response).setupResult;
        }

        return result;
    }

    private void handleErrorCodes(DataCallState response) {
        LinkProperties lp = new LinkProperties();
        response.setLinkProperties(lp, false);

        // update mPendingProtocol, its picked up for next retry
        if (response.status == FailCause.ONLY_IPV4_ALLOWED.getErrorCode()) {
            if (!isV4AddrPresent(lp)) {
                // ONLY_IPV4_ALLOWED error code returned but data call not established
                // Set mPendingProtocol appropriately for next retry
                mPendingProtocol = RILConstants.SETUP_DATA_PROTOCOL_IP;
            }
        } else {
            if (response.status == FailCause.ONLY_IPV6_ALLOWED.getErrorCode()) {
                if (!isV6AddrPresent(lp)) {
                    // ONLY_IPV6_ALLOWED error code returned but data call not established
                    // Set mPendingProtocol appropriately for next retry
                    mPendingProtocol = RILConstants.SETUP_DATA_PROTOCOL_IPV6;
                }
            }
        }
        if (mPendingProtocol != null) {
            if (DBG) log("mPendingProtocol set to:" + mPendingProtocol);
        }
    }

    private int getSuggestedRetryTime(AsyncResult ar) {
        int retry = -1;
        if (ar.exception == null) {
            DataCallState response = (DataCallState) ar.result;
            retry =  response.suggestedRetryTime;
        }
        return retry;
    }

    private DataCallState.SetupResult setLinkProperties(DataCallState response,
            LinkProperties lp) {
        // Check if system property dns usable
        boolean okToUseSystemPropertyDns = false;
        String propertyPrefix = "net." + response.ifname + ".";
        String dnsServers[] = new String[2];
        dnsServers[0] = SystemProperties.get(propertyPrefix + "dns1");
        dnsServers[1] = SystemProperties.get(propertyPrefix + "dns2");
        okToUseSystemPropertyDns = isDnsOk(dnsServers);

        // set link properties based on data call response
        return response.setLinkProperties(lp, okToUseSystemPropertyDns);
    }

    public static class UpdateLinkPropertyResult {
        public DataCallState.SetupResult setupResult = DataCallState.SetupResult.SUCCESS;
        public LinkProperties oldLp;
        public LinkProperties newLp;
        public UpdateLinkPropertyResult(LinkProperties curLp) {
            oldLp = curLp;
            newLp = curLp;
        }
    }

    private UpdateLinkPropertyResult updateLinkProperty(DataCallState newState) {
        UpdateLinkPropertyResult result = new UpdateLinkPropertyResult(mLinkProperties);

        if (newState == null) return result;

        DataCallState.SetupResult setupResult;
        result.newLp = new LinkProperties();

        // set link properties based on data call response
        result.setupResult = setLinkProperties(newState, result.newLp);
        if (result.setupResult != DataCallState.SetupResult.SUCCESS) {
            if (DBG) log("updateLinkProperty failed : " + result.setupResult);
            return result;
        }
        // copy HTTP proxy as it is not part DataCallState.
        result.newLp.setHttpProxy(mLinkProperties.getHttpProxy());

        if (DBG && (! result.oldLp.equals(result.newLp))) {
            if (DBG) log("updateLinkProperty old != new");
            if (VDBG) log("updateLinkProperty old LP=" + result.oldLp);
            if (VDBG) log("updateLinkProperty new LP=" + result.newLp);
        }
        mLinkProperties = result.newLp;
        // Data call was successful, clear pending protocol
        mPendingProtocol = null;

        checkAndUpdatePartialProtocolFailure(mLinkProperties);

        return result;
    }

    /* Check for partial protocol failure if it was a IPV4V6 attempt */
    private void checkAndUpdatePartialProtocolFailure(LinkProperties lp) {
        if (getDataCallProtocol().equals(RILConstants.SETUP_DATA_PROTOCOL_IPV4V6)) {
            if (DBG) log("checkAndUpdatePartialProtocolFailure() LP:" + lp.toString());

            /* Save v4 and v6 connected states */
            boolean isIpv4Connected = isV4AddrPresent(lp);
            boolean isIpv6Connected = isV6AddrPresent(lp);

            /* If only v4 or v6 got connected, its partial success */
            if ((isIpv4Connected && !isIpv6Connected) || (!isIpv4Connected && isIpv6Connected)) {
                mPartialSuccess = true;
                if (DBG) {
                    log("Warning: partial data call failure, isIpv4Connected:" +
                            isIpv4Connected + " isIpv6Connected:" + isIpv6Connected);
                }
            } else {
                if (isIpv4Connected && isIpv6Connected) {
                    if (DBG) log("Dual-IP call successful.");

                    mPartialSuccess = false;
                    mInPartialRetry = false;
                } else {
                    if (DBG) log("Error: Both v4 and v6 calls have failed.");
                }
            }
        }
    }

    /**
     * The parent state for all other states.
     */
    private class DcDefaultState extends State {
        @Override
        public void enter() {
            phone.mCM.registerForRilConnected(getHandler(), EVENT_RIL_CONNECTED, null);
            phone.mCM.registerForQosStateChangedInd(getHandler(), EVENT_QOS_IND, null);
        }
        @Override
        public void exit() {
            phone.mCM.unregisterForRilConnected(getHandler());
            phone.mCM.unregisterForQosStateChangedInd(getHandler());
        }
        @Override
        public boolean processMessage(Message msg) {
            AsyncResult ar;

            switch (msg.what) {
                case AsyncChannel.CMD_CHANNEL_FULL_CONNECTION: {
                    if (mAc != null) {
                        if (VDBG) log("Disconnecting to previous connection mAc=" + mAc);
                        mAc.replyToMessage(msg, AsyncChannel.CMD_CHANNEL_FULLY_CONNECTED,
                                AsyncChannel.STATUS_FULL_CONNECTION_REFUSED_ALREADY_CONNECTED);
                    } else {
                        mAc = new AsyncChannel();
                        mAc.connected(null, getHandler(), msg.replyTo);
                        if (VDBG) log("DcDefaultState: FULL_CONNECTION reply connected");
                        mAc.replyToMessage(msg, AsyncChannel.CMD_CHANNEL_FULLY_CONNECTED,
                                AsyncChannel.STATUS_SUCCESSFUL, mId, "hi");
                    }
                    break;
                }
                case AsyncChannel.CMD_CHANNEL_DISCONNECT: {
                    if (VDBG) log("CMD_CHANNEL_DISCONNECT");
                    mAc.disconnect();
                    break;
                }
                case AsyncChannel.CMD_CHANNEL_DISCONNECTED: {
                    if (VDBG) log("CMD_CHANNEL_DISCONNECTED");
                    mAc = null;
                    break;
                }
                case DataConnectionAc.REQ_IS_INACTIVE: {
                    boolean val = getCurrentState() == mInactiveState;
                    if (VDBG) log("REQ_IS_INACTIVE  isInactive=" + val);
                    mAc.replyToMessage(msg, DataConnectionAc.RSP_IS_INACTIVE, val ? 1 : 0);
                    break;
                }
                case DataConnectionAc.REQ_GET_CID: {
                    if (VDBG) log("REQ_GET_CID  cid=" + cid);
                    mAc.replyToMessage(msg, DataConnectionAc.RSP_GET_CID, cid);
                    break;
                }
                case DataConnectionAc.REQ_GET_APNSETTING: {
                    if (VDBG) log("REQ_GET_APNSETTING  apnSetting=" + mApn);
                    mAc.replyToMessage(msg, DataConnectionAc.RSP_GET_APNSETTING, mApn);
                    break;
                }
                case DataConnectionAc.REQ_GET_LINK_PROPERTIES: {
                    LinkProperties lp = new LinkProperties(mLinkProperties);
                    if (VDBG) log("REQ_GET_LINK_PROPERTIES linkProperties" + lp);
                    mAc.replyToMessage(msg, DataConnectionAc.RSP_GET_LINK_PROPERTIES, lp);
                    break;
                }
                case DataConnectionAc.REQ_SET_LINK_PROPERTIES_HTTP_PROXY: {
                    ProxyProperties proxy = (ProxyProperties) msg.obj;
                    if (VDBG) log("REQ_SET_LINK_PROPERTIES_HTTP_PROXY proxy=" + proxy);
                    mLinkProperties.setHttpProxy(proxy);
                    mAc.replyToMessage(msg, DataConnectionAc.RSP_SET_LINK_PROPERTIES_HTTP_PROXY);
                    break;
                }
                case DataConnectionAc.REQ_UPDATE_LINK_PROPERTIES_DATA_CALL_STATE: {
                    DataCallState newState = (DataCallState) msg.obj;
                    UpdateLinkPropertyResult result =
                                             updateLinkProperty(newState);
                    if (VDBG) {
                        log("REQ_UPDATE_LINK_PROPERTIES_DATA_CALL_STATE result="
                            + result + " newState=" + newState);
                    }
                    mAc.replyToMessage(msg,
                                   DataConnectionAc.RSP_UPDATE_LINK_PROPERTIES_DATA_CALL_STATE,
                                   result);
                    break;
                }
                case DataConnectionAc.REQ_GET_LINK_CAPABILITIES: {
                    LinkCapabilities lc = new LinkCapabilities(mCapabilities);
                    if (VDBG) log("REQ_GET_LINK_CAPABILITIES linkCapabilities" + lc);
                    mAc.replyToMessage(msg, DataConnectionAc.RSP_GET_LINK_CAPABILITIES, lc);
                    break;
                }
                case DataConnectionAc.REQ_RESET:
                    if (VDBG) log("DcDefaultState: msg.what=REQ_RESET");
                    mAc.replyToMessage(msg, DataConnectionAc.RSP_RESET);
                    transitionTo(mInactiveState);
                    break;
                case DataConnectionAc.REQ_GET_REFCOUNT: {
                    if (VDBG) log("REQ_GET_REFCOUNT  refCount=" + mRefCount);
                    mAc.replyToMessage(msg, DataConnectionAc.RSP_GET_REFCOUNT, mRefCount);
                    break;
                }
                case DataConnectionAc.REQ_ADD_APNCONTEXT: {
                    ApnContext apnContext = (ApnContext) msg.obj;
                    if (VDBG) log("REQ_ADD_APNCONTEXT apn=" + apnContext.getApnType());
                    if (!mApnList.contains(apnContext)) {
                        mApnList.add(apnContext);
                    }
                    mAc.replyToMessage(msg, DataConnectionAc.RSP_ADD_APNCONTEXT);
                    break;
                }
                case DataConnectionAc.REQ_REMOVE_APNCONTEXT: {
                    ApnContext apnContext = (ApnContext) msg.obj;
                    if (VDBG) log("REQ_REMOVE_APNCONTEXT apn=" + apnContext.getApnType());
                    mApnList.remove(apnContext);
                    mAc.replyToMessage(msg, DataConnectionAc.RSP_REMOVE_APNCONTEXT);
                    break;
                }
                case DataConnectionAc.REQ_GET_APNCONTEXT_LIST: {
                    if (VDBG) log("REQ_GET_APNCONTEXT_LIST num in list=" + mApnList.size());
                    mAc.replyToMessage(msg, DataConnectionAc.RSP_GET_APNCONTEXT_LIST,
                                       new ArrayList<ApnContext>(mApnList));
                    break;
                }
                case DataConnectionAc.REQ_GET_PARTIAL_FAILURE_STATUS: {
                    if (VDBG) log("REQ_GET_PARTIAL_FAILURE_STATUS mPartialSuccess=" + isPartialSuccess());
                    // send 1 for true, 0 for false
                    mAc.replyToMessage(msg, DataConnectionAc.RSP_GET_PARTIAL_FAILURE_STATUS,
                                       (isPartialSuccess() ? 1 : 0), 0, null);
                    break;
                }
                case DataConnectionAc.REQ_SET_RECONNECT_INTENT: {
                    PendingIntent intent = (PendingIntent) msg.obj;
                    if (VDBG) log("REQ_SET_RECONNECT_INTENT");
                    mReconnectIntent = intent;
                    mAc.replyToMessage(msg, DataConnectionAc.RSP_SET_RECONNECT_INTENT);
                    break;
                }
                case DataConnectionAc.REQ_GET_RECONNECT_INTENT: {
                    if (VDBG) log("REQ_GET_RECONNECT_INTENT");
                    mAc.replyToMessage(msg, DataConnectionAc.RSP_GET_RECONNECT_INTENT,
                                       mReconnectIntent);
                    break;
                }
                case EVENT_CONNECT:
                    if (DBG) log("DcDefaultState: msg.what=EVENT_CONNECT, fail not expected");
                    ConnectionParams cp = (ConnectionParams) msg.obj;
                    notifyConnectCompleted(cp, FailCause.UNKNOWN);
                    break;

                case EVENT_DISCONNECT:
                    if (DBG) {
                        log("DcDefaultState deferring msg.what=EVENT_DISCONNECT" + mRefCount);
                    }
                    deferMessage(msg);
                    break;

                case EVENT_DISCONNECT_ALL:
                    if (DBG) {
                        log("DcDefaultState deferring msg.what=EVENT_DISCONNECT_ALL" + mRefCount);
                    }
                    deferMessage(msg);
                    break;

                case EVENT_RIL_CONNECTED:
                    ar = (AsyncResult)msg.obj;
                    if (ar.exception == null) {
                        mRilVersion = (Integer)ar.result;
                        if (DBG) {
                            log("DcDefaultState: msg.what=EVENT_RIL_CONNECTED mRilVersion=" +
                                mRilVersion);
                        }
                    } else {
                        log("Unexpected exception on EVENT_RIL_CONNECTED");
                        mRilVersion = -1;
                    }
                    break;

                default:
                    if (DBG) {
                        log("DcDefaultState: shouldn't happen but ignore msg.what=0x" +
                                Integer.toHexString(msg.what));
                    }
                    break;
            }

            return HANDLED;
        }
    }
    private DcDefaultState mDefaultState = new DcDefaultState();

    /**
     * The state machine is inactive and expects a EVENT_CONNECT.
     */
    private class DcInactiveState extends State {
        private ConnectionParams mConnectionParams = null;
        private FailCause mFailCause = null;
        private DisconnectParams mDisconnectParams = null;

        public void setEnterNotificationParams(ConnectionParams cp, FailCause cause,
                                               int retryOverride) {
            if (VDBG) log("DcInactiveState: setEnterNoticationParams cp,cause");
            mConnectionParams = cp;
            mFailCause = cause;
            mRetryOverride = retryOverride;
        }

        public void setEnterNotificationParams(DisconnectParams dp) {
            if (VDBG) log("DcInactiveState: setEnterNoticationParams dp");
            mDisconnectParams = dp;
        }

        @Override
        public void enter() {
            mTag += 1;

            /**
             * Now that we've transitioned to Inactive state we
             * can send notifications. Previously we sent the
             * notifications in the processMessage handler but
             * that caused a race condition because the synchronous
             * call to isInactive.
             */
            if ((mConnectionParams != null) && (mFailCause != null)) {
                if (VDBG) log("DcInactiveState: enter notifyConnectCompleted");
                notifyConnectCompleted(mConnectionParams, mFailCause);
            }
            if (mDisconnectParams != null) {
                if (VDBG) log("DcInactiveState: enter notifyDisconnectCompleted");
                notifyDisconnectCompleted(mDisconnectParams, true);
            }
            clearSettings();
        }

        @Override
        public void exit() {
            // clear notifications
            mConnectionParams = null;
            mFailCause = null;
            mDisconnectParams = null;
        }

        @Override
        public boolean processMessage(Message msg) {
            boolean retVal;

            switch (msg.what) {
                case DataConnectionAc.REQ_RESET:
                    if (DBG) {
                        log("DcInactiveState: msg.what=RSP_RESET, ignore we're already reset");
                    }
                    mAc.replyToMessage(msg, DataConnectionAc.RSP_RESET);
                    retVal = HANDLED;
                    break;

                case EVENT_CONNECT:
                    ConnectionParams cp = (ConnectionParams) msg.obj;
                    cp.tag = mTag;
                    if (DBG) {
                        log("DcInactiveState msg.what=EVENT_CONNECT." + "RefCount = "
                                + mRefCount);
                    }
                    mRefCount = 1;
                    onConnect(cp);
                    transitionTo(mActivatingState);
                    retVal = HANDLED;
                    break;

                case EVENT_DISCONNECT:
                    if (DBG) log("DcInactiveState: msg.what=EVENT_DISCONNECT");
                    notifyDisconnectCompleted((DisconnectParams)msg.obj, false);
                    retVal = HANDLED;
                    break;

                case EVENT_DISCONNECT_ALL:
                    if (DBG) log("DcInactiveState: msg.what=EVENT_DISCONNECT_ALL");
                    notifyDisconnectCompleted((DisconnectParams)msg.obj, false);
                    retVal = HANDLED;
                    break;

                default:
                    if (VDBG) {
                        log("DcInactiveState nothandled msg.what=0x" +
                                Integer.toHexString(msg.what));
                    }
                    retVal = NOT_HANDLED;
                    break;
            }
            return retVal;
        }
    }
    private DcInactiveState mInactiveState = new DcInactiveState();

    /**
     * The state machine is activating a connection.
     */
    private class DcActivatingState extends State {
        @Override
        public boolean processMessage(Message msg) {
            boolean retVal;
            AsyncResult ar;
            ConnectionParams cp;

            switch (msg.what) {
                case EVENT_CONNECT:
                    if (DBG) log("DcActivatingState deferring msg.what=EVENT_CONNECT refCount = "
                            + mRefCount);
                    deferMessage(msg);
                    retVal = HANDLED;
                    break;

                case EVENT_SETUP_DATA_CONNECTION_DONE:
                    if (DBG) log("DcActivatingState msg.what=EVENT_SETUP_DATA_CONNECTION_DONE");

                    ar = (AsyncResult) msg.obj;
                    cp = (ConnectionParams) ar.userObj;

                    DataCallState.SetupResult result = onSetupConnectionCompleted(ar);
                    if (DBG) log("DcActivatingState onSetupConnectionCompleted result=" + result);
                    switch (result) {
                        case SUCCESS:
                            // All is well
                            mActiveState.setEnterNotificationParams(cp, FailCause.NONE);
                            transitionTo(mActiveState);
                            break;
                        case ERR_BadCommand:
                            // Vendor ril rejected the command and didn't connect.
                            // Transition to inactive but send notifications after
                            // we've entered the mInactive state.
                            mInactiveState.setEnterNotificationParams(cp, result.mFailCause, -1);
                            transitionTo(mInactiveState);
                            break;
                        case ERR_UnacceptableParameter:
                            // The addresses given from the RIL are bad
                            tearDownData(cp);
                            transitionTo(mDisconnectingErrorCreatingConnection);
                            break;
                        case ERR_GetLastErrorFromRil:
                            // Request failed and this is an old RIL
                            phone.mCM.getLastDataCallFailCause(
                                    obtainMessage(EVENT_GET_LAST_FAIL_DONE, cp));
                            break;
                        case ERR_RilError:
                            // Request failed and mFailCause has the reason
                            mInactiveState.setEnterNotificationParams(cp, result.mFailCause,
                                                                      getSuggestedRetryTime(ar));
                            transitionTo(mInactiveState);
                            break;
                        case ERR_Stale:
                            // Request is stale, ignore.
                            break;
                        default:
                            throw new RuntimeException("Unknown SetupResult, should not happen");
                    }
                    retVal = HANDLED;
                    break;

                case EVENT_GET_LAST_FAIL_DONE:
                    ar = (AsyncResult) msg.obj;
                    cp = (ConnectionParams) ar.userObj;
                    FailCause cause = FailCause.UNKNOWN;

                    if (cp.tag == mTag) {
                        if (DBG) log("DcActivatingState msg.what=EVENT_GET_LAST_FAIL_DONE");
                        if (ar.exception == null) {
                            int rilFailCause = ((int[]) (ar.result))[0];
                            cause = FailCause.fromInt(rilFailCause);
                        }
                        // Transition to inactive but send notifications after
                        // we've entered the mInactive state.
                        mInactiveState.setEnterNotificationParams(cp, cause, -1);
                        transitionTo(mInactiveState);
                    } else {
                        if (DBG) {
                            log("DcActivatingState EVENT_GET_LAST_FAIL_DONE is stale cp.tag="
                                + cp.tag + ", mTag=" + mTag);
                        }
                    }

                    retVal = HANDLED;
                    break;

                default:
                    if (VDBG) {
                        log("DcActivatingState not handled msg.what=0x" +
                                Integer.toHexString(msg.what));
                    }
                    retVal = NOT_HANDLED;
                    break;
            }
            return retVal;
        }
    }
    private DcActivatingState mActivatingState = new DcActivatingState();

    /**
     * The state machine is connected, expecting an EVENT_DISCONNECT.
     */
    private class DcActiveState extends State {
        private ConnectionParams mConnectionParams = null;
        private FailCause mFailCause = null;
        private boolean mPendingRequest = false;

        public void setEnterNotificationParams(ConnectionParams cp, FailCause cause) {
            if (VDBG) log("DcInactiveState: setEnterNoticationParams cp,cause");
            mConnectionParams = cp;
            mFailCause = cause;
        }

        @Override public void enter() {
            /**
             * Now that we've transitioned to Active state we
             * can send notifications. Previously we sent the
             * notifications in the processMessage handler but
             * that caused a race condition because the synchronous
             * call to isActive.
             */
            if ((mConnectionParams != null) && (mFailCause != null)) {
                if (VDBG) log("DcActiveState: enter notifyConnectCompleted");
                notifyConnectCompleted(mConnectionParams, mFailCause);
            }
        }

        @Override
        public void exit() {
            // clear notifications
            mConnectionParams = null;
            mFailCause = null;
            mPendingProtocol = null;
            mInPartialRetry = false;
            mPartialSuccess = false;
        }

        @Override
        public boolean processMessage(Message msg) {
            boolean retVal;
            AsyncResult ar;
            ConnectionParams cp;

            switch (msg.what) {
                case EVENT_CONNECT:
                    cp = (ConnectionParams) msg.obj;
                    if (cp != null && !cp.apn.isInPartialRetry()) {
                        /* No partial retry necessary */
                        mRefCount++;
                        if (DBG)
                            log("DcActiveState msg.what=EVENT_CONNECT RefCount=" + mRefCount);
                        if (msg.obj != null) {
                            notifyConnectCompleted(cp, FailCause.NONE);
                        }
                    } else {
                        if (mPendingRequest) {
                            log("DcActiveState Already in partial retry, ignoring multiple requests");
                        } else {
                            handlePartialRetrySetupRequest(msg);
                            mPendingRequest = true;
                        }
                    }
                    retVal = HANDLED;
                    break;
                case EVENT_SETUP_DATA_CONNECTION_DONE:
                    if (DBG) log("DcActiveState msg.what=EVENT_SETUP_DATA_CONNECTION_DONE");

                    ar = (AsyncResult) msg.obj;
                    cp = (ConnectionParams) ar.userObj;

                    retVal = NOT_HANDLED;
                    if (mInPartialRetry) {
                        handlePartialRetrySetupRequestCompleted(msg);
                        /* update the partial retry result in the APN so that
                         * the next retry can reuse this information.
                         */
                        cp.apn.setInPartialRetry(mInPartialRetry);
                        retVal = HANDLED;
                    }
                    mPendingRequest = false;
                    break;
                case EVENT_QOS_ENABLE:
                case EVENT_QOS_GET_STATUS:
                    if (DBG) log("DcActiveState moving to DcQosActiveState msg.what="
                                + msg.what);
                    deferMessage(msg);
                    transitionTo(mQosActiveState);
                    retVal = HANDLED;
                    break;
                case EVENT_DISCONNECT:
                    mRefCount--;
                    if (DBG) log("DcActiveState msg.what=EVENT_DISCONNECT RefCount=" + mRefCount);
                    if (mRefCount == 0)
                    {
                        DisconnectParams dp = (DisconnectParams) msg.obj;
                        dp.tag = mTag;
                        tearDownData(dp);
                        transitionTo(mDisconnectingState);
                    } else {
                        if (msg.obj != null) {
                            notifyDisconnectCompleted((DisconnectParams) msg.obj, false);
                        }
                    }
                    retVal = HANDLED;
                    break;

                case EVENT_DISCONNECT_ALL:
                    if (DBG) {
                        log("DcActiveState msg.what=EVENT_DISCONNECT_ALL RefCount=" + mRefCount);
                    }
                    mRefCount = 0;
                    DisconnectParams dp = (DisconnectParams) msg.obj;
                    dp.tag = mTag;
                    tearDownData(dp);
                    transitionTo(mDisconnectingState);
                    retVal = HANDLED;
                    break;

                default:
                    if (VDBG) {
                        log("DcActiveState not handled msg.what=0x" +
                                Integer.toHexString(msg.what));
                    }
                    retVal = NOT_HANDLED;
                    break;
            }
            return retVal;
        }

        private void handlePartialRetrySetupRequest(Message msg) {
            ConnectionParams cp;
            mInPartialRetry = true;
            if (msg.obj != null) {
                cp = ((ConnectionParams)msg.obj);
                // Increment tag since we are retrying from the same DC state
                mTag++;
                cp.tag = mTag;
                if (DBG) log("DcActiveState partial retry for apn" + cp.apn.toString());
                onConnect(cp);
            }
        }

        private void handlePartialRetrySetupRequestCompleted(Message msg) {
            AsyncResult ar;
            ConnectionParams cp;
            ar = (AsyncResult) msg.obj;
            cp = (ConnectionParams) ar.userObj;

            DataCallState.SetupResult result = onSetupConnectionCompleted(ar);
            if (DBG) {
                log("DcActiveState onSetupConnectionCompleted result=" +
                        result + " isPartialSuccess:" + isPartialSuccess());
            }

            /* For all errors, notify state, DCT does error handling */
            if ((cp != null) && (result.mFailCause != null)) {
                if (VDBG) log("DcActiveState: partial retry: notifyConnectCompleted");
                notifyConnectCompleted(cp, result.mFailCause);
            }
        }
    }
    private DcActiveState mActiveState = new DcActiveState();

    /**
     * The state machine is connected, expecting QoS requests
     */
    private class DcQosActiveState extends State {
        private QosSpec qosSpec = null;

        @Override
        public void enter() {
        }

        @Override
        public void exit() {
            // clear QosSpec
        }

        @Override
        public boolean processMessage(Message msg) {
            boolean retVal = NOT_HANDLED;
            int qosId;
            String error;

            switch (msg.what) {
            case EVENT_QOS_ENABLE:
                if (DBG) log("DcQosActiveState msg.what=EVENT_QOS_ENABLE");
                // Send out qosRequest
                qosSpec = (QosSpec) msg.obj;
                onQosSetup(qosSpec);
                retVal = HANDLED;
                break;
            case EVENT_QOS_DISABLE:
                if (DBG) log("DcQosActiveState msg.what=EVENT_QOS_DISABLE");
                // Send out qosRequest
                qosId = msg.arg1;
                onQosRelease(qosId);
                retVal = HANDLED;
                break;
            case EVENT_QOS_SUSPEND:
                if (DBG) log("DcQosActiveState msg.what=EVENT_QOS_SUSPEND");
                // Send out qosSuspend
                qosId = msg.arg1;
                onQosSuspend(qosId);
                retVal = HANDLED;
                break;
            case EVENT_QOS_RESUME:
                if (DBG) log("DcQosActiveState msg.what=EVENT_QOS_RESUME");
                // Send out qosResume
                qosId = msg.arg1;
                onQosResume(qosId);
                retVal = HANDLED;
                break;
            case EVENT_QOS_GET_STATUS:
                if (DBG) log("DcQosActiveState msg.what=EVENT_QOS_GET_STATUS");
                // Send out qosRequest
                qosId = msg.arg1;
                onQosGetStatus(qosId);
                retVal = HANDLED;
                break;
            case EVENT_QOS_IND:
                log("DcQosActiveState msg.what=EVENT_QOS_IND");
                onQosStateChangedInd((AsyncResult)msg.obj);
                // If all QosSpecs are empty, go back to active.
                if (mQosFlowIds.size() == 0) {
                    transitionTo(mActiveState);
                }
                retVal = HANDLED;
                break;

            case EVENT_QOS_ENABLE_DONE:
                if (DBG) log("DcQosActiveState msg.what=EVENT_QOS_ENABLE_DONE");

                error = getAsyncException(msg);
                AsyncResult ar = (AsyncResult) msg.obj;

                String responses[] = (String[])ar.result;
                int userData = (Integer) ar.userObj;
                onQosSetupDone(userData, responses, error);
                retVal = HANDLED;
                break;

            case EVENT_QOS_DISABLE_DONE:
                if (DBG) log("DcQosActiveState msg.what=EVENT_QOS_DISABLE_DONE");

                error = getAsyncException(msg);

                qosId = (Integer) ((AsyncResult)msg.obj).userObj;
                onQosReleaseDone(qosId, error);
                retVal = HANDLED;
                break;

            case EVENT_QOS_SUSPEND_DONE:
                if (DBG) log("DcQosActiveState msg.what=EVENT_QOS_SUSPEND_DONE");

                error = getAsyncException(msg);

                qosId = (Integer) ((AsyncResult)msg.obj).userObj;
                onQosSuspendDone(qosId, error);
                retVal = HANDLED;
                break;

            case EVENT_QOS_RESUME_DONE:
                if (DBG) log("DcQosActiveState msg.what=EVENT_QOS_RESUME_DONE");

                error = getAsyncException(msg);

                qosId = (Integer) ((AsyncResult)msg.obj).userObj;
                onQosResumeDone(qosId, error);
                retVal = HANDLED;
                break;

            case EVENT_QOS_GET_STATUS_DONE:
                if (DBG) log("DcQosActiveState msg.what=EVENT_QOS_GET_STATUS_DONE");

                error = getAsyncException(msg);
                qosId = (Integer) ((AsyncResult)msg.obj).userObj;
                onQosGetStatusDone(qosId, (AsyncResult)msg.obj, error);
                // If all QosSpecs are empty, go back to active.
                if (mQosFlowIds.size() == 0) {
                    transitionTo(mActiveState);
                }
                retVal = HANDLED;
                break;

            case EVENT_DISCONNECT:
                if (DBG) log("DcQosActiveState msg.what=EVENT_DISCONNECT");
                //Release QoS for all flows
                tearDownQos();
                deferMessage(msg);
                retVal = HANDLED;
                break;

            default:
                if (VDBG)
                    log("DcQosActiveState nothandled msg.what=" + msg.what);
                retVal = NOT_HANDLED;
                break;
            }
            return retVal;
        }

        private String getAsyncException(Message msg) {
            AsyncResult ar = (AsyncResult) msg.obj;
            String ex = null;

            if (ar.exception != null) {
                if (DBG) log("Error in response" + ar.result);
                ex = ar.result == null ? null : (String)ar.result;
            }
            return ex;
        }
    }
    private DcQosActiveState mQosActiveState = new DcQosActiveState();

    /**
     * The state machine is disconnecting.
     */
    private class DcDisconnectingState extends State {
        @Override
        public boolean processMessage(Message msg) {
            boolean retVal;

            switch (msg.what) {
                case EVENT_CONNECT:
                    if (DBG) log("DcDisconnectingState msg.what=EVENT_CONNECT. Defer. RefCount = "
                            + mRefCount);
                    deferMessage(msg);
                    retVal = HANDLED;
                    break;

                case EVENT_DEACTIVATE_DONE:
                    if (DBG) log("DcDisconnectingState msg.what=EVENT_DEACTIVATE_DONE");
                    AsyncResult ar = (AsyncResult) msg.obj;
                    DisconnectParams dp = (DisconnectParams) ar.userObj;
                    if (dp.tag == mTag) {
                        // Transition to inactive but send notifications after
                        // we've entered the mInactive state.
                        mInactiveState.setEnterNotificationParams((DisconnectParams) ar.userObj);
                        transitionTo(mInactiveState);
                    } else {
                        if (DBG) log("DcDisconnectState EVENT_DEACTIVATE_DONE stale dp.tag="
                                + dp.tag + " mTag=" + mTag);
                    }
                    retVal = HANDLED;
                    break;

                default:
                    if (VDBG) {
                        log("DcDisconnectingState not handled msg.what=0x" +
                                Integer.toHexString(msg.what));
                    }
                    retVal = NOT_HANDLED;
                    break;
            }
            return retVal;
        }
    }
    private DcDisconnectingState mDisconnectingState = new DcDisconnectingState();

    /**
     * The state machine is disconnecting after an creating a connection.
     */
    private class DcDisconnectionErrorCreatingConnection extends State {
        @Override
        public boolean processMessage(Message msg) {
            boolean retVal;

            switch (msg.what) {
                case EVENT_DEACTIVATE_DONE:
                    AsyncResult ar = (AsyncResult) msg.obj;
                    ConnectionParams cp = (ConnectionParams) ar.userObj;
                    if (cp.tag == mTag) {
                        if (DBG) {
                            log("DcDisconnectionErrorCreatingConnection" +
                                " msg.what=EVENT_DEACTIVATE_DONE");
                        }

                        // Transition to inactive but send notifications after
                        // we've entered the mInactive state.
                        mInactiveState.setEnterNotificationParams(cp,
                                FailCause.UNACCEPTABLE_NETWORK_PARAMETER, -1);
                        transitionTo(mInactiveState);
                    } else {
                        if (DBG) {
                            log("DcDisconnectionErrorCreatingConnection EVENT_DEACTIVATE_DONE" +
                                    " stale dp.tag=" + cp.tag + ", mTag=" + mTag);
                        }
                    }
                    retVal = HANDLED;
                    break;

                default:
                    if (VDBG) {
                        log("DcDisconnectionErrorCreatingConnection not handled msg.what=0x"
                                + Integer.toHexString(msg.what));
                    }
                    retVal = NOT_HANDLED;
                    break;
            }
            return retVal;
        }
    }
    private DcDisconnectionErrorCreatingConnection mDisconnectingErrorCreatingConnection =
                new DcDisconnectionErrorCreatingConnection();

    // ******* public interface

    /**
     * Bring up a connection to the apn and return an AsyncResult in onCompletedMsg.
     * Used for cellular networks that use Acesss Point Names (APN) such
     * as GSM networks.
     *
     * @param onCompletedMsg is sent with its msg.obj as an AsyncResult object.
     *        With AsyncResult.userObj set to the original msg.obj,
     *        AsyncResult.result = FailCause and AsyncResult.exception = Exception().
     * @param apn is the Access Point Name to bring up a connection to
     */
    public void bringUp(Message onCompletedMsg, DataProfile apn) {
        sendMessage(obtainMessage(EVENT_CONNECT, new ConnectionParams(apn, onCompletedMsg)));
    }

    /**
     * Tear down the connection through the apn on the network.
     *
     * @param onCompletedMsg is sent with its msg.obj as an AsyncResult object.
     *        With AsyncResult.userObj set to the original msg.obj.
     */
    public void tearDown(String reason, Message onCompletedMsg) {
        sendMessage(obtainMessage(EVENT_DISCONNECT, new DisconnectParams(reason, onCompletedMsg)));
    }

    public void qosSetup(QosSpec qosSpec) {
        sendMessage(obtainMessage(EVENT_QOS_ENABLE, qosSpec));
    }

    public void qosRelease(int qosId) {
        sendMessage(obtainMessage(EVENT_QOS_DISABLE, qosId, 0));
    }

    public void qosModify(int qosId, QosSpec qosSpec) {
        sendMessage(obtainMessage(EVENT_QOS_MODIFY, qosId, 0,
                qosSpec));
    }

    public void qosSuspend(int qosId) {
        sendMessage(obtainMessage(EVENT_QOS_SUSPEND, qosId, 0));
    }

    public void qosResume(int qosId) {
        sendMessage(obtainMessage(EVENT_QOS_RESUME, qosId, 0));
    }

    public void getQosStatus(int qosId) {
        sendMessage(obtainMessage(EVENT_QOS_GET_STATUS, qosId, 0));
    }

    /**
     * Initiate QoS Setup with the given parameters
     *
     * @param qosSpec
     *            QoS Spec
     */
    protected void onQosSetup(QosSpec qosSpec) {
        if (DBG) log("Requesting QoS Setup. QosSpec:" + qosSpec.toString());
        phone.mCM.setupQosReq(cid, qosSpec.getRilQosSpec(),
                obtainMessage(EVENT_QOS_ENABLE_DONE, qosSpec.getUserData()));
    }

    /**
     * Initiate QoS Release for the given QoS ID
     *
     * @param qosId
     *            QoS ID
     */
    protected void onQosRelease(int qosId) {
        if (DBG) log("Requesting QoS Release, qosId" + qosId);

        phone.mCM.releaseQos(qosId, obtainMessage(EVENT_QOS_DISABLE_DONE, qosId));
    }

    /**
     * Initiate QoS Suspend for the given QoS ID
     *
     * @param qosId
     *            QoS ID
     */
    protected void onQosSuspend(int qosId) {
        if (DBG) log("Requesting QoS Suspend, qosId" + qosId);

        phone.mCM.suspendQos(qosId, obtainMessage(EVENT_QOS_SUSPEND_DONE, qosId));
    }

    /**
     * Initiate QoS Resume for the given QoS ID
     *
     * @param qosId
     *            QoS ID
     */
    protected void onQosResume(int qosId) {
        if (DBG) log("Requesting QoS Resume, qosId" + qosId);

        phone.mCM.resumeQos(qosId, obtainMessage(EVENT_QOS_RESUME_DONE, qosId));
    }

    /**
     * Get QoS status and parameters for a given QoS ID
     *
     * @param qosId
     *            QoS ID
     */
    protected void onQosGetStatus(int qosId) {
        if (DBG) log("Get QoS Status, qosId:" + qosId);

        phone.mCM.getQosStatus(qosId, obtainMessage(EVENT_QOS_GET_STATUS_DONE, qosId));
    }

    /**
     * QoS Setup is complete. Notify upper layers
     *
     * @param userData
     *            User Data recieved in the asynchronous response
     * @param responses
     *            Fields from the QoS Setup Response
     * @param error
     *            error string
     */
    protected void onQosSetupDone(int userData, String[] responses, String error) {
        boolean failure = false;
        int state = QosSpec.QosIndStates.REQUEST_FAILED;

        QosIndication ind = new QosIndication();
        ind.setUserData(userData);

        if (error == null) {
            try {
                // non zero response is a failure
                if (responses[0].equals("0")) {
                    ind.setQosId(Integer.parseInt(responses[1]));
                    mQosFlowIds.add(Integer.parseInt(responses[1]));
                    if (DBG) log("Added QosId:" + Integer.parseInt(responses[1])
                            + " to DC:" + cid + " QoS Flow Count:" + mQosFlowIds.size());
                } else {
                    failure = true;
                }
            } catch (NumberFormatException e) {
                log("onQosSetupDone: Exception" + e);
                failure = true;
            } catch (NullPointerException e) {
                log("onQosSetupDone: Exception" + e);
                failure = true;
            }
        }

        if (!failure) {
            state = QosSpec.QosIndStates.INITIATED;
        } else {
            log("Error in Qos Setup, going back to Active State");
            transitionTo(mActiveState);
        }

        ind.setIndState(state, error);
        phone.mContext.sendBroadcast(ind.getIndication());

        if (DBG) log("onQosSetupDone Complete, userData:" + userData + " error:" + error);
    }

    /**
     * QoS Release Done. Notify upper layers.
     *
     * @param error
     */
    protected void onQosReleaseDone(int qosId, String error) {

        if (mQosFlowIds.contains(qosId)) {
            QosIndication ind = new QosIndication();
            ind.setIndState(QosSpec.QosIndStates.RELEASING, error);
            ind.setQosId(qosId);
            phone.mContext.sendBroadcast(ind.getIndication());

            mQosFlowIds.remove(mQosFlowIds.indexOf(qosId));

            if (DBG) log("onQosReleaseDone Complete, qosId:" + qosId
                    + " error:" + error + " QoS Flow Count:" + mQosFlowIds.size());
        } else {
            if (DBG) log("onQosReleaseDone Invalid qosId:" + qosId + " error:" + error);
        }
    }

    /**
     * QoS Suspend Done. Notify upper layers.
     *
     * @param error
     */
    protected void onQosSuspendDone(int qosId, String error) {

        if (mQosFlowIds.contains(qosId)) {
            QosIndication ind = new QosIndication();
            ind.setIndState(QosSpec.QosIndStates.SUSPENDING, error);
            ind.setQosId(qosId);
            phone.mContext.sendBroadcast(ind.getIndication());

            if (DBG) log("onQosSuspendDone Complete, qosId:" + qosId
                    + " error:" + error);
        } else {
            if (DBG) log("onQosSuspendDone Invalid qosId:" + qosId + " error:" + error);
        }
    }

    /**
     * QoS Resume Done. Notify upper layers.
     *
     * @param error
     */
    protected void onQosResumeDone(int qosId, String error) {

        if (mQosFlowIds.contains(qosId)) {
            QosIndication ind = new QosIndication();
            ind.setIndState(QosSpec.QosIndStates.RESUMING, error);
            ind.setQosId(qosId);
            phone.mContext.sendBroadcast(ind.getIndication());

            if (DBG) log("onQosResumeDone Complete, qosId:" + qosId
                    + " error:" + error);
        } else {
            if (DBG) log("onQosResumeDone Invalid qosId:" + qosId + " error:" + error);
        }
    }

    /**
     * QoS Get Status Done. Notify upper layers.
     *
     * @param ar
     */
    protected void onQosGetStatusDone(int qosId, AsyncResult ar, String error) {
        String qosStatusResp[] = (String[])ar.result;
        QosSpec spec = null;
        int qosStatus = QosSpec.QosStatus.NONE;
        int status = QosSpec.QosIndStates.REQUEST_FAILED;

        if (qosStatusResp != null && qosStatusResp.length >= 2) {
            if (DBG) log("Entire Status Msg:" + Arrays.toString(qosStatusResp));

            // Process status for valid QoS status and QoS ID
            if (isValidQos(qosId) && (qosStatusResp[1] != null)) {
                qosStatus = Integer.parseInt(qosStatusResp[1]);

                switch (qosStatus) {
                    case QosSpec.QosStatus.NONE:
                        status = QosSpec.QosIndStates.NONE;
                        break;
                    case QosSpec.QosStatus.ACTIVATED:
                    case QosSpec.QosStatus.SUSPENDED:
                        status = QosSpec.QosIndStates.ACTIVATED;
                        break;
                    default:
                        log("Invalid qosStatus:" + qosStatus);
                        break;
                }

                if (qosStatusResp.length > 2) {
                    // There are QoS flow/filter specs, create QoS Spec object
                    spec = new QosSpec();

                    for (int i = 2; i < qosStatusResp.length; i++)
                        spec.createPipe(qosStatusResp[i]);

                    if (DBG) log("QoS Spec for upper layers:" + spec.toString());
                }
            }
        } else {
            log("Invalid Qos Status message, going back to Active State");
            transitionTo(mActiveState);
        }

        // send an indication
        QosIndication ind = new QosIndication();
        ind.setQosId(qosId);
        ind.setIndState(status, error);
        ind.setQosState(qosStatus);
        ind.setQosSpec(spec);
        phone.mContext.sendBroadcast(ind.getIndication());
    }

    /**
     * Handler for all QoS indications
     */
    protected void onQosStateChangedInd(AsyncResult ar) {
        String qosInd[] = (String[])ar.result;
        int qosIndState = QosSpec.QosIndStates.REQUEST_FAILED;

        if (qosInd == null || qosInd.length != 2) {
            // Invalid QoS Indication, ignore it
            log("Invalid Qos State Changed Ind:" + ar.result);
            return;
        }

        if (DBG) log("onQosStateChangedInd: qosId:" + qosInd[0] + ":" + qosInd[1]);

        QosIndication ind = new QosIndication();

        try {
            ind.setQosId(Integer.parseInt(qosInd[0]));

            // Converting RIL's definition of QoS state into the one defined in QosSpec
            int qosState = Integer.parseInt(qosInd[1]);

            switch(qosState) {
                case RIL_QosIndStates.RIL_QOS_ACTIVATED:
                    qosIndState = QosSpec.QosIndStates.ACTIVATED;
                    break;
                case RIL_QosIndStates.RIL_QOS_USER_RELEASE:
                    qosIndState = QosSpec.QosIndStates.RELEASED;
                    break;
                case RIL_QosIndStates.RIL_QOS_NETWORK_RELEASE:
                    qosIndState = QosSpec.QosIndStates.RELEASED_NETWORK;
                    break;
                case RIL_QosIndStates.RIL_QOS_SUSPENDED:
                    qosIndState = QosSpec.QosIndStates.SUSPENDED;
                    break;
                case RIL_QosIndStates.RIL_QOS_MODIFIED:
                    qosIndState = QosSpec.QosIndStates.MODIFIED;
                    break;
                default:
                    log("Invalid Qos State, ignoring indication!");
                    break;
            }
        } catch (NumberFormatException e) {
            if (DBG) log("Exception processing indication:" + e);
        } catch (NullPointerException e) {
            if (DBG) log("Exception processing indication:" + e);
        }

        ind.setIndState(qosIndState, null);
        phone.mContext.sendBroadcast(ind.getIndication());
    }

    /**
     * TODO: This should be an asynchronous call and we wouldn't
     * have to use handle the notification in the DcInactiveState.enter.
     *
     * @return true if the state machine is in the inactive state.
     */
    public boolean isInactive() {
        boolean retVal = getCurrentState() == mInactiveState;
        return retVal;
    }

    /**
     * Check if QoS enabled for this data call
     * @return
     */
    public boolean isQosAvailable() {
        boolean retVal = getCurrentState() == mQosActiveState;
        return retVal;
    }

    /**
     * Check if the QoS ID is valid
     * @return
     */
    public boolean isValidQos(int qosId) {
        return !mQosFlowIds.isEmpty() && mQosFlowIds.contains(qosId);
    }

    protected String getDataCallProtocol() {
        String protocol = null;
        if (phone.getServiceState().getRoaming()) {
            protocol = mApn.roamingProtocol;
        } else {
            protocol = mApn.protocol;
        }

        return mPendingProtocol == null ? protocol : mPendingProtocol;
    }

    public boolean isV4AddrPresent(LinkProperties lp) {
        boolean found = false;
        for (LinkAddress linkAddr : lp.getLinkAddresses()) {
            if (linkAddr.getAddress() instanceof Inet4Address) {
                found = true;
                break;
            }
        }
        return found;
    }

    public boolean isV6AddrPresent(LinkProperties lp) {
        boolean found = false;
        for (LinkAddress linkAddr : lp.getLinkAddresses()) {
            if (linkAddr.getAddress() instanceof Inet6Address) {
                found = true;
                break;
            }
        }
        return found;
    }

    private boolean isPartialSuccess() {
        return mPartialSuccess;
    }

    /**
     * Tear down the connection through the apn on the network.  Ignores refcount and
     * and always tears down.
     *
     * @param onCompletedMsg is sent with its msg.obj as an AsyncResult object.
     *        With AsyncResult.userObj set to the original msg.obj.
     */
    public void tearDownAll(String reason, Message onCompletedMsg) {
        sendMessage(obtainMessage(EVENT_DISCONNECT_ALL,
                new DisconnectParams(reason, onCompletedMsg)));
    }
}
