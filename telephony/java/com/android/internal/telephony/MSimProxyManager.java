/*
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

package com.android.internal.telephony;

import android.content.Context;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.telephony.ServiceState;

public class MSimProxyManager {
    static final String LOG_TAG = "PROXY";

    //***** Class Variables
    private static MSimProxyManager sMSimProxyManager;

    private Phone[] mProxyPhones;

    private UiccManager mUiccManager;

    private CommandsInterface[] mCi;

    private Context mContext;

    //MSimIccPhoneBookInterfaceManager; Proxy to use proper IccPhoneBookInterfaceManagerProxy object
    private MSimIccPhoneBookInterfaceManagerProxy mMSimIccPhoneBookInterfaceManagerProxy;

    //MSimPhoneSubInfoProxy to use proper PhoneSubInfoProxy object
    private MSimPhoneSubInfoProxy mMSimPhoneSubInfoProxy;

    //MSimIccSmsInterfaceManager to use proper IccSmsInterfaceManager object
    private MSimIccSmsInterfaceManager mMSimIccSmsInterfaceManager;

    private CardSubscriptionManager mCardSubscriptionManager;

    private SubscriptionManager mSubscriptionManager;

    //***** Class Methods
    public static MSimProxyManager getInstance(Context context, Phone[] phoneProxy,
            UiccManager uiccMgr, CommandsInterface[] ci) {
        if (sMSimProxyManager == null) {
            sMSimProxyManager = new MSimProxyManager(context, phoneProxy, uiccMgr, ci);
        }
        return sMSimProxyManager;
    }

    static public MSimProxyManager getInstance() {
        return sMSimProxyManager;
    }

    private MSimProxyManager(Context context, Phone[] phoneProxy, UiccManager uiccManager,
            CommandsInterface[] ci) {
        logd("Constructor - Enter");

        mContext = context;
        mProxyPhones = phoneProxy;
        mUiccManager = uiccManager;
        mCi = ci;

        mMSimIccPhoneBookInterfaceManagerProxy
                = new MSimIccPhoneBookInterfaceManagerProxy(mProxyPhones);
        mMSimPhoneSubInfoProxy = new MSimPhoneSubInfoProxy(mProxyPhones);
        mMSimIccSmsInterfaceManager = new MSimIccSmsInterfaceManager(mProxyPhones);
        mCardSubscriptionManager = CardSubscriptionManager.getInstance(context, uiccManager, ci);
        mSubscriptionManager = SubscriptionManager.getInstance(context, uiccManager, ci);

        logd("Constructor - Exit");
    }

    public void updateDataConnectionTracker(int sub) {
        ((MSimPhoneProxy) mProxyPhones[sub]).updateDataConnectionTracker();
    }

    public void enableDataConnectivity(int sub) {
        ((MSimPhoneProxy) mProxyPhones[sub]).setInternalDataEnabled(true);
    }

    public void disableDataConnectivity(int sub,
            Message dataCleanedUpMsg) {
        ((MSimPhoneProxy) mProxyPhones[sub]).setInternalDataEnabled(false, dataCleanedUpMsg);
    }

    public void updateCurrentCarrierInProvider(int sub) {
        ((MSimPhoneProxy) mProxyPhones[sub]).updateCurrentCarrierInProvider();
    }

    public void checkAndUpdatePhoneObject(Subscription userSub) {
        int subId = userSub.subId;
        if ((userSub.appType.equals("SIM")
                || userSub.appType.equals("USIM"))
                && (!mProxyPhones[subId].getPhoneName().equals("GSM"))) {
            logd("gets New GSM phone" );
            ((PhoneProxy) mProxyPhones[subId]).updatePhoneObject(ServiceState.RADIO_TECHNOLOGY_GSM);
        } else if ((userSub.appType.equals("RUIM")
                || userSub.appType.equals("CSIM"))
                && (!mProxyPhones[subId].getPhoneName().equals("CDMA"))) {
            logd("gets New CDMA phone" );
            ((PhoneProxy) mProxyPhones[subId]).updatePhoneObject(ServiceState.RADIO_TECHNOLOGY_1xRTT);
        }
    }

    private void logd(String string) {
        Log.d(LOG_TAG, string);
    }

    public void registerForAllDataDisconnected(int sub, Handler h, int what, Object obj) {
        ((MSimPhoneProxy) mProxyPhones[sub]).registerForAllDataDisconnected(h, what, obj);
    }
}
