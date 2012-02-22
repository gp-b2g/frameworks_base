/*
 * Copyright (C) 2011 The Android Open Source Project
 * Copyright (c) 2011-2012, Code Aurora Forum. All rights reserved.
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
import android.os.SystemProperties;
import android.util.Log;
import android.telephony.TelephonyManager;

import com.android.internal.telephony.IccCardStatus.CardState;
import com.android.internal.telephony.cat.CatService;

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
    private static final int EVENT_RADIO_OFF = 4;
    private static UiccManager mInstance;

    private Context mContext;
    private CommandsInterface[] mCi;
    private CatService[] mCatService;
    private UiccCard[] mUiccCards = new UiccCard[MSimConstants.RIL_MAX_CARDS];

    private RegistrantList mIccChangedRegistrants = new RegistrantList();

    public static UiccManager getInstance(Context c, CommandsInterface[] ci) {
        if (mInstance == null) {
            mInstance = new UiccManager(c, ci);
        } else {
            mInstance.mContext = c;
            mInstance.mCi = ci;
        }
        return mInstance;
    }

    public static UiccManager getInstance(Context c, CommandsInterface ci) {
        CommandsInterface[] arrayCi = new CommandsInterface[1];
        arrayCi[0] = ci;
        return getInstance(c, arrayCi);
    }

    public static UiccManager getInstance() {
        if (mInstance == null) {
            return null;
        } else {
            return mInstance;
        }
    }

    private UiccManager(Context c, CommandsInterface[] ci) {
        Log.d(LOG_TAG, "Creating UiccManager");
        mContext = c;
        mCi = ci;
        mCatService = new CatService[mCi.length];
        for (int i = 0; i < mCi.length; i++) {
            Integer index = new Integer(i);
            mCi[i].registerForIccStatusChanged(this, EVENT_ICC_STATUS_CHANGED, index);
            mCi[i].registerForNotAvailable(this, EVENT_RADIO_UNAVAILABLE, index);
            mCi[i].registerForOff(this, EVENT_RADIO_OFF, index);
            // TODO remove this once modem correctly notifies the unsols
            mCi[i].registerForOn(this, EVENT_ICC_STATUS_CHANGED, index);
            mCatService[i] = new CatService( mCi[i], mContext, i);

        }
    }

    @Override
    public void handleMessage (Message msg) {
        Integer index = getCiIndex(msg);

        if (index < 0 || index >= mCi.length) {
            Log.e(LOG_TAG, "Invalid index - " + index + " received with event " + msg.what);
            return;
        }

        switch (msg.what) {
            case EVENT_ICC_STATUS_CHANGED:
                Log.d(LOG_TAG, "Received EVENT_ICC_STATUS_CHANGED, calling getIccCardStatus"
                       + " on index " + index);
                mCi[index].getIccCardStatus(obtainMessage(EVENT_GET_ICC_STATUS_DONE, index));
                break;
            case EVENT_GET_ICC_STATUS_DONE:
                Log.d(LOG_TAG, "Received EVENT_GET_ICC_STATUS_DONE on index " + index);
                AsyncResult ar = (AsyncResult)msg.obj;
                onGetIccCardStatusDone(ar, index);
                break;
            case EVENT_RADIO_UNAVAILABLE:
                Log.d(LOG_TAG, "EVENT_RADIO_UNAVAILABLE on index " + index);
                disposeCard(index);
                break;
            case EVENT_RADIO_OFF:
                if (!isAirplaneModeCardPowerDown()) {
                    Log.d(LOG_TAG, "EVENT_RADIO_OFF calling getIccCardStatus on index " + index);
                    mCi[index].getIccCardStatus(obtainMessage(EVENT_GET_ICC_STATUS_DONE, index));
                }
                break;
            default:
                Log.e(LOG_TAG, " Unknown Event " + msg.what);
        }
    }

    private boolean isAirplaneModeCardPowerDown() {
        return 0 == SystemProperties.getInt("persist.radio.apm_sim_not_pwdn", 0);
    }

    private Integer getCiIndex(Message msg) {
        AsyncResult ar;
        Integer index = new Integer(MSimConstants.DEFAULT_CARD_INDEX);

        /*
         * The events can be come in two ways. By explicitly sending it using
         * sendMessage, in this case the user object passed is msg.obj and from
         * the CommandsInterface, in this case the user object is msg.obj.userObj
         */
        if (msg != null) {
            if (msg.obj != null && msg.obj instanceof Integer) {
                index = (Integer)msg.obj;
            } else if(msg.obj != null && msg.obj instanceof AsyncResult) {
                ar = (AsyncResult)msg.obj;
                if (ar.userObj != null && ar.userObj instanceof Integer) {
                    index = (Integer)ar.userObj;
                }
            }
        }
        return index;
    }

    private synchronized void onGetIccCardStatusDone(AsyncResult ar, Integer index) {
        if (ar.exception != null) {
            Log.e(LOG_TAG,"Error getting ICC status. "
                    + "RIL_REQUEST_GET_ICC_STATUS should "
                    + "never return an error", ar.exception);
            return;
        }

        IccCardStatus status = (IccCardStatus)ar.result;

        if (mUiccCards[index] == null) {
            Log.d(LOG_TAG, "Creating a new card");
            mUiccCards[index] = new UiccCard(mContext, mCi[index], status);
        } else {
            Log.d(LOG_TAG, "Update already existing card");
            mUiccCards[index].update(mContext, mCi[index], status);
        }

        Log.d(LOG_TAG, "Notifying IccChangedRegistrants");
        mIccChangedRegistrants.notifyRegistrants(new AsyncResult(null, index, null));
    }

    public synchronized UiccCard getUiccCard() {
        return getUiccCard(MSimConstants.DEFAULT_CARD_INDEX);
    }

    public synchronized UiccCard getUiccCard(int slotId) {
        if (slotId >= 0 && slotId < mUiccCards.length) {
            return mUiccCards[slotId];
        }
        return null;
    }

    public synchronized UiccCard[] getIccCards() {
        // Return cloned array since we don't want to give out reference
        // to internal data structure.
        return mUiccCards.clone();
    }

    // Easy to use API
    public synchronized UiccCardApplication getUiccCardApplication(AppFamily family) {
        return getUiccCardApplication(MSimConstants.DEFAULT_CARD_INDEX, family);
    }

    public synchronized UiccCardApplication getUiccCardApplication(int slotId, AppFamily family) {
        if (slotId >= 0 && slotId < mUiccCards.length) {
            UiccCard c = mUiccCards[slotId];
            if (c != null) {
                return c.getApplication(family);
            }
        }
        return null;
    }

    // Easy to use API
    public synchronized IccRecords getIccRecords(AppFamily family) {
        return getIccRecords(MSimConstants.DEFAULT_CARD_INDEX, family);
    }

    public synchronized IccRecords getIccRecords(int slotId, AppFamily family) {
        UiccCardApplication app = getUiccCardApplication(slotId, family);
        if (app != null) {
            return app.getIccRecords();
        }
        return null;
    }

    // Easy to use API
    public synchronized IccFileHandler getIccFileHandler(AppFamily family) {
        return getIccFileHandler(MSimConstants.DEFAULT_CARD_INDEX, family);
    }

    public synchronized IccFileHandler getIccFileHandler(int slotId, AppFamily family) {
        UiccCardApplication app = getUiccCardApplication(slotId, family);
        if (app != null) {
            return app.getIccFileHandler();
        }
        return null;
    }

    // Destroys the card object
    private synchronized void disposeCard(int index) {
        if ((index < mUiccCards.length) && (mUiccCards[index] != null)) {
            Log.d(LOG_TAG, "Disposing card " + index);
            mUiccCards[index].dispose();
            mUiccCards[index] = null;
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

   //Gets the CatService for the SlotId specified
   public CatService getCatService(int slotId) {
       return mCatService[slotId];
   }
}
