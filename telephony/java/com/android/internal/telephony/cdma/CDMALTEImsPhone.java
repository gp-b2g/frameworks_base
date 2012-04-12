/*
 * Copyright (C) 2006 The Android Open Source Project
 * Copyright (c) 2012, Code Aurora Forum. All rights reserved.
 * Not a Contribution.
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

package com.android.internal.telephony.cdma;

import static com.android.internal.telephony.TelephonyProperties.PROPERTY_ICC_OPERATOR_ALPHA;
import static com.android.internal.telephony.TelephonyProperties.PROPERTY_ICC_OPERATOR_NUMERIC;

import com.android.internal.telephony.Call;
import com.android.internal.telephony.CallDetails;
import com.android.internal.telephony.CallManager;
import com.android.internal.telephony.CallStateException;
import com.android.internal.telephony.CallTracker;
import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.Connection;
import com.android.internal.telephony.IccCardApplicationStatus;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneFactory;
import com.android.internal.telephony.PhoneNotifier;
import com.android.internal.telephony.PhoneSubInfo;
import com.android.internal.telephony.TelephonyProperties;
import com.android.internal.telephony.ims.RilImsPhone;
import com.android.internal.telephony.Connection.DisconnectCause;

import android.content.Context;
import android.os.AsyncResult;
import android.os.Message;
import android.os.PowerManager;
import android.os.SystemProperties;
import android.telephony.PhoneNumberUtils;
import android.telephony.ServiceState;
import android.util.Log;

public class CDMALTEImsPhone extends CDMALTEPhone {
    private static final String LOG_TAG = "CDMALTEImsPhone";
    private RilImsPhone imsPhone;

    public CDMALTEImsPhone(Context context, CommandsInterface ci, PhoneNotifier notifier) {
        super(context, ci, notifier);
    }

    @Override
    public State getState() {
        return mCT.state;
    }

    @Override
    public void setState(Phone.State newState) {
        mCT.state = newState;
    }

    protected void init(Context context, PhoneNotifier notifier) {
        Log.d(LOG_TAG, "init()");

        mCM.setPhoneType(Phone.PHONE_TYPE_CDMA);
        mCT = new CdmaImsCallTracker(this);
        mCdmaSSM = CdmaSubscriptionSourceManager.getInstance(context, mCM, this,
                EVENT_CDMA_SUBSCRIPTION_SOURCE_CHANGED, null);
        mDataConnectionTracker = new CdmaDataConnectionTracker (this);
        mRuimPhoneBookInterfaceManager = new RuimPhoneBookInterfaceManager(this);
        mSubInfo = new PhoneSubInfo(this);
        mEriManager = new EriManager(this, context, EriManager.ERI_FROM_XML);

        mCM.registerForAvailable(this, EVENT_RADIO_AVAILABLE, null);
        mCM.registerForOffOrNotAvailable(this, EVENT_RADIO_OFF_OR_NOT_AVAILABLE, null);
        mCM.registerForOn(this, EVENT_RADIO_ON, null);
        mCM.setOnSuppServiceNotification(this, EVENT_SSN, null);
        mSST.registerForNetworkAttached(this, EVENT_REGISTERED_TO_NETWORK, null);
        mCM.setEmergencyCallbackMode(this, EVENT_EMERGENCY_CALLBACK_MODE_ENTER, null);
        mCM.registerForExitEmergencyCallbackMode(this, EVENT_EXIT_EMERGENCY_CALLBACK_RESPONSE,
                null);
        if (PhoneFactory.isCallOnImsEnabled()) {
            mCM.registerForImsNetworkStateChanged(this, EVENT_IMS_STATE_CHANGED, null);

            // Query for registration state in case we have missed the UNSOL
            mCM.getImsRegistrationState(this.obtainMessage(EVENT_IMS_STATE_DONE));
        }

        PowerManager pm
            = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,LOG_TAG);

        //Change the system setting
        SystemProperties.set(TelephonyProperties.CURRENT_ACTIVE_PHONE,
                Integer.toString(Phone.PHONE_TYPE_CDMA));

        // This is needed to handle phone process crashes
        String inEcm=SystemProperties.get(TelephonyProperties.PROPERTY_INECM_MODE, "false");
        mIsPhoneInEcmState = inEcm.equals("true");
        if (mIsPhoneInEcmState) {
            // Send a message which will invoke handleExitEmergencyCallbackMode
            mCM.exitEmergencyCallbackMode(obtainMessage(EVENT_EXIT_EMERGENCY_CALLBACK_RESPONSE));
        }

        // get the string that specifies the carrier OTA Sp number
        mCarrierOtaSpNumSchema = SystemProperties.get(
                TelephonyProperties.PROPERTY_OTASP_NUM_SCHEMA,"");

        // Sets operator alpha property by retrieving from build-time system property
        String operatorAlpha = SystemProperties.get("ro.cdma.home.operator.alpha");
        setSystemProperty(PROPERTY_ICC_OPERATOR_ALPHA, operatorAlpha);

        // Sets operator numeric property by retrieving from build-time system property
        String operatorNumeric = SystemProperties.get(PROPERTY_CDMA_HOME_OPERATOR_NUMERIC);
        setSystemProperty(PROPERTY_ICC_OPERATOR_NUMERIC, operatorNumeric);

        // Sets iso country property by retrieving from build-time system property
        setIsoCountryProperty(operatorNumeric);

        // Sets current entry in the telephony carrier table
        updateCurrentCarrierInProvider(operatorNumeric);

        // Notify voicemails.
        //updateVoiceMail();
    }

    @Override
    public void handleMessage(Message msg) {
        switch (msg.what) {
        // handle the select network completion callbacks.
            case EVENT_IMS_STATE_CHANGED:
                mCM.getImsRegistrationState(this.obtainMessage(EVENT_IMS_STATE_DONE));
                break;
            case EVENT_IMS_STATE_DONE:
                AsyncResult ar = (AsyncResult) msg.obj;
                if (ar.exception == null) {
                    int[] responseArray = (int[]) ar.result;
                    Log.d(LOG_TAG, "IMS registration state is: " + responseArray[0]);
                    if (responseArray[0] == 1) { // IMS is registered
                        createImsPhone();
                    } else {
                        destroyImsPhone(); // IMS is unregistered
                    }
                } else {
                    Log.e(LOG_TAG, "IMS State query failed!");
                }
                break;
            default:
                super.handleMessage(msg);
        }
    }

    public String getPhoneName() {
        return "CDMALTEIms";
    }

    public int getMaxConnectionsPerCall() {
        return CdmaImsCallTracker.MAX_CONNECTIONS_PER_CALL;
    }

    public int getMaxConnections() {
        return CdmaImsCallTracker.MAX_CONNECTIONS;
    }

    public Connection
    dial (String dialString) throws CallStateException {
        // Need to make sure dialString gets parsed properly
        String newDialString = PhoneNumberUtils.stripSeparators(dialString);
        Log.d(LOG_TAG, "dialString=" + newDialString);
        newDialString = PhoneNumberUtils.formatDialString(newDialString); // only for cdma
        Log.d(LOG_TAG, "formated dialString=" + newDialString);
        CallDetails calldetails = new CallDetails();
        calldetails.call_domain = CallDetails.RIL_CALL_DOMAIN_CS;
        return mCT.dial(newDialString, calldetails);
    }

    private void createImsPhone() {
        Log.d(LOG_TAG, "Creating RilImsPhone");
        if (imsPhone == null) {
            if (getCallTracker() != null) {
                imsPhone = new RilImsPhone(getContext(), mNotifier, getCallTracker(),
                        mCM);
                CallManager.getInstance().registerPhone(imsPhone);
            } else {
                Log.e(LOG_TAG, "Null call tracker!!! Unable to create RilImsPhone");
            }
        }
    }

    private void destroyImsPhone() {
        if (imsPhone != null) {
            CallManager.getInstance().unregisterPhone(imsPhone);
            imsPhone.dispose();
        }
        imsPhone = null;
    }

    @Override
    public void dispose() {
        mCM.unregisterForImsNetworkStateChanged(this);
        destroyImsPhone();
        super.dispose();
    }

    public DisconnectCause
    disconnectCauseFromCode(int causeCode) {
        /**
         * See 22.001 Annex F.4 for mapping of cause codes
         * to local tones
         */

        switch (causeCode) {
            case CallFailCause.USER_BUSY:
                return DisconnectCause.BUSY;
            case CallFailCause.NO_CIRCUIT_AVAIL:
                return DisconnectCause.CONGESTION;
            case CallFailCause.ACM_LIMIT_EXCEEDED:
                return DisconnectCause.LIMIT_EXCEEDED;
            case CallFailCause.CALL_BARRED:
                return DisconnectCause.CALL_BARRED;
            case CallFailCause.FDN_BLOCKED:
                return DisconnectCause.FDN_BLOCKED;
            case CallFailCause.DIAL_MODIFIED_TO_USSD:
                return DisconnectCause.DIAL_MODIFIED_TO_USSD;
            case CallFailCause.DIAL_MODIFIED_TO_SS:
                return DisconnectCause.DIAL_MODIFIED_TO_SS;
            case CallFailCause.DIAL_MODIFIED_TO_DIAL:
                return DisconnectCause.DIAL_MODIFIED_TO_DIAL;
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
            default:
                int serviceState = getServiceState().getState();
                if (serviceState == ServiceState.STATE_POWER_OFF) {
                    return DisconnectCause.POWER_OFF;
                } else if (serviceState == ServiceState.STATE_OUT_OF_SERVICE
                        || serviceState == ServiceState.STATE_EMERGENCY_ONLY) {
                    return DisconnectCause.OUT_OF_SERVICE;
                } else if (mCdmaSubscriptionSource ==
                               CdmaSubscriptionSourceManager.SUBSCRIPTION_FROM_RUIM
                           && (getUiccApplication() == null ||
                               getUiccApplication().getState() !=
                                    IccCardApplicationStatus.AppState.APPSTATE_READY)) {
                    return DisconnectCause.ICC_ERROR;
                } else if (causeCode==CallFailCause.NORMAL_CLEARING) {
                    return DisconnectCause.NORMAL;
                } else {
                    return DisconnectCause.ERROR_UNSPECIFIED;
                }
        }
    }

}
