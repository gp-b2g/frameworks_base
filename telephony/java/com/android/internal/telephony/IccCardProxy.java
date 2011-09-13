/*
 * Copyright (c) 2010-2011, Code Aurora Forum. All rights reserved.
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

import com.android.internal.telephony.CommandsInterface.RadioTechnology;
import com.android.internal.telephony.cdma.CdmaSubscriptionSourceManager;
import com.android.internal.telephony.UiccManager.AppFamily;
import com.android.internal.telephony.gsm.SIMRecords;
import static com.android.internal.telephony.Phone.CDMA_SUBSCRIPTION_NV;
import static com.android.internal.telephony.TelephonyProperties.PROPERTY_SIM_STATE;

/*
 * The Phone App UI and the external world assumes that there is only one icc card,
 * and one icc application available at a time. But the Uicc Manager can handle
 * multiple instances of icc objects. This class implements the icc interface to expose
 * the  first application on the first icc card, so that external apps wont break.
 */

public class IccCardProxy extends Handler implements IccCard {

    private static final String LOG_TAG = "RIL_IccCardProxy";

    private static final int EVENT_RADIO_OFF_OR_UNAVAILABLE = 1;
    private static final int EVENT_RADIO_ON = 2;
    private static final int EVENT_ICC_CHANGED = 3;
    private static final int EVENT_ICC_ABSENT = 4;
    private static final int EVENT_ICC_LOCKED = 5;
    private static final int EVENT_APP_READY = 6;
    private static final int EVENT_RECORDS_LOADED = 7;
    private static final int EVENT_IMSI_READY = 8;
    private static final int EVENT_NETWORK_LOCKED = 9;
    private static final int EVENT_CDMA_SUBSCRIPTION_SOURCE_CHANGED = 11;

    private Context mContext;
    private CommandsInterface mCi;

    private RegistrantList mReadyRegistrants = new RegistrantList();
    private RegistrantList mAbsentRegistrants = new RegistrantList();
    private RegistrantList mPinLockedRegistrants = new RegistrantList();
    private RegistrantList mNetworkLockedRegistrants = new RegistrantList();

    private AppFamily mCurrentAppType = AppFamily.APP_FAM_3GPP; //default to 3gpp?
    private UiccManager mUiccManager = null;
    private UiccCard mUiccCard = null;
    private IccRecords mIccRecords = null;
    private CdmaSubscriptionSourceManager mCdmaSSM = null;
    private boolean mFirstRun = true;
    private boolean mRadioOn = false;
    private boolean mCdmaSubscriptionFromNv = false;
    private boolean mIsMultimodeCdmaPhone =
            SystemProperties.getBoolean("ro.config.multimode_cdma", false);
    private boolean mQuietMode = false; // when set to true IccCardProxy will not broadcast
                                        // ACTION_SIM_STATE_CHANGED intents
    private boolean mInitialized = false;

    public IccCardProxy(Context context, CommandsInterface ci) {
        this.mContext = context;
        this.mCi = ci;
        mCdmaSSM = CdmaSubscriptionSourceManager.getInstance(context,
                ci, this, EVENT_CDMA_SUBSCRIPTION_SOURCE_CHANGED, null);
        mUiccManager = UiccManager.getInstance();
        mUiccManager.registerForIccChanged(this, EVENT_ICC_CHANGED, null);
        ci.registerForOn(this,EVENT_RADIO_ON, null);
        ci.registerForOffOrNotAvailable(this, EVENT_RADIO_OFF_OR_UNAVAILABLE, null);
        SystemProperties.set(PROPERTY_SIM_STATE, "ABSENT");
    }

    public void dispose() {
        //Cleanup icc references
        mUiccManager.unregisterForIccChanged(this);
        mUiccManager = null;
        mCi.unregisterForOn(this);
        mCi.unregisterForOffOrNotAvailable(this);
        mCdmaSSM.dispose(this);
    }

