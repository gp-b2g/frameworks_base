/*
 * Copyright (C) 2006 The Android Open Source Project
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

import android.os.Handler;
import android.os.Message;
import android.os.SystemProperties;

/**
 * {@hide}
 */
public interface IccCard {
    /* The extra data for broacasting intent INTENT_ICC_STATE_CHANGE */
    static public final String INTENT_KEY_ICC_STATE = "ss";
    /* UNKNOWN means the ICC state is unknown */
    static public final String INTENT_VALUE_ICC_UNKNOWN = "UNKNOWN";
    /* NOT_READY means the ICC interface is not ready (eg, radio is off or powering on) */
    static public final String INTENT_VALUE_ICC_NOT_READY = "NOT_READY";
    /* ABSENT means ICC is missing */
    static public final String INTENT_VALUE_ICC_ABSENT = "ABSENT";
    /* CARD_IO_ERROR means for three consecutive times there was SIM IO error */
    static public final String INTENT_VALUE_ICC_CARD_IO_ERROR = "CARD_IO_ERROR";
    /* LOCKED means ICC is locked by pin or by network */
    static public final String INTENT_VALUE_ICC_LOCKED = "LOCKED";
    /* READY means ICC is ready to access */
    static public final String INTENT_VALUE_ICC_READY = "READY";
    /* IMSI means ICC IMSI is ready in property */
    static public final String INTENT_VALUE_ICC_IMSI = "IMSI";
    /* LOADED means all ICC records, including IMSI, are loaded */
    static public final String INTENT_VALUE_ICC_LOADED = "LOADED";
    /* The extra data for broacasting intent INTENT_ICC_STATE_CHANGE */
    static public final String INTENT_KEY_LOCKED_REASON = "reason";
    /* PIN means ICC is locked on PIN1 */
    static public final String INTENT_VALUE_LOCKED_ON_PIN = "PIN";
    /* PUK means ICC is locked on PUK1 */
    static public final String INTENT_VALUE_LOCKED_ON_PUK = "PUK";
    /* PERSO means ICC is locked on PERSONALIZATION */
    static public final String INTENT_VALUE_LOCKED_PERSO = "PERSO";
    /* PERM_DISABLED means ICC is permanently disabled due to puk fails */
    static public final String INTENT_VALUE_ABSENT_ON_PERM_DISABLED = "PERM_DISABLED";

    /*
      UNKNOWN is a transient state, for example, after uesr inputs ICC pin under
      PIN_REQUIRED state, the query for ICC status returns UNKNOWN before it
      turns to READY
     */
    public enum State {
        UNKNOWN,
        ABSENT,
        PIN_REQUIRED,
        PUK_REQUIRED,
        PERSO_LOCKED,
        READY,
        NOT_READY,
        PERM_DISABLED,
        CARD_IO_ERROR;

        public boolean isPinLocked() {
            return ((this == PIN_REQUIRED) || (this == PUK_REQUIRED));
        }

        public boolean iccCardExist() {
            return ((this == PIN_REQUIRED) || (this == PUK_REQUIRED)
                    || (this == PERSO_LOCKED) || (this == READY)
                    || (this == PERM_DISABLED) || (this == CARD_IO_ERROR));
        }
        
        public String getIntentString() {
            switch (this) {
                case ABSENT: return INTENT_VALUE_ICC_ABSENT;
                case PIN_REQUIRED: return INTENT_VALUE_ICC_LOCKED;
                case PUK_REQUIRED: return INTENT_VALUE_ICC_LOCKED;
                case PERSO_LOCKED: return INTENT_VALUE_ICC_LOCKED;
                case READY: return INTENT_VALUE_ICC_READY;
                case NOT_READY: return INTENT_VALUE_ICC_NOT_READY;
                case PERM_DISABLED: return INTENT_VALUE_ICC_LOCKED;
                case CARD_IO_ERROR: return INTENT_VALUE_ICC_CARD_IO_ERROR;
                default: return INTENT_VALUE_ICC_UNKNOWN;
            }
        }

