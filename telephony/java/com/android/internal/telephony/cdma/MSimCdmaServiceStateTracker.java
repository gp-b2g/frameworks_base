/*
 * Copyright (C) 2008 The Android Open Source Project
 * Copyright (c) 2011 Code Aurora Forum. All rights reserved.
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

import android.telephony.MSimTelephonyManager;
import android.util.Log;

import com.android.internal.telephony.UiccManager.AppFamily;
import com.android.internal.telephony.UiccCardApplication;
import com.android.internal.telephony.Subscription;

/**
 * {@hide}
 */
final class MSimCdmaServiceStateTracker extends CdmaServiceStateTracker {

    public MSimCdmaServiceStateTracker(CDMAPhone phone) {
        super(phone);
    }

    @Override
    protected UiccCardApplication getUiccCardApplication() {
        Subscription subscriptionData = ((MSimCDMAPhone)phone).getSubscriptionInfo();
        if(subscriptionData != null) {
            return  mUiccManager.getUiccCardApplication(subscriptionData.slotId, AppFamily.APP_FAM_3GPP2);
        }
        return null;
    }

    protected void updateCdmaSubscription() {
        cm.getCDMASubscription(obtainMessage(EVENT_POLL_STATE_CDMA_SUBSCRIPTION));
    }

    @Override
    protected String getSystemProperty(String property, String defValue) {
        return MSimTelephonyManager.getTelephonyProperty(property, ((MSimCDMAPhone)phone).getSubscription(), defValue);
    }

    @Override
    protected void log(String s) {
        Log.d(LOG_TAG, "[CdmaSST] [SUB : " + ((MSimCDMAPhone)phone).getSubscription() + "] " + s);
    }

    @Override
    protected void loge(String s) {
        Log.e(LOG_TAG, "[CdmaSST] [SUB : " + ((MSimCDMAPhone)phone).getSubscription() + "] " + s);
    }

    //@Override
    //private void sloge(String s) {
    //    Log.e(LOG_TAG, "[CdmaSST] [SUB : " + ((MSimCDMAPhone)phone).getSubscription() + "] " + s);
    //}
}
