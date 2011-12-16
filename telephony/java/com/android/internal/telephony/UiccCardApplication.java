/*
 * Copyright (C) 2006 The Android Open Source Project
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

import com.android.internal.telephony.IccCardApplicationStatus.AppState;
import com.android.internal.telephony.IccCardApplicationStatus.AppType;
import com.android.internal.telephony.IccCardApplicationStatus.PersoSubState;
import com.android.internal.telephony.IccCardStatus.PinState;
import com.android.internal.telephony.cdma.RuimFileHandler;
import com.android.internal.telephony.cdma.RuimRecords;
import com.android.internal.telephony.gsm.SIMFileHandler;
import com.android.internal.telephony.gsm.SIMRecords;
import com.android.internal.telephony.ims.IsimFileHandler;
import com.android.internal.telephony.ims.IsimUiccRecords;

/**
 * {@hide}
 */
public class UiccCardApplication {
    private static final String LOG_TAG = "RIL_UiccCardApplication";
    private static final boolean DBG = true;

    private static final int EVENT_PIN1PUK1_DONE = 1;
    private static final int EVENT_CHANGE_FACILITY_LOCK_DONE = 2;
    private static final int EVENT_CHANGE_PIN1_DONE = 3;
    private static final int EVENT_CHANGE_PIN2_DONE = 4;
    private static final int EVENT_QUERY_FACILITY_FDN_DONE = 5;
    private static final int EVENT_CHANGE_FACILITY_FDN_DONE = 6;
    private static final int EVENT_PIN2PUK2_DONE = 7;

    private UiccCard mUiccCard; //parent
    private AppState      mAppState;
    private AppType       mAppType;
    private PersoSubState mPersoSubState;
    private String        mAid;
    private String        mAppLabel;
    private boolean       mPin1Replaced;
    private PinState      mPin1State;
    private PinState      mPin2State;
    private boolean       mIccFdnEnabled = false; // Default to disabled.
    private boolean       mIccFdnAvailable = true; // Default is enabled.
    private boolean mDesiredFdnEnabled;
    private int mPin1RetryCount = -1;
    private int mPin2RetryCount = -1;

    private CommandsInterface mCi;
    private Context mContext;
    private IccRecords mIccRecords;
    private IccFileHandler mIccFh;

    private boolean mDestroyed = false; //set to true once this App is commanded to be disposed of.

    private RegistrantList mReadyRegistrants = new RegistrantList();
    private RegistrantList mPinLockedRegistrants = new RegistrantList();
    private RegistrantList mPersoLockedRegistrants = new RegistrantList();

    UiccCardApplication(UiccCard uiccCard, IccCardApplicationStatus as, Context c, CommandsInterface ci) {
        log("Creating UiccApp: " + as);
        mUiccCard = uiccCard;
        mAppState = as.app_state;
        mAppType = as.app_type;
        mPersoSubState = as.perso_substate;
        mAid = as.aid;
        mAppLabel = as.app_label;
        mPin1Replaced = (as.pin1_replaced != 0);
        mPin1State = as.pin1;
        mPin2State = as.pin2;

        mContext = c;
        mCi = ci;

        mIccFh = createIccFileHandler(as.app_type);
        mIccRecords = createIccRecords(as.app_type, mContext, mCi);
        if (mAppState == AppState.APPSTATE_READY) {
            queryFdn();
        }
    }

