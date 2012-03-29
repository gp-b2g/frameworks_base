/*
 * Copyright (C) 2006, 2011 The Android Open Source Project
 * Copyright (c) 2012, Code Aurora Forum. All rights reserved.
 * Not a contribution.
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

package com.android.internal.telephony.ims;

import android.content.Context;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.os.Registrant;
import android.os.SystemProperties;
import android.telephony.CellLocation;
import android.telephony.PhoneNumberUtils;
import android.telephony.ServiceState;
import android.telephony.SignalStrength;
import android.util.Log;

import com.android.internal.telephony.Call;
import com.android.internal.telephony.CallDetails;
import com.android.internal.telephony.CallFailCause;
import com.android.internal.telephony.CallStateException;
import com.android.internal.telephony.CallTracker;
import com.android.internal.telephony.Connection.DisconnectCause;
import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.Connection;
import com.android.internal.telephony.IccPhoneBookInterfaceManager;
import com.android.internal.telephony.MmiCode;
import com.android.internal.telephony.OperatorInfo;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneBase;
import com.android.internal.telephony.PhoneNotifier;
import com.android.internal.telephony.PhoneProxy;
import com.android.internal.telephony.PhoneSubInfo;
import com.android.internal.telephony.RILConstants;
import com.android.internal.telephony.CallBase;
import com.android.internal.telephony.TelephonyProperties;
import com.android.internal.telephony.UUSInfo;

import java.util.List;

/**
 * {@hide}
 */
public class RilImsPhone extends PhoneBase {
    private static final String LOG_TAG = "RilImsPhone";
    private static final boolean DEBUG = true;
    static final int MAX_CONNECTIONS = 7;
    static final int MAX_CONNECTIONS_PER_CALL = 5;

    private State state = Phone.State.IDLE; // phone state for IMS phone
    private CallTracker mTracker;
    // A call that is ringing or (call) waiting
    private CallBase ringingCall;
    private CallBase foregroundCall;
    private CallBase backgroundCall;

    public RilImsPhone(Context context, PhoneNotifier notifier, CallTracker tracker,
            CommandsInterface cm) {
        super(notifier, context, cm);

        ringingCall = new CallBase(this, tracker);
        foregroundCall = new CallBase(this, tracker);
        backgroundCall = new CallBase(this, tracker);
        mTracker = tracker; // Fix this change name to mCT or check all PhoneBase methods using mCT
        mCT= tracker;//hack
        tracker.imsPhone = this;
    }

    @Override
    public boolean equals(Object o) {
        if (o == this) return true;
        return false;
    }

    public String getPhoneName() {
        return "RilIms";
    }

    public boolean equals(Phone phone) {
        return phone == this;
    }

    @Override
    public void acceptCall(int callType) throws CallStateException {
        mTracker.acceptCall(this, callType);
    }

    public void acceptCall() throws CallStateException {
        synchronized (RilImsPhone.class) {
            if ((ringingCall.getState() == Call.State.INCOMING) ||
                    (ringingCall.getState() == Call.State.WAITING)) {
                if (DEBUG) Log.d(LOG_TAG, "acceptCall");
                // Always unmute when answering a new call
                mTracker.acceptCall(this);
            } else {
                throw new CallStateException("phone not ringing");
            }
        }
    }

    public void rejectCall() throws CallStateException {
        synchronized (RilImsPhone.class) {
            if (ringingCall.getState().isRinging()) {
                if (DEBUG) Log.d(LOG_TAG, "rejectCall");
                mTracker.rejectCall(this);
            } else {
                throw new CallStateException("phone not ringing");
            }
        }
    }

    public boolean canDial() {
        //TODO: Add checks to meet IMS call requirements
        int serviceState = getServiceState().getState();
        Log.v(LOG_TAG, "canDial(): serviceState = " + serviceState);
        if (serviceState == ServiceState.STATE_POWER_OFF) return false;

        String disableCall = SystemProperties.get(
                TelephonyProperties.PROPERTY_DISABLE_CALL, "false");
        Log.v(LOG_TAG, "canDial(): disableCall = " + disableCall);
        if (disableCall.equals("true")) return false;

        Log.v(LOG_TAG, "canDial(): ringingCall: " + getRingingCall().getState());
        Log.v(LOG_TAG, "canDial(): foregndCall: " + getForegroundCall().getState());
        Log.v(LOG_TAG, "canDial(): backgndCall: " + getBackgroundCall().getState());
        return !getRingingCall().isRinging()
                && (!getForegroundCall().getState().isAlive()
                    || !getBackgroundCall().getState().isAlive());
    }

