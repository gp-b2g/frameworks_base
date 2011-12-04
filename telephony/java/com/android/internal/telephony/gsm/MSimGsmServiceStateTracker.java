/*
 * Copyright (C) 2006 The Android Open Source Project
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

package com.android.internal.telephony.gsm;

import android.content.Intent;
import android.provider.Telephony.Intents;
import android.telephony.MSimTelephonyManager;
import android.util.Log;

import com.android.internal.telephony.Subscription;
import com.android.internal.telephony.UiccCardApplication;
import com.android.internal.telephony.UiccManager.AppFamily;

/**
 * {@hide}
 */
final class MSimGsmServiceStateTracker extends GsmServiceStateTracker {

    public MSimGsmServiceStateTracker(GSMPhone phone) {
        super(phone);
    }

    public void updateRecords() {
        updateIccAvailability();
    }

    @Override
    protected UiccCardApplication getUiccCardApplication() {
        Subscription subscriptionData = ((MSimGSMPhone)phone).getSubscriptionInfo();
        if(subscriptionData != null) {
            return  mUiccManager.getUiccCardApplication(subscriptionData.slotId,
                    AppFamily.APP_FAM_3GPP);
        }
        return null;
    }

    @Override
    public String getSystemProperty(String property, String defValue) {
        return MSimTelephonyManager.getTelephonyProperty(property, ((MSimGSMPhone)phone).getSubscription(), defValue);
    }

    @Override
    protected void log(String s) {
        Log.d(LOG_TAG, "[GsmSST] [SUB : " + ((MSimGSMPhone)phone).getSubscription() + "] " + s);
    }

    @Override
    protected void loge(String s) {
        Log.e(LOG_TAG, "[GsmSST] [SUB : " + ((MSimGSMPhone)phone).getSubscription() + "] " + s);
    }

}
