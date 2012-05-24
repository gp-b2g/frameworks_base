/*
 * Copyright (C) 2006 The Android Open Source Project
 * Copyright (c) 2012, Code Aurora Forum. All rights reserved.
 * Not a Contribution.
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

import com.android.internal.telephony.*;
import android.content.Context;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.Registrant;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.util.Log;
import android.text.TextUtils;

import android.telephony.PhoneNumberUtils;
import android.telephony.ServiceState;
import com.android.internal.telephony.TelephonyProperties;
import com.android.internal.telephony.RILConstants;
import com.android.internal.telephony.Connection.DisconnectCause;
import com.android.internal.telephony.cdma.CdmaCallWaitingNotification;
import com.android.internal.telephony.cdma.CdmaSubscriptionSourceManager;

/**
 * {@hide}
 */
public class ConnectionBase extends Connection {
    static final String LOG_TAG = "CONNECTIONBASE";

    //***** Instance Variables

    public CallTracker owner;
    Call parent;


    public String address;             // MAY BE NULL!!!
    String dialString;          // outgoing calls only
    String postDialString;      // outgoing calls only
    public boolean isIncoming;
    boolean disconnected;
    String cnapName;
    public int index;          // index in CallTracker.connections[], -1 if unassigned
    // The GSM index is 1 + this

    /*
     * These time/timespan values are based on System.currentTimeMillis(),
     * i.e., "wall clock" time.
     */
    long createTime;
    public long connectTime;
    long disconnectTime;

    /*
     * These time/timespan values are based on SystemClock.elapsedRealTime(),
     * i.e., time since boot.  They are appropriate for comparison and
     * calculating deltas.
     */
    long connectTimeReal;
    long duration;
    long holdingStartTime;  // The time when the Connection last transitioned
                            // into HOLDING

    int nextPostDialChar;       // index into postDialString

    public DisconnectCause cause = DisconnectCause.NOT_DISCONNECTED;
    PostDialState postDialState = PostDialState.NOT_STARTED;
    int numberPresentation = Connection.PRESENTATION_ALLOWED;
    int cnapNamePresentation  = Connection.PRESENTATION_ALLOWED;//only used for cdma
    UUSInfo uusInfo = null; // only used for GSM , move it to constructor
    public CallDetails callDetails = null;


    Handler h;

    private PowerManager.WakeLock mPartialWakeLock;

    //***** Event Constants
    static final int EVENT_DTMF_DONE = 1;
    static final int EVENT_PAUSE_DONE = 2;
    static final int EVENT_NEXT_POST_DIAL = 3;
    static final int EVENT_WAKE_LOCK_TIMEOUT = 4;

    //***** Constants
    static final int WAKE_LOCK_TIMEOUT_MILLIS = 60*1000;
    static final int PAUSE_DELAY_MILLIS = 2 * 1000;//cdma
    //static final int PAUSE_DELAY_MILLIS = 3 * 1000;//gsm, check specs for diffference between gsm n cdma

    //***** Inner Classes

    class MyHandler extends Handler {
        MyHandler(Looper l) {super(l);}

        public void
        handleMessage(Message msg) {

            switch (msg.what) {
                case EVENT_NEXT_POST_DIAL:
                case EVENT_DTMF_DONE:
                case EVENT_PAUSE_DONE:
                    processNextPostDialChar();
                    break;
                case EVENT_WAKE_LOCK_TIMEOUT:
                    releaseWakeLock();
                    break;
            }
        }
    }

    //***** Constructors

    /** This is probably an MT call that we first saw in a CLCC response */
    /*package*/
    public ConnectionBase (Context context, DriverCall dc, CallTracker ct, int index) {
        createWakeLock(context);
        acquireWakeLock();

        owner = ct;
        h = new MyHandler(owner.getLooper());

        address = dc.number;

        isIncoming = dc.isMT;
        createTime = System.currentTimeMillis();
        cnapName = dc.name; //TBD if present check
        cnapNamePresentation = dc.namePresentation;
        numberPresentation = dc.numberPresentation;

        if (dc.uusInfo!= null ) // only for Gsm
            uusInfo = dc.uusInfo;
        if (dc.callDetails != null)
            callDetails = dc.callDetails;

        if ((callDetails != null) && (callDetails.call_domain == CallDetails.RIL_CALL_DOMAIN_PS)) {
            parent = imsParentFromDCState(dc.state); //parent = call from ImsPhone
        }else {
            parent = parentFromDCState(dc.state); // parent = call from CdmaPhone
        }
        this.index = index;
        if (parent != null ) parent.attach(this, dc);
        else {
            Log.e(LOG_TAG, "This ConnectionBase does not have a parent call");
        }
    }