    public Connection dial(String dialString) throws CallStateException {
        synchronized (RilImsPhone.class) {
            // Need to make sure dialString gets parsed properly
            // dialString = PhoneNumberUtils.stripSeparators(dialString);
            return dialInternal(dialString, null);
        }
    }

    private Connection dialInternal(String dialString, CallDetails det)
            throws CallStateException {
        clearDisconnected();

        if (!canDial()) {
            throw new CallStateException("cannot dial in current state");
        }
        if (foregroundCall.getState() == Call.State.ACTIVE) {
            // This is currently not supported
            switchHoldingAndActive();
        }
        if (foregroundCall.getState() != Call.State.IDLE) {
            // we should have failed in !canDial() above before we get here
            throw new CallStateException("cannot dial in current state");
        }

        // foregroundCall.setMute(false);
        if (det != null) {
            det.call_domain = CallDetails.RIL_CALL_DOMAIN_PS;
        } else {
            det = new CallDetails(CallDetails.RIL_CALL_TYPE_VOICE, CallDetails.RIL_CALL_DOMAIN_CS,
                    null);// CS voice
        }
        Connection c = mTracker.dial(dialString, det);
        return c;
    }

    public void switchHoldingAndActive() throws CallStateException {
        if (DEBUG) Log.d(LOG_TAG, " ~~~~~~  switch fg and bg");
        synchronized (RilImsPhone.class) {
            mTracker.switchWaitingOrHoldingAndActive();
        }
    }

    public boolean canConference() {
        logUnexpectedCdmaMethodCall("canConference");
        return false;
    }

    public void conference() throws CallStateException {
        logUnexpectedCdmaMethodCall("conference");
    }

    public boolean canTransfer() {
        logUnexpectedCdmaMethodCall("canTransfer");
        return false;
    }

    public void explicitCallTransfer() throws CallStateException {
        logUnexpectedCdmaMethodCall("explicitCallTransfer");
    }

    /**
     * Notify any interested party of a Phone state change {@link Phone.State}
     */
    public void notifyPhoneStateChanged() {
        mNotifier.notifyPhoneState(this);
    }

    void updatePhoneState() {
        State oldState = state;

        if (getRingingCall().isRinging()) {
            state = State.RINGING;
        } else if (getForegroundCall().isIdle()
                && getBackgroundCall().isIdle()) {
            state = State.IDLE;
        } else {
            state = State.OFFHOOK;
        }

        if (state != oldState) {
            Log.d(LOG_TAG, " ^^^ new phone state: " + state);
            notifyPhoneStateChanged();
        }
    }

    /**
     * Notify registrants of a change in the call state. This notifies changes in {@link Call.State}
     * Use this when changes in the precise call state are needed, else use notifyPhoneStateChanged.
     */
    public void notifyPreciseCallStateChanged() {
        /* we'd love it if this was package-scoped*/
        super.notifyPreciseCallStateChangedP();
    }

    public void clearDisconnected() {
        synchronized (RilImsPhone.class) {
            ringingCall.clearDisconnected();
            foregroundCall.clearDisconnected();
            backgroundCall.clearDisconnected();

            updatePhoneState();
            notifyPreciseCallStateChanged();
        }
    }

    public void sendDtmf(char c) {
        logUnexpectedCdmaMethodCall("sendDtmf");
    }

    public void startDtmf(char c) {
        logUnexpectedCdmaMethodCall("startDtmf");
    }

    public void stopDtmf() {
        logUnexpectedCdmaMethodCall("stopDtmf");
    }

    public void getOutgoingCallerIdDisplay(Message onComplete) {
        // FIXME: what to reply?
        AsyncResult.forMessage(onComplete, null, null);
        onComplete.sendToTarget();
    }

    public void setOutgoingCallerIdDisplay(int commandInterfaceCLIRMode,
                                           Message onComplete) {
        AsyncResult.forMessage(onComplete, null, null);
        onComplete.sendToTarget();
    }

    public void getCallWaiting(Message onComplete) {
        AsyncResult.forMessage(onComplete, null, null);
        onComplete.sendToTarget();
    }

    public void setCallWaiting(boolean enable, Message onComplete) {
        logUnexpectedCdmaMethodCall("setCallWaiting");
    }

    public void setMute(boolean muted) {
        synchronized (RilImsPhone.class) {
            mTracker.setMute(muted);
        }
    }