    /*
     * The card application that the external world sees will be based on the
     * voice radio technology only!
     */
    public void setVoiceRadioTech(RadioTechnology radioTech) {
        Log.d(LOG_TAG, "Setting radio tech " + radioTech);
        if (radioTech.isGsm()) {
            mCurrentAppType = AppFamily.APP_FAM_3GPP;
        } else {
            mCurrentAppType = AppFamily.APP_FAM_3GPP2;
        }
        mFirstRun = true;
        updateQuietMode();
    }

    /** This function does not necessarily updates mQuietMode right away
     * In case of 3GPP2 subscription it needs more information (subscription source)
     */
    private void updateQuietMode() {
        Log.d(LOG_TAG, "Updating Quiet mode");
        if (mCurrentAppType == AppFamily.APP_FAM_3GPP) {
            mInitialized = true;
            mQuietMode = false;
            Log.d(LOG_TAG, "3GPP subscription -> QuietMode: " + mQuietMode);
            sendMessage(obtainMessage(EVENT_ICC_CHANGED));
        } else {
            //In case of 3gpp2 we need to find out if subscription used is coming from
            //NV in which case we shouldn't broadcast any sim states changes if at the
            //same time ro.config.multimode_cdma property set to false.
            mInitialized = false;
            handleCdmaSubscriptionSource();
        }
    }

    public void handleMessage(Message msg) {
        switch (msg.what) {
            case EVENT_RADIO_OFF_OR_UNAVAILABLE:
                mRadioOn = false;
                break;
            case EVENT_RADIO_ON:
                mRadioOn = true;
                if (!mInitialized) {
                    updateQuietMode();
                }
                break;
            case EVENT_ICC_CHANGED:
                if (mInitialized) {
                    updateIccAvailability();
                    updateStateProperty();
                }
                break;
            case EVENT_ICC_ABSENT:
                mAbsentRegistrants.notifyRegistrants();
                broadcastIccStateChangedIntent(INTENT_VALUE_ICC_ABSENT, null);
                break;
            case EVENT_ICC_LOCKED:
                processLockedState();
                break;
            case EVENT_APP_READY:
                broadcastIccStateChangedIntent(INTENT_VALUE_ICC_READY, null);
                break;
            case EVENT_RECORDS_LOADED:
                broadcastIccStateChangedIntent(INTENT_VALUE_ICC_LOADED, null);
                break;
            case EVENT_IMSI_READY:
                broadcastIccStateChangedIntent(INTENT_VALUE_ICC_IMSI, null);
                break;
            case EVENT_NETWORK_LOCKED:
                broadcastIccStateChangedIntent(INTENT_VALUE_ICC_LOCKED,
                        INTENT_VALUE_LOCKED_NETWORK);
                mNetworkLockedRegistrants.notifyRegistrants();
                break;
            case EVENT_CDMA_SUBSCRIPTION_SOURCE_CHANGED:
                updateQuietMode();
                break;
            default:
                Log.e(LOG_TAG, "Unhandled message with number: " + msg.what);
                break;
        }
    }

    void updateIccAvailability() {
        mUiccCard = mUiccManager.getUiccCard();
        IccCard.State state = State.UNKNOWN;
        IccRecords newRecords = null;
        if (mUiccCard != null) {
            state = mUiccCard.getState();
            Log.d(LOG_TAG,"Card State = " + state);
            newRecords = mUiccCard.getIccRecords();
        }

        if (mFirstRun) {
            if (mUiccCard == null || state == State.ABSENT) {
                broadcastIccStateChangedIntent(INTENT_VALUE_ICC_ABSENT, null);
            }
            mFirstRun = false;
        }

        if (mIccRecords != newRecords) {
            if (mIccRecords != null) {
                unregisterUiccCardEvents();
                mIccRecords = null;
            }
            if (newRecords == null) {
                if (mRadioOn) {
                    broadcastIccStateChangedIntent(INTENT_VALUE_ICC_ABSENT, null);
                } else {
                    broadcastIccStateChangedIntent(INTENT_VALUE_ICC_NOT_READY, null);
                }
            } else {
                mIccRecords = newRecords;
                registerUiccCardEvents();
            }
        } else {

        }
    }

