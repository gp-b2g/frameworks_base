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

import android.app.NotificationManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.SQLException;
import android.os.Handler;
import android.os.Message;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.provider.Telephony;
import android.telephony.MSimTelephonyManager;
import android.text.TextUtils;
import android.util.Log;
import android.net.Uri;

import com.android.internal.telephony.Subscription;
import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneNotifier;
import com.android.internal.telephony.TelephonyProperties;
import com.android.internal.telephony.SubscriptionManager;
import com.android.internal.telephony.UiccCardApplication;
import com.android.internal.telephony.UiccManager.AppFamily;

import static com.android.internal.telephony.MSimConstants.EVENT_SUBSCRIPTION_ACTIVATED;
import static com.android.internal.telephony.MSimConstants.EVENT_SUBSCRIPTION_DEACTIVATED;

public class MSimGSMPhone extends GSMPhone {
    //protected final static String LOG_TAG = "MSimGSMPhone";

    // Holds the subscription information
    Subscription mSubscriptionData = null;
    int mSubscription = 0;
    NotificationManager mNotificationManager;

    // Call Forward icons. Values have to be same as mentioned in
    // NotificationMgr.java
    private static final int CALL_FORWARD_NOTIFICATION = 6;
    private static final int CALL_FORWARD_NOTIFICATION_SUB2 = 21;

    public
    MSimGSMPhone (Context context, CommandsInterface ci, PhoneNotifier notifier, int subscription) {
        this(context, ci, notifier, false, subscription);
    }

    public
    MSimGSMPhone (Context context, CommandsInterface ci,
            PhoneNotifier notifier, boolean unitTestMode, int subscription) {
        super(context, ci, notifier, unitTestMode);

        mSubscription = subscription;

        Log.d(LOG_TAG, "MSimGSMPhone: constructor: sub = " + mSubscription);

        mNotificationManager = (NotificationManager) mContext
                .getSystemService(Context.NOTIFICATION_SERVICE);

        mVmNumGsmKey = mVmNumGsmKey + mSubscription;
        mVmCountKey = mVmCountKey + mSubscription;
        mVmId = mVmId + mSubscription;

        mDataConnectionTracker = new MSimGsmDataConnectionTracker (this);

        SubscriptionManager subMgr = SubscriptionManager.getInstance();
        subMgr.registerForSubscriptionActivated(mSubscription,
                this, EVENT_SUBSCRIPTION_ACTIVATED, null);
        subMgr.registerForSubscriptionDeactivated(mSubscription,
                this, EVENT_SUBSCRIPTION_DEACTIVATED, null);

        setProperties();
    }

