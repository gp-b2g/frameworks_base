/*
 * Copyright (C) 2008 The Android Open Source Project
 * Copyright (c) 2012 Code Aurora Forum. All rights reserved.
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

import android.app.ActivityManagerNative;
import android.content.Intent;
import android.os.Handler;
import android.os.Message;
import android.util.Log;

import android.telephony.ServiceState;
import com.android.internal.telephony.gsm.MSimGSMPhone;
import com.android.internal.telephony.cdma.MSimCDMAPhone;


public class MSimPhoneProxy extends PhoneProxy {

    private int mSubscription = 0;

    //***** Constructors
    public MSimPhoneProxy(Phone phone) {
        super(phone);

        mSubscription = phone.getSubscription();
    }

    protected void init() {
        mIccCardProxy = new MSimIccCardProxy(mActivePhone.getContext(),
                mCommandsInterface, mActivePhone.getSubscription());
    }

    @Override
    protected void createNewPhone(int newVoiceRadioTech) {
        if (ServiceState.isCdma(newVoiceRadioTech)) {
            Log.d(LOG_TAG, "MSimPhoneProxy: deleteAndCreatePhone: Creating MSimCdmaPhone");
            mActivePhone = MSimPhoneFactory.getMSimCdmaPhone(mSubscription);
        } else if (ServiceState.isGsm(newVoiceRadioTech)) {
            Log.d(LOG_TAG, "MSimPhoneProxy: deleteAndCreatePhone: Creating MSimGsmPhone");
            mActivePhone = MSimPhoneFactory.getMSimGsmPhone(mSubscription);
        }
    }

    @Override
    protected void sendBroadcastStickyIntent() {
        // Send an Intent to the PhoneApp that we had a radio technology change
        Intent intent = new Intent(TelephonyIntents.ACTION_RADIO_TECHNOLOGY_CHANGED);
        intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
        intent.putExtra(Phone.PHONE_NAME_KEY, mActivePhone.getPhoneName());
        intent.putExtra(MSimConstants.SUBSCRIPTION_KEY, mSubscription);
        ActivityManagerNative.broadcastStickyIntent(intent, null);
    }

    public PhoneSubInfoProxy getPhoneSubInfoProxy(){
        return mPhoneSubInfoProxy;
    }

    public IccPhoneBookInterfaceManagerProxy getIccPhoneBookInterfaceManagerProxy() {
        return mIccPhoneBookInterfaceManagerProxy;
    }

    public IccSmsInterfaceManager getIccSmsInterfaceManager() {
        return mIccSmsInterfaceManager;
    }

    public IccFileHandler getIccFileHandler() {
        return ((MSimGSMPhone)mActivePhone).getIccFileHandler();
    }

    public boolean updateCurrentCarrierInProvider() {
        if (mActivePhone instanceof MSimCDMAPhone) {
            return ((MSimCDMAPhone)mActivePhone).updateCurrentCarrierInProvider();
        } else if (mActivePhone instanceof MSimGSMPhone) {
            return ((MSimGSMPhone)mActivePhone).updateCurrentCarrierInProvider();
        } else {
           logd("Phone object is not MultiSim. This should not hit!!!!");
           return false;
        }
    }

    public void updateDataConnectionTracker() {
        logd("Updating Data Connection Tracker");
        if (mActivePhone instanceof MSimCDMAPhone) {
            ((MSimCDMAPhone)mActivePhone).updateDataConnectionTracker();
        } else if (mActivePhone instanceof MSimGSMPhone) {
            ((MSimGSMPhone)mActivePhone).updateDataConnectionTracker();
        } else {
           logd("Phone object is not MultiSim. This should not hit!!!!");
        }
    }

    public void setInternalDataEnabled(boolean enable) {
        setInternalDataEnabled(enable, null);
    }

    public void setInternalDataEnabled(boolean enable, Message onCompleteMsg) {
        if (mActivePhone instanceof MSimCDMAPhone) {
            ((MSimCDMAPhone)mActivePhone).setInternalDataEnabled(enable, onCompleteMsg);
        } else if (mActivePhone instanceof MSimGSMPhone) {
            ((MSimGSMPhone)mActivePhone).setInternalDataEnabled(enable, onCompleteMsg);
        } else {
           logd("Phone object is not MultiSim. This should not hit!!!!");
        }
    }

    public void registerForAllDataDisconnected(Handler h, int what, Object obj) {
        if (mActivePhone instanceof MSimCDMAPhone) {
            ((MSimCDMAPhone)mActivePhone).registerForAllDataDisconnected(h, what, obj);
        } else if (mActivePhone instanceof MSimGSMPhone) {
            ((MSimGSMPhone)mActivePhone).registerForAllDataDisconnected(h, what, obj);
        } else {
           logd("Phone object is not MultiSim. This should not hit!!!!");
        }
    }

    public void unregisterForAllDataDisconnected(Handler h) {
        if (mActivePhone instanceof MSimCDMAPhone) {
            ((MSimCDMAPhone)mActivePhone).unregisterForAllDataDisconnected(h);
        } else if (mActivePhone instanceof MSimGSMPhone) {
            ((MSimGSMPhone)mActivePhone).unregisterForAllDataDisconnected(h);
        } else {
           logd("Phone object is not MultiSim. This should not hit!!!!");
        }
    }

    public int getSubscription() {
        return mSubscription;
    }

    private void logv(String msg) {
        Log.v(LOG_TAG, "[PhoneProxy(" + mSubscription + ")] " + msg);
    }

    private void logd(String msg) {
        Log.d(LOG_TAG, "[PhoneProxy(" + mSubscription + ")] " + msg);
    }

    private void logw(String msg) {
        Log.w(LOG_TAG, "[PhoneProxy(" + mSubscription + ")] " + msg);
    }

    private void loge(String msg) {
        Log.e(LOG_TAG, "[PhoneProxy(" + mSubscription + ")] " + msg);
    }

    private void logi(String msg) {
        Log.i(LOG_TAG, "[PhoneProxy(" + mSubscription + ")] " + msg);
    }
}