    private void registerUiccCardEvents() {
        if (mUiccCard != null) mUiccCard.registerForReady(this, EVENT_APP_READY, null);
        if (mUiccCard != null) mUiccCard.registerForLocked(this, EVENT_ICC_LOCKED, null);
        if (mUiccCard != null) mUiccCard.registerForNetworkLocked(this, EVENT_NETWORK_LOCKED, null);
        if (mUiccCard != null) mUiccCard.registerForAbsent(this, EVENT_ICC_ABSENT, null);
        if (mIccRecords != null) mIccRecords.registerForImsiReady(this, EVENT_IMSI_READY, null);
        if (mIccRecords != null) mIccRecords.registerForRecordsLoaded(this, EVENT_RECORDS_LOADED, null);
    }

    private void unregisterUiccCardEvents() {
        if (mUiccCard != null) mUiccCard.unregisterForReady(this);
        if (mUiccCard != null) mUiccCard.unregisterForLocked(this);
        if (mUiccCard != null) mUiccCard.unregisterForNetworkLocked(this);
        if (mUiccCard != null) mUiccCard.unregisterForAbsent(this);
        if (mIccRecords != null) mIccRecords.unregisterForImsiReady(this);
        if (mIccRecords != null) mIccRecords.unregisterForRecordsLoaded(this);
    }

    private void updateStateProperty() {
        SystemProperties.set(PROPERTY_SIM_STATE, getState().toString());
    }

    /* why do external apps need to use this? */
    public void broadcastIccStateChangedIntent(String value, String reason) {
        if (mQuietMode) {
            Log.e(LOG_TAG, "QuietMode: NOT Broadcasting intent ACTION_SIM_STATE_CHANGED " +  value
                    + " reason " + reason);
            return;
        }

        Intent intent = new Intent(TelephonyIntents.ACTION_SIM_STATE_CHANGED);
        intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
        intent.putExtra(Phone.PHONE_NAME_KEY, "Phone");
        intent.putExtra(INTENT_KEY_ICC_STATE, value);
        intent.putExtra(INTENT_KEY_LOCKED_REASON, reason);

        Log.d(LOG_TAG, "Broadcasting intent ACTION_SIM_STATE_CHANGED " +  value
            + " reason " + reason);
        ActivityManagerNative.broadcastStickyIntent(intent, READ_PHONE_STATE);
    }

    public void changeIccFdnPassword(String oldPassword, String newPassword, Message onComplete) {
        if (mUiccCard != null) {
            mUiccCard.changeIccFdnPassword(oldPassword, newPassword, onComplete);
        } else if (onComplete != null) {
            Exception e = new RuntimeException("ICC card is absent.");
            AsyncResult.forMessage(onComplete).exception = e;
            onComplete.sendToTarget();
            return;
        }
    }

    public void changeIccLockPassword(String oldPassword, String newPassword, Message onComplete) {
        if (mUiccCard != null) {
            mUiccCard.changeIccLockPassword(oldPassword, newPassword, onComplete);
        } else if (onComplete != null) {
            Exception e = new RuntimeException("ICC card is absent.");
            AsyncResult.forMessage(onComplete).exception = e;
            onComplete.sendToTarget();
            return;
        }
    }

    private void processLockedState() {
        if (mUiccCard == null) {
            //Don't need to do anything if non-existent application is locked
            return;
        }
        IccCard.State appState = mUiccCard.getState();
        switch (appState) {
            case PIN_REQUIRED:
                mPinLockedRegistrants.notifyRegistrants();
                broadcastIccStateChangedIntent(INTENT_VALUE_ICC_LOCKED, INTENT_VALUE_LOCKED_ON_PIN);
                break;
            case PUK_REQUIRED:
                broadcastIccStateChangedIntent(INTENT_VALUE_ICC_LOCKED, INTENT_VALUE_LOCKED_ON_PUK);
                break;
            case PERM_DISABLED:
                broadcastIccStateChangedIntent(INTENT_VALUE_ICC_ABSENT,
                        INTENT_VALUE_ABSENT_ON_PERM_DISABLED);
                break;
        }
    }

    public State getIccCardState() {
        return getState();
    }

    public State getState() {
        if (mCurrentAppType == AppFamily.APP_FAM_3GPP2 && mCdmaSubscriptionFromNv) {
            return State.READY;
        }
        if (mUiccCard != null) {
            mUiccCard.getState();
        }

        return State.UNKNOWN;
    }

    public boolean getIccFdnEnabled() {
        Boolean retValue = mUiccCard != null ? mUiccCard.getIccFdnEnabled() : false;
        return retValue;
    }

    public boolean getIccLockEnabled() {
        /* defaults to true, if ICC is absent */
        Boolean retValue = mUiccCard != null ? mUiccCard.getIccLockEnabled() : true;
        return retValue;
    }

    public String getServiceProviderName() {
        if (mUiccCard != null) {
            return mUiccCard.getServiceProviderName();
        }
        return null;
    }

    public boolean hasIccCard() {
        if (mUiccCard != null && mUiccCard.getState().iccCardExist()) {
            return true;
        }
        return false;
    }

    public boolean isApplicationOnIcc(IccCardApplication.AppType type) {
        Boolean retValue = mUiccCard != null ? mUiccCard.isApplicationOnIcc(type) : false;
        return retValue;
    }

    public void registerForReady(Handler h, int what, Object obj) {
        Registrant r = new Registrant (h, what, obj);

        mReadyRegistrants.add(r);

        if (getState() == State.READY) {
            r.notifyRegistrant();
        }
    }

    public void unregisterForReady(Handler h) {
        mReadyRegistrants.remove(h);
    }

    /**
     * Notifies handler of any transition into State.ABSENT
     */
    public void registerForAbsent(Handler h, int what, Object obj) {
        Registrant r = new Registrant (h, what, obj);

        mAbsentRegistrants.add(r);

        if (getState() == State.ABSENT) {
            r.notifyRegistrant();
        }
    }

    public void unregisterForAbsent(Handler h) {
        mAbsentRegistrants.remove(h);
    }

    /**
     * Notifies handler of any transition into State.NETWORK_LOCKED
     */
    public void registerForNetworkLocked(Handler h, int what, Object obj) {
        Registrant r = new Registrant (h, what, obj);

        mNetworkLockedRegistrants.add(r);

        if (getState() == State.NETWORK_LOCKED) {
            r.notifyRegistrant();
        }
    }

    public void unregisterForNetworkLocked(Handler h) {
        mNetworkLockedRegistrants.remove(h);
    }

    /**
     * Notifies handler of any transition into State.isPinLocked()
     */
    public void registerForLocked(Handler h, int what, Object obj) {
        Registrant r = new Registrant (h, what, obj);

        mPinLockedRegistrants.add(r);

        if (getState().isPinLocked()) {
            r.notifyRegistrant();
        }
    }

    public void unregisterForLocked(Handler h) {
        mPinLockedRegistrants.remove(h);
    }

    public void setIccFdnEnabled(boolean enabled, String password, Message onComplete) {
        if (mUiccCard != null) {
            mUiccCard.setIccFdnEnabled(enabled, password, onComplete);
        } else if (onComplete != null) {
            Exception e = new RuntimeException("ICC card is absent.");
            AsyncResult.forMessage(onComplete).exception = e;
            onComplete.sendToTarget();
            return;
        }
    }

    public void setIccLockEnabled(boolean enabled, String password, Message onComplete) {
        if (mUiccCard != null) {
            mUiccCard.setIccLockEnabled(enabled, password, onComplete);
        } else if (onComplete != null) {
            Exception e = new RuntimeException("ICC card is absent.");
            AsyncResult.forMessage(onComplete).exception = e;
            onComplete.sendToTarget();
            return;
        }
    }

    /**
     * Use invokeDepersonalization from PhoneBase class instead.
     */
    public void supplyNetworkDepersonalization(String pin, Message onComplete) {
        if (mUiccCard != null) {
            mUiccCard.supplyNetworkDepersonalization(pin, onComplete);
        } else if (onComplete != null) {
            Exception e = new RuntimeException("CommandsInterface is not set.");
            AsyncResult.forMessage(onComplete).exception = e;
            onComplete.sendToTarget();
            return;
        }
    }

    public void supplyPin(String pin, Message onComplete) {
        if (mUiccCard != null) {
            mUiccCard.supplyPin(pin, onComplete);
        } else if (onComplete != null) {
            Exception e = new RuntimeException("ICC card is absent.");
            AsyncResult.forMessage(onComplete).exception = e;
            onComplete.sendToTarget();
            return;
        }
    }

    public void supplyPin2(String pin2, Message onComplete) {
        if (mUiccCard != null) {
            mUiccCard.supplyPin2(pin2, onComplete);
        } else if (onComplete != null) {
            Exception e = new RuntimeException("ICC card is absent.");
            AsyncResult.forMessage(onComplete).exception = e;
            onComplete.sendToTarget();
            return;
        }
    }

    public void supplyPuk(String puk, String newPin, Message onComplete) {
        if (mUiccCard != null) {
            mUiccCard.supplyPuk(puk, newPin, onComplete);
        } else if (onComplete != null) {
            Exception e = new RuntimeException("ICC card is absent.");
            AsyncResult.forMessage(onComplete).exception = e;
            onComplete.sendToTarget();
            return;
        }
    }

    public void supplyPuk2(String puk2, String newPin2, Message onComplete) {
        if (mUiccCard != null) {
            mUiccCard.supplyPuk2(puk2, newPin2, onComplete);
        } else if (onComplete != null) {
            Exception e = new RuntimeException("ICC card is absent.");
            AsyncResult.forMessage(onComplete).exception = e;
            onComplete.sendToTarget();
            return;
        }
    }

    /**
     * Handles the call to get the subscription source
     *
     * @param holds the new CDMA subscription source value
     */
    private void handleCdmaSubscriptionSource() {
        int newSubscriptionSource = mCdmaSSM.getCdmaSubscriptionSource();
        mCdmaSubscriptionFromNv = newSubscriptionSource == CDMA_SUBSCRIPTION_NV;
        if (mCdmaSubscriptionFromNv && mIsMultimodeCdmaPhone) {
            Log.d(LOG_TAG, "Cdma multimode phone detected. Forcing IccCardProxy into 3gpp mode");
            mCurrentAppType = AppFamily.APP_FAM_3GPP;
        }
        boolean newQuietMode = mCdmaSubscriptionFromNv
                && (mCurrentAppType == AppFamily.APP_FAM_3GPP2) && !mIsMultimodeCdmaPhone;
        if (mQuietMode == false && newQuietMode == true) {
            // Last thing to do before switching to Quiet mode is
            // broadcast ICC_READY
            Log.d(LOG_TAG, "Switching to QuietMode.");
            broadcastIccStateChangedIntent(INTENT_VALUE_ICC_READY, null);
        }
        mQuietMode = newQuietMode;
        Log.d(LOG_TAG, "QuietMode is " + mQuietMode + " (app_type: " + mCurrentAppType + " nv: "
                + mCdmaSubscriptionFromNv + " multimode: " + mIsMultimodeCdmaPhone + ")");
        mInitialized = true;
        sendMessage(obtainMessage(EVENT_ICC_CHANGED));
    }

    public IccFileHandler getIccFileHandler() {
        if (mUiccCard != null) {
            return mUiccCard.getIccFileHandler();
        }
        return null;
    }

    public IccRecords getIccRecords() {
        return mIccRecords;
    }

    public boolean getIccRecordsLoaded() {
        if (mIccRecords != null) {
            return mIccRecords.getRecordsLoaded();
        }
        return false;
    }
}