    @Override
    public void dispose() {
        super.dispose();

        SubscriptionManager subMgr = SubscriptionManager.getInstance();
        subMgr.unregisterForSubscriptionActivated(mSubscription, this);
        subMgr.unregisterForSubscriptionDeactivated(mSubscription, this);
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

            default:
                super.handleMessage(msg);
        }
    }

    private void onSubscriptionActivated() {
        SubscriptionManager subMgr = SubscriptionManager.getInstance();
        mSubscriptionData = subMgr.getCurrentSubscription(mSubscription);

        log("SUBSCRIPTION ACTIVATED : slotId : " + mSubscriptionData.slotId
                + "appid : " + mSubscriptionData.m3gppIndex
                + "subId : " + mSubscriptionData.subId
                + "subStatus : " + mSubscriptionData.subStatus);

        // Make sure properties are set for proper subscription.
        setProperties();

        updateIccAvailability();
        mSST.updateIccAvailability();
        ((MSimGsmDataConnectionTracker)mDataConnectionTracker).updateRecords();

        // read the subscription specifics now
        mCM.getIMEI(obtainMessage(EVENT_GET_IMEI_DONE));
        mCM.getBasebandVersion(obtainMessage(EVENT_GET_BASEBAND_VERSION_DONE));
        mCM.getIMEISV(obtainMessage(EVENT_GET_IMEISV_DONE));

    }

    private void onSubscriptionDeactivated() {
        log("SUBSCRIPTION DEACTIVATED");
        mSubscriptionData = null;
        resetSubSpecifics();
    }

    public void resetSubSpecifics() {
        mImei = null;
        mImeiSv = null;
        setVoiceMessageCount(0);
        if (getCallForwardingIndicator()) {
            int notificationId = (mSubscription == 0) ? CALL_FORWARD_NOTIFICATION :
                    CALL_FORWARD_NOTIFICATION_SUB2;
            mNotificationManager.cancel(notificationId);
        }

    }

    //Gets Subscription information in the Phone Object
    public Subscription getSubscriptionInfo() {
        return mSubscriptionData;
    }

    /**
     * Returns the subscription id.
     */
    @Override
    public int getSubscription() {
        return mSubscription;
    }

    /**
     * Initialize the MultiSim Specifics here.
     * Should be called from the base class constructor
     */
    @Override
    protected void initSubscriptionSpecifics() {
        mSST = new MSimGsmServiceStateTracker(this);
    }

    // Set the properties per subscription
    @Override
    protected void setProperties() {
        //Change the system property
        MSimTelephonyManager.setTelephonyProperty(TelephonyProperties.CURRENT_ACTIVE_PHONE,
                mSubscription,
                new Integer(Phone.PHONE_TYPE_GSM).toString());
    }

    @Override
    protected UiccCardApplication getUiccCardApplication() {
        if(mSubscriptionData != null) {
            return  mUiccManager.getUiccCardApplication(mSubscriptionData.slotId,
                    AppFamily.APP_FAM_3GPP);
        }
        return null;
    }

    @Override
    public void setSystemProperty(String property, String value) {
        if(getUnitTestMode()) {
            return;
        }
        MSimTelephonyManager.setTelephonyProperty(property, mSubscription, value);
    }

    public String getSystemProperty(String property, String defValue) {
        if(getUnitTestMode()) {
            return null;
        }
        return MSimTelephonyManager.getTelephonyProperty(property, mSubscription, defValue);
    }

    public void updateDataConnectionTracker() {
        ((MSimGsmDataConnectionTracker)mDataConnectionTracker).update();
    }

    public void setInternalDataEnabled(boolean enable, Message onCompleteMsg) {
        ((MSimGsmDataConnectionTracker)mDataConnectionTracker)
                .setInternalDataEnabled(enable, onCompleteMsg);
    }

    /**
     * @return operator numeric.
     */
    public String getOperatorNumeric() {
        String operatorNumeric = null;
        if (mIccRecords != null) {
            operatorNumeric = mIccRecords.getOperatorNumeric();
        }
        return operatorNumeric;
    }

    /**
     * Sets the "current" field in the telephony provider according to the operator numeric.
     *
     * @return true for success; false otherwise.
     */
    public boolean updateCurrentCarrierInProvider() {
        int currentDds = 0;
        String operatorNumeric = getOperatorNumeric();

        try {
            currentDds = Settings.System.getInt(mContext.getContentResolver(),
                    Settings.System.MULTI_SIM_DATA_CALL_SUBSCRIPTION);
        } catch (Settings.SettingNotFoundException snfe) {
            Log.e(LOG_TAG, "Exception Reading Dual Sim Data Subscription Value.", snfe);
        }

        Log.d(LOG_TAG, "updateCurrentCarrierInProvider: mSubscription = " + getSubscription()
                + " currentDds = " + currentDds + " operatorNumeric = " + operatorNumeric);

        if (!TextUtils.isEmpty(operatorNumeric) && (getSubscription() == currentDds)) {
            try {
                Uri uri = Uri.withAppendedPath(Telephony.Carriers.CONTENT_URI, "current");
                ContentValues map = new ContentValues();
                map.put(Telephony.Carriers.NUMERIC, operatorNumeric);
                mContext.getContentResolver().insert(uri, map);
                return true;
            } catch (SQLException e) {
                Log.e(LOG_TAG, "Can't store current operator", e);
            }
        }
        return false;
    }

    public void registerForAllDataDisconnected(Handler h, int what, Object obj) {
        ((MSimGsmDataConnectionTracker)mDataConnectionTracker)
                .registerForAllDataDisconnected(h, what, obj);
    }

    public void unregisterForAllDataDisconnected(Handler h) {
        ((MSimGsmDataConnectionTracker)mDataConnectionTracker).unregisterForAllDataDisconnected(h);
    }

    @Override
    protected String getVmSimImsi() {
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(getContext());
        return sp.getString((VM_SIM_IMSI + mSubscription), null);
    }

    @Override
    protected void setVmSimImsi(String imsi) {
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(getContext());
        SharedPreferences.Editor editor = sp.edit();
        editor.putString((VM_SIM_IMSI + mSubscription), imsi);
        editor.apply();
    }

    /**
     * This method stores the CF_ENABLED flag in preferences
     * @param enabled
     */
    @Override
    /*package*/ void setCallForwardingPreference(boolean enabled) {
        if (LOCAL_DEBUG) Log.d(LOG_TAG, "Set callforwarding info to perferences");
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(mContext);
        SharedPreferences.Editor edit = sp.edit();
        edit.putBoolean((CF_ENABLED + mSubscription), enabled);
        edit.commit();

        // Using the same method as VoiceMail to be able to track when the sim card is changed.
        setVmSimImsi(getSubscriberId());
    }

    @Override
    protected boolean getCallForwardingPreference() {
        if (LOCAL_DEBUG) Log.d(LOG_TAG, "Get callforwarding info from perferences");

        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(mContext);
        boolean cf = sp.getBoolean((CF_ENABLED + mSubscription), false);
        return cf;
    }
}