    /** This is an MO call/three way call, created when dialing */
    /*package*/
    public ConnectionBase(Context context, String dialString, CallTracker ct, Call parent) {
        createWakeLock(context);
        acquireWakeLock();

        owner = ct;
        h = new MyHandler(owner.getLooper());

        this.dialString = dialString;
        this.address = PhoneNumberUtils.extractNetworkPortionAlt(dialString);
        this.postDialString = PhoneNumberUtils.extractPostDialPortion(dialString);

        index = -1;

        isIncoming = false;
        cnapName = null;
        createTime = System.currentTimeMillis();

        if (parent != null) {
            this.parent = parent;

            //for the cdma three way call case, do not change parent state
            // pass remote caller id in cdma 3 way call only
            if (parent.state == Call.State.ACTIVE) {
                cnapNamePresentation = Connection.PRESENTATION_ALLOWED;
                numberPresentation = Connection.PRESENTATION_ALLOWED;
                parent.attachFake(this, Call.State.ACTIVE);
            } else {//MO call for Gsm & Cdma, set state to dialing
                parent.attachFake(this, Call.State.DIALING);
            }
        }
    }

    /** This is an MO call/three way call, created when dialing */
    /*package*/
    public ConnectionBase(Context context, String dialString, CallTracker ct, Call parent,
            CallDetails moCallDetails) {
        createWakeLock(context);
        acquireWakeLock();

        owner = ct;
        h = new MyHandler(owner.getLooper());

        this.dialString = dialString;
        if ((moCallDetails != null)
                && (moCallDetails.call_domain == CallDetails.RIL_CALL_DOMAIN_PS))
            this.address = dialString;
        else
            this.address = PhoneNumberUtils.extractNetworkPortionAlt(dialString);
        this.postDialString = PhoneNumberUtils.extractPostDialPortion(dialString);

        index = -1;

        isIncoming = false;
        cnapName = null;
        createTime = System.currentTimeMillis();
        callDetails = moCallDetails;

        if (parent != null) {
            this.parent = parent;

            //for the cdma three way call case, do not change parent state
            // pass remote caller id in cdma 3 way call only
            if (parent.state == Call.State.ACTIVE) {
                cnapNamePresentation = Connection.PRESENTATION_ALLOWED;
                numberPresentation = Connection.PRESENTATION_ALLOWED;
                parent.attachFake(this, Call.State.ACTIVE);
            } else {//MO call for Gsm & Cdma, set state to dialing
                parent.attachFake(this, Call.State.DIALING);
            }
        }
    }

    /** This is a Call waiting call for cdma*/
    public ConnectionBase(Context context, CdmaCallWaitingNotification cw, CallTracker ct,
            Call parent) {
        createWakeLock(context);
        acquireWakeLock();

        owner = ct;
        h = new MyHandler(owner.getLooper());
        address = cw.number;
        numberPresentation = cw.numberPresentation;
        cnapName = cw.name;
        cnapNamePresentation = cw.namePresentation;
        index = -1;
        isIncoming = true;
        createTime = System.currentTimeMillis();
        connectTime = 0;
        this.parent = parent;
        parent.attachFake(this, Call.State.WAITING);
    }

    /** This is a Call waiting call for cdma*/
    public ConnectionBase(Context context, CdmaCallWaitingNotification cw, CallTracker ct,
            Call parent, CallDetails moCallDetails ) {
        createWakeLock(context);
        acquireWakeLock();

        owner = ct;
        h = new MyHandler(owner.getLooper());
        address = cw.number;
        numberPresentation = cw.numberPresentation;
        cnapName = cw.name;
        cnapNamePresentation = cw.namePresentation;
        index = -1;
        isIncoming = true;
        createTime = System.currentTimeMillis();
        connectTime = 0;
        callDetails = moCallDetails;
        this.parent = parent;
        parent.attachFake(this, Call.State.WAITING);
    }