    void update (IccCardApplicationStatus as, Context c, CommandsInterface ci) {
        if (mDestroyed) {
            loge("Application updated after destroyed! Fix me!");
            return;
        }

        log(mAppType + " update. New " + as);
        mContext = c;
        mCi = ci;
        AppType oldAppType = mAppType;
        AppState oldAppState = mAppState;
        PersoSubState oldPersoSubState = mPersoSubState;
        mAppType = as.app_type;
        mAppState = as.app_state;
        mPersoSubState = as.perso_substate;
        mAid = as.aid;
        mAppLabel = as.app_label;
        mPin1Replaced = (as.pin1_replaced != 0);
        mPin1State = as.pin1;
        mPin2State = as.pin2;

        if (mAppType != oldAppType) {
            mIccFh.dispose();
            mIccRecords.dispose();
            mIccFh = createIccFileHandler(as.app_type);
            mIccRecords = createIccRecords(as.app_type, c, ci);
        }

        if (mPersoSubState != oldPersoSubState &&
                isPersoLocked()) {
                // mPersoSubState == PersoSubState.PERSOSUBSTATE_SIM_NETWORK) {
            notifyPersoLockedRegistrantsIfNeeded(null);
        }

        if (mAppState != oldAppState) {
            log(oldAppType + " changed state: " + oldAppState + " -> " + mAppState);
            // If the app state turns to APPSTATE_READY, then query FDN status,
            //as it might have failed in earlier attempt.
            if (mAppState == AppState.APPSTATE_READY) {
                queryFdn();
            }
            notifyPinLockedRegistrantsIfNeeded(null);
            notifyReadyRegistrantsIfNeeded(null);
        }
    }

    synchronized void dispose() {
        log(mAppType + " being Disposed");
        mDestroyed = true;
        if (mIccRecords != null) { mIccRecords.dispose();}
        if (mIccFh != null) { mIccFh.dispose();}
        mIccRecords = null;
        mIccFh = null;
    }

    private IccRecords createIccRecords(AppType type, Context c, CommandsInterface ci) {
        if (type == AppType.APPTYPE_USIM || type == AppType.APPTYPE_SIM) {
            return new SIMRecords(this, c, ci);
        } else if (type == AppType.APPTYPE_RUIM || type == AppType.APPTYPE_CSIM){
            return new RuimRecords(this, c, ci);
        } else if (type == AppType.APPTYPE_ISIM) {
            return new IsimUiccRecords(this, c, ci);
        } else {
            // Unknown app type (maybe detection is still in progress)
            return null;
        }
    }

    private IccFileHandler createIccFileHandler(AppType type) {
        switch (type) {
            case APPTYPE_SIM:
                return new SIMFileHandler(this, mAid, mCi);
            case APPTYPE_RUIM:
                return new RuimFileHandler(this, mAid, mCi);
            case APPTYPE_USIM:
                return new UsimFileHandler(this, mAid, mCi);
            case APPTYPE_CSIM:
                return new CsimFileHandler(this, mAid, mCi);
            case APPTYPE_ISIM:
                return new IsimFileHandler(this, mAid, mCi);
            default:
                return null;
        }
    }

    private void queryFdn() {
        //This shouldn't change run-time. So needs to be called only once.
        int serviceClassX;

        serviceClassX = CommandsInterface.SERVICE_CLASS_VOICE +
                        CommandsInterface.SERVICE_CLASS_DATA +
                        CommandsInterface.SERVICE_CLASS_FAX;
        mCi.queryFacilityLockForApp (
                CommandsInterface.CB_FACILITY_BA_FD, "", serviceClassX, mAid,
                mHandler.obtainMessage(EVENT_QUERY_FACILITY_FDN_DONE));
    }
    /**
     * Interperate EVENT_QUERY_FACILITY_LOCK_DONE
     * @param ar is asyncResult of Query_Facility_Locked
     */
    private void onQueryFdnEnabled(AsyncResult ar) {
        if(ar.exception != null) {
            log("Error in querying facility lock:" + ar.exception);
            return;
        }

        int[] ints = (int[])ar.result;
        if (ints.length != 0) {
            //0 - Available & Disabled, 1-Available & Enabled, 2-Unavailable.
            if (ints[0] == 2) {
                mIccFdnEnabled = false;
                mIccFdnAvailable = false;
            } else {
                mIccFdnEnabled = (ints[0] == 1) ? true : false;
                mIccFdnAvailable = true;
            }
            log("Query facility FDN : FDN service available: "+ mIccFdnAvailable
                    +" enabled: "  + mIccFdnEnabled);
        } else {
            loge("Bogus facility lock response");
        }
    }

