/*
 * Copyright (C) 2006 The Android Open Source Project
 * Copyright (c) 2011-2012 Code Aurora Forum. All rights reserved.
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

package com.android.internal.telephony.gsm;

import android.app.AlarmManager;
import android.content.Context;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.os.RegistrantList;
import android.provider.Settings;
import android.net.TrafficStats;
import android.os.SystemProperties;
import android.util.Log;

import com.android.internal.telephony.ApnContext;
import com.android.internal.telephony.DataConnection;
import com.android.internal.telephony.DataConnectionAc;
import com.android.internal.telephony.IccRecords;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.Subscription;
import com.android.internal.telephony.MSimPhoneFactory;
import com.android.internal.telephony.UiccManager;
import com.android.internal.telephony.UiccManager.AppFamily;
import com.android.internal.telephony.UiccCardApplication;

import java.util.ArrayList;
import java.util.Collection;

/**
 * This file is used to handle Multi sim case
 * Functions are overriden to register and notify data disconnect
 */
public final class MSimGsmDataConnectionTracker extends GsmDataConnectionTracker {

    /** Subscription id */
    protected int mSubscription;

    protected MSimGSMPhone mPhone;
    /**
     * List of messages that are waiting to be posted, when data call disconnect
     * is complete
     */
    private ArrayList <Message> mDisconnectAllCompleteMsgList = new ArrayList<Message>();

    private RegistrantList mAllDataDisconnectedRegistrants = new RegistrantList();

    protected int mDisconnectPendingCount = 0;

    protected Message mPendingDataDisableCompleteMsg;
    Subscription mSubscriptionData;

    /*check data activity*/
    private static final int MAX_DATA_ACTIVITY_ERROR_COUNT = 20;
    private int mDataActivityErrorCount = 0;
    private long mTxPkts = 0;
    private long mRxPkts = 0;

    /*this property is used for turning on data activity reset.*/
    private static final boolean SUPPORT_DATA_ACTIVITY_RESET =
        SystemProperties.getBoolean("persist.telephony.da.reset", true);

    MSimGsmDataConnectionTracker(MSimGSMPhone p) {
        super(p);
        mPhone = p;
        mSubscription = mPhone.getSubscription();
        mInternalDataEnabled = mSubscription == MSimPhoneFactory.getDataSubscription();
        log("mInternalDataEnabled (is data sub?) = " + mInternalDataEnabled);
    }

    protected void registerForAllEvents() {
        mPhone.mCM.registerForAvailable (this, EVENT_RADIO_AVAILABLE, null);
        mPhone.mCM.registerForOffOrNotAvailable(this, EVENT_RADIO_OFF_OR_NOT_AVAILABLE, null);
        mPhone.mCM.registerForDataCallListChanged(this, EVENT_DATA_STATE_CHANGED, null);
        mPhone.getCallTracker().registerForVoiceCallEnded (this, EVENT_VOICE_CALL_ENDED, null);
        mPhone.getCallTracker().registerForVoiceCallStarted (this, EVENT_VOICE_CALL_STARTED, null);
        mPhone.getServiceStateTracker().registerForDataConnectionAttached(this,
               EVENT_DATA_CONNECTION_ATTACHED, null);
        mPhone.getServiceStateTracker().registerForDataConnectionDetached(this,
               EVENT_DATA_CONNECTION_DETACHED, null);
        mPhone.getServiceStateTracker().registerForRoamingOn(this, EVENT_ROAMING_ON, null);
        mPhone.getServiceStateTracker().registerForRoamingOff(this, EVENT_ROAMING_OFF, null);
        mPhone.getServiceStateTracker().registerForPsRestrictedEnabled(this,
                EVENT_PS_RESTRICT_ENABLED, null);
        mPhone.getServiceStateTracker().registerForPsRestrictedDisabled(this,
                EVENT_PS_RESTRICT_DISABLED, null);

        sendMessageDelayed(obtainMessage(EVENT_CHECK_DATA_ACTIVITY),10*1000);
    }

    protected void unregisterForAllEvents() {
         //Unregister for all events
        mPhone.mCM.unregisterForAvailable(this);
        mPhone.mCM.unregisterForOffOrNotAvailable(this);
        mPhone.mCM.unregisterForDataCallListChanged(this);
        if (mIccRecords != null) { mIccRecords.unregisterForRecordsLoaded(this);}
        mPhone.getCallTracker().unregisterForVoiceCallEnded(this);
        mPhone.getCallTracker().unregisterForVoiceCallStarted(this);
        mPhone.getServiceStateTracker().unregisterForDataConnectionAttached(this);
        mPhone.getServiceStateTracker().unregisterForDataConnectionDetached(this);
        mPhone.getServiceStateTracker().unregisterForRoamingOn(this);
        mPhone.getServiceStateTracker().unregisterForRoamingOff(this);
        mPhone.getServiceStateTracker().unregisterForPsRestrictedEnabled(this);
        mPhone.getServiceStateTracker().unregisterForPsRestrictedDisabled(this);

        if (hasMessages(EVENT_CHECK_DATA_ACTIVITY)){
            removeMessages(EVENT_CHECK_DATA_ACTIVITY);
        }
    }