    public void dispose() {
    }

    static boolean
    equalsHandlesNulls (Object a, Object b) {
        return (a == null) ? (b == null) : a.equals (b);
    }

    /*package*/ public boolean
    compareTo(DriverCall c) {
        // On mobile originated (MO) calls, the phone number may have changed
        // due to a SIM Toolkit call control modification.
        //
        // We assume we know when MO calls are created (since we created them)
        // and therefore don't need to compare the phone number anyway.
        if (! (isIncoming || c.isMT)) return true;

        // ... but we can compare phone numbers on MT calls, and we have
        // no control over when they begin, so we might as well

        String cAddress = PhoneNumberUtils.stringFromStringAndTOA(c.number, c.TOA);
        return isIncoming == c.isMT && equalsHandlesNulls(address, cAddress);
    }


    public String getOrigDialString(){
        return dialString;
    }

    public String getAddress() {
        return address;
    }

    public String getCnapName() {
        return cnapName;
    }

    public int getCnapNamePresentation() {
        return cnapNamePresentation;
    }

    public Call getCall() {
        return parent;
    }

    public long getCreateTime() {
        return createTime;
    }

    public long getConnectTime() {
        return connectTime;
    }

    public long getDisconnectTime() {
        return disconnectTime;
    }

    public long getDurationMillis() {
        if (connectTimeReal == 0) {
            return 0;
        } else if (duration == 0) {
            return SystemClock.elapsedRealtime() - connectTimeReal;
        } else {
            return duration;
        }
    }

    public long getHoldDurationMillis() {
        if (getState() != Call.State.HOLDING) {
            // If not holding, return 0
            return 0;
        } else {
            return SystemClock.elapsedRealtime() - holdingStartTime;
        }
    }

    public DisconnectCause getDisconnectCause() {
        return cause;
    }

    public boolean isIncoming() {
        return isIncoming;
    }

    public Call.State getState() {
        if (disconnected) {
            return Call.State.DISCONNECTED;
        } else {
            return super.getState();
        }
    }

    public void hangup() throws CallStateException {
        if (!disconnected) {
            owner.hangup(this);
        } else {
            throw new CallStateException ("disconnected");
        }
    }

    public void separate() throws CallStateException {
        if (!disconnected) {
            owner.separate(this);
        } else {
            throw new CallStateException ("disconnected");
        }
    }

    public PostDialState getPostDialState() {
        return postDialState;
    }

    public void proceedAfterWaitChar() {
        if (postDialState != PostDialState.WAIT) {
            Log.w(LOG_TAG, "ConnectionBase.proceedAfterWaitChar(): Expected "
                    + "getPostDialState() to be WAIT but was " + postDialState);
            return;
        }

        setPostDialState(PostDialState.STARTED);

        processNextPostDialChar();
    }

    public void proceedAfterWildChar(String str) {
        if (postDialState != PostDialState.WILD) {
            Log.w(LOG_TAG, "CdmaConnection.proceedAfterWaitChar(): Expected "
                    + "getPostDialState() to be WILD but was " + postDialState);
            return;
        }

        setPostDialState(PostDialState.STARTED);

        if (false) {
            boolean playedTone = false;
            int len = (str != null ? str.length() : 0);

            for (int i=0; i<len; i++) {
                char c = str.charAt(i);
                Message msg = null;

                if (i == len-1) {
                    msg = h.obtainMessage(EVENT_DTMF_DONE);
                }

                if (PhoneNumberUtils.is12Key(c)) {
                    owner.cm.sendDtmf(c, msg);
                    playedTone = true;
                }
            }

            if (!playedTone) {
                processNextPostDialChar();
            }
        } else {
            // make a new postDialString, with the wild char replacement string
            // at the beginning, followed by the remaining postDialString.

            StringBuilder buf = new StringBuilder(str);
            buf.append(postDialString.substring(nextPostDialChar));
            postDialString = buf.toString();
            nextPostDialChar = 0;
            if (Phone.DEBUG_PHONE) {
                log("proceedAfterWildChar: new postDialString is " +
                        postDialString);
            }

            processNextPostDialChar();
        }
    }