    public boolean getMute() {
        return mTracker.getMute();
    }

    public Call getForegroundCall() {
        return foregroundCall;
    }

    public Call getBackgroundCall() {
        return backgroundCall;
    }

    public Call getRingingCall() {
        return ringingCall;
    }

    public ServiceState getServiceState() {
        // TODO: Query RIL Registration state and convert that to Service state
        ServiceState s = new ServiceState();
        s.setState(ServiceState.STATE_IN_SERVICE);
        return s;
    }

    @Override
    public CellLocation getCellLocation() {
        logUnexpectedCdmaMethodCall("getCellLocation");
        return null;
    }

    @Override
    public DataState getDataConnectionState(String apnType) {
        logUnexpectedCdmaMethodCall("getDataConnectionState");
        return null;
    }

    @Override
    public DataActivityState getDataActivityState() {
        logUnexpectedCdmaMethodCall("getDataActivityState");
        return null;
    }

    @Override
    public SignalStrength getSignalStrength() {
        logUnexpectedCdmaMethodCall("getSignalStrength");
        return null;
    }

    @Override
    public List<? extends MmiCode> getPendingMmiCodes() {
        logUnexpectedCdmaMethodCall("getPendingMmiCodes");
        return null;
    }

    @Override
    public void sendUssdResponse(String ussdMessge) {
        logUnexpectedCdmaMethodCall("sendUssdResponse");
    }

    @Override
    public void registerForSuppServiceNotification(Handler h, int what, Object obj) {
        logUnexpectedCdmaMethodCall("registerForSuppServiceNotification");
    }

    @Override
    public void unregisterForSuppServiceNotification(Handler h) {
        logUnexpectedCdmaMethodCall("unregisterForSuppServiceNotification");
    }

    @Override
    public boolean handlePinMmi(String dialString) {
        logUnexpectedCdmaMethodCall("handlePinMmi");
        return false;
    }

    @Override
    public boolean handleInCallMmiCommands(String command) throws CallStateException {
        logUnexpectedCdmaMethodCall("handleInCallMmiCommands");
        return false;
    }

    @Override
    public void setRadioPower(boolean power) {
        logUnexpectedCdmaMethodCall("setRadioPower");
    }

    @Override
    public String getLine1Number() {
        logUnexpectedCdmaMethodCall("getLine1Number");
        return null;
    }

    @Override
    public String getLine1AlphaTag() {
        logUnexpectedCdmaMethodCall("getLine1AlphaTag");
        return null;
    }

    @Override
    public void setLine1Number(String alphaTag, String number, Message onComplete) {
        logUnexpectedCdmaMethodCall("setLine1Number");
    }

    @Override
    public String getVoiceMailNumber() {
        logUnexpectedCdmaMethodCall("getVoiceMailNumber");
        return null;
    }

    @Override
    public String getVoiceMailAlphaTag() {
        logUnexpectedCdmaMethodCall("getVoiceMailAlphaTag");
        return null;
    }

    @Override
    public void setVoiceMailNumber(String alphaTag, String voiceMailNumber, Message onComplete) {
        logUnexpectedCdmaMethodCall("setVoiceMailNumber");
    }

    @Override
    public void getCallForwardingOption(int commandInterfaceCFReason, Message onComplete) {
        logUnexpectedCdmaMethodCall("getCallForwardingOption");
    }

    @Override
    public void setCallForwardingOption(int commandInterfaceCFReason, int commandInterfaceCFAction,
            String dialingNumber, int timerSeconds, Message onComplete) {
        logUnexpectedCdmaMethodCall("setCallForwardingOption");
    }

    @Override
    public void getAvailableNetworks(Message response) {
        logUnexpectedCdmaMethodCall("getAvailableNetworks");
    }

    @Override
    public void setNetworkSelectionModeAutomatic(Message response) {
        logUnexpectedCdmaMethodCall("setNetworkSelectionModeAutomatic");
    }

    @Override
    public void selectNetworkManually(OperatorInfo network, Message response) {
        logUnexpectedCdmaMethodCall("selectNetworkManually");
    }

    @Override
    public void getNeighboringCids(Message response) {
        logUnexpectedCdmaMethodCall("getNeighboringCids");
    }

    @Override
    public void setOnPostDialCharacter(Handler h, int what, Object obj) {
        logUnexpectedCdmaMethodCall("setOnPostDialCharacter");
    }