    @Override
    public void handleMessage (Message msg) {
        if (!isActiveDataSubscription()) {
            loge("Ignore GSM msgs since GSM phone is not the current DDS");
            return;
        }
        switch (msg.what) {
            case EVENT_SET_INTERNAL_DATA_ENABLE:
                boolean enabled = (msg.arg1 == ENABLED) ? true : false;
                onSetInternalDataEnabled(enabled, (Message) msg.obj);
                break;
            case EVENT_CHECK_DATA_ACTIVITY:
                onCheckDataActivity();
                break;
            default:
                super.handleMessage(msg);
        }
    }

    private void onCheckDataActivity(){
        if (!SUPPORT_DATA_ACTIVITY_RESET){
            return;
        }
        long prevTxPkts,prevRxPkts;
        prevTxPkts = mTxPkts;
        prevRxPkts = mRxPkts;

        mTxPkts = TrafficStats.getMobileTxPackets();
        mRxPkts = TrafficStats.getMobileRxPackets();

        if (prevTxPkts >0 || prevRxPkts >0){
            long sent = mTxPkts-prevTxPkts;
            long received = mRxPkts- prevRxPkts;
            if (sent > 0 && received == 0){
                mDataActivityErrorCount++;
            }else if (sent == 0 && received >0){
                mDataActivityErrorCount++;
            }else if (sent >0 && received >0){
                mDataActivityErrorCount = 0;
            }
        }

        if (mDataActivityErrorCount > MAX_DATA_ACTIVITY_ERROR_COUNT){
            log("something is wrong with data activity, reset the pdp connection");
            cleanUpAllConnections("data activity reset");
        }

        //periodic check
        sendMessageDelayed(obtainMessage(EVENT_CHECK_DATA_ACTIVITY),10*1000);
    }

    /**
     * If tearDown is true, this only tears down a CONNECTED session. Presently,
     * there is no mechanism for abandoning an INITING/CONNECTING session,
     * but would likely involve cancelling pending async requests or
     * setting a flag or new state to ignore them when they came in
     *
     * Notify Data connection after disonnect complete
     *
     * @param tearDown true if the underlying GsmDataConnection should be
     * disconnected.
     * @param reason reason for the clean up.
     *
     */
    @Override
    protected void cleanUpAllConnections(boolean tearDown, String reason) {
        super.cleanUpAllConnections(tearDown, reason);

        log("cleanUpConnection: mDisconnectPendingCount = " + mDisconnectPendingCount);
        if (tearDown && mDisconnectPendingCount == 0) {
            notifyDataDisconnectComplete();
            notifyAllDataDisconnected();
        }
    }

    @Override
    protected void cleanUpConnection(boolean tearDown, ApnContext apnContext) {

        if (apnContext == null) {
            if (DBG) log("cleanUpConnection: apn context is null");
            return;
        }

        if (DBG) {
            log("cleanUpConnection: tearDown=" + tearDown + " reason=" + apnContext.getReason());
        }
        DataConnectionAc dcac = apnContext.getDataConnectionAc();
        if (tearDown) {
            boolean isConnected = (apnContext.getState() != State.IDLE
                                   && apnContext.getState() != State.FAILED);
            if (!isConnected) {
                // The request is tearDown and but ApnContext is not connected.
                // If apnContext is not enabled anymore, break the linkage to the DCAC/DC.
                apnContext.setState(State.IDLE);
                if (!apnContext.isReady()) {
                    apnContext.setDataConnection(null);
                    apnContext.setDataConnectionAc(null);
                }
            } else {
                // Connection is still there. Try to clean up.
                if (dcac != null) {
                    if (apnContext.getState() != State.DISCONNECTING) {
                        if (DBG) log("cleanUpConnection: tearing down");
                        Message msg = obtainMessage(EVENT_DISCONNECT_DONE, apnContext);
                        apnContext.getDataConnection().tearDown(apnContext.getReason(), msg);
                        apnContext.setState(State.DISCONNECTING);
                        mDisconnectPendingCount++;
                    }
                } else {
                    // apn is connected but no reference to dcac.
                    // Should not be happen, but reset the state in case.
                    apnContext.setState(State.IDLE);
                    mPhone.notifyDataConnection(apnContext.getReason(),
                                                apnContext.getApnType());
                }
            }
        } else {
            // force clean up the data connection.
            if (dcac != null) dcac.resetSync();
            apnContext.setState(State.IDLE);
            mPhone.notifyDataConnection(apnContext.getReason(), apnContext.getApnType());
            apnContext.setDataConnection(null);
            apnContext.setDataConnectionAc(null);
        }

        // make sure reconnection alarm is cleaned up if there is no ApnContext
        // associated to the connection.
        if (dcac != null) {
            Collection<ApnContext> apnList = dcac.getApnListSync();
            if (apnList.isEmpty()) {
                cancelReconnectAlarm(dcac);
            }
        }

    }