    public void cancelPostDial() {
        setPostDialState(PostDialState.CANCELLED);
    }

    /**
     * Called when this Connection is being hung up locally (eg, user pressed "end")
     * Note that at this point, the hangup request has been dispatched to the radio
     * but no response has yet been received so update() has not yet been called
     */
    public void
    onHangupLocal() {
        cause = DisconnectCause.LOCAL;
    }

    // TBD move this to phone & implement in cdma & gsmphone
    DisconnectCause
    disconnectCauseFromCode(PhoneBase phone, int causeCode) {
        /**
         * See 22.001 Annex F.4 for mapping of cause codes
         * to local tones
         */

        switch (causeCode) {
            case CallFailCause.USER_BUSY:
                return DisconnectCause.BUSY;
            case CallFailCause.NO_CIRCUIT_AVAIL:
            case CallFailCause.TEMPORARY_FAILURE:
            case CallFailCause.SWITCHING_CONGESTION:
            case CallFailCause.CHANNEL_NOT_AVAIL:
            case CallFailCause.QOS_NOT_AVAIL:
            case CallFailCause.BEARER_NOT_AVAIL:
                return DisconnectCause.CONGESTION;
            case CallFailCause.ACM_LIMIT_EXCEEDED:
                return DisconnectCause.LIMIT_EXCEEDED;
            case CallFailCause.CALL_BARRED:
                return DisconnectCause.CALL_BARRED;
            case CallFailCause.FDN_BLOCKED:
                return DisconnectCause.FDN_BLOCKED;

            case CallFailCause.UNOBTAINABLE_NUMBER:
                return DisconnectCause.UNOBTAINABLE_NUMBER;

            case CallFailCause.DIAL_MODIFIED_TO_USSD:
                return DisconnectCause.DIAL_MODIFIED_TO_USSD;
            case CallFailCause.DIAL_MODIFIED_TO_SS:
                return DisconnectCause.DIAL_MODIFIED_TO_SS;
            case CallFailCause.DIAL_MODIFIED_TO_DIAL:
                return DisconnectCause.DIAL_MODIFIED_TO_DIAL;
            case CallFailCause.CDMA_LOCKED_UNTIL_POWER_CYCLE:
                return DisconnectCause.CDMA_LOCKED_UNTIL_POWER_CYCLE;
            case CallFailCause.CDMA_DROP:
                return DisconnectCause.CDMA_DROP;
            case CallFailCause.CDMA_INTERCEPT:
                return DisconnectCause.CDMA_INTERCEPT;
            case CallFailCause.CDMA_REORDER:
                return DisconnectCause.CDMA_REORDER;
            case CallFailCause.CDMA_SO_REJECT:
                return DisconnectCause.CDMA_SO_REJECT;
            case CallFailCause.CDMA_RETRY_ORDER:
                return DisconnectCause.CDMA_RETRY_ORDER;
            case CallFailCause.CDMA_ACCESS_FAILURE:
                return DisconnectCause.CDMA_ACCESS_FAILURE;
            case CallFailCause.CDMA_PREEMPTED:
                return DisconnectCause.CDMA_PREEMPTED;
            case CallFailCause.CDMA_NOT_EMERGENCY:
                return DisconnectCause.CDMA_NOT_EMERGENCY;
            case CallFailCause.CDMA_ACCESS_BLOCKED:
                return DisconnectCause.CDMA_ACCESS_BLOCKED;
            case CallFailCause.ERROR_UNSPECIFIED:
            case CallFailCause.NORMAL_CLEARING:
            default:
                int serviceState = phone.getServiceState().getState();
                if (serviceState == ServiceState.STATE_POWER_OFF) {
                    return DisconnectCause.POWER_OFF;
                } else if (serviceState == ServiceState.STATE_OUT_OF_SERVICE
                        || serviceState == ServiceState.STATE_EMERGENCY_ONLY) {
                    return DisconnectCause.OUT_OF_SERVICE;
                } else if (causeCode == CallFailCause.NORMAL_CLEARING) {
                    return DisconnectCause.NORMAL;
                }else {
                    return DisconnectCause.ERROR_UNSPECIFIED;
                }
        }
    }

