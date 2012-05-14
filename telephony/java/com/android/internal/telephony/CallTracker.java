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

import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.os.Registrant;
import android.os.RegistrantList;
import android.util.Log;

import com.android.internal.telephony.CommandException;
import com.android.internal.telephony.cdma.CdmaCall;

/**
 * {@hide}
 */
public abstract class CallTracker extends Handler {

    private static final boolean DBG_POLL = false;

    //***** Constants

    static final int POLL_DELAY_MSEC = 250;

    protected int pendingOperations;
    protected boolean needsPoll;
    protected Message lastRelevantPoll;

    public CommandsInterface cm;

    public PhoneBase phone;
    public PhoneBase imsPhone;

    //CS calls
    public Call ringingCall;
    // A call that is ringing or (call) waiting
    public Call foregroundCall;
    public Call backgroundCall;

    //PS calls
    public Call ringingCallIms;
    public Call foregroundCallIms;
    public Call backgroundCallIms;

    public Phone.State state = Phone.State.IDLE; //Phone state for base phone
    public boolean mIsInEmergencyCall = false;
    public boolean callSwitchPending = false;

    //***** Events

    protected static final int EVENT_POLL_CALLS_RESULT             = 1;
    protected static final int EVENT_CALL_STATE_CHANGE             = 2;
    protected static final int EVENT_REPOLL_AFTER_DELAY            = 3;
    protected static final int EVENT_OPERATION_COMPLETE            = 4;
    protected static final int EVENT_GET_LAST_CALL_FAIL_CAUSE      = 5;

    protected static final int EVENT_SWITCH_RESULT                 = 8;
    protected static final int EVENT_RADIO_AVAILABLE               = 9;
    protected static final int EVENT_RADIO_NOT_AVAILABLE           = 10;
    protected static final int EVENT_CONFERENCE_RESULT             = 11;
    protected static final int EVENT_SEPARATE_RESULT               = 12;
    protected static final int EVENT_ECT_RESULT                    = 13;
    protected static final int EVENT_EXIT_ECM_RESPONSE_CDMA        = 14;
    protected static final int EVENT_CALL_WAITING_INFO_CDMA        = 15;
    protected static final int EVENT_THREE_WAY_DIAL_L2_RESULT_CDMA = 16;
    protected static final int EVENT_MODIFY_CALL                   = 17;
    protected static final int EVENT_SWITCH_RESULT_IMS             = 18;

    public abstract void acceptCall() throws CallStateException;
    public abstract void rejectCall() throws CallStateException;
    public abstract void switchWaitingOrHoldingAndActive() throws CallStateException;
    public abstract void setMute(boolean muted);
    public abstract boolean getMute();
    public abstract void dispose();
    public abstract void clearDisconnected();
    public abstract boolean canConference();
    public abstract boolean canDial();
    public abstract boolean canTransfer();
    public abstract void explicitCallTransfer() throws CallStateException;
    public abstract void conference() throws CallStateException;

    public RegistrantList voiceCallEndedRegistrants = new RegistrantList();
    public RegistrantList voiceCallStartedRegistrants = new RegistrantList();
    public RegistrantList imsCallEndedRegistrants = new RegistrantList();
    public RegistrantList imsCallStartedRegistrants = new RegistrantList();
    public RegistrantList callWaitingRegistrants =  new RegistrantList();

    protected void pollCallsWhenSafe() {
        needsPoll = true;

        if (checkNoOperationsPending()) {
            lastRelevantPoll = obtainMessage(EVENT_POLL_CALLS_RESULT);
            cm.getCurrentCalls(lastRelevantPoll);
        }
    }

    protected void
    pollCallsAfterDelay() {
        Message msg = obtainMessage();

        msg.what = EVENT_REPOLL_AFTER_DELAY;
        sendMessageDelayed(msg, POLL_DELAY_MSEC);
    }

    protected boolean
    isCommandExceptionRadioNotAvailable(Throwable e) {
        return e != null && e instanceof CommandException
                && ((CommandException)e).getCommandError()
                        == CommandException.Error.RADIO_NOT_AVAILABLE;
    }

    protected abstract void handlePollCalls(AsyncResult ar);

    protected void handleRadioAvailable() {
        pollCallsWhenSafe();
    }

    /**
     * Obtain a complete message that indicates that this operation
     * does not require polling of getCurrentCalls(). However, if other
     * operations that do need getCurrentCalls() are pending or are
     * scheduled while this operation is pending, the invocation
     * of getCurrentCalls() will be postponed until this
     * operation is also complete.
     */
    protected Message
    obtainNoPollCompleteMessage(int what) {
        pendingOperations++;
        lastRelevantPoll = null;
        return obtainMessage(what);
    }