    /**
     * Called when EVENT_DISCONNECT_DONE is received.
     */
    @Override
    protected void onDisconnectDone(int connId, AsyncResult ar) {
        super.onDisconnectDone(connId, ar);
        if (mDisconnectPendingCount > 0)
            mDisconnectPendingCount--;

        if (mDisconnectPendingCount == 0) {
            notifyDataDisconnectComplete();
            notifyAllDataDisconnected();
        }
    }

    @Override
    protected void broadcastMessenger() {
        // Broadcast the data connection tracker messenger only if
        // this is corresponds to the current DDS.
        if (!isActiveDataSubscription()) {
            return;
        }
        super.broadcastMessenger();
    }

    @Override
    protected IccRecords getUiccCardApplication() {
        Subscription subscriptionData = mPhone.getSubscriptionInfo();
        if(subscriptionData != null) {
            return  mUiccManager.getIccRecords(subscriptionData.slotId, AppFamily.APP_FAM_3GPP);
        }
        return null;
    }

    @Override
    public boolean setInternalDataEnabled(boolean enable) {
        return setInternalDataEnabled(enable, null);
    }

    public boolean setInternalDataEnabled(boolean enable, Message onCompleteMsg) {
        if (DBG)
            log("setInternalDataEnabled(" + enable + ")");

        Message msg = obtainMessage(EVENT_SET_INTERNAL_DATA_ENABLE, onCompleteMsg);
        msg.arg1 = (enable ? ENABLED : DISABLED);
        sendMessage(msg);
        return true;
    }

    @Override
    protected void onSetInternalDataEnabled(boolean enable) {
        onSetInternalDataEnabled(enable, null);
    }

    protected void onSetInternalDataEnabled(boolean enable, Message onCompleteMsg) {
        log("onSetInternalDataEnabled");
        boolean prevEnabled = getAnyDataEnabled();
        boolean sendOnComplete = true;
        if (mInternalDataEnabled != enable) {
            synchronized (this) {
                mInternalDataEnabled = enable;
            }
            if (prevEnabled != getAnyDataEnabled()) {
                sendOnComplete = false;
                if (!prevEnabled) {
                    resetAllRetryCounts();
                    onTrySetupData(Phone.REASON_DATA_ENABLED);
                } else {
                    cleanUpAllConnections(null, onCompleteMsg);
                }
            }
        }

        if (sendOnComplete) {
            if (onCompleteMsg != null) {
                onCompleteMsg.sendToTarget();
            }
        }
    }

    @Override
    public void cleanUpAllConnections(String cause) {
        cleanUpAllConnections(cause, null);
    }

    public void updateRecords() {
        if (isActiveDataSubscription()) {
            updateIccAvailability();
        }
    }

    public void cleanUpAllConnections(String cause, Message disconnectAllCompleteMsg) {
        log("cleanUpAllConnections");
        if (disconnectAllCompleteMsg != null) {
            mDisconnectAllCompleteMsgList.add(disconnectAllCompleteMsg);
        }

        Message msg = obtainMessage(EVENT_CLEAN_UP_ALL_CONNECTIONS);
        msg.obj = cause;
        sendMessage(msg);
    }

    /** Returns true if this is current DDS. */
    protected boolean isActiveDataSubscription() {
        return (mSubscription == MSimPhoneFactory.getDataSubscription());
    }

    // setAsCurrentDataConnectionTracker
    protected void update() {
        log("update");
        if (isActiveDataSubscription()) {
            log("update(): Active DDS, register for all events now!");
            registerForAllEvents();
            updateIccAvailability();

            mUserDataEnabled = Settings.Secure.getInt(mPhone.getContext().getContentResolver(),
                    Settings.Secure.MOBILE_DATA, 1) == 1;
            mPhone.updateCurrentCarrierInProvider();
            broadcastMessenger();
            log("reload the apn list.");
            sendMessage(obtainMessage(EVENT_RECORDS_LOADED));
        } else {
            log("update(): NOT the active DDS, unregister for all events!");
            unregisterForAllEvents();
        }
        //reset data activity check condition
        mDataActivityErrorCount = 0;
        mTxPkts = mRxPkts = 0;
    }

    protected void notifyDataDisconnectComplete() {
        log("notifyDataDisconnectComplete");
        for (Message m: mDisconnectAllCompleteMsgList) {
            m.sendToTarget();
        }
        mDisconnectAllCompleteMsgList.clear();
    }

    protected void notifyAllDataDisconnected() {
        mAllDataDisconnectedRegistrants.notifyRegistrants();
    }

    public void registerForAllDataDisconnected(Handler h, int what, Object obj) {
        mAllDataDisconnectedRegistrants.addUnique(h, what, obj);

        if (isDisconnected()) {
            log("notify All Data Disconnected");
            mAllDataDisconnectedRegistrants.notifyRegistrants();
        }
    }

    public void unregisterForAllDataDisconnected(Handler h) {
        mAllDataDisconnectedRegistrants.remove(h);
    }

    @Override
    protected void log(String s) {
        Log.d(LOG_TAG, "[MSimGsmDCT:" + mSubscription + "] " + s);
    }

    @Override
    protected void loge(String s) {
        Log.e(LOG_TAG, "[MSimGsmDCT:" + mSubscription + "] " + s);
    }
}