        /**
         * Locked state have a reason (PIN, PUK, NETWORK, PERM_DISABLED, CARD_IO_ERROR)
         * @return reason
         */
        public String getReason() {
            switch (this) {
                case PIN_REQUIRED: return INTENT_VALUE_LOCKED_ON_PIN;
                case PUK_REQUIRED: return INTENT_VALUE_LOCKED_ON_PUK;
                case PERSO_LOCKED: return INTENT_VALUE_LOCKED_PERSO;
                case PERM_DISABLED: return INTENT_VALUE_ABSENT_ON_PERM_DISABLED;
                case CARD_IO_ERROR: return INTENT_VALUE_ICC_CARD_IO_ERROR;
                default: return null;
            }
        }
    }

    public State getState();
    public IccRecords getIccRecords();
    public IccFileHandler getIccFileHandler();

    /**
     * Notifies handler of any transition into State.ABSENT
     */
    public void registerForAbsent(Handler h, int what, Object obj);
    public void unregisterForAbsent(Handler h);

    /**
     * Notifies handler of any transition into State.PERSO_LOCKED
     */
    public void registerForPersoLocked(Handler h, int what, Object obj);
    public void unregisterForPersoLocked(Handler h);

    /**
     * Notifies handler of any transition into State.isPinLocked()
     */
    public void registerForLocked(Handler h, int what, Object obj);
    public void unregisterForLocked(Handler h);

    public void registerForReady(Handler h, int what, Object obj);
    public void unregisterForReady(Handler h);

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

    public void supplyPin (String pin, Message onComplete);
    public void supplyPuk (String puk, String newPin, Message onComplete);
    public void supplyPin2 (String pin2, Message onComplete);
    public void supplyPuk2 (String puk2, String newPin2, Message onComplete);
    public void supplyDepersonalization (String pin, int type, Message onComplete);

    /**
     * Check whether fdn (fixed dialing number) service is available.
     * @return true if ICC fdn service available
     *         false if ICC fdn service not available
    */
    public boolean getIccFdnAvailable();

    /**
     * Check whether ICC pin lock is enabled
     * This is a sync call which returns the cached pin enabled state
     *
     * @return true for ICC locked enabled
     *         false for ICC locked disabled
     */
    public boolean getIccLockEnabled();

    /**
     * Check whether ICC fdn (fixed dialing number) is enabled
     * This is a sync call which returns the cached pin enabled state
     *
     * @return true for ICC fdn enabled
     *         false for ICC fdn disabled
     */
     public boolean getIccFdnEnabled();

     /**
     * @return No. of Attempts remaining to unlock PIN1/PUK1
     */
    public int getIccPin1RetryCount();

    /**
     * @return No. of Attempts remaining to unlock PIN2/PUK2
     */
    public int getIccPin2RetryCount();


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
             String password, Message onComplete);

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
             String password, Message onComplete);

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
             Message onComplete);

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
             Message onComplete);

    /**
     * Returns service provider name stored in ICC card.
     * If there is no service provider name associated or the record is not
     * yet available, null will be returned <p>
     *
     * Please use this value when display Service Provider Name in idle mode <p>
     *
     * Usage of this provider name in the UI is a common carrier requirement.
     *
     * Also available via Android property "gsm.sim.operator.alpha"
     *
     * @return Service Provider Name stored in ICC card
     *         null if no service provider name associated or the record is not
     *         yet available
     *
     */
    public String getServiceProviderName ();
    public State getIccCardState();
    public boolean isApplicationOnIcc(IccCardApplicationStatus.AppType type);

    /**
     * @return true if a ICC card is present
     */
    public boolean hasIccCard();

    /**
     * @return true if ICC card is PIN2 blocked
     */
    public boolean getIccPin2Blocked();

    /**
     * @return true if ICC card is PUK2 blocked
     */
    public boolean getIccPuk2Blocked();

}