    public DisconnectCause
    disconnectCauseFromCode(int causeCode, PhoneBase phone) {
        /**
         * See 22.001 Annex F.4 for mapping of cause codes to local tones
         */

        switch (causeCode) {
            case CallFailCause.NO_CIRCUIT_AVAIL:
            case CallFailCause.TEMPORARY_FAILURE:
            case CallFailCause.SWITCHING_CONGESTION:
            case CallFailCause.CHANNEL_NOT_AVAIL:
            case CallFailCause.QOS_NOT_AVAIL:
            case CallFailCause.BEARER_NOT_AVAIL:
                return DisconnectCause.CONGESTION;
            case CallFailCause.ACM_LIMIT_EXCEEDED:
                return DisconnectCause.LIMIT_EXCEEDED;
            case CallFailCause.CALL_BARRED:
                return DisconnectCause.CALL_BARRED;
            case CallFailCause.FDN_BLOCKED:
                return DisconnectCause.FDN_BLOCKED;
            case CallFailCause.UNOBTAINABLE_NUMBER:
                return DisconnectCause.UNOBTAINABLE_NUMBER;
            case CallFailCause.DIAL_MODIFIED_TO_USSD:
                return DisconnectCause.DIAL_MODIFIED_TO_USSD;
            case CallFailCause.DIAL_MODIFIED_TO_SS:
                return DisconnectCause.DIAL_MODIFIED_TO_SS;
            case CallFailCause.DIAL_MODIFIED_TO_DIAL:
                return DisconnectCause.DIAL_MODIFIED_TO_DIAL;
            case CallFailCause.USER_BUSY:
                return DisconnectCause.BUSY;
            case CallFailCause.CDMA_LOCKED_UNTIL_POWER_CYCLE:
                return DisconnectCause.CDMA_LOCKED_UNTIL_POWER_CYCLE;
            case CallFailCause.CDMA_DROP:
                return DisconnectCause.CDMA_DROP;
            case CallFailCause.CDMA_INTERCEPT:
                return DisconnectCause.CDMA_INTERCEPT;
            case CallFailCause.CDMA_REORDER:
                return DisconnectCause.CDMA_REORDER;
            case CallFailCause.CDMA_SO_REJECT:
                return DisconnectCause.CDMA_SO_REJECT;
            case CallFailCause.CDMA_RETRY_ORDER:
                return DisconnectCause.CDMA_RETRY_ORDER;
            case CallFailCause.CDMA_ACCESS_FAILURE:
                return DisconnectCause.CDMA_ACCESS_FAILURE;
            case CallFailCause.CDMA_PREEMPTED:
                return DisconnectCause.CDMA_PREEMPTED;
            case CallFailCause.CDMA_NOT_EMERGENCY:
                return DisconnectCause.CDMA_NOT_EMERGENCY;
            case CallFailCause.CDMA_ACCESS_BLOCKED:
                return DisconnectCause.CDMA_ACCESS_BLOCKED;
            case CallFailCause.ERROR_UNSPECIFIED:
            case CallFailCause.NORMAL_CLEARING:
            default: {
                return phone.disconnectCauseFromCode(causeCode);
            }
        }
    }

    public void
    onRemoteDisconnect(int causeCode) {
        onDisconnect(disconnectCauseFromCode(causeCode, owner.phone));
    }


    public void
    onRemoteDisconnect(PhoneBase phone, int causeCode) {
        onDisconnect(disconnectCauseFromCode(causeCode, phone));
    }

    /** Called when the radio indicates the connection has been disconnected */
    public void
    onDisconnect(DisconnectCause cause) {
        this.cause = cause;

        if (!disconnected) {
            doDisconnect();
            if (false) Log.d(LOG_TAG,
                    "onDisconnect: cause=" + cause);
            owner.phone.notifyDisconnect(this);

            if (parent != null) {
                parent.connectionDisconnected(this);
            }
        }
        releaseWakeLock();
    }

