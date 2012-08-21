/*
 * Copyright (c) 2010-2012, Code Aurora Forum. All rights reserved.
 * Copyright (C) 2011-2012 Code Aurora Forum. All rights reserved.
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

import static android.Manifest.permission.READ_PHONE_STATE;
import android.telephony.MSimTelephonyManager;
import android.app.ActivityManagerNative;
import android.content.Context;
import android.content.Intent;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.os.Registrant;
import android.os.RegistrantList;
import android.os.SystemProperties;
import android.util.Log;
import android.telephony.TelephonyManager;

import com.android.internal.telephony.gsm.SIMRecords;
import com.android.internal.telephony.IccCardApplicationStatus.AppState;
import com.android.internal.telephony.IccCardApplicationStatus.PersoSubState;
import com.android.internal.telephony.IccCardStatus.CardState;
import com.android.internal.telephony.IccCardStatus.PinState;
import com.android.internal.telephony.cdma.CdmaSubscriptionSourceManager;
import com.android.internal.telephony.MSimConstants;
import com.android.internal.telephony.UiccManager.AppFamily;

public class MSimIccCardProxy extends IccCardProxy {
    private static final String LOG_TAG = "RIL_MSimIccCardProxy";
    private static final boolean DBG = true;

    private static final int EVENT_ICC_RECORD_EVENTS = 500;
    private static final int EVENT_SUBSCRIPTION_ACTIVATED = 501;
    private static final int EVENT_SUBSCRIPTION_DEACTIVATED = 502;

    private int mCardIndex;
    private Subscription mSubscriptionData = null;

    public MSimIccCardProxy(Context context, CommandsInterface ci, int cardIndex) {
        super(context, ci);

        mCardIndex = cardIndex;

        //TODO: Card index and subscription are same???
        SubscriptionManager subMgr = SubscriptionManager.getInstance();
        subMgr.registerForSubscriptionActivated(mCardIndex, this, EVENT_SUBSCRIPTION_ACTIVATED, null);
        subMgr.registerForSubscriptionDeactivated(mCardIndex, this, EVENT_SUBSCRIPTION_DEACTIVATED, null);

        resetProperties();
    }

    @Override
    public void dispose() {
        super.dispose();

        resetProperties();
    }

    @Override
    public void handleMessage(Message msg) {
        switch (msg.what) {
            case EVENT_SUBSCRIPTION_ACTIVATED:
                log("EVENT_SUBSCRIPTION_ACTIVATED");
                onSubscriptionActivated();
                break;

            case EVENT_SUBSCRIPTION_DEACTIVATED:
                log("EVENT_SUBSCRIPTION_DEACTIVATED");
                onSubscriptionDeactivated();
                break;

            case EVENT_RECORDS_LOADED:
                if ((mCurrentAppType == AppFamily.APP_FAM_3GPP) && (mIccRecords != null)) {
                    String operator = ((SIMRecords)mIccRecords).getOperatorNumeric();
                    int sub = mCardIndex;
                    if (operator != null) {
                      String property = sub == 0 ? TelephonyProperties.PROPERTY_ICC_OPERATOR_NUMERIC : TelephonyProperties.PROPERTY_ICC2_OPERATOR_NUMERIC;
                      SystemProperties.set(property, operator);
                    } else {
                        Log.e(LOG_TAG, "EVENT_RECORDS_LOADED Operator name is null");
                    }
                    String countryCode = ((SIMRecords)mIccRecords).getCountryCode();
                    if (countryCode != null) {
                        SystemProperties.set(
                                sub == 0 ? TelephonyProperties.PROPERTY_ICC_OPERATOR_ISO_COUNTRY
                                        : TelephonyProperties.PROPERTY_ICC2_OPERATOR_ISO_COUNTRY,
                                MccTable.countryCodeForMcc(Integer.parseInt(countryCode)));
                    } else {
                        Log.e(LOG_TAG, "EVENT_RECORDS_LOADED Country code is null");
                    }
                }
                broadcastIccStateChangedIntent(INTENT_VALUE_ICC_LOADED, null);
                break;

            case EVENT_ICC_RECORD_EVENTS:
                if ((mCurrentAppType == AppFamily.APP_FAM_3GPP) && (mIccRecords != null)) {
                    int sub = mCardIndex;
                    AsyncResult ar = (AsyncResult)msg.obj;
                    int eventCode = (Integer) ar.result;
                    if (eventCode == SIMRecords.EVENT_SPN) {
                        SystemProperties.set(
                                sub == 0 ? TelephonyProperties.PROPERTY_ICC_OPERATOR_ALPHA
                                        : TelephonyProperties.PROPERTY_ICC2_OPERATOR_ALPHA,
                                mIccRecords.spn);
                    }
                }
                break;

            default:
                super.handleMessage(msg);
        }
    }

    private void onSubscriptionActivated() {
        SubscriptionManager subMgr = SubscriptionManager.getInstance();
        mSubscriptionData = subMgr.getCurrentSubscription(mCardIndex);

        resetProperties();
        updateIccAvailability();
        updateStateProperty();
    }

    private void onSubscriptionDeactivated() {
        mSubscriptionData = null;
        resetProperties();
        updateIccAvailability();
        updateStateProperty();
    }


    @Override
    void updateIccAvailability() {
        UiccCard newCard = mUiccManager.getUiccCard(mCardIndex);
        CardState state = CardState.CARDSTATE_ABSENT;
        UiccCardApplication newApp = null;
        IccRecords newRecords = null;
        if (newCard != null) {
            state = newCard.getCardState();
            Log.d(LOG_TAG,"Card State = " + state);
            newApp = newCard.getApplication(mCurrentAppType);
            if (newApp != null) {
                newRecords = newApp.getIccRecords();
            }
        } else {
            Log.d(LOG_TAG,"No card available");
        }

        if (mIccRecords != newRecords || mUiccApplication != newApp || mUiccCard != newCard) {
            log("Icc changed. Reregestering.");
            unregisterUiccCardEvents();
            mUiccCard = null;
            mUiccApplication = null;
            mIccRecords = null;
            mUiccCard = newCard;
            mUiccApplication = newApp;

            if (newRecords != null) {

                mIccRecords = newRecords;
                registerUiccCardEvents();
                updateActiveRecord();
            }
        }

        updateExternalState();
    }

    void resetProperties() {
        if (mSubscriptionData != null
                && mCurrentAppType == AppFamily.APP_FAM_3GPP) {
            // sub0
            if (0 == mSubscriptionData.subId) {
                SystemProperties.set(TelephonyProperties.PROPERTY_ICC_OPERATOR_NUMERIC, "");
            }
            // sub1
            else if (1 == mSubscriptionData.subId) {
                SystemProperties.set(TelephonyProperties.PROPERTY_ICC2_OPERATOR_NUMERIC, "");
            }
            SystemProperties
                    .set(mSubscriptionData.subId == 0 ? TelephonyProperties.PROPERTY_ICC_OPERATOR_ISO_COUNTRY
                            : TelephonyProperties.PROPERTY_ICC2_OPERATOR_ISO_COUNTRY, "");
            SystemProperties.set(
                    mSubscriptionData.subId == 0 ? TelephonyProperties.PROPERTY_ICC_OPERATOR_ALPHA
                            : TelephonyProperties.PROPERTY_ICC2_OPERATOR_ALPHA, "");
         }
    }

    private void updateStateProperty() {
        if (mSubscriptionData != null) {
            SystemProperties.set
                    (mSubscriptionData.subId == 0 ? TelephonyProperties.PROPERTY_SIM_STATE
                            : TelephonyProperties.PROPERTY_SIM2_STATE, getState().toString());
        }
    }

    @Override
    protected void registerUiccCardEvents() {
        super.registerUiccCardEvents();
        if (mIccRecords != null) mIccRecords.registerForRecordsEvents(this, EVENT_ICC_RECORD_EVENTS, null);
    }

    @Override
    protected void unregisterUiccCardEvents() {
        super.unregisterUiccCardEvents();
        if (mIccRecords != null) mIccRecords.unregisterForRecordsEvents(this);
    }

    @Override
    public void broadcastIccStateChangedIntent(String value, String reason) {
        int subId = mCardIndex;
        if (mQuietMode) {
            log("QuietMode: NOT Broadcasting intent ACTION_SIM_STATE_CHANGED " +  value
                    + " reason " + reason);
            return;
        }

        Intent intent = new Intent(TelephonyIntents.ACTION_SIM_STATE_CHANGED);
        //intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
        intent.putExtra(Phone.PHONE_NAME_KEY, "Phone");
        intent.putExtra(INTENT_KEY_ICC_STATE, value);
        intent.putExtra(INTENT_KEY_LOCKED_REASON, reason);

        intent.putExtra(MSimConstants.SUBSCRIPTION_KEY, subId);
        log("Broadcasting intent ACTION_SIM_STATE_CHANGED " +  value
            + " reason " + reason + " for subscription : " + subId);
        ActivityManagerNative.broadcastStickyIntent(intent, READ_PHONE_STATE);
    }

    @Override
    protected void setExternalState(State newState) {

        if (newState == mExternalState) {
            return;
        }
        mExternalState = newState;
        SystemProperties.set(mCardIndex == 0 ? TelephonyProperties.PROPERTY_SIM_STATE
                : TelephonyProperties.PROPERTY_SIM2_STATE, getState().toString());
        broadcastIccStateChangedIntent(mExternalState.getIntentString(), null);
        if (mSubscriptionData != null) {
            SystemProperties.set(mCardIndex == 0 ? TelephonyProperties.PROPERTY_SIM_STATE
                    : TelephonyProperties.PROPERTY_SIM2_STATE, getState().toString());
        }
        // If Quiet mode has not been evaluated yet
        // don't broadcast anything
        if (mInitialized) {
            broadcastCurrentState();
        }
    }

    @Override
    protected void log(String msg) {
        if (DBG) Log.d(LOG_TAG, msg);
    }

    @Override
    protected void loge(String msg) {
        Log.e(LOG_TAG, msg);
    }
}