    /**
     * Parse the error response to obtain No of attempts remaining to unlock PIN1/PUK1
     */
    private void parsePinPukErrorResult(AsyncResult ar, boolean isPin1) {
        int[] intArray = (int[]) ar.result;
        int length = intArray.length;
        mPin1RetryCount = -1;
        mPin2RetryCount = -1;
        if (length > 0) {
            if (isPin1) {
                mPin1RetryCount = intArray[0];
            } else {
                mPin2RetryCount = intArray[0];
            }
        }
    }

    private Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg){
            AsyncResult ar;

            if (mDestroyed) {
                loge("Received message " + msg + "[" + msg.what
                        + "] while being destroyed. Ignoring.");
                return;
            }

            switch (msg.what) {
                case EVENT_PIN1PUK1_DONE:
                case EVENT_PIN2PUK2_DONE:
                    // a PIN/PUK/PIN2/PUK2/Network Personalization
                    // request has completed. ar.userObj is the response Message
                    ar = (AsyncResult)msg.obj;
                    // TODO should abstract these exceptions
                    if ((ar.exception != null) && (ar.result != null)) {
                        if (msg.what == EVENT_PIN1PUK1_DONE) {
                            parsePinPukErrorResult(ar, true);
                        } else {
                            parsePinPukErrorResult(ar, false);
                        }
                    }
                    AsyncResult.forMessage(((Message)ar.userObj)).exception
                                                        = ar.exception;
                    ((Message)ar.userObj).sendToTarget();
                    break;
                case EVENT_QUERY_FACILITY_FDN_DONE:
                    ar = (AsyncResult)msg.obj;
                    onQueryFdnEnabled(ar);
                    break;
                case EVENT_CHANGE_FACILITY_LOCK_DONE:
                    ar = (AsyncResult)msg.obj;
                    if (ar.exception == null) {
                        log("EVENT_CHANGE_FACILITY_LOCK_DONE ");
                    } else {
                        if (ar.result != null) {
                            parsePinPukErrorResult(ar, true);
                        }
                        loge("Error change facility sim lock with exception "
                            + ar.exception);
                    }
                    AsyncResult.forMessage(((Message)ar.userObj)).exception
                                                        = ar.exception;
                    ((Message)ar.userObj).sendToTarget();
                    break;
                case EVENT_CHANGE_FACILITY_FDN_DONE:
                    ar = (AsyncResult)msg.obj;

                    if (ar.exception == null) {
                        mIccFdnEnabled = mDesiredFdnEnabled;
                        log("EVENT_CHANGE_FACILITY_FDN_DONE: " +
                                "mIccFdnEnabled= " + mIccFdnEnabled);
                    } else {
                        if (ar.result != null) {
                            parsePinPukErrorResult(ar, false);
                        }
                        loge("Error change facility fdn with exception "
                                + ar.exception);
                    }
                    AsyncResult.forMessage(((Message)ar.userObj)).exception
                                                        = ar.exception;
                    ((Message)ar.userObj).sendToTarget();
                    break;
                case EVENT_CHANGE_PIN1_DONE:
                    ar = (AsyncResult)msg.obj;
                    if(ar.exception != null) {
                        loge("Error in change icc app password with exception"
                            + ar.exception);
                        if (ar.result != null) {
                            parsePinPukErrorResult(ar, true);
                        }
                    }
                    AsyncResult.forMessage(((Message)ar.userObj)).exception
                                                        = ar.exception;
                    ((Message)ar.userObj).sendToTarget();
                    break;
                case EVENT_CHANGE_PIN2_DONE:
                    ar = (AsyncResult)msg.obj;
                    if(ar.exception != null) {
                        loge("Error in change icc app password with exception"
                            + ar.exception);
                        if (ar.result != null) {
                            parsePinPukErrorResult(ar, false);
                        }
                    }
                    AsyncResult.forMessage(((Message)ar.userObj)).exception
                                                        = ar.exception;
                    ((Message)ar.userObj).sendToTarget();
                    break;
                default:
                    loge("Unknown Event " + msg.what);
            }
        }
    };

    public void registerForReady(Handler h, int what, Object obj) {
        Registrant r = new Registrant (h, what, obj);
        mReadyRegistrants.add(r);
        notifyReadyRegistrantsIfNeeded(r);
    }

    public void unregisterForReady(Handler h) {
        mReadyRegistrants.remove(h);
    }

    /**
     * Notifies handler of any transition into State.isPinLocked()
     */
    public void registerForLocked(Handler h, int what, Object obj) {
        Registrant r = new Registrant (h, what, obj);
        mPinLockedRegistrants.add(r);
        notifyPinLockedRegistrantsIfNeeded(r);
    }

    public void unregisterForLocked(Handler h) {
        mPinLockedRegistrants.remove(h);
    }

    /**
     * Notifies handler of any transition into State.PERSO_LOCKED
     */
    public void registerForPersoLocked(Handler h, int what, Object obj) {
        Registrant r = new Registrant (h, what, obj);
        mPersoLockedRegistrants.add(r);
        notifyPersoLockedRegistrantsIfNeeded(r);
    }

    public void unregisterForPersoLocked(Handler h) {
        mPersoLockedRegistrants.remove(h);
    }

    /** Notifies specified registrant.
     *
     * @param r Registrant to be notified. If null - all registrants will be notified
     */
    private synchronized void notifyReadyRegistrantsIfNeeded(Registrant r) {
        if (mDestroyed) {
            return;
        }
        if (mAppState == AppState.APPSTATE_READY) {
            if (mPin1State == PinState.PINSTATE_ENABLED_NOT_VERIFIED || mPin1State == PinState.PINSTATE_ENABLED_BLOCKED || mPin1State == PinState.PINSTATE_ENABLED_PERM_BLOCKED) {
                loge("Sanity check failed! APPSTATE is ready while PIN1 is not verified!!!");
                //Don't notify if application is in insane state
                return;
            }
            if (r == null) {
                log("Notifying registrants: READY");
                mReadyRegistrants.notifyRegistrants();
            } else {
                log("Notifying 1 registrant: READY");
                r.notifyRegistrant(new AsyncResult(null, null, null));
            }
        }
    }

    /** Notifies specified registrant.
     *
     * @param r Registrant to be notified. If null - all registrants will be notified
     */
    private synchronized void notifyPinLockedRegistrantsIfNeeded(Registrant r) {
        if (mDestroyed) {
            return;
        }

        if (mAppState == AppState.APPSTATE_PIN ||
                mAppState == AppState.APPSTATE_PUK) {
            if (mPin1State == PinState.PINSTATE_ENABLED_VERIFIED || mPin1State == PinState.PINSTATE_DISABLED) {
                loge("Sanity check failed! APPSTATE is locked while PIN1 is not!!!");
                //Don't notify if application is in insane state
                return;
            }
            if (r == null) {
                log("Notifying registrants: LOCKED");
                mPinLockedRegistrants.notifyRegistrants();
            } else {
                log("Notifying 1 registrant: LOCKED");
                r.notifyRegistrant(new AsyncResult(null, null, null));
            }
        }
    }

    /** Notifies specified registrant.
     *
     * @param r Registrant to be notified. If null - all registrants will be notified
     */
    private synchronized void notifyPersoLockedRegistrantsIfNeeded(Registrant r) {
        if (mDestroyed) {
            return;
        }

        if (mAppState == AppState.APPSTATE_SUBSCRIPTION_PERSO &&
                isPersoLocked()) {
                // mPersoSubState == PersoSubState.PERSOSUBSTATE_SIM_NETWORK) {
            AsyncResult ar = new AsyncResult(null, mPersoSubState.ordinal(), null);
            if (r == null) {
                log("Notifying registrants: PERSO_LOCKED");
                mPersoLockedRegistrants.notifyRegistrants(ar);
            } else {
                log("Notifying 1 registrant: PERSO_LOCKED");
                r.notifyRegistrant(ar);
            }
        }
    }

    public AppState getState() {
        return mAppState;
    }

    public AppType getType() {
        return mAppType;
    }

    public PersoSubState getPersoSubState() {
        return mPersoSubState;
    }

    public String getAid() {
        return mAid;
    }

    public String getAppLabel() {
        return mAppLabel;
    }

    public PinState getPin1State() {
        if (mPin1Replaced) {
            return mUiccCard.getUniversalPinState();
        }
        return mPin1State;
    }

    public IccFileHandler getIccFileHandler() {
        return mIccFh;
    }

    public IccRecords getIccRecords() {
        return mIccRecords;
    }

    public UiccCard getCard() {
        return mUiccCard;
    }

    public boolean isPersoLocked() {
        switch (mPersoSubState) {
            case PERSOSUBSTATE_UNKNOWN:
            case PERSOSUBSTATE_IN_PROGRESS:
            case PERSOSUBSTATE_READY:
                return false;
            default:
                return true;
        }
    }

    /**
     * Supply the ICC PIN to the ICC
     *
     * When the operation is complete, onComplete will be sent to its
     * Handler.
     *
     * onComplete.obj will be an AsyncResult
     *
     * ((AsyncResult)onComplete.obj).exception == null on success
     * ((AsyncResult)onComplete.obj).exception != null on fail
     *
     * If the supplied PIN is incorrect:
     * ((AsyncResult)onComplete.obj).exception != null
     * && ((AsyncResult)onComplete.obj).exception
     *       instanceof com.android.internal.telephony.gsm.CommandException)
     * && ((CommandException)(((AsyncResult)onComplete.obj).exception))
     *          .getCommandError() == CommandException.Error.PASSWORD_INCORRECT
     *
     *
     */
    public void supplyPin (String pin, Message onComplete) {
        mCi.supplyIccPinForApp(pin, mAid, mHandler.obtainMessage(EVENT_PIN1PUK1_DONE, onComplete));
    }

    public void supplyPuk (String puk, String newPin, Message onComplete) {
        mCi.supplyIccPukForApp(puk, newPin, mAid,
                mHandler.obtainMessage(EVENT_PIN1PUK1_DONE, onComplete));
    }

    public void supplyPin2 (String pin2, Message onComplete) {
        mCi.supplyIccPin2ForApp(pin2, mAid,
                mHandler.obtainMessage(EVENT_PIN2PUK2_DONE, onComplete));
    }

    public void supplyPuk2 (String puk2, String newPin2, Message onComplete) {
        mCi.supplyIccPuk2ForApp(puk2, newPin2, mAid,
                mHandler.obtainMessage(EVENT_PIN2PUK2_DONE, onComplete));
    }

    public void supplyDepersonalization (String pin, int type, Message onComplete) {
        log("Network Despersonalization: pin = " + pin + " , type = " + type);
        mCi.supplyDepersonalization(pin, type, onComplete);
    }

    /**
     * Check whether ICC pin lock is enabled
     * This is a sync call which returns the cached pin enabled state
     *
     * @return true for ICC locked enabled
     *         false for ICC locked disabled
     */
    public boolean getIccLockEnabled() {
        // Use getPin1State to take into account pin1Replaced flag
        PinState pinState = getPin1State();
        return pinState == PinState.PINSTATE_ENABLED_NOT_VERIFIED ||
               pinState == PinState.PINSTATE_ENABLED_VERIFIED ||
               pinState == PinState.PINSTATE_ENABLED_BLOCKED ||
               pinState == PinState.PINSTATE_ENABLED_PERM_BLOCKED;
    }

    /**
     * Check whether ICC fdn (fixed dialing number) is enabled
     * This is a sync call which returns the cached pin enabled state
     *
     * @return true for ICC fdn enabled
     *         false for ICC fdn disabled
     */
     public boolean getIccFdnEnabled() {
        return mIccFdnEnabled;
     }

    /**
     * Check whether fdn (fixed dialing number) service is available.
     * @return true if ICC fdn service available
     *         false if ICC fdn service not available
     */
    public boolean getIccFdnAvailable() {
        return mIccFdnAvailable;
    }

     /**
     * @return No. of Attempts remaining to unlock PIN1/PUK1
     */
     public int getIccPin1RetryCount() {
         return mPin1RetryCount;
     }

     /**
      * @return No. of Attempts remaining to unlock PIN2/PUK2
     */
     public int getIccPin2RetryCount() {
         return mPin2RetryCount;
     }

     /**
      * Set the ICC pin lock enabled or disabled
      * When the operation is complete, onComplete will be sent to its handler
      *
      * @param enabled "true" for locked "false" for unlocked.
      * @param password needed to change the ICC pin state, aka. Pin1
      * @param onComplete
      *        onComplete.obj will be an AsyncResult
      *        ((AsyncResult)onComplete.obj).exception == null on success
      *        ((AsyncResult)onComplete.obj).exception != null on fail
      */
     public void setIccLockEnabled (boolean enabled,
             String password, Message onComplete) {
         int serviceClassX;
         serviceClassX = CommandsInterface.SERVICE_CLASS_VOICE +
                 CommandsInterface.SERVICE_CLASS_DATA +
                 CommandsInterface.SERVICE_CLASS_FAX;

         mCi.setFacilityLockForApp(CommandsInterface.CB_FACILITY_BA_SIM,
                 enabled, password, serviceClassX, mAid,
                 mHandler.obtainMessage(EVENT_CHANGE_FACILITY_LOCK_DONE, onComplete));
     }

     /**
      * Set the ICC fdn enabled or disabled
      * When the operation is complete, onComplete will be sent to its handler
      *
      * @param enabled "true" for locked "false" for unlocked.
      * @param password needed to change the ICC fdn enable, aka Pin2
      * @param onComplete
      *        onComplete.obj will be an AsyncResult
      *        ((AsyncResult)onComplete.obj).exception == null on success
      *        ((AsyncResult)onComplete.obj).exception != null on fail
      */
     public void setIccFdnEnabled (boolean enabled,
             String password, Message onComplete) {
         int serviceClassX;
         mDesiredFdnEnabled = enabled;
         serviceClassX = CommandsInterface.SERVICE_CLASS_VOICE +
                 CommandsInterface.SERVICE_CLASS_DATA +
                 CommandsInterface.SERVICE_CLASS_FAX +
                 CommandsInterface.SERVICE_CLASS_SMS;

         mDesiredFdnEnabled = enabled;

         mCi.setFacilityLockForApp(CommandsInterface.CB_FACILITY_BA_FD,
                 enabled, password, serviceClassX, mAid,
                 mHandler.obtainMessage(EVENT_CHANGE_FACILITY_FDN_DONE, onComplete));
     }

     /**
      * Change the ICC password used in ICC pin lock
      * When the operation is complete, onComplete will be sent to its handler
      *
      * @param oldPassword is the old password
      * @param newPassword is the new password
      * @param onComplete
      *        onComplete.obj will be an AsyncResult
      *        ((AsyncResult)onComplete.obj).exception == null on success
      *        ((AsyncResult)onComplete.obj).exception != null on fail
      */
     public void changeIccLockPassword(String oldPassword, String newPassword,
             Message onComplete) {
         log("Change Pin1 old: " + oldPassword + " new: " + newPassword);
         mCi.changeIccPinForApp(oldPassword, newPassword, mAid,
                 onComplete);

     }

    /**
     * Change the ICC password used in ICC fdn enable
     * When the operation is complete, onComplete will be sent to its handler
     *
     * @param oldPassword is the old password
     * @param newPassword is the new password
     * @param onComplete
     *        onComplete.obj will be an AsyncResult
     *        ((AsyncResult)onComplete.obj).exception == null on success
     *        ((AsyncResult)onComplete.obj).exception != null on fail
     */
    public void changeIccFdnPassword(String oldPassword, String newPassword,
            Message onComplete) {
        log("Change Pin2 old: " + oldPassword + " new: " + newPassword);
        mCi.changeIccPin2ForApp(oldPassword, newPassword, mAid,
                onComplete);
    }

    /**
     * @return true if ICC card is PIN2 blocked
     */
    public boolean getIccPin2Blocked() {
        return mPin2State == PinState.PINSTATE_ENABLED_BLOCKED;
    }

    /**
     * @return true if ICC card is PUK2 blocked
     */
    public boolean getIccPuk2Blocked() {
        return mPin2State == PinState.PINSTATE_ENABLED_PERM_BLOCKED;
    }

    private void log(String msg) {
        if (DBG) Log.d(LOG_TAG, msg);
    }

    private void loge(String msg) {
        Log.e(LOG_TAG, msg);
    }
}