    /** Called when the call waiting connection has been hung up */
   public void
    onLocalDisconnect() {
        if (!disconnected) {
            doDisconnect();
            if (false) Log.d(LOG_TAG,
                    "onLoalDisconnect" );

            if (parent != null) {
                parent.detach(this);
            }
        }
        releaseWakeLock();
    }

    // Returns true if state has changed, false if nothing changed
    public boolean
    update (DriverCall dc) {
        Call newParent;
        boolean changed = false;
        boolean wasConnectingInOrOut = isConnectingInOrOut();
        boolean wasHolding = (getState() == Call.State.HOLDING);

        if ((dc.callDetails != null) && (dc.callDetails.call_domain == CallDetails.RIL_CALL_DOMAIN_PS)) {
            newParent = imsParentFromDCState(dc.state); //parent = ImsPhone
        }else {
        newParent = parentFromDCState(dc.state); // parent = CdmaPhone
        }

        if (Phone.DEBUG_PHONE) log("parent= " +parent +", newParent= " + newParent);

        if (!equalsHandlesNulls(address, dc.number)) {
            if (Phone.DEBUG_PHONE) log("update: phone # changed!");
            address = dc.number;
            changed = true;
        }

        // A null cnapName should be the same as ""
        //TBD ensure this is a nop for Gsm
        if (TextUtils.isEmpty(dc.name)) {
            if (!TextUtils.isEmpty(cnapName)) {
                changed = true;
                cnapName = "";
            }
        } else if (!dc.name.equals(cnapName)) {
            changed = true;
            cnapName = dc.name;
        }

        if (Phone.DEBUG_PHONE) log("--dssds----"+cnapName);
        cnapNamePresentation = dc.namePresentation;
        numberPresentation = dc.numberPresentation;

        if (newParent != parent) {
            if (parent != null) {
                parent.detach(this);
            }
            newParent.attach(this, dc);
            parent = newParent;
            changed = true;
        } else {
            boolean parentStateChange;
            parentStateChange = parent.update (this, dc);
            changed = changed || parentStateChange;
        }

        /** Some state-transition events */

        if (Phone.DEBUG_PHONE) log(
                "Update, wasConnectingInOrOut=" + wasConnectingInOrOut +
                ", wasHolding=" + wasHolding +
                ", isConnectingInOrOut=" + isConnectingInOrOut() +
                ", changed=" + changed);


        if (wasConnectingInOrOut && !isConnectingInOrOut()) {
            onConnectedInOrOut();
        }

        if (changed && !wasHolding && (getState() == Call.State.HOLDING)) {
            // We've transitioned into HOLDING
            onStartedHolding();//TBD
        }

        return changed;
    }

    /**
     * Called when this Connection is in the foregroundCall
     * when a dial is initiated.
     * We know we're ACTIVE, and we know we're going to end up
     * HOLDING in the backgroundCall
     */
    public void
    fakeHoldBeforeDial() {
        if (parent != null) {
            parent.detach(this);
        }

        parent = owner.backgroundCall;
        parent.attachFake(this, Call.State.HOLDING);

        onStartedHolding();
    }

    public int
    getCDMAIndex() throws CallStateException {
        if (index >= 0) {
            return index + 1;
        } else {
            throw new CallStateException ("CDMA connection index not assigned");
        }
    }

    /**
     * An incoming or outgoing call has connected
     */
    public void
    onConnectedInOrOut() {
        connectTime = System.currentTimeMillis();
        connectTimeReal = SystemClock.elapsedRealtime();
        duration = 0;

        // bug #678474: incoming call interpreted as missed call, even though
        // it sounds like the user has picked up the call.
        if (Phone.DEBUG_PHONE) {
            log("onConnectedInOrOut: connectTime=" + connectTime);
        }

        if (!isIncoming) {
            // outgoing calls only
            processNextPostDialChar();
        } else {
            // Only release wake lock for incoming calls, for outgoing calls the wake lock
            // will be released after any pause-dial is completed
            releaseWakeLock();
        }
    }