    @Override
    public void getDataCallList(Message response) {
        logUnexpectedCdmaMethodCall("getDataCallList");
    }

    @Override
    public void updateServiceLocation() {
        logUnexpectedCdmaMethodCall("updateServiceLocation");
    }

    @Override
    public void enableLocationUpdates() {
        logUnexpectedCdmaMethodCall("enableLocationUpdates");
    }

    @Override
    public void disableLocationUpdates() {
        logUnexpectedCdmaMethodCall("disableLocationUpdates");
    }

    @Override
    public boolean getDataRoamingEnabled() {
        logUnexpectedCdmaMethodCall("getDataRoamingEnabled");
        return false;
    }

    @Override
    public void setDataRoamingEnabled(boolean enable) {
        logUnexpectedCdmaMethodCall("setDataRoamingEnabled");
    }

    @Override
    public String getDeviceId() {
        logUnexpectedCdmaMethodCall("getDeviceId");
        return null;
    }

    @Override
    public String getDeviceSvn() {
        logUnexpectedCdmaMethodCall("getDeviceSvn");
        return null;
    }

    @Override
    public String getSubscriberId() {
        logUnexpectedCdmaMethodCall("getSubscriberId");
        return null;
    }

    @Override
    public String getEsn() {
        logUnexpectedCdmaMethodCall("getEsn");
        return null;
    }

    @Override
    public String getMeid() {
        logUnexpectedCdmaMethodCall("getMeid");
        return null;
    }

    @Override
    public String getImei() {
        logUnexpectedCdmaMethodCall("getImei");
        return null;
    }

    @Override
    public PhoneSubInfo getPhoneSubInfo() {
        logUnexpectedCdmaMethodCall("getPhoneSubInfo");
        return null;
    }

    @Override
    public IccPhoneBookInterfaceManager getIccPhoneBookInterfaceManager() {
        logUnexpectedCdmaMethodCall("getIccPhoneBookInterfaceManager");
        return null;
    }

    @Override
    public void activateCellBroadcastSms(int activate, Message response) {
        logUnexpectedCdmaMethodCall("activateCellBroadcastSms");
    }

    @Override
    public void getCellBroadcastSmsConfig(Message response) {
        logUnexpectedCdmaMethodCall("getCellBroadcastSmsConfig");
    }

    @Override
    public void setCellBroadcastSmsConfig(int[] configValuesArray, Message response) {
        logUnexpectedCdmaMethodCall("setCellBroadcastSmsConfig");
    }

    @Override
    protected void updateIccAvailability() {
        logUnexpectedCdmaMethodCall("updateIccAvailability");
    }

    @Override
    public State getState() {
        return state;
    }

    @Override
    public void setState(Phone.State newState) {
        state = newState;
    }

    @Override
    public int getPhoneType() {
        return RILConstants.RIL_IMS_PHONE;
    }

    public Connection dial(String dialString, CallDetails details) throws CallStateException {
        return dialInternal(dialString, details);
    }

    @Override
    public void notifyDisconnect(Connection cn) {
        logUnexpectedCdmaMethodCall("notifyDisconnect");
    }

    public Registrant getPostDialHandler() {
        logUnexpectedCdmaMethodCall("getPostDialHandler");
        return null;
    }

    public int getSupportedDomain() {
        return CallDetails.RIL_CALL_DOMAIN_PS;
    }

    public int getMaxConnectionsPerCall() {
        return MAX_CONNECTIONS_PER_CALL;
    }

    public int getMaxConnections() {
        return MAX_CONNECTIONS;
    }

    public CallTracker getCallTracker() {
        return mTracker;
    }