    /**
     * @return true if we're idle or there's a call to getCurrentCalls() pending
     * but nothing else
     */
    private boolean
    checkNoOperationsPending() {
        if (DBG_POLL) log("checkNoOperationsPending: pendingOperations=" +
                pendingOperations);
        return pendingOperations == 0;
    }


    //***** Overridden from Handler
    public abstract void handleMessage (Message msg);
    public abstract void registerForVoiceCallStarted(Handler h, int what, Object obj);
    public abstract void unregisterForVoiceCallStarted(Handler h);
    public abstract void registerForVoiceCallEnded(Handler h, int what, Object obj);
    public abstract void unregisterForVoiceCallEnded(Handler h);
    public abstract void hangupWaitingOrBackground();
    public abstract void hangupConnectionByIndex(Call call, int index)
            throws CallStateException;
    public abstract void hangup (Call call) throws CallStateException;
    public abstract Connection dial(String dialString) throws CallStateException;

    protected abstract void log(String msg);

    public boolean isInEmergencyCall() {
        return mIsInEmergencyCall;
    };

    //***** Called from ConnectionBase
    public void
    hangup (ConnectionBase conn) throws CallStateException {
        throw new CallStateException ("ConnectionBase " + conn
                + "does not belong to CallTracker " + this);
    }

    public void
    separate(ConnectionBase conn) throws CallStateException {
        throw new CallStateException("ConnectionBase " + conn
                + "does not belong to CallTracker " + this);
    }


    public void
    hangup (Connection conn) throws CallStateException {
        throw new CallStateException ("Connection " + conn
                + "does not belong to CallTracker " + this);
    }

    public void
    separate(Connection conn) throws CallStateException {
        throw new CallStateException("Connection " + conn
                + "does not belong to CallTracker " + this);
    }

    public void registerForCallWaiting(Handler h, int what, Object obj) {
        Registrant r = new Registrant (h, what, obj);
        callWaitingRegistrants.add(r);
    }

    public void unregisterForCallWaiting(Handler h) {
        callWaitingRegistrants.remove(h);
    }

    public Connection getConnectionByIndex(Call call, int index)
            throws CallStateException {
        int count = call.connections.size();
        for (int i = 0; i < count; i++) {
            Connection cn = call.connections.get(i);
            if (cn.getIndex() == index) {
                return (Connection)cn;
            }
        }

        return null;
    }


    public Connection dial(String dialString, int clirMode, UUSInfo uusInfo) throws CallStateException {
        throw new CallStateException("Dial with clirmode & UUSInfo " +
                "does not belong to CallTracker " + this);
    }

    public Connection dial(String dialString, UUSInfo uusInfo) throws CallStateException {
        throw new CallStateException("Dial with UUSInfo does not belong to CallTracker " + this);
    }

    public Connection dial(String dialString, int clirMode) throws CallStateException {
        throw new CallStateException("Dial with Clirmode does not belong to CallTracker " + this);
    }

    public Connection dial(String dialString, CallDetails calldetails) throws CallStateException {
        throw new CallStateException("Dial with calldetails does not belong to CallTracker "
                + this);
    }

    public void acceptCall(PhoneBase incomingPhone) throws CallStateException {
        throw new CallStateException(
                "Accept with incomingphone is not supported in this CallTracker " + this);
    }

    public void acceptCall(PhoneBase phone, int callType) throws CallStateException {
        throw new CallStateException("Accept with CallType is not supported in this CallTracker "
                + this);
    }

    void hangupAllCalls(PhoneBase owner) throws CallStateException {
        hangupAllCallsP(owner);
    }

    protected void hangupAllCallsP(PhoneBase owner) throws CallStateException {
        throw new CallStateException("hangupAllCalls is not supported in this CallTracker");
    }

    public void rejectCall(PhoneBase phone) throws CallStateException {
        throw new CallStateException(
                "rejectCall with PhoneBase is not supported in this CallTracker");
    }

    public void switchWaitingOrHoldingAndActiveIms() throws CallStateException{
        throw new CallStateException(
                "switchWaitingOrHoldingAndActiveIms is not supported in this CallTracker");
    }

    public void createImsCalls() {
        log("createImsCalls is not supported in this CallTracker"+this);
    }

    public void
    clearDisconnected(PhoneBase phone) {
        log("clearDisconnected with phone is not supported in this CallTracker"+this);
    }
}
