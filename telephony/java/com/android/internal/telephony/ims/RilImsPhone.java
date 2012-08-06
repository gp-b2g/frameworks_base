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
    private ServiceState mServiceState;

    public RilImsPhone(Context context, PhoneNotifier notifier, CallTracker tracker,
            CommandsInterface cm) {
        super(notifier, context, cm);

        mCT= tracker;
        mCT.imsPhone = this;
        mCT.createImsCalls();
        cm.registerForImsNetworkStateChanged(this, EVENT_IMS_STATE_CHANGED, null);

        // Query for registration state in case we have missed the UNSOL
        cm.getImsRegistrationState(this.obtainMessage(EVENT_IMS_STATE_DONE));
        mServiceState = new ServiceState();
        mServiceState.setStateOutOfService();
    }

    public void handleMessage(Message msg) {
        Log.d(LOG_TAG, "Received event:" + msg.what);
        switch (msg.what) {
            case EVENT_IMS_STATE_CHANGED:
                mCM.getImsRegistrationState(this.obtainMessage(EVENT_IMS_STATE_DONE));
                break;
            case EVENT_IMS_STATE_DONE:
                AsyncResult ar = (AsyncResult) msg.obj;
                if (ar.exception == null && ar.result != null && ((int[]) ar.result).length >= 1) {
                    int[] responseArray = (int[]) ar.result;
                    Log.d(LOG_TAG, "IMS registration state is: " + responseArray[0]);
                    if (responseArray[0] == 0)
                        mServiceState.setState(ServiceState.STATE_OUT_OF_SERVICE);
                    else if (responseArray[0] == 1)
                        mServiceState.setState(ServiceState.STATE_IN_SERVICE);
                } else {
                    Log.e(LOG_TAG, "IMS State query failed!");
                }
                break;
            default:
                super.handleMessage(msg);
        }
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
        mCT.acceptCall(this, callType);
    }

    public void acceptCall() throws CallStateException {
        mCT.acceptCall(this);
    }

    public void rejectCall() throws CallStateException {
        mCT.rejectCall(this);
    }

    public boolean canDial() {
        Log.v(LOG_TAG, "canDial(): serviceState = " + mServiceState.getState());
        if (mServiceState.getState() != ServiceState.STATE_IN_SERVICE) return false;

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
            return dialInternal(dialString, null);
        }
    }

    private Connection dialInternal(String dialString, CallDetails det)
            throws CallStateException {

        if (!canDial()) {
            throw new CallStateException("cannot dial in current state");
        }

        // foregroundCall.setMute(false);
        if (det != null) {
            if (DEBUG) Log.d(LOG_TAG, " PS call dial");
            det.call_domain = CallDetails.RIL_CALL_DOMAIN_PS;
        } else {
            if (DEBUG) Log.d(LOG_TAG, " CS call dial, dorcing domain change");
            det = new CallDetails(CallDetails.RIL_CALL_TYPE_VOICE, CallDetails.RIL_CALL_DOMAIN_CS,
                    null);// CS voice
        }
        Connection c = mCT.dial(dialString, det);
        return c;
    }

    public void switchHoldingAndActive() throws CallStateException {
        if (DEBUG) Log.d(LOG_TAG, " ~~~~~~  switch fg and bg");
        synchronized (RilImsPhone.class) {
            mCT.switchWaitingOrHoldingAndActiveIms();
        }
    }

    public boolean canConference() {
        logUnexpectedMethodCall("canConference");
        return false;
    }

    public void conference() throws CallStateException {
        logUnexpectedMethodCall("conference");
    }

    public boolean canTransfer() {
        logUnexpectedMethodCall("canTransfer");
        return false;
    }

    public void explicitCallTransfer() throws CallStateException {
        logUnexpectedMethodCall("explicitCallTransfer");
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
        mCT.clearDisconnected(this);
        /*
        synchronized (RilImsPhone.class) {
            ringingCall.clearDisconnected();
            foregroundCall.clearDisconnected();
            backgroundCall.clearDisconnected();

            updatePhoneState();
            notifyPreciseCallStateChanged();
        }
        */
    }

    public void sendDtmf(char c) {
        logUnexpectedMethodCall("sendDtmf");
    }

    public void startDtmf(char c) {
        logUnexpectedMethodCall("startDtmf");
    }

    public void stopDtmf() {
        logUnexpectedMethodCall("stopDtmf");
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
        logUnexpectedMethodCall("setCallWaiting");
    }

    public void setMute(boolean muted) {
        synchronized (RilImsPhone.class) {
            mCT.setMute(muted);
        }
    }

    public boolean getMute() {
        return mCT.getMute();
    }

    public Call getForegroundCall() {
        return mCT.foregroundCallIms;
    }

    public Call getBackgroundCall() {
        return mCT.backgroundCallIms;
    }

    public Call getRingingCall() {
        return mCT.ringingCallIms;
    }

    public ServiceState getServiceState() {
        return mServiceState;
    }

    @Override
    public CellLocation getCellLocation() {
        logUnexpectedMethodCall("getCellLocation");
        return null;
    }

    @Override
    public DataState getDataConnectionState(String apnType) {
        logUnexpectedMethodCall("getDataConnectionState");
        return null;
    }

    @Override
    public DataActivityState getDataActivityState() {
        logUnexpectedMethodCall("getDataActivityState");
        return null;
    }

    @Override
    public SignalStrength getSignalStrength() {
        logUnexpectedMethodCall("getSignalStrength");
        return null;
    }

    @Override
    public List<? extends MmiCode> getPendingMmiCodes() {
        logUnexpectedMethodCall("getPendingMmiCodes");
        return null;
    }

    @Override
    public void sendUssdResponse(String ussdMessge) {
        logUnexpectedMethodCall("sendUssdResponse");
    }

    @Override
    public void registerForSuppServiceNotification(Handler h, int what, Object obj) {
        logUnexpectedMethodCall("registerForSuppServiceNotification");
    }

    @Override
    public void unregisterForSuppServiceNotification(Handler h) {
        logUnexpectedMethodCall("unregisterForSuppServiceNotification");
    }

    @Override
    public boolean handlePinMmi(String dialString) {
        logUnexpectedMethodCall("handlePinMmi");
        return false;
    }

    @Override
    public boolean handleInCallMmiCommands(String command) throws CallStateException {
        logUnexpectedMethodCall("handleInCallMmiCommands");
        return false;
    }

    @Override
    public void setRadioPower(boolean power) {
        logUnexpectedMethodCall("setRadioPower");
    }

    @Override
    public String getLine1Number() {
        logUnexpectedMethodCall("getLine1Number");
        return null;
    }

    @Override
    public String getLine1AlphaTag() {
        logUnexpectedMethodCall("getLine1AlphaTag");
        return null;
    }

    @Override
    public void setLine1Number(String alphaTag, String number, Message onComplete) {
        logUnexpectedMethodCall("setLine1Number");
    }

    @Override
    public String getVoiceMailNumber() {
        logUnexpectedMethodCall("getVoiceMailNumber");
        return null;
    }

    @Override
    public String getVoiceMailAlphaTag() {
        logUnexpectedMethodCall("getVoiceMailAlphaTag");
        return null;
    }

    @Override
    public void setVoiceMailNumber(String alphaTag, String voiceMailNumber, Message onComplete) {
        logUnexpectedMethodCall("setVoiceMailNumber");
    }

    @Override
    public void getCallForwardingOption(int commandInterfaceCFReason, Message onComplete) {
        logUnexpectedMethodCall("getCallForwardingOption");
    }

    @Override
    public void setCallForwardingOption(int commandInterfaceCFReason, int commandInterfaceCFAction,
            String dialingNumber, int timerSeconds, Message onComplete) {
        logUnexpectedMethodCall("setCallForwardingOption");
    }
    
    public void setVideoCallForwardingOption(int commandInterfaceCFReason,
            int commandInterfaceCFAction,
            String dialingNumber,
            int timerSeconds,
            Message onComplete) {
    	Log.e(LOG_TAG, "setVideoCallForwardingOption: not possible in CDMA");
    }

    @Override
    public void getAvailableNetworks(Message response) {
        logUnexpectedMethodCall("getAvailableNetworks");
    }

    @Override
    public void setNetworkSelectionModeAutomatic(Message response) {
        logUnexpectedMethodCall("setNetworkSelectionModeAutomatic");
    }

    @Override
    public void selectNetworkManually(OperatorInfo network, Message response) {
        logUnexpectedMethodCall("selectNetworkManually");
    }

    @Override
    public void getNeighboringCids(Message response) {
        logUnexpectedMethodCall("getNeighboringCids");
    }

    @Override
    public void setOnPostDialCharacter(Handler h, int what, Object obj) {
        logUnexpectedMethodCall("setOnPostDialCharacter");
    }

    @Override
    public void getDataCallList(Message response) {
        logUnexpectedMethodCall("getDataCallList");
    }

    @Override
    public void updateServiceLocation() {
        logUnexpectedMethodCall("updateServiceLocation");
    }

    @Override
    public void enableLocationUpdates() {
        logUnexpectedMethodCall("enableLocationUpdates");
    }

    @Override
    public void disableLocationUpdates() {
        logUnexpectedMethodCall("disableLocationUpdates");
    }

    @Override
    public boolean getDataRoamingEnabled() {
        logUnexpectedMethodCall("getDataRoamingEnabled");
        return false;
    }

    @Override
    public void setDataRoamingEnabled(boolean enable) {
        logUnexpectedMethodCall("setDataRoamingEnabled");
    }

    @Override
    public String getDeviceId() {
        logUnexpectedMethodCall("getDeviceId");
        return null;
    }

    @Override
    public String getDeviceSvn() {
        logUnexpectedMethodCall("getDeviceSvn");
        return null;
    }

    @Override
    public String getSubscriberId() {
        logUnexpectedMethodCall("getSubscriberId");
        return null;
    }

    @Override
    public String getEsn() {
        logUnexpectedMethodCall("getEsn");
        return null;
    }

    @Override
    public String getMeid() {
        logUnexpectedMethodCall("getMeid");
        return null;
    }

    @Override
    public String getImei() {
        logUnexpectedMethodCall("getImei");
        return null;
    }

    @Override
    public PhoneSubInfo getPhoneSubInfo() {
        logUnexpectedMethodCall("getPhoneSubInfo");
        return null;
    }

    @Override
    public IccPhoneBookInterfaceManager getIccPhoneBookInterfaceManager() {
        logUnexpectedMethodCall("getIccPhoneBookInterfaceManager");
        return null;
    }

    @Override
    public void activateCellBroadcastSms(int activate, Message response) {
        logUnexpectedMethodCall("activateCellBroadcastSms");
    }

    @Override
    public void getCellBroadcastSmsConfig(Message response) {
        logUnexpectedMethodCall("getCellBroadcastSmsConfig");
    }

    @Override
    public void setCellBroadcastSmsConfig(int[] configValuesArray, Message response) {
        logUnexpectedMethodCall("setCellBroadcastSmsConfig");
    }

    @Override
    protected void updateIccAvailability() {
        logUnexpectedMethodCall("updateIccAvailability");
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
        logUnexpectedMethodCall("notifyDisconnect");
    }

    public Registrant getPostDialHandler() {
        logUnexpectedMethodCall("getPostDialHandler");
        return null;
    }

    public int getMaxConnectionsPerCall() {
        return MAX_CONNECTIONS_PER_CALL;
    }

    public int getMaxConnections() {
        return MAX_CONNECTIONS;
    }

    public CallTracker getCallTracker() {
        return mCT;
    }

    public DisconnectCause
    disconnectCauseFromCode(int causeCode) {
        /**
         * See 22.001 Annex F.4 for mapping of cause codes to local tones
         */
        int serviceState = mServiceState.getState();
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

    @Override
    public void dispose() {
        synchronized (PhoneProxy.lockForRadioTechnologyChange) {
            Log.d(LOG_TAG, "dispose ");
            clearDisconnected();
            mCT.imsPhone = null;
        }
    }

    /**
     * Common error logger method for unexpected calls to RilImsPhone methods.
     */
    private void logUnexpectedMethodCall(String name)
    {
        Log.e(LOG_TAG, "Error! " + name + "() is not supported by " + getPhoneName());
    }

    public  void avoidCurrentCdmaSystem(boolean on,Message response){
    }

    public void enableEngineerMode(int on) {
    }
}