    private void
    doDisconnect() {
       index = -1;
       disconnectTime = System.currentTimeMillis();
       duration = SystemClock.elapsedRealtime() - connectTimeReal;
       disconnected = true;
    }

    private void
    onStartedHolding() {
        holdingStartTime = SystemClock.elapsedRealtime();
    }
    /**
     * Performs the appropriate action for a post-dial char, but does not
     * notify application. returns false if the character is invalid and
     * should be ignored
     */
    private boolean
    processPostDialChar(char c) {
        if (PhoneNumberUtils.is12Key(c)) {
            owner.cm.sendDtmf(c, h.obtainMessage(EVENT_DTMF_DONE));
        } else if (c == PhoneNumberUtils.PAUSE) {
            setPostDialState(PostDialState.PAUSE);//TBD check why it is not in Gsm
            /*
             * From TS 22.101: It continues... Upon the called party answering
             * the UE shall send the DTMF digits automatically to the network
             * after a delay of 3 seconds(plus/minus 20 %). The digits shall be
             * sent according to the procedures and timing specified in 3GPP TS
             * 24.008 [13]. The first occurrence of the
             * "DTMF Control Digits Separator" shall be used by the ME to
             * distinguish between the addressing digits (i.e. the phone number)
             * and the DTMF digits. Upon subsequent occurrences of the
             * separator, the UE shall pause again for 3 seconds before sending
             * any further DTMF digits.
             */

            //TBD 3s for gsm n 2s for cdma check specs
            h.sendMessageDelayed(h.obtainMessage(EVENT_PAUSE_DONE),
                    PAUSE_DELAY_MILLIS);
        } else if (c == PhoneNumberUtils.WAIT) {
            setPostDialState(PostDialState.WAIT);
        } else if (c == PhoneNumberUtils.WILD) {
            setPostDialState(PostDialState.WILD);
        } else {
            return false;
        }

        return true;
    }

    public String getRemainingPostDialString() {
        if (postDialState == PostDialState.CANCELLED
                || postDialState == PostDialState.COMPLETE
                || postDialString == null
                || postDialString.length() <= nextPostDialChar) {
            return "";
        }

        String subStr = postDialString.substring(nextPostDialChar);

        //cdma specific - check what is done here
        if (subStr != null) {
            int wIndex = subStr.indexOf(PhoneNumberUtils.WAIT);
            int pIndex = subStr.indexOf(PhoneNumberUtils.PAUSE);

            if (wIndex > 0 && (wIndex < pIndex || pIndex <= 0)) {
                subStr = subStr.substring(0, wIndex);
            } else if (pIndex > 0) {
                subStr = subStr.substring(0, pIndex);
            }
        }
        return subStr;
    }

    public void updateParent(Call oldParent, Call newParent){
        if (newParent != oldParent) {
            if (oldParent != null) {
                oldParent.detach(this);
            }
            newParent.attachFake(this, Call.State.ACTIVE);
            parent = newParent;
        }
    }

    @Override
    protected void finalize()
    {
        /**
         * It is understood that This finializer is not guaranteed
         * to be called and the release lock call is here just in
         * case there is some path that doesn't call onDisconnect
         * and or onConnectedInOrOut.
         */
        if (mPartialWakeLock.isHeld()) {
            Log.e(LOG_TAG, "UNEXPECTED; mPartialWakeLock is held when finalizing.");
        }
        releaseWakeLock();
    }
    void processNextPostDialChar() {
        PhoneBase phone = getPhoneFromConnection();
        char c = 0;
        Registrant postDialHandler;

        if (postDialState == PostDialState.CANCELLED) {
            releaseWakeLock();
            //Log.v("CDMA", "##### processNextPostDialChar: postDialState == CANCELLED, bail");
            return;
        }

        if (postDialString == null ||
                postDialString.length() <= nextPostDialChar) {
            setPostDialState(PostDialState.COMPLETE);

            // We were holding a wake lock until pause-dial was complete, so give it up now
            releaseWakeLock();

            // notifyMessage.arg1 is 0 on complete
            c = 0;
        } else {
            boolean isValid;

            setPostDialState(PostDialState.STARTED);

            c = postDialString.charAt(nextPostDialChar++);

            isValid = processPostDialChar(c);

            if (!isValid) {
                // Will call processNextPostDialChar
                h.obtainMessage(EVENT_NEXT_POST_DIAL).sendToTarget();
                // Don't notify application
                Log.e("CDMA", "processNextPostDialChar: c=" + c + " isn't valid!");
                return;
            }
        }

        postDialHandler = phone.mPostDialHandler;

        Message notifyMessage;

        if (postDialHandler != null &&
                (notifyMessage = postDialHandler.messageForRegistrant()) != null) {
            // The AsyncResult.result is the Connection object
            PostDialState state = postDialState;
            AsyncResult ar = AsyncResult.forMessage(notifyMessage);
            ar.result = this;
            ar.userObj = state;

            // arg1 is the character that was/is being processed
            notifyMessage.arg1 = c;

            notifyMessage.sendToTarget();
        }
    }


