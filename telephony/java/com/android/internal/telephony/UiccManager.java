/*
 * Copyright (C) 2011 The Android Open Source Project
 * Copyright (c) 2011, Code Aurora Forum. All rights reserved.
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
        APP_FAM_3GPP2,
        APP_FAM_IMS
    }
    private static final int EVENT_ICC_STATUS_CHANGED = 1;
    private static final int EVENT_GET_ICC_STATUS_DONE = 2;
    private static final int EVENT_RADIO_UNAVAILABLE = 3;
    private static UiccManager mInstance;

    private Context mContext;
    private CommandsInterface mCi;
    private UiccCard mUiccCard;

    private RegistrantList mIccChangedRegistrants = new RegistrantList();

    public static UiccManager getInstance(Context c, CommandsInterface ci) {
        if (mInstance == null) {
            mInstance = new UiccManager(c, ci);
        } else {
            mInstance.mContext = c;
            mInstance.mCi = ci;
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

    private UiccManager(Context c, CommandsInterface ci) {
        Log.d(LOG_TAG, "Creating UiccManager");
        mContext = c;
        mCi = ci;
        mCi.registerForIccStatusChanged(this, EVENT_ICC_STATUS_CHANGED, null);
        mCi.registerForNotAvailable(this, EVENT_RADIO_UNAVAILABLE, null);
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
            case EVENT_RADIO_UNAVAILABLE:
                Log.d(LOG_TAG, "EVENT_RADIO_UNAVAILABLE");
                disposeCard();
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

        if (mUiccCard == null) {
            Log.d(LOG_TAG, "Creating a new card");
            mUiccCard = new UiccCard(mContext, mCi, status);
        } else {
            Log.d(LOG_TAG, "Update already existing card");
            mUiccCard.update(mContext, mCi , status);
        }

        Log.d(LOG_TAG, "Notifying IccChangedRegistrants");
        mIccChangedRegistrants.notifyRegistrants();
    }

    public synchronized UiccCard getUiccCard() {
        return mUiccCard;
    }

    // Easy to use API
    public synchronized UiccCardApplication getUiccCardApplication(AppFamily family) {
        if (mUiccCard != null) {
            return mUiccCard.getApplication(family);
        }
        return null;
    }

    // Easy to use API
    public synchronized IccRecords getIccRecords(AppFamily family) {
        if (mUiccCard != null) {
            UiccCardApplication app = mUiccCard.getApplication(family);
            if (app != null) {
                return app.getIccRecords();
            }
        }
        return null;
    }

    // Easy to use API
    public synchronized IccFileHandler getIccFileHandler(AppFamily family) {
        if (mUiccCard != null) {
            UiccCardApplication app = mUiccCard.getApplication(family);
            if (app != null) {
                return app.getIccFileHandler();
            }
        }
        return null;
    }

    // Destroys the card object
    private synchronized void disposeCard() {
        Log.d(LOG_TAG, "Disposing card ");
        if (mUiccCard != null) {
            mUiccCard.dispose();
            mUiccCard = null;
        }
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
