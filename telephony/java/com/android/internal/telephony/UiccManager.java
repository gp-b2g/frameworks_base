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

import com.android.internal.telephony.IccCardStatus.CardState;
import com.android.internal.telephony.cdma.CDMALTEPhone;
import com.android.internal.telephony.cdma.CDMAPhone;
import com.android.internal.telephony.gsm.GSMPhone;

import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.os.Registrant;
import android.os.RegistrantList;
import android.util.Log;

/* This class is responsible for keeping all knowledge about
 * ICCs in the system. It is also used as API to get appropriate
 * applications to pass them to phone and service trackers.
 */
public class UiccManager extends Handler {
    private final static String LOG_TAG = "RIL_UiccManager";
    public enum AppFamily {
        APP_FAM_3GPP,
        APP_FAM_3GPP2;
    }
    private static final int EVENT_ICC_STATUS_CHANGED = 1;
    private static final int EVENT_GET_ICC_STATUS_DONE = 2;
    private static UiccManager mInstance;

    private PhoneBase mCurrentPhone;
    private CommandsInterface mCi;
    private IccCard mIccCard;

    private RegistrantList mIccChangedRegistrants = new RegistrantList();

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
        mCi = mCurrentPhone.mCM;
        mCi.registerForIccStatusChanged(this, EVENT_ICC_STATUS_CHANGED, null);
        // TODO remove this once modem correctly notifies the unsols
        mCi.registerForOn(this, EVENT_ICC_STATUS_CHANGED, null);
    }

    @Override
    public void handleMessage (Message msg) {
        switch (msg.what) {
            case EVENT_ICC_STATUS_CHANGED:
                Log.d(LOG_TAG, "Received EVENT_ICC_STATUS_CHANGED, calling getIccCardStatus");
                mCi.getIccCardStatus(obtainMessage(EVENT_GET_ICC_STATUS_DONE));
                break;
            case EVENT_GET_ICC_STATUS_DONE:
                Log.d(LOG_TAG, "Received EVENT_GET_ICC_STATUS_DONE");
                AsyncResult ar = (AsyncResult)msg.obj;
                onGetIccCardStatusDone(ar);
                break;
            default:
                Log.e(LOG_TAG, " Unknown Event " + msg.what);
        }
    }

    private synchronized void onGetIccCardStatusDone(AsyncResult ar) {
        if (ar.exception != null) {
            Log.e(LOG_TAG,"Error getting ICC status. "
                    + "RIL_REQUEST_GET_ICC_STATUS should "
                    + "never return an error", ar.exception);
            return;
        }

        IccCardStatus status = (IccCardStatus)ar.result;

        //Update already existing card
        if (mIccCard != null && status.getCardState() == CardState.CARDSTATE_PRESENT) {
            mIccCard.update(mCurrentPhone, status);
        }

        //Dispose of removed card
        if (mIccCard != null && status.getCardState() != CardState.CARDSTATE_PRESENT) {
            mIccCard.dispose();
            mIccCard = null;
        }

        //Create new card
        if (mIccCard == null && status.getCardState() == CardState.CARDSTATE_PRESENT) {
            mIccCard = new IccCard(mCurrentPhone, status, mCurrentPhone.getPhoneName(), true);
        }

        Log.d(LOG_TAG, "Notifying IccChangedRegistrants");
        mIccChangedRegistrants.notifyRegistrants();
    }

    private void setNewPhone(PhoneBase phone) {
        Log.d(LOG_TAG, "setNewPhone");
        if (mCurrentPhone != phone) {
            if (mIccCard != null) {
                // Refresh card if phone changed
                // TODO: Remove once card is simplified
                Log.d(LOG_TAG, "Disposing card since phone object changed");
                mIccCard.dispose();
                mIccCard = null;
            }
            sendMessage(obtainMessage(EVENT_ICC_STATUS_CHANGED));
        }
        mCurrentPhone = phone;
    }

    public IccCard getIccCard() {
        return mIccCard;
    }
    //Notifies when card status changes
    public void registerForIccChanged(Handler h, int what, Object obj) {
        Registrant r = new Registrant (h, what, obj);
        synchronized (mIccChangedRegistrants) {
            mIccChangedRegistrants.add(r);
        }
        //Notify registrant right after registering, so that it will get the latest ICC status,
        //otherwise which may not happen until there is an actual change in ICC status.
        r.notifyRegistrant();
    }
    public void unregisterForIccChanged(Handler h) {
        synchronized (mIccChangedRegistrants) {
            mIccChangedRegistrants.remove(h);
        }
    }
}