    public DisconnectCause
    disconnectCauseFromCode(int causeCode) {
        /**
         * See 22.001 Annex F.4 for mapping of cause codes to local tones
         */

        switch (causeCode) {
            case CallFailCause.NO_CIRCUIT_AVAIL:
            case CallFailCause.TEMPORARY_FAILURE:
            case CallFailCause.SWITCHING_CONGESTION:
            case CallFailCause.CHANNEL_NOT_AVAIL:
            case CallFailCause.QOS_NOT_AVAIL:
            case CallFailCause.BEARER_NOT_AVAIL:
                return DisconnectCause.CONGESTION;
            case CallFailCause.ACM_LIMIT_EXCEEDED:
                return DisconnectCause.LIMIT_EXCEEDED;
            case CallFailCause.CALL_BARRED:
                return DisconnectCause.CALL_BARRED;
            case CallFailCause.FDN_BLOCKED:
                return DisconnectCause.FDN_BLOCKED;
            case CallFailCause.UNOBTAINABLE_NUMBER:
                return DisconnectCause.UNOBTAINABLE_NUMBER;
            case CallFailCause.DIAL_MODIFIED_TO_USSD:
                return DisconnectCause.DIAL_MODIFIED_TO_USSD;
            case CallFailCause.DIAL_MODIFIED_TO_SS:
                return DisconnectCause.DIAL_MODIFIED_TO_SS;
            case CallFailCause.DIAL_MODIFIED_TO_DIAL:
                return DisconnectCause.DIAL_MODIFIED_TO_DIAL;
            case CallFailCause.USER_BUSY:
                return DisconnectCause.BUSY;
            case CallFailCause.CDMA_LOCKED_UNTIL_POWER_CYCLE:
                return DisconnectCause.CDMA_LOCKED_UNTIL_POWER_CYCLE;
            case CallFailCause.CDMA_DROP:
                return DisconnectCause.CDMA_DROP;
            case CallFailCause.CDMA_INTERCEPT:
                return DisconnectCause.CDMA_INTERCEPT;
            case CallFailCause.CDMA_REORDER:
                return DisconnectCause.CDMA_REORDER;
            case CallFailCause.CDMA_SO_REJECT:
                return DisconnectCause.CDMA_SO_REJECT;
            case CallFailCause.CDMA_RETRY_ORDER:
                return DisconnectCause.CDMA_RETRY_ORDER;
            case CallFailCause.CDMA_ACCESS_FAILURE:
                return DisconnectCause.CDMA_ACCESS_FAILURE;
            case CallFailCause.CDMA_PREEMPTED:
                return DisconnectCause.CDMA_PREEMPTED;
            case CallFailCause.CDMA_NOT_EMERGENCY:
                return DisconnectCause.CDMA_NOT_EMERGENCY;
            case CallFailCause.CDMA_ACCESS_BLOCKED:
                return DisconnectCause.CDMA_ACCESS_BLOCKED;
            case CallFailCause.ERROR_UNSPECIFIED:
            case CallFailCause.NORMAL_CLEARING:
            default: {
                int serviceState = getServiceState().getState();
                if (serviceState == ServiceState.STATE_POWER_OFF) {
                    return DisconnectCause.POWER_OFF;
                } else if (serviceState == ServiceState.STATE_OUT_OF_SERVICE
                        || serviceState == ServiceState.STATE_EMERGENCY_ONLY) {
                    return DisconnectCause.OUT_OF_SERVICE;
                } else if (causeCode == CallFailCause.NORMAL_CLEARING) {
                    return DisconnectCause.NORMAL;
                } else {
                    return DisconnectCause.ERROR_UNSPECIFIED;
                }
            }
        }
    }

    @Override
    public void dispose() {
        synchronized (PhoneProxy.lockForRadioTechnologyChange) {
            Log.d(LOG_TAG, "dispose ");

            // Unregister from all former registered events

            // hangup ims connections
            // if any handover to CS domain , it has to be done before this
            for (Connection c : foregroundCall.connections) {
                try {
                    if (c != null)
                        mCT.hangup(c);
                } catch (CallStateException ex) {
                    Log.e(LOG_TAG,
                            "unexpected error on forground call hangup during imsphone dispose");
                }
            }

            for (Connection c : backgroundCall.connections) {
                try {
                    if (c != null)
                        mCT.hangup(c);
                } catch (CallStateException ex) {
                    Log.e(LOG_TAG,
                            "unexpected error on forground call hangup during imsphone dispose");
                }
            }

            for (Connection c : ringingCall.connections) {
                try {
                    if (c != null)
                        mCT.hangup(c);
                } catch (CallStateException ex) {
                    Log.e(LOG_TAG,
                            "unexpected error on forground call hangup during imsphone dispose");
                }
            }

            /*
             * TODO try { if(mCT.pendingMO != null) hangup(pendingMO); } catch
             * (CallStateException ex) { Log.e(LOG_TAG,
             * "unexpected error on hangup during dispose"); }
             */
            clearDisconnected();

            mCT.imsPhone = null;
        }
    }

    /**
     * Common error logger method for unexpected calls to CDMA-only methods.
     */
    private void logUnexpectedCdmaMethodCall(String name)
    {
        Log.e(LOG_TAG, "Error! " + name + "() is not supported by " + getPhoneName());
    }
}
