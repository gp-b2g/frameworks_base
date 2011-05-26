/*
 * Copyright (C) 2011 The Android Open Source Project
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

import com.android.internal.telephony.cdma.CDMALTEPhone;
import com.android.internal.telephony.cdma.CDMAPhone;
import com.android.internal.telephony.gsm.GSMPhone;

import android.util.Log;

/* This class is responsible for keeping all knowledge about
 * ICCs in the system. It is also used as API to get appropriate
 * applications to pass them to phone and service trackers.
 */
public class UiccManager {
    private final static String LOG_TAG = "RIL_UiccManager";
    public enum AppFamily {
        APP_FAM_3GPP,
        APP_FAM_3GPP2;
    }

    private static UiccManager mInstance;

    private PhoneBase mCurrentPhone;
    private AppFamily mCurrentCardType;
    private IccCard mIccCard;

    public static UiccManager getInstance(PhoneBase phone) {
        if (mInstance == null) {
            mInstance = new UiccManager(phone);
        } else {
            mInstance.setNewPhone(phone);
        }
        return mInstance;
    }

    public static UiccManager getInstance() {
        if (mInstance == null) {
            return null;
        } else {
            return mInstance;
        }
    }

    private UiccManager(PhoneBase phone) {
        Log.d(LOG_TAG, "Creating UiccManager");
        setNewPhone(phone);
    }

    private void setNewPhone(PhoneBase phone) {
        mCurrentPhone = phone;
        if (phone instanceof GSMPhone) {
            Log.d(LOG_TAG, "New phone is GSMPhone");
            updateCurrentCard(AppFamily.APP_FAM_3GPP);
        } else if (phone instanceof CDMALTEPhone){
            Log.d(LOG_TAG, "New phone type is CDMALTEPhone");
            updateCurrentCard(AppFamily.APP_FAM_3GPP);
        } else if (phone instanceof CDMAPhone){
            Log.d(LOG_TAG, "New phone type is CDMAPhone");
            updateCurrentCard(AppFamily.APP_FAM_3GPP2);
        } else {
            Log.e(LOG_TAG, "Unhandled phone type. Critical error!");
        }
    }
    private void updateCurrentCard(AppFamily cardType) {
        if (mCurrentCardType == cardType && mIccCard != null) {
            return;
        }

        if (mIccCard != null) {
            mIccCard.dispose();
            mIccCard = null;
        }

        mCurrentCardType = cardType;

        if (cardType == AppFamily.APP_FAM_3GPP) {
            mIccCard = new IccCard(mCurrentPhone, mCurrentPhone.getPhoneName(), true, true);
        } else if (cardType == AppFamily.APP_FAM_3GPP2){
            mIccCard = new IccCard(mCurrentPhone, mCurrentPhone.getPhoneName(), false, true);
        }
    }

    public IccCard getIccCard() {
        return mIccCard;
    }
}