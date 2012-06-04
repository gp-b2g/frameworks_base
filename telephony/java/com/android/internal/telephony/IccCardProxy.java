/*
 * Copyright (c) 2010-2012, Code Aurora Forum. All rights reserved.
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

import com.android.internal.telephony.Phone;
import com.android.internal.telephony.IccCardApplicationStatus.AppState;
import com.android.internal.telephony.IccCardApplicationStatus.AppType;
import com.android.internal.telephony.IccCardApplicationStatus.PersoSubState;
import com.android.internal.telephony.IccCardStatus.CardState;
import com.android.internal.telephony.IccCardStatus.PinState;
import com.android.internal.telephony.cdma.CdmaSubscriptionSourceManager;
import com.android.internal.telephony.cdma.RuimRecords;
import com.android.internal.telephony.UiccManager.AppFamily;
import static com.android.internal.telephony.TelephonyProperties.PROPERTY_SIM_STATE;

/*
 * The Phone App UI and the external world assumes that there is only one icc card,
 * and one icc application available at a time. But the Uicc Manager can handle
 * multiple instances of icc objects. This class implements the icc interface to expose
 * the current (based on voice radio technology) application on the uicc card, so
 * that external apps wont break.
 */

public class IccCardProxy extends Handler implements IccCard {
    private static final String LOG_TAG = "RIL_IccCardProxy";
    private static final boolean DBG = true;

    private static final int EVENT_RADIO_OFF_OR_UNAVAILABLE = 1;
    private static final int EVENT_RADIO_ON = 2;
    private static final int EVENT_ICC_CHANGED = 3;
    private static final int EVENT_ICC_ABSENT = 4;
    private static final int EVENT_ICC_LOCKED = 5;
    private static final int EVENT_APP_READY = 6;
    protected static final int EVENT_RECORDS_LOADED = 7;
    private static final int EVENT_IMSI_READY = 8;
    private static final int EVENT_PERSO_LOCKED = 9;
    private static final int EVENT_CDMA_SUBSCRIPTION_SOURCE_CHANGED = 11;

    private Context mContext;
    private CommandsInterface mCi;

    private RegistrantList mReadyRegistrants = new RegistrantList();
    protected RegistrantList mAbsentRegistrants = new RegistrantList();
    private RegistrantList mPinLockedRegistrants = new RegistrantList();
    private RegistrantList mPersoLockedRegistrants = new RegistrantList();

    protected AppFamily mCurrentAppType = AppFamily.APP_FAM_3GPP; //default to 3gpp?
    protected UiccManager mUiccManager = null;
    protected UiccCard mUiccCard = null;
    protected UiccCardApplication mUiccApplication = null;
    protected IccRecords mIccRecords = null;
    private CdmaSubscriptionSourceManager mCdmaSSM = null;
    private boolean mRadioOn = false;
    private boolean mCdmaSubscriptionFromNv = false;
    private boolean mIsMultimodeCdmaPhone =
            SystemProperties.getBoolean(TelephonyProperties.PROPERTY_MULTIMODE_CDMA, false);
    protected boolean mQuietMode = false; // when set to true IccCardProxy will not broadcast
                                        // ACTION_SIM_STATE_CHANGED intents
    protected boolean mInitialized = false;
    protected IccCard.State mExternalState = State.UNKNOWN;
    private PersoSubState mPersoSubState = PersoSubState.PERSOSUBSTATE_UNKNOWN;

    public IccCardProxy(Context context, CommandsInterface ci) {
        log("Creating");
        this.mContext = context;
        this.mCi = ci;
        mCdmaSSM = CdmaSubscriptionSourceManager.getInstance(context,
                ci, this, EVENT_CDMA_SUBSCRIPTION_SOURCE_CHANGED, null);
        mUiccManager = UiccManager.getInstance();
        mUiccManager.registerForIccChanged(this, EVENT_ICC_CHANGED, null);
        ci.registerForOn(this,EVENT_RADIO_ON, null);
        ci.registerForOffOrNotAvailable(this, EVENT_RADIO_OFF_OR_UNAVAILABLE, null);
        setExternalState(State.NOT_READY);
    }

    public void dispose() {
        log("Disposing");
        //Cleanup icc references
        mUiccManager.unregisterForIccChanged(this);
        mUiccManager = null;
        mCi.unregisterForOn(this);
        mCi.unregisterForOffOrNotAvailable(this);
        mCdmaSSM.dispose(this);
    }

    /*
     * The card application that the external world sees will be based on the
     * phone type only!
     */
    public void setPhoneType(int phoneType) {
        log("Setting Phone Type " + phoneType);
        if (phoneType == Phone.PHONE_TYPE_GSM) {
            mCurrentAppType = AppFamily.APP_FAM_3GPP;
        } else if (phoneType == Phone.PHONE_TYPE_CDMA) {
            mCurrentAppType = AppFamily.APP_FAM_3GPP2;
        }
        updateCardState();
        updateActiveRecord();
    }

    /**
     * Trigger to go sync our card related state based on the current
     * card state
     */
    private void updateCardState () {
        updateQuietMode();
        sendMessage(obtainMessage(EVENT_ICC_CHANGED));
    }

    /**
     * This method sets the IccRecord, corresponding to the currently active
     * subscription, as the active record.
     */
    protected void updateActiveRecord() {
        log("updateActiveRecord app type = " + mCurrentAppType +
                "mIccRecords = " + mIccRecords);

        if (null == mIccRecords) {
            return;
        }

        if (mCurrentAppType == AppFamily.APP_FAM_3GPP2) {
            int newSubscriptionSource = mCdmaSSM.getCdmaSubscriptionSource();
            if (newSubscriptionSource == CdmaSubscriptionSourceManager.SUBSCRIPTION_FROM_RUIM) {
                // Set this as the Active record.
                log("Setting Ruim Record as active");
                mIccRecords.recordsRequired();
            }
        } else if (mCurrentAppType == AppFamily.APP_FAM_3GPP) {
            log("Setting SIM Record as active");
            mIccRecords.recordsRequired();
        }
    }

    /**
     * Updates the flag which determines when sim states are to
     * to be broadcasted
     */
    private void updateQuietMode() {
        log("Updating quiet mode");
        boolean oldQuietMode = mQuietMode;
        boolean newQuietMode;
        if (mCurrentAppType == AppFamily.APP_FAM_3GPP) {
            newQuietMode = false;
            log("3GPP subscription -> QuietMode: " + newQuietMode);
        } else {
            //In case of 3gpp2 we need to find out if subscription used is coming from
            //NV in which case we shouldn't broadcast any sim states changes if at the
            //same time ro.config.multimode_cdma property set to false.
            int newSubscriptionSource = mCdmaSSM.getCdmaSubscriptionSource();
            mCdmaSubscriptionFromNv =
                newSubscriptionSource == CdmaSubscriptionSourceManager.SUBSCRIPTION_FROM_NV;
            if (mCdmaSubscriptionFromNv && mIsMultimodeCdmaPhone) {
                log("Cdma multimode phone detected. Forcing IccCardProxy into 3gpp mode");
                mCurrentAppType = AppFamily.APP_FAM_3GPP;
            }
            newQuietMode = mCdmaSubscriptionFromNv
                    && (mCurrentAppType == AppFamily.APP_FAM_3GPP2) && !mIsMultimodeCdmaPhone;
        }

        if (mQuietMode == false && newQuietMode == true) {
            // Last thing to do before switching to quiet mode is
            // broadcast ICC_READY
            log("Switching to QuietMode.");
            setExternalState(State.READY);
            mQuietMode = newQuietMode;
        } else if (mQuietMode == true && newQuietMode == false) {
            log("Switching out from QuietMode. Force broadcast of current state:" + mExternalState);
            mQuietMode = newQuietMode;
            broadcastCurrentState();
        }
        log("QuietMode is " + mQuietMode + " (app_type: " + mCurrentAppType + " nv: "
                + mCdmaSubscriptionFromNv + " multimode: " + mIsMultimodeCdmaPhone + ")");
        mInitialized = true;
    }

    public void handleMessage(Message msg) {
        switch (msg.what) {
            case EVENT_RADIO_OFF_OR_UNAVAILABLE:
                mRadioOn = false;
                if (CommandsInterface.RadioState.RADIO_UNAVAILABLE == mCi.getRadioState()) {
                    setExternalState(State.NOT_READY);
                }
                break;
            case EVENT_RADIO_ON:
                mRadioOn = true;
                if (!mInitialized) {
                    updateQuietMode();
                }
                break;
            case EVENT_ICC_CHANGED:
                if (!mInitialized) {
                    updateQuietMode();
                }
                updateIccAvailability();
                break;
            case EVENT_ICC_LOCKED:
                processLockedState();
                break;
            case EVENT_APP_READY:
                setExternalState(State.READY);
                break;
            case EVENT_RECORDS_LOADED:
                broadcastIccStateChangedIntent(INTENT_VALUE_ICC_LOADED, null);
                break;
            case EVENT_IMSI_READY:
                broadcastIccStateChangedIntent(INTENT_VALUE_ICC_IMSI, null);
                break;
            case EVENT_PERSO_LOCKED:
                mPersoSubState = mUiccApplication.getPersoSubState();
                mPersoLockedRegistrants.notifyRegistrants((AsyncResult)msg.obj);
                setExternalState(State.PERSO_LOCKED);
                break;
            case EVENT_CDMA_SUBSCRIPTION_SOURCE_CHANGED:
                updateQuietMode();
                updateActiveRecord();
                break;
            default:
                loge("Unhandled message with number: " + msg.what);
                break;
        }
    }

    void updateIccAvailability() {
        UiccCard newCard = mUiccManager.getUiccCard();
        CardState state = CardState.CARDSTATE_ABSENT;
        UiccCardApplication newApp = null;
        IccRecords newRecords = null;
        if (newCard != null) {
            state = newCard.getCardState();
            Log.d(LOG_TAG,"Card State = " + state);
            newApp = newCard.getApplication(mCurrentAppType);
            if (newApp != null) {
                newRecords = newApp.getIccRecords();
            }
        } else {
            Log.d(LOG_TAG,"No card available");
        }

        if (mIccRecords != newRecords || mUiccApplication != newApp || mUiccCard != newCard) {
            log("Icc changed. Reregestering.");
            unregisterUiccCardEvents();
            mUiccCard = newCard;
            mUiccApplication = newApp;
            mIccRecords = newRecords;
            registerUiccCardEvents();
            updateActiveRecord();
        }

        updateExternalState();
    }

    /**
     * When the radio is turned off, lower layers power down the card
     * unless persist.radio.sim_not_pwdn property is set to '1'.
     * When the property is not set and radio is turned off,
     * broadcast NOT_READY
     * When the property is set, broadcast what is the current state
     * of the card
     */
    protected void updateExternalState() {
        if (mUiccCard == null || mUiccCard.getCardState() == CardState.CARDSTATE_ABSENT) {
            if (0 == SystemProperties.getInt("persist.radio.apm_sim_not_pwdn", 0)) {
                if (mRadioOn) {
                    setExternalState(State.ABSENT);
                } else {
                    setExternalState(State.NOT_READY);
                }
            } else {
                setExternalState(State.ABSENT);
            }
            return;
        }

        if (mUiccCard.getCardState() == CardState.CARDSTATE_ERROR) {
            setExternalState(State.CARD_IO_ERROR);
            return;
        }

        if (mUiccApplication == null) {
            setExternalState(State.UNKNOWN);
            return;
        }

        switch (mUiccApplication.getState()) {
            case APPSTATE_UNKNOWN:
            case APPSTATE_DETECTED:
                setExternalState(State.UNKNOWN);
                break;
            case APPSTATE_PIN:
                setExternalState(State.PIN_REQUIRED);
                break;
            case APPSTATE_PUK:
                setExternalState(State.PUK_REQUIRED);
                break;
            case APPSTATE_SUBSCRIPTION_PERSO:
                if (mUiccApplication.isPersoLocked()) {
                    mPersoSubState = mUiccApplication.getPersoSubState();
                    setExternalState(State.PERSO_LOCKED);
                } else {
                    setExternalState(State.UNKNOWN);
                }
                break;
            case APPSTATE_READY:
                setExternalState(State.READY);
                break;
        }
    }

    protected void registerUiccCardEvents() {
        if (mUiccCard != null) mUiccCard.registerForAbsent(this, EVENT_ICC_ABSENT, null);
        if (mUiccApplication != null) mUiccApplication.registerForReady(this, EVENT_APP_READY, null);
        if (mUiccApplication != null) mUiccApplication.registerForLocked(this, EVENT_ICC_LOCKED, null);
        if (mUiccApplication != null) mUiccApplication.registerForPersoLocked(this, EVENT_PERSO_LOCKED, null);
        if (mIccRecords != null) mIccRecords.registerForImsiReady(this, EVENT_IMSI_READY, null);
        if (mIccRecords != null) mIccRecords.registerForRecordsLoaded(this, EVENT_RECORDS_LOADED, null);
    }

    protected void unregisterUiccCardEvents() {
        if (mUiccCard != null) mUiccCard.unregisterForAbsent(this);
        if (mUiccApplication != null) mUiccApplication.unregisterForReady(this);
        if (mUiccApplication != null) mUiccApplication.unregisterForLocked(this);
        if (mUiccApplication != null) mUiccApplication.unregisterForPersoLocked(this);
        if (mIccRecords != null) mIccRecords.unregisterForImsiReady(this);
        if (mIccRecords != null) mIccRecords.unregisterForRecordsLoaded(this);
    }

    /* why do external apps need to use this? */
    public void broadcastIccStateChangedIntent(String value, String reason) {
        if (mQuietMode) {
            log("QuietMode: NOT Broadcasting intent ACTION_SIM_STATE_CHANGED " +  value
                    + " reason " + reason);
            return;
        }

        Intent intent = new Intent(TelephonyIntents.ACTION_SIM_STATE_CHANGED);
        intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
        intent.putExtra(Phone.PHONE_NAME_KEY, "Phone");
        intent.putExtra(INTENT_KEY_ICC_STATE, value);
        intent.putExtra(INTENT_KEY_LOCKED_REASON, reason);

        log("Broadcasting intent ACTION_SIM_STATE_CHANGED " +  value
            + " reason " + reason);
        ActivityManagerNative.broadcastStickyIntent(intent, READ_PHONE_STATE);
    }

    public void changeIccFdnPassword(String oldPassword, String newPassword, Message onComplete) {
        if (mUiccApplication != null) {
            mUiccApplication.changeIccFdnPassword(oldPassword, newPassword, onComplete);
        } else if (onComplete != null) {
            Exception e = new RuntimeException("ICC card is absent.");
            AsyncResult.forMessage(onComplete).exception = e;
            onComplete.sendToTarget();
            return;
        }
    }

    public void changeIccLockPassword(String oldPassword, String newPassword, Message onComplete) {
        if (mUiccApplication != null) {
            mUiccApplication.changeIccLockPassword(oldPassword, newPassword, onComplete);
        } else if (onComplete != null) {
            Exception e = new RuntimeException("ICC card is absent.");
            AsyncResult.forMessage(onComplete).exception = e;
            onComplete.sendToTarget();
            return;
        }
    }

    private void processLockedState() {
        if (mUiccApplication == null) {
            //Don't need to do anything if non-existent application is locked
            return;
        }
        PinState pin1State = mUiccApplication.getPin1State();
        if (pin1State == PinState.PINSTATE_ENABLED_PERM_BLOCKED) {
            setExternalState(State.PERM_DISABLED);
            return;
        }

        AppState appState = mUiccApplication.getState();
        switch (appState) {
            case APPSTATE_PIN:
                mPinLockedRegistrants.notifyRegistrants();
                setExternalState(State.PIN_REQUIRED);
                break;
            case APPSTATE_PUK:
                setExternalState(State.PUK_REQUIRED);
                break;
        }
    }

    public State getIccCardState() {
        return getState();
    }

    public boolean getIccFdnEnabled() {
        Boolean retValue = mUiccApplication != null ? mUiccApplication.getIccFdnEnabled() : false;
        return retValue;
    }

    public boolean getIccFdnAvailable() {
        boolean retValue = mUiccApplication != null ? mUiccApplication.getIccFdnAvailable() : false;
        return retValue;
    }

    public boolean getIccLockEnabled() {
        /* defaults to false, if ICC is absent/deactivated */
        Boolean retValue = mUiccApplication != null ? mUiccApplication.getIccLockEnabled() : false;
        return retValue;
    }

    public int getIccPin1RetryCount() {
        int retValue = mUiccApplication != null ? mUiccApplication.getIccPin1RetryCount() : -1;
        return retValue;
    }

    public int getIccPin2RetryCount() {
        int retValue = mUiccApplication != null ? mUiccApplication.getIccPin2RetryCount() : -1;
        return retValue;
    }

    public boolean getIccPin2Blocked() {
        /* defaults to disabled */
        Boolean retValue = mUiccApplication != null ? mUiccApplication.getIccPin2Blocked() : false;
        return retValue;
    }

    public boolean getIccPuk2Blocked() {
        /* defaults to disabled */
        Boolean retValue = mUiccApplication != null ? mUiccApplication.getIccPuk2Blocked() : false;
        return retValue;
    }

    public String getServiceProviderName() {
        if (mIccRecords != null) {
            return mIccRecords.getServiceProviderName();
        }
        return null;
    }

    public boolean hasIccCard() {
        if (mUiccCard != null && mUiccCard.getCardState() != CardState.CARDSTATE_ABSENT) {
            return true;
        }
        return false;
    }

    public boolean isApplicationOnIcc(IccCardApplicationStatus.AppType type) {
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
     * Notifies handler of any transition into State.PERSO_LOCKED
     */
    public void registerForPersoLocked(Handler h, int what, Object obj) {
        Registrant r = new Registrant (h, what, obj);
        mPersoLockedRegistrants.add(r);

        if (getState() == State.PERSO_LOCKED) {
            r.notifyRegistrant(new AsyncResult(null, mPersoSubState.ordinal(), null));
        }
    }

    public void unregisterForPersoLocked(Handler h) {
        mPersoLockedRegistrants.remove(h);
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
        if (mUiccApplication != null) {
            mUiccApplication.setIccFdnEnabled(enabled, password, onComplete);
        } else if (onComplete != null) {
            Exception e = new RuntimeException("ICC card is absent.");
            AsyncResult.forMessage(onComplete).exception = e;
            onComplete.sendToTarget();
            return;
        }
    }

    public void setIccLockEnabled(boolean enabled, String password, Message onComplete) {
        if (mUiccApplication != null) {
            mUiccApplication.setIccLockEnabled(enabled, password, onComplete);
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
    public void supplyDepersonalization(String pin, int type, Message onComplete) {
        if (mUiccApplication != null) {
            mUiccApplication.supplyDepersonalization(pin, type, onComplete);
        } else if (onComplete != null) {
            Exception e = new RuntimeException("CommandsInterface is not set.");
            AsyncResult.forMessage(onComplete).exception = e;
            onComplete.sendToTarget();
            return;
        }
    }

    public void supplyPin(String pin, Message onComplete) {
        if (mUiccApplication != null) {
            mUiccApplication.supplyPin(pin, onComplete);
        } else if (onComplete != null) {
            Exception e = new RuntimeException("ICC card is absent.");
            AsyncResult.forMessage(onComplete).exception = e;
            onComplete.sendToTarget();
            return;
        }
    }

    public void supplyPin2(String pin2, Message onComplete) {
        if (mUiccApplication != null) {
            mUiccApplication.supplyPin2(pin2, onComplete);
        } else if (onComplete != null) {
            Exception e = new RuntimeException("ICC card is absent.");
            AsyncResult.forMessage(onComplete).exception = e;
            onComplete.sendToTarget();
            return;
        }
    }

    public void supplyPuk(String puk, String newPin, Message onComplete) {
        if (mUiccApplication != null) {
            mUiccApplication.supplyPuk(puk, newPin, onComplete);
        } else if (onComplete != null) {
            Exception e = new RuntimeException("ICC card is absent.");
            AsyncResult.forMessage(onComplete).exception = e;
            onComplete.sendToTarget();
            return;
        }
    }

    public void supplyPuk2(String puk2, String newPin2, Message onComplete) {
        if (mUiccApplication != null) {
            mUiccApplication.supplyPuk2(puk2, newPin2, onComplete);
        } else if (onComplete != null) {
            Exception e = new RuntimeException("ICC card is absent.");
            AsyncResult.forMessage(onComplete).exception = e;
            onComplete.sendToTarget();
            return;
        }
    }

    protected void broadcastCurrentState() {
        broadcastIccStateChangedIntent(mExternalState.getIntentString(),
                mExternalState.getReason());
        // TODO: Need to notify registrants for other states as well.
        if ( State.ABSENT == mExternalState) {
            mAbsentRegistrants.notifyRegistrants();
        }
    }

    protected void setExternalState(State newState) {
        if (newState == mExternalState) {
            return;
        }
        mExternalState = newState;
        SystemProperties.set(PROPERTY_SIM_STATE, mExternalState.toString());

        // If Quiet mode has not been evaluated yet
        // don't broadcast anything
        if (mInitialized) {
            broadcastCurrentState();
        }
    }

    public State getState() {
        return mExternalState;
    }

    public IccFileHandler getIccFileHandler() {
        if (mUiccApplication != null) {
            return mUiccApplication.getIccFileHandler();
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

    public String getCardType() {
        if (mUiccApplication != null) {
            return mUiccApplication.getCardType();
        }
        return "UNKNOWN";
    }

    protected void log(String msg) {
        if (DBG) Log.d(LOG_TAG, msg);
    }

    protected void loge(String msg) {
        Log.e(LOG_TAG, msg);
    }
}