    /** "connecting" means "has never been ACTIVE" for both incoming
     *  and outgoing calls
     */
    private boolean
    isConnectingInOrOut() {
        return parent == null || parent == getPhoneFromConnection().getRingingCall()
                || parent.state == Call.State.DIALING
                || parent.state == Call.State.ALERTING;
    }

    private Call
    parentFromDCState (DriverCall.State state) {
        switch (state) {
            case ACTIVE:
            case DIALING:
            case ALERTING:
                return owner.foregroundCall;
            //break;

            case HOLDING:
                return owner.backgroundCall;
            //break;

            case INCOMING:
            case WAITING:
                return owner.ringingCall;
            //break;

            default:
                throw new RuntimeException("illegal call state: " + state);
        }
    }

    private Call
    imsParentFromDCState(DriverCall.State state) {
        switch (state) {
            case ACTIVE:
            case DIALING:
            case ALERTING:
                return owner.imsPhone.getForegroundCall();

            case HOLDING:
                return owner.imsPhone.getBackgroundCall();

            case INCOMING:
            case WAITING:
                return owner.imsPhone.getRingingCall();

            default:
                throw new RuntimeException("illegal call state: " + state);
        }
    }

    /**
     * Set post dial state and acquire wake lock while switching to "started" or "wait"
     * state, the wake lock will be released if state switches out of "started" or "wait"
     * state or after WAKE_LOCK_TIMEOUT_MILLIS.
     * @param s new PostDialState
     */
    private void setPostDialState(PostDialState s) {
        if (s == PostDialState.STARTED ||
                s == PostDialState.PAUSE) {
            synchronized (mPartialWakeLock) {
                if (mPartialWakeLock.isHeld()) {
                    h.removeMessages(EVENT_WAKE_LOCK_TIMEOUT);
                } else {
                    acquireWakeLock();
                }
                Message msg = h.obtainMessage(EVENT_WAKE_LOCK_TIMEOUT);
                h.sendMessageDelayed(msg, WAKE_LOCK_TIMEOUT_MILLIS);
            }
        } else {
            h.removeMessages(EVENT_WAKE_LOCK_TIMEOUT);
            releaseWakeLock();
        }
        postDialState = s;
    }

    private void createWakeLock(Context context) {
        PowerManager pm = (PowerManager)context.getSystemService(Context.POWER_SERVICE);
        mPartialWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, LOG_TAG);
    }

    private void acquireWakeLock() {
        log("acquireWakeLock");
        mPartialWakeLock.acquire();
    }

    private void releaseWakeLock() {
        synchronized (mPartialWakeLock) {
            if (mPartialWakeLock.isHeld()) {
                log("releaseWakeLock");
                mPartialWakeLock.release();
            }
        }
    }

    private void log(String msg) {
        Log.d(LOG_TAG, "" + msg);
    }

    @Override
    public int getNumberPresentation() {
        return numberPresentation;
    }

    @Override
    public UUSInfo getUUSInfo() {
        return uusInfo;
    }

    @Override
    public CallDetails getCallDetails() {
        return this.callDetails;
    }

    public PhoneBase getPhoneFromConnection()
    {
        return (PhoneBase)(parent.getPhone());
    }
}
