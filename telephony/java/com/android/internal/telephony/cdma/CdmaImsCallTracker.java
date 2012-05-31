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

package com.android.internal.telephony.cdma;

import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.os.Registrant;
import android.os.RegistrantList;
import android.telephony.PhoneNumberUtils;
import android.telephony.ServiceState;
import android.text.TextUtils;
import android.util.Log;
import android.os.SystemProperties;

import com.android.internal.telephony.Call;
import com.android.internal.telephony.CallDetails;
import com.android.internal.telephony.CallFailCause;
import com.android.internal.telephony.CallStateException;
import com.android.internal.telephony.CallTracker;
import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.Connection;
import com.android.internal.telephony.DriverCall;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneBase;
import com.android.internal.telephony.CallBase;
import com.android.internal.telephony.ConnectionBase;
import com.android.internal.telephony.TelephonyProperties;
import com.android.internal.telephony.ims.RilImsPhone;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;


/**
 * {@hide}
 */
public final class CdmaImsCallTracker extends CallTracker {
    static final String LOG_TAG = "CDMAIMSCallTracker";

    private static final boolean REPEAT_POLLING = false;

    private static final boolean DBG_POLL = true;

    //***** Constants

    static final int MAX_CONNECTIONS = 2;
    static final int MAX_CONNECTIONS_PER_CALL = 1; // TBD check this value for IMS

    //***** Instance Variables

    ConnectionBase connections[] = new ConnectionBase[MAX_CONNECTIONS];

    // connections dropped during last poll
    ArrayList<ConnectionBase> droppedDuringPoll
    = new ArrayList<ConnectionBase>(MAX_CONNECTIONS);

    ConnectionBase pendingMO;
    boolean hangupPendingMO;
    boolean pendingCallInEcm=false;
    boolean mIsInEmergencyCall = false;
    //CDMAPhone phone;

    boolean desiredMute = false;    // false = mute off

    int pendingCallClirMode;

    private boolean mIsEcmTimerCanceled = false;

    //    boolean needsPoll;



    //***** Events

    //***** Constructors
    CdmaImsCallTracker(CDMALTEImsPhone Rilphone) {

        this.phone = Rilphone;
        Log.e(LOG_TAG, " phone object in constructor "+phone);
        cm = phone.mCM;
        cm.registerForCallStateChanged(this, EVENT_CALL_STATE_CHANGE, null);
        cm.registerForModifyCall(this, EVENT_MODIFY_CALL, null);
        cm.registerForOn(this, EVENT_RADIO_AVAILABLE, null);
        cm.registerForNotAvailable(this, EVENT_RADIO_NOT_AVAILABLE, null);
        cm.registerForCallWaitingInfo(this, EVENT_CALL_WAITING_INFO_CDMA, null);

        ringingCall = new CallBase(phone);
        // A call that is ringing or (call) waiting -
        // These are calls of CdmaPhone

        foregroundCall = new CallBase(phone);
        backgroundCall = new CallBase(phone);
        foregroundCall.setGeneric(false);
    }

    public void createImsCalls() {
        ringingCallIms = new CallBase(imsPhone);
        foregroundCallIms = new CallBase(imsPhone);
        backgroundCallIms = new CallBase(imsPhone);
        foregroundCallIms.setGeneric(false);
    }

    public void dispose() {
        cm.unregisterForCallStateChanged(this);
        cm.unregisterForModifyCall(this);
        cm.unregisterForOn(this);
        cm.unregisterForNotAvailable(this);
        cm.unregisterForCallWaitingInfo(this);
        for(ConnectionBase c : connections) {
            try {
                if(c != null) hangup(c);
            } catch (CallStateException ex) {
                Log.e(LOG_TAG, "unexpected error on hangup during dispose");
            }
        }

        try {
            if(pendingMO != null) hangup(pendingMO);
        } catch (CallStateException ex) {
            Log.e(LOG_TAG, "unexpected error on hangup during dispose");
        }

        if (phone != null ) clearDisconnected(phone);
        if (imsPhone != null ) clearDisconnected(imsPhone);

    }

    protected void finalize() {
        Log.d(LOG_TAG, "ImsCallTracker finalized");
    }

    //***** Instance Methods

    //***** Public Methods
    public void registerForVoiceCallStarted(Handler h, int what, Object obj) {
        Registrant r = new Registrant(h, what, obj);
        voiceCallStartedRegistrants.add(r);
        // Notify if in call when registering
        if (phone.getState() != Phone.State.IDLE) {
            r.notifyRegistrant(new AsyncResult(null, null, null));
        }
    }

    public void unregisterForVoiceCallStarted(Handler h) {
        voiceCallStartedRegistrants.remove(h);
    }

    public void registerForVoiceCallEnded(Handler h, int what, Object obj) {
        Registrant r = new Registrant(h, what, obj);
        voiceCallEndedRegistrants.add(r);
    }

    public void unregisterForVoiceCallEnded(Handler h) {
        voiceCallEndedRegistrants.remove(h);
    }

    public void registerForImsCallStarted(Handler h, int what, Object obj) {
        Registrant r = new Registrant(h, what, obj);
        imsCallStartedRegistrants.add(r);
        // Notify if in call when registering
        if (imsPhone.getState() != Phone.State.IDLE) {
            r.notifyRegistrant(new AsyncResult(null, null, null));
        }
    }

    public void unregisterForImsCallStarted(Handler h) {
        imsCallStartedRegistrants.remove(h);
    }

    public void registerForImsCallEnded(Handler h, int what, Object obj) {
        Registrant r = new Registrant(h, what, obj);
        imsCallStartedRegistrants.add(r);
    }

    public void unregisterForImsCallEnded(Handler h) {
        imsCallStartedRegistrants.remove(h);
    }

    private void
    fakeHoldForegroundBeforeDial() {
        //this method is nop for now
        List<Connection> connCopy;

        // We need to make a copy here, since fakeHoldBeforeDial()
        // modifies the lists, and we don't want to reverse the order
        connCopy = (List<Connection>) foregroundCall.connections.clone();
        //TBD copy imsphone foreground call
        for (int i = 0, s = connCopy.size() ; i < s ; i++) {
            ConnectionBase conn = (ConnectionBase)connCopy.get(i);

            conn.fakeHoldBeforeDialIms();
        }
    }

    private PhoneBase getPhoneForDomain(int domain) {
        PhoneBase ret = null;
        if (domain == CallDetails.RIL_CALL_DOMAIN_CS) {
            ret = phone;
        } else if (domain == CallDetails.RIL_CALL_DOMAIN_PS) {
            ret = imsPhone;
        }
        return ret;
    }

    /**
     * clirMode is one of the CLIR_ constants
     */
    public Connection
    dial (String dialString, int clirMode) throws CallStateException {
        // note that this triggers call state changed notif
        PhoneBase phone = getPhoneForDomain(CallDetails.RIL_CALL_DOMAIN_CS);

        if (phone == null) {
            throw new CallStateException("Unable to find phone for domain " );
        }
        Call foregroundCall = phone.getForegroundCall();

        clearDisconnected(phone);

        if (!canDial()) {
            throw new CallStateException("cannot dial in current state");
        }

        String inEcm=SystemProperties.get(TelephonyProperties.PROPERTY_INECM_MODE, "false");
        boolean isPhoneInEcmMode = inEcm.equals("true");
        boolean isEmergencyCall =
                PhoneNumberUtils.isLocalEmergencyNumber(dialString, phone.getContext());

        // Cancel Ecm timer if a second emergency call is originating in Ecm mode
        if (isPhoneInEcmMode && isEmergencyCall) {
            handleEcmTimer(phone.CANCEL_ECM_TIMER);
        }

        // We are initiating a call therefore even if we previously
        // didn't know the state (i.e. Generic was true) we now know
        // and therefore can set Generic to false.
        foregroundCall.setGeneric(false);

        // The new call must be assigned to the foreground call.
        // That call must be idle, so place anything that's
        // there on hold
        if (foregroundCall.getState() == Call.State.ACTIVE) {
            return dialThreeWay(dialString, phone);
        }

        dialString = PhoneNumberUtils.formatDialString(dialString); // only for cdma
        pendingMO = new ConnectionBase(phone.getContext(), dialString, (CallTracker) this,
                (Call) foregroundCall);
        hangupPendingMO = false;

        if (pendingMO.address == null || pendingMO.address.length() == 0
                || pendingMO.address.indexOf(PhoneNumberUtils.WILD) >= 0) {
            // Phone number is invalid
            pendingMO.cause = Connection.DisconnectCause.INVALID_NUMBER;

            // handlePollCalls() will notice this call not present
            // and will mark it as dropped.
            pollCallsWhenSafe();
        } else {
            // Always unmute when initiating a new call
            setMute(false);

            // Check data call
            disableDataCallInEmergencyCall(dialString);

            // In Ecm mode, if another emergency call is dialed, Ecm mode will not exit.
            if(!isPhoneInEcmMode || (isPhoneInEcmMode && isEmergencyCall)) {
                cm.dial(pendingMO.address, clirMode, obtainCompleteMessage());
            } else {
                phone.exitEmergencyCallbackMode();
                phone.setOnEcbModeExitResponse(this,EVENT_EXIT_ECM_RESPONSE_CDMA, null);
                pendingCallClirMode=clirMode;
                pendingCallInEcm=true;
            }
        }

        updatePhoneState(phone);
        phone.notifyPreciseCallStateChangedP();

        return pendingMO;
    }

    public Connection
    dial (String dialString) throws CallStateException {
        return dial(dialString, CommandsInterface.CLIR_DEFAULT);
    }

    public Connection
    dial(String dialString,CallDetails callDetails) throws CallStateException {
        return dial(dialString, CommandsInterface.CLIR_DEFAULT, callDetails);
    }

    public Connection
    dial(String dialString, int clirMode, CallDetails callDetails)throws CallStateException
    {
        PhoneBase dialPhone;
        boolean isDialRequestPending = false;
        String inEcm = SystemProperties.get(TelephonyProperties.PROPERTY_INECM_MODE, "false");
        boolean isPhoneInEcmMode = inEcm.equals("true");
        boolean isEmergencyCall =
                PhoneNumberUtils.isLocalEmergencyNumber(dialString, phone.getContext());

        if (callDetails == null) {
            throw new CallStateException("Null call details not expected in dial ");
        }

        // Cancel Ecm timer if a second emergency call is originating in Ecm
        // mode
        if (isPhoneInEcmMode && isEmergencyCall) {
            // Force domain to CS
            if (callDetails.call_domain != CallDetails.RIL_CALL_DOMAIN_CS) {
                Log.i(LOG_TAG, "Emergency call forced on CS");
                callDetails.call_domain = CallDetails.RIL_CALL_DOMAIN_CS;
            }
            dialPhone = getPhoneForDomain(callDetails.call_domain);
            if (dialPhone != null)
                handleEcmTimer(dialPhone.CANCEL_ECM_TIMER);
        } else {
            dialPhone = getPhoneForDomain(callDetails.call_domain);
        }

        if (dialPhone == null) {
            throw new CallStateException("Unable to find phone for domain "
                    + callDetails.call_domain);
        }
        Log.d(LOG_TAG, "dialphone is " + dialPhone + "call details" + callDetails );

        if (!canDial(dialPhone)) {
            throw new CallStateException("cannot dial in current state");
        }

        Call foregroundCall = dialPhone.getForegroundCall();

        // note that this triggers call state changed notify
        clearDisconnected(dialPhone);
        // We are initiating a call therefore even if we previously
        // didn't know the state (i.e. Generic was true) we now know
        // and therefore can set Generic to false.
        foregroundCall.setGeneric(false);

        // The new call must be assigned to the foreground call.
        // That call must be idle, so place anything that's
        // there on hold

        if (foregroundCall.getState() == Call.State.ACTIVE
                && (dialPhone != imsPhone)) {
            //This is a cdma 1x Conference calling/3way
            return dialThreeWay(dialString, phone);
        } else if ((foregroundCall.getState() == Call.State.ACTIVE)
                && (dialPhone == imsPhone)) {

           Log.d(LOG_TAG, "2nd IMS call dial, start holding 1st");
            switchWaitingOrHoldingAndActiveIms();
            fakeHoldForegroundBeforeDial();
            isDialRequestPending = true;

        }

        pendingMO = new ConnectionBase(phone.getContext(), dialString, (CallTracker) this,
                (Call) foregroundCall, callDetails);
        hangupPendingMO = false;

        if (pendingMO.address == null || pendingMO.address.length() == 0
                || pendingMO.address.indexOf(PhoneNumberUtils.WILD) >= 0) {
            // Phone number is invalid
            pendingMO.cause = Connection.DisconnectCause.INVALID_NUMBER;

            // handlePollCalls() will notice this call not present
            // and will mark it as dropped.
            pollCallsWhenSafe();
        } else {
            if(isDialRequestPending == false) {
                // Always unmute when initiating a new call
                setMute(false);
            }

            // Check data call
            disableDataCallInEmergencyCall(dialString);

            // In Ecm mode, if another emergency call is dialed, Ecm mode will
            // not exit.
            if ((!isPhoneInEcmMode || (isPhoneInEcmMode && isEmergencyCall))
                && (isDialRequestPending == false)) {
                cm.dial(pendingMO.address, clirMode, null, callDetails, obtainCompleteMessage());
            } else {
                if (dialPhone != imsPhone) {
                    dialPhone.exitEmergencyCallbackMode();
                    dialPhone.setOnEcbModeExitResponse(this, EVENT_EXIT_ECM_RESPONSE_CDMA, null);
                }
                pendingCallClirMode = clirMode;
                pendingCallInEcm = true;
            }
        }

        updatePhoneState(dialPhone);
        dialPhone.notifyPreciseCallStateChangedP();

        return pendingMO;
    }

    private Connection
    dialThreeWay (String dialString, PhoneBase phone) {
        if (!phone.getForegroundCall().isIdle()) {
            // Check data call
            disableDataCallInEmergencyCall(dialString);

            // Attach the new connection to foregroundCall
            // format string is specific to cdma
            dialString = PhoneNumberUtils.formatDialString(dialString);
            pendingMO = new ConnectionBase(phone.getContext(),
                    dialString, (CallTracker)this, phone.getForegroundCall());
            cm.sendCDMAFeatureCode(pendingMO.address,
                    obtainMessage(EVENT_THREE_WAY_DIAL_L2_RESULT_CDMA));
            return pendingMO;
        }
        return null;
    }

    public void
    acceptCall() throws CallStateException {
        acceptCall(phone, CallDetails.RIL_CALL_TYPE_VOICE);
    }

    public void
    acceptCall(PhoneBase incomingPhone) throws CallStateException {
        Call ringingCall = incomingPhone.getRingingCall();
        Connection conn = ringingCall.getEarliestConnection();
        int type = CallDetails.RIL_CALL_TYPE_VOICE;

        if (conn != null && conn.getCallDetails() != null) {

            type = conn.getCallDetails().call_type;
            Log.d(LOG_TAG, "Accepting call with type " + type);
        } // It is ok to continue here even if conn is null.
          // Exception will be thrown by acceptCall(PhoneBase, int)

        acceptCall(incomingPhone, type);
    }

    public void
    acceptCall(PhoneBase incomingPhone, int callType) throws CallStateException {

        if (incomingPhone != phone && (imsPhone != null && incomingPhone != imsPhone)) {
            throw new CallStateException("incoming call rejected in canAccept");
        }
        Call ringingCall=incomingPhone.getRingingCall();
        Call foregroundCall=incomingPhone.getForegroundCall();

        if (ringingCall.getState() == Call.State.INCOMING) {
            Log.i("phone", "acceptCall: incoming with calltype...");
            // Always unmute when answering a new call
            setMute(false);
            cm.acceptCall(obtainCompleteMessage(), callType );
        } else if (ringingCall.getState() == Call.State.WAITING) {
            if (incomingPhone == phone) {
                ConnectionBase cwConn = (ConnectionBase) (ringingCall.getLatestConnection());

                // Since there is no network response for supplimentary
                // service for CDMA, we assume call waiting is answered.
                // ringing Call state change to idle is in CallBase.detach
                // triggered by updateParent.
                cwConn.updateParent(ringingCall, foregroundCall);
                cwConn.onConnectedInOrOut();
                updatePhoneState(incomingPhone);
                switchWaitingOrHoldingAndActive();
            } else {
                updatePhoneState(incomingPhone);
                switchWaitingOrHoldingAndActiveIms();
            }
        } else {
            throw new CallStateException("phone not ringing");
        }

    }
    public void
    rejectCall () throws CallStateException {
        rejectCall(phone);
    }

    public void
    rejectCall (PhoneBase phone) throws CallStateException {
        // AT+CHLD=0 means "release held or UDUB"
        // so if the phone isn't ringing, this could hang up held
        if (phone.getRingingCall().getState().isRinging()) {
            cm.rejectCall(obtainCompleteMessage());
        } else {
            throw new CallStateException("phone not ringing");
        }
    }

    //CDMA 1x call switching will send flash request
    // Call switching is handled by network
    public void
    switchWaitingOrHoldingAndActive() throws CallStateException {
        // Should we bother with this check?
        if (ringingCall.getState() == Call.State.INCOMING) {
            throw new CallStateException("cannot be in the incoming state");
        } else if (foregroundCall.getConnections().size() > 1) {
            flashAndSetGenericTrue();
        } else {
            // Send a flash command to CDMA network for putting the other party on hold.
            // For CDMA networks which do not support this the user would just hear a beep
            // from the network. For CDMA networks which do support it will put the other
            // party on hold.
            cm.sendCDMAFeatureCode("", obtainMessage(EVENT_SWITCH_RESULT));
        }
    }

    // Call switching for IMS calls
    public void
    switchWaitingOrHoldingAndActiveIms () throws CallStateException {
        if (imsPhone.getRingingCall().getState() == Call.State.INCOMING) {
            throw new CallStateException("cannot be in the incoming state");
        } else if (callSwitchPending == false) {
            cm.switchWaitingOrHoldingAndActive(
                    obtainCompleteMessage(EVENT_SWITCH_RESULT_IMS));
            callSwitchPending = true;
        } else {
            Log.w(LOG_TAG, "Call Switch request ignored due to pending response");
        }
    }

    //Conference calls not supported
    public void
    conference() throws CallStateException {
        Log.w(LOG_TAG, "Conference not supported in CDMA");
    }

    public void
    explicitCallTransfer() throws CallStateException {
        cm.explicitCallTransfer(obtainCompleteMessage(EVENT_ECT_RESULT));
    }

    public void
    clearDisconnected() {
        clearDisconnected(phone);
    }

    public void
    clearDisconnected(PhoneBase phone) {
        if (phone != null) {
        internalClearDisconnected(phone);
        updatePhoneState(phone);
        phone.notifyPreciseCallStateChangedP();
        }
    }

    //TBD phase 2 requirements
    public boolean
    canConference() {
        return foregroundCall.getState() == Call.State.ACTIVE
                && backgroundCall.getState() == Call.State.HOLDING
                && !backgroundCall.isFull()
                && !foregroundCall.isFull();
    }

    public boolean
    canDial() {
        return canDial(phone);
    }

    public boolean
    canDial(PhoneBase dialPhone) {
        boolean ret;
        String disableCall = SystemProperties.get(
                TelephonyProperties.PROPERTY_DISABLE_CALL, "false");

        /*
         * Call Manager does the can dial check based on calls from both CDMA &
         * IMS phones if 1x has both foreground & back ground calls , new calls
         * cannot be originated
         */
        return ret = (pendingMO == null
                && disableCall.equals("false"));
    }

    public boolean
    canTransfer() {
        Log.e(LOG_TAG, "canTransfer: not possible in CDMA");
        return false;
    }

    //***** Private Instance Methods

    private void
    internalClearDisconnected(PhoneBase phone) {
        phone.getRingingCall().clearDisconnected();
        phone.getForegroundCall().clearDisconnected();
        phone.getBackgroundCall().clearDisconnected();
    }

    /**
     * Obtain a message to use for signalling "invoke getCurrentCalls() when
     * this operation and all other pending operations are complete
     */
    private Message
    obtainCompleteMessage() {
        return obtainCompleteMessage(EVENT_OPERATION_COMPLETE);
    }

    /**
     * Obtain a message to use for signalling "invoke getCurrentCalls() when
     * this operation and all other pending operations are complete
     */
    private Message
    obtainCompleteMessage(int what) {
        pendingOperations++;
        lastRelevantPoll = null;
        needsPoll = true;

        if (DBG_POLL) log("obtainCompleteMessage: pendingOperations=" +
                pendingOperations + ", needsPoll=" + needsPoll);

        return obtainMessage(what);
    }

    private void
    operationComplete() {
        pendingOperations--;

        if (DBG_POLL) log("operationComplete: pendingOperations=" +
                pendingOperations + ", needsPoll=" + needsPoll);

        if (pendingOperations == 0 && needsPoll) {
            lastRelevantPoll = obtainMessage(EVENT_POLL_CALLS_RESULT);
            cm.getCurrentCalls(lastRelevantPoll);
        } else if (pendingOperations < 0) {
            // this should never happen
            Log.e(LOG_TAG,"ImsCallTracker.pendingOperations < 0");
            pendingOperations = 0;
        }
    }

    private void
    updatePhoneState(PhoneBase incomingPhone) {

        if (incomingPhone == null) {
            log("null phone object in updatePhoneState");
            return;
        }
        Phone.State oldState = incomingPhone.getState();
        Phone.State curState = oldState;

        if (incomingPhone.getRingingCall().isRinging()) {
            curState = Phone.State.RINGING;
        } else if (pendingMO != null ||
                !(incomingPhone.getForegroundCall().isIdle()
                        && incomingPhone.getBackgroundCall().isIdle())) {
            curState = Phone.State.OFFHOOK;
        } else {
            curState = Phone.State.IDLE;
        }
        if (curState != oldState) {
            incomingPhone.setState(curState);
            incomingPhone.notifyPhoneStateChanged();
        }

        if (incomingPhone == phone) {

            if (curState == Phone.State.IDLE && oldState != curState) {
                voiceCallEndedRegistrants.notifyRegistrants(
                        new AsyncResult(null, null, null));
            } else if (oldState == Phone.State.IDLE && oldState != curState) {
                voiceCallStartedRegistrants.notifyRegistrants(
                        new AsyncResult(null, null, null));
            }
            log("update phone state, old=" + oldState + " new=" + curState);

        } else if (incomingPhone == imsPhone) {

            incomingPhone.setState(curState);

            if (curState == Phone.State.IDLE && oldState != curState) {
                imsCallEndedRegistrants.notifyRegistrants(
                        new AsyncResult(null, null, null));
            } else if (oldState == Phone.State.IDLE && oldState != curState) {
                imsCallStartedRegistrants.notifyRegistrants(
                        new AsyncResult(null, null, null));
            }
            log("update ims phone state, old=" + oldState + " new=" + curState);

        }
    }

    private void dumpConnection(ConnectionBase con) {
        if (con != null) {
            Log.d(LOG_TAG, "[conn] number: " + con.address +
                    " index: " + con.index + " incoming: " +
                    con.isIncoming + " alive: " + con.isAlive() +
                    " ringing: " + con.isRinging());
        }
    }
    private void dumpDC(DriverCall dc) {
        if (dc != null) {
            Log.d(LOG_TAG, "[ dc ] number:" + dc.number + " index: " +
                    dc.index + " incoming: " + dc.isMT + " state: " + dc.state +
                    "callDetails" + dc.callDetails);
        }
    }
    private void dumpState(List dcalls) {
        Log.d(LOG_TAG, "Connections:");
        for (int i = 0 ; i < connections.length ; i ++) {
            if(connections[i] == null) {
                Log.d(LOG_TAG, "Connection " + i + ": NULL");
            } else {
                Log.d(LOG_TAG, "Connection " + i + ": " );
                dumpConnection(connections[i]);
            }
        }
        if (dcalls != null) {
            Log.d(LOG_TAG, "Driver Calls:");
            for (Object dcall : dcalls) {
                DriverCall dc = (DriverCall) dcall;
                dumpDC(dc);
            }
        }
    }
    // ***** Overwritten from CallTracker

    protected void
    handlePollCalls(AsyncResult ar) {
        List polledCalls;
        Log.d(LOG_TAG, ">handlePollCalls");

        if (ar.exception == null) {
            polledCalls = (List)ar.result;
        } else if (isCommandExceptionRadioNotAvailable(ar.exception)) {
            // just a dummy empty ArrayList to cause the loop
            // to hang up all the calls
            polledCalls = new ArrayList();
        } else {
            // Radio probably wasn't ready--try again in a bit
            // But don't keep polling if the channel is closed
            pollCallsAfterDelay();
            return;
        }

        Connection newRinging = null; //or waiting
        boolean hasNonHangupStateChanged = false;// Any change besides a dropped connection
        boolean needsPollDelay = false;
        boolean unknownConnectionAppeared = false;
        PhoneBase newRingingPhone = null; //TBD add null checks before accessing
        PhoneBase stateChangedPhone = null;

        dumpState(polledCalls);
        for (int i = 0, curDC = 0, dcSize = polledCalls.size()
                ; i < connections.length; i++) {
            ConnectionBase conn = connections[i];
            DriverCall dc = null;

            // polledCall list is sparse
            if (curDC < dcSize) {
                dc = (DriverCall) polledCalls.get(curDC);

                if (dc.index == i+1) {
                    curDC++;
                } else {
                    dc = null;
                }
            }

            if (DBG_POLL) log("poll: conn[i=" + i + "]=" +
                    conn+", dc=" + dc);

            if (conn != null && dc != null && !TextUtils.isEmpty(conn.address) && !conn.compareTo(dc)) {
                // This means we received a different call than we expected in the call list.
                // Drop the call, and set conn to null, so that the dc can be processed as a new
                // call by the logic below.
                // This may happen if for some reason the modem drops the call, and replaces it
                // with another one, but still using the same index (for instance, if BS drops our
                // MO and replaces with an MT due to priority rules)
                Log.d(LOG_TAG, "New call with same index. Dropping old call");
                droppedDuringPoll.add(conn);
                conn = null;
            }
            if (conn == null && dc != null) {
                Log.d(LOG_TAG, "conn(" + conn + ")");
                // Connection appeared in CLCC response that we don't know about
                if (pendingMO != null && pendingMO.compareTo(dc)) {

                    if (DBG_POLL) log("poll: pendingMO=" + pendingMO);

                    // It's our pending mobile originating call
                    connections[i] = pendingMO;
                    pendingMO.index = i;
                    pendingMO.update(dc);
                    stateChangedPhone = pendingMO.getPhoneFromConnection();
                    pendingMO = null;

                    // Someone has already asked to hangup this call
                    if (hangupPendingMO) {
                        hangupPendingMO = false;
                        // Re-start Ecm timer when an uncompleted emergency call ends
                        if (mIsEcmTimerCanceled) {
                            handleEcmTimer(phone.RESTART_ECM_TIMER);
                        }

                        try {
                            if (Phone.DEBUG_PHONE) log(
                                    "poll: hangupPendingMO, hangup conn " + i);
                            hangup(connections[i]);
                        } catch (CallStateException ex) {
                            Log.e(LOG_TAG, "unexpected error on hangup");
                        }

                        // Do not continue processing this poll
                        // Wait for hangup and repoll
                        return;
                    }
                } else {
                    if (Phone.DEBUG_PHONE) {
                        log("pendingMo=" + pendingMO + ", dc=" + dc);
                    }
                    // find if the MT call is a new ring or unknown connection
                    newRinging = checkMtFindNewRinging(dc,i);

                    if (newRinging == null) {
                        unknownConnectionAppeared = true;
                    } else {
                        stateChangedPhone = newRinging.getPhoneFromConnection();
                    }
                    checkAndEnableDataCallAfterEmergencyCallDropped();
                }
                hasNonHangupStateChanged = true;
            } else if (conn != null && dc == null) {
                if(dcSize != 0)
                {
                    // This happens if the call we are looking at (index i)
                    // got dropped but the call list is not yet empty.
                    Log.d(LOG_TAG, "conn != null, dc == null. Still have connections in the call list");
                    droppedDuringPoll.add(conn);
                } else {
                    // This case means the RIL has no more active call anymore and
                    // we need to clean up the foregroundCall and ringingCall.
                    cleanupCalls(phone);
                    cleanupCalls(imsPhone);
                }

                // Re-start Ecm timer when the connected emergency call ends
                if (mIsEcmTimerCanceled) {
                    handleEcmTimer(phone.RESTART_ECM_TIMER);
                }
                // If emergency call is not going through while dialing
                checkAndEnableDataCallAfterEmergencyCallDropped();

                // Dropped connections are removed from the CallTracker
                // list but kept in the Call list
                connections[i] = null;
            } else if (conn != null && dc != null ) {
                if (conn.isIncoming != dc.isMT) {
                    // Call collision case
                    if (dc.isMT == true){
                        // Mt call takes precedence than Mo,drops Mo
                        droppedDuringPoll.add(conn);
                        // find if the MT call is a new ring or unknown connection
                        newRinging = checkMtFindNewRinging(dc,i);
                        if (newRinging == null) {
                            unknownConnectionAppeared = true;
                        }
                        checkAndEnableDataCallAfterEmergencyCallDropped();
                    } else {
                        // Call info stored in conn is not consistent with the call info from dc.
                        // We should follow the rule of MT calls taking precedence over MO calls
                        // when there is conflict, so here we drop the call info from dc and
                        // continue to use the call info from conn, and only take a log.
                        Log.e(LOG_TAG,"Error in RIL, Phantom call appeared " + dc);
                    }
                } else {
                    boolean changed;
                    changed = conn.update(dc);
                    hasNonHangupStateChanged = hasNonHangupStateChanged || changed;
                    stateChangedPhone = conn.getPhoneFromConnection();
                }
            }

            if (REPEAT_POLLING) {
                if (dc != null) {
                    // FIXME with RIL, we should not need this anymore
                    if ((dc.state == DriverCall.State.DIALING
                            /*&& cm.getOption(cm.OPTION_POLL_DIALING)*/)
                            || (dc.state == DriverCall.State.ALERTING
                                    /*&& cm.getOption(cm.OPTION_POLL_ALERTING)*/)
                                    || (dc.state == DriverCall.State.INCOMING
                                            /*&& cm.getOption(cm.OPTION_POLL_INCOMING)*/)
                                            || (dc.state == DriverCall.State.WAITING
                                                    /*&& cm.getOption(cm.OPTION_POLL_WAITING)*/)
                            ) {
                        // Sometimes there's no unsolicited notification
                        // for state transitions
                        needsPollDelay = true;
                    }
                }
            }
        }

        // This is the first poll after an ATD.
        // We expect the pending call to appear in the list
        // If it does not, we land here
        if (pendingMO != null) {
            Log.d(LOG_TAG,"Pending MO dropped before poll fg state:"
                    + foregroundCall.getState());

            droppedDuringPoll.add(pendingMO);
            pendingMO = null;
            hangupPendingMO = false;
            if( pendingCallInEcm) {
                pendingCallInEcm = false;
            }
        }

        if (newRinging != null) {
            newRingingPhone = newRinging.getPhoneFromConnection();
            newRingingPhone.notifyNewRingingConnectionP(newRinging);
        }

        // clear the "local hangup" and "missed/rejected call"
        // cases from the "dropped during poll" list
        // These cases need no "last call fail" reason
        for (int i = droppedDuringPoll.size() - 1; i >= 0 ; i--) {
            ConnectionBase conn = droppedDuringPoll.get(i);

            if (conn.isIncoming() && conn.getConnectTime() == 0) {
                // Missed or rejected call
                Connection.DisconnectCause cause;
                if (conn.cause == Connection.DisconnectCause.LOCAL) {
                    cause = Connection.DisconnectCause.INCOMING_REJECTED;
                } else {
                    cause = Connection.DisconnectCause.INCOMING_MISSED;
                }

                if (Phone.DEBUG_PHONE) {
                    log("missed/rejected call, conn.cause=" + conn.cause);
                    log("setting cause to " + cause);
                }
                droppedDuringPoll.remove(i);
                conn.onDisconnect(cause);
            } else if (conn.cause == Connection.DisconnectCause.LOCAL) {
                // Local hangup
                droppedDuringPoll.remove(i);
                conn.onDisconnect(Connection.DisconnectCause.LOCAL);
            } else if (conn.cause == Connection.DisconnectCause.INVALID_NUMBER) {
                droppedDuringPoll.remove(i);
                conn.onDisconnect(Connection.DisconnectCause.INVALID_NUMBER);
            }
        }

        // Any non-local disconnects: determine cause
        if (droppedDuringPoll.size() > 0) {
            cm.getLastCallFailCause(
                    obtainNoPollCompleteMessage(EVENT_GET_LAST_CALL_FAIL_CAUSE));
        }

        if (needsPollDelay) {
            pollCallsAfterDelay();
        }

        // Cases when we can no longer keep disconnected Connection's
        // with their previous calls
        // 1) the phone has started to ring
        // 2) A Call/Connection object has changed state...
        //    we may have switched or held or answered (but not hung up)
        if (newRinging != null) {
            internalClearDisconnected(newRingingPhone);
            newRingingPhone.notifyPreciseCallStateChangedP();
        }

        if (hasNonHangupStateChanged && stateChangedPhone!= null) {
            internalClearDisconnected(stateChangedPhone);
            stateChangedPhone.notifyPreciseCallStateChangedP();
        }

        updatePhoneState(phone);
        if (imsPhone != null )updatePhoneState(imsPhone);

        if (unknownConnectionAppeared) {// unknown connection notified only to cdmaphone
            phone.notifyUnknownConnection();
        }

        Log.d(LOG_TAG, "<handlePollCalls");

        //dumpState();
    }

    private void cleanupCalls(PhoneBase phone)
    {
        // Loop through foreground call connections as
        // it contains the known logical connections.

        int count = phone.getForegroundCall().connections.size();
        for (int n = 0; n < count; n++) {
            if (Phone.DEBUG_PHONE)
                log("adding fgCall cn " + n + " to droppedDuringPoll");
            ConnectionBase cn = (ConnectionBase)(phone.getForegroundCall().connections.get(n));
            droppedDuringPoll.add(cn);
        }
        count = phone.getRingingCall().connections.size();
        // Loop through ringing call connections as
        // it may contain the known logical connections.
        for (int n = 0; n < count; n++) {
            if (Phone.DEBUG_PHONE)
                log("adding rgCall cn " + n + " to droppedDuringPoll");
            ConnectionBase cn = (ConnectionBase)(phone.getRingingCall().connections.get(n));
            droppedDuringPoll.add(cn);
        }
        phone.getForegroundCall().setGeneric(false);
        phone.getRingingCall().setGeneric(false);
    }

    //***** Called from ConnectionBase
    /*package*/ @Override
    public void
    hangup (ConnectionBase conn) throws CallStateException {
        if (conn.owner != this) {
            throw new CallStateException ("ConnectionBase " + conn
                    + "does not belong to ImsCallTracker " + this);
        }
        Call call = conn.getCall();
        PhoneBase phone = conn.getPhoneFromConnection();
        Call ringingCall = phone.getRingingCall();
        if (conn == pendingMO) {
            // We're hanging up an outgoing call that doesn't have it's
            // GSM index assigned yet

            if (Phone.DEBUG_PHONE) log("hangup: set hangupPendingMO to true");
            hangupPendingMO = true;
        } else if ((call == ringingCall)
                && (ringingCall.getState() == Call.State.WAITING)) {
            // Handle call waiting hang up case.
            //
            // The ringingCall state will change to IDLE in Call.detach
            // if the ringing call connection size is 0. We don't specifically
            // set the ringing call state to IDLE here to avoid a race condition
            // where a new call waiting could get a hang up from an old call
            // waiting ringingCall.
            //
            // PhoneApp does the call log itself since only PhoneApp knows
            // the hangup reason is user ignoring or timing out. So conn.onDisconnect()
            // is not called here. Instead, conn.onLocalDisconnect() is called.
            conn.onLocalDisconnect();
            updatePhoneState(phone);
            phone.notifyPreciseCallStateChanged();
            return;
        } else {
            try {
                cm.hangupConnection (conn.getIndex(), obtainCompleteMessage());
            } catch (CallStateException ex) {
                // Ignore "connection not found"
                // Call may have hung up already
                Log.w(LOG_TAG,"ImsCallTracker WARN: hangup() on absent connection "
                        + conn);
            }
        }

        conn.onHangupLocal();
    }


    /*package*/@Override
    public void
    separate (ConnectionBase conn) throws CallStateException {
        if (conn.owner != this) {
            throw new CallStateException ("ConnectionBase " + conn
                    + "does not belong to ImsCallTracker " + this);
        }
        try {
            cm.separateConnection (conn.getIndex(),
                    obtainCompleteMessage(EVENT_SEPARATE_RESULT));
        } catch (CallStateException ex) {
            // Ignore "connection not found"
            // Call may have hung up already
            Log.w(LOG_TAG,"ImsCallTracker WARN: separate() on absent connection "
                    + conn);
        }
    }

    //***** Called from CDMAPhone

    /*package*/ public void
    setMute(boolean mute) {
        desiredMute = mute;
        cm.setMute(desiredMute, null);
    }

    /*package*/ public boolean
    getMute() {
        return desiredMute;
    }


    public void hangupWaitingOrBackground() {
        if (Phone.DEBUG_PHONE) log("hangupWaitingOrBackground");
        cm.hangupWaitingOrBackground(obtainCompleteMessage());
    }

    /* package */ public void
    hangup (Call call) throws CallStateException {
        if (call.getConnections().size() == 0) {
            throw new CallStateException("no connections in call");
        }
        PhoneBase phone = (PhoneBase)(call.getPhone());
        Call ringingCall = phone.getRingingCall();
        Call foregroundCall = phone.getForegroundCall();
        Call backgroundCall = phone.getBackgroundCall();

        if (call == ringingCall) {
            if (Phone.DEBUG_PHONE) log("(ringing) hangup waiting or background");
            cm.hangupWaitingOrBackground(obtainCompleteMessage());
        } else if (call == foregroundCall) {
            if (call.isDialingOrAlerting()) {
                if (Phone.DEBUG_PHONE) {
                    log("(foregnd) hangup dialing or alerting...");
                }
                hangup((ConnectionBase)(call.getConnections().get(0)));
            } else {
                hangupForegroundResumeBackground();
            }
        } else if (call == backgroundCall) {
            if (ringingCall.isRinging()) {
                if (Phone.DEBUG_PHONE) {
                    log("hangup all conns in background call");
                }
                hangupAllConnections(call);
            } else {
                hangupWaitingOrBackground();
            }
        } else {
            throw new RuntimeException ("Call " + call +
                    "does not belong to ImsCallTracker " + this);
        }

        call.onHangupLocal();
        phone.notifyPreciseCallStateChangedP();
    }

    /* package */
    void hangupForegroundResumeBackground() {
        if (Phone.DEBUG_PHONE) log("hangupForegroundResumeBackground");
        cm.hangupForegroundResumeBackground(obtainCompleteMessage());
    }

    public void hangupConnectionByIndex(Call call, int index)
            throws CallStateException {
        int count = call.connections.size();
        for (int i = 0; i < count; i++) {
            ConnectionBase cn = (ConnectionBase)call.connections.get(i);
            if (cn.getCDMAIndex() == index) {
                cm.hangupConnection(index, obtainCompleteMessage());
                return;
            }
        }

        throw new CallStateException("no gsm index found");
    }

    void hangupAllConnections(Call call) throws CallStateException{
        try {
            int count = call.connections.size();
            for (int i = 0; i < count; i++) {
                ConnectionBase cn = (ConnectionBase)call.connections.get(i);
                cm.hangupConnection(cn.getCDMAIndex(), obtainCompleteMessage());
            }
        } catch (CallStateException ex) {
            Log.e(LOG_TAG, "hangupConnectionByIndex caught " + ex);
        }
    }

    /* package */
    ConnectionBase getConnectionByIndex(CallBase call, int index)
            throws CallStateException {
        int count = call.connections.size();
        for (int i = 0; i < count; i++) {
            ConnectionBase cn = (ConnectionBase)call.connections.get(i);
            if (cn.getCDMAIndex() == index) {
                return cn;
            }
        }

        return null;
    }

    private void flashAndSetGenericTrue() throws CallStateException {
        cm.sendCDMAFeatureCode("", obtainMessage(EVENT_SWITCH_RESULT));

        // Set generic to true because in CDMA it is not known what
        // the status of the call is after a call waiting is answered,
        // 3 way call merged or a switch between calls.
        foregroundCall.setGeneric(true);
        phone.notifyPreciseCallStateChangedP();
    }

    private Phone.SuppService getFailedService(int what) {
        switch (what) {
            case EVENT_SWITCH_RESULT:
            case EVENT_SWITCH_RESULT_IMS:
                return Phone.SuppService.SWITCH;
            case EVENT_CONFERENCE_RESULT:
                return Phone.SuppService.CONFERENCE;
            case EVENT_SEPARATE_RESULT:
                return Phone.SuppService.SEPARATE;
            case EVENT_ECT_RESULT:
                return Phone.SuppService.TRANSFER;
        }
        return Phone.SuppService.UNKNOWN;
    }

    private void handleRadioNotAvailable() {
        // handlePollCalls will clear out its
        // call list when it gets the CommandException
        // error result from this
        pollCallsWhenSafe();
    }

    private void notifyCallWaitingInfo(CdmaCallWaitingNotification obj) {
        if (callWaitingRegistrants != null) {
            callWaitingRegistrants.notifyRegistrants(new AsyncResult(null, obj, null));
        }
    }

    private void handleCallWaitingInfo (CdmaCallWaitingNotification cw) {
        //TBD- phase 2 requirements
        // Check how many connections in foregroundCall.
        // If the connection in foregroundCall is more
        // than one, then the connection information is
        // not reliable anymore since it means either
        // call waiting is connected or 3 way call is
        // dialed before, so set generic.
        if (foregroundCall.connections.size() > 1 ) {
            foregroundCall.setGeneric(true);
        }

        // Create a new ConnectionBase which attaches itself to ringingCall.
        ringingCall.setGeneric(false);
        new ConnectionBase(phone.getContext(), cw, this, ringingCall);
        updatePhoneState(phone);

        // Finally notify application
        notifyCallWaitingInfo(cw);
    }
    //****** Overridden from Handler

    public void
    handleMessage (Message msg) {
        AsyncResult ar;

        if (!phone.mIsTheCurrentActivePhone) {
            Log.w(LOG_TAG, "Ignoring events received on inactive CdmaPhone");
            return;
        }
        switch (msg.what) {
            case EVENT_POLL_CALLS_RESULT:{
                Log.d(LOG_TAG, "Event EVENT_POLL_CALLS_RESULT Received");
                ar = (AsyncResult)msg.obj;

                if(msg == lastRelevantPoll) {
                    if(DBG_POLL) log(
                            "handle EVENT_POLL_CALL_RESULT: set needsPoll=F");
                    needsPoll = false;
                    lastRelevantPoll = null;
                    handlePollCalls((AsyncResult)msg.obj);
                }
            }
            break;

            case EVENT_OPERATION_COMPLETE:
                operationComplete();
                break;

            case EVENT_SWITCH_RESULT:
                // In GSM call operationComplete() here which gets the
                // current call list. But in CDMA there is no list so
                // there is nothing to do.
                break;
            case EVENT_SWITCH_RESULT_IMS:
                // operationComplete() here  gets the
                // current call list.
                // This event will also be called when the call is placed
                // on hold while there is another dialed call. If Hold succeeds,
                // dialPendingCall would be invoked.Else getCurrentCalls is anyways
                // invoked through operationComplete,which will get the new
                // call states depending on which UI would be updated.
                    callSwitchPending = false;
                    ar = (AsyncResult)msg.obj;
                    if (ar.exception != null) {
                        Log.i(LOG_TAG,
                                "Exception during IMS call switching");
                        //phone.notifySuppServiceFailed(getFailedService(msg.what));
                    } else {
                        if (pendingMO != null) {
                            dialPendingCall();
                        }
                    }
                    operationComplete();
                break;
            case EVENT_GET_LAST_CALL_FAIL_CAUSE: {
                int causeCode;
                PhoneBase connPhone;
                ar = (AsyncResult)msg.obj;

                operationComplete();

                if (ar.exception != null) {
                    // An exception occurred...just treat the disconnect
                    // cause as "normal"
                    causeCode = CallFailCause.NORMAL_CLEARING;
                    Log.i(LOG_TAG,
                            "Exception during getLastCallFailCause, assuming normal disconnect");
                } else {
                    causeCode = ((int[])ar.result)[0];
                }

                for (int i = 0, s =  droppedDuringPoll.size()
                        ; i < s ; i++
                        ) {
                    ConnectionBase conn = droppedDuringPoll.get(i);
                    if (conn.callDetails !=null)
                        connPhone = getPhoneForDomain(conn.callDetails.call_domain);
                    else
                        connPhone = getPhoneForDomain(CallDetails.RIL_CALL_DOMAIN_CS);

                    conn.onRemoteDisconnect(connPhone, causeCode);
                    updatePhoneState(connPhone);
                    connPhone.notifyPreciseCallStateChanged();
                }

                droppedDuringPoll.clear();
            }
            break;

            case EVENT_REPOLL_AFTER_DELAY:
            case EVENT_CALL_STATE_CHANGE:
                pollCallsWhenSafe();
                break;

            case EVENT_RADIO_AVAILABLE:
                handleRadioAvailable();
                break;

            case EVENT_RADIO_NOT_AVAILABLE:
                handleRadioNotAvailable();
                break;

            case EVENT_EXIT_ECM_RESPONSE_CDMA:
                //no matter the result, we still do the same here
                if (pendingCallInEcm) {
                    cm.dial(pendingMO.address, pendingCallClirMode, obtainCompleteMessage());
                    pendingCallInEcm = false;
                }
                phone.unsetOnEcbModeExitResponse(this);
                break;

            case EVENT_CALL_WAITING_INFO_CDMA:
                ar = (AsyncResult)msg.obj;
                if (ar.exception == null) {
                    handleCallWaitingInfo((CdmaCallWaitingNotification)ar.result);
                    Log.d(LOG_TAG, "Event EVENT_CALL_WAITING_INFO_CDMA Received");
                }
                break;

            case EVENT_THREE_WAY_DIAL_L2_RESULT_CDMA:
                ar = (AsyncResult)msg.obj;
                if (ar.exception == null && pendingMO != null) {
                    // Assume 3 way call is connected
                    pendingMO.onConnectedInOrOut();
                    if(!PhoneNumberUtils.isEmergencyNumber(pendingMO.address)) {
                        pendingMO = null;
                    }
                }
                break;

            default:{
                throw new RuntimeException("unexpected event not handled");
            }
        }
    }

    void dialPendingCall() {
        Log.d(LOG_TAG, "dialPendingCall: enter");
        if (pendingMO.address == null || pendingMO.address.length() == 0
            || pendingMO.address.indexOf(PhoneNumberUtils.WILD) >= 0) {
            // Phone number is invalid
            pendingMO.cause = Connection.DisconnectCause.INVALID_NUMBER;

            // handlePollCalls() will notice this call not present
            // and will mark it as dropped.
            pollCallsWhenSafe();
        } else {
            Log.d(LOG_TAG, "dialPendingCall: dialing...");
            // Always unmute when initiating a new call
            setMute(false);
            // uusinfo is null as it is not applicable to cdma
            // TODO but they may be applicable to IMS - need to check
            cm.dial(pendingMO.address, pendingCallClirMode, null, pendingMO.callDetails,
                    obtainCompleteMessage());
        }
        PhoneBase phone = pendingMO.getPhoneFromConnection();
        updatePhoneState(phone);
        phone.notifyPreciseCallStateChanged();
    }

    /**
     * Handle Ecm timer to be canceled or re-started
     */
    private void handleEcmTimer(int action) {
        phone.handleTimerInEmergencyCallbackMode(action);
        switch(action) {
            case CDMAPhone.CANCEL_ECM_TIMER: mIsEcmTimerCanceled = true; break;
            case CDMAPhone.RESTART_ECM_TIMER: mIsEcmTimerCanceled = false; break;
            default:
                Log.e(LOG_TAG, "handleEcmTimer, unsupported action " + action);
        }
    }

    /**
     * Disable data call when emergency call is connected
     */
    private void disableDataCallInEmergencyCall(String dialString) {
        if (PhoneNumberUtils.isLocalEmergencyNumber(dialString, phone.getContext())) {
            if (Phone.DEBUG_PHONE) log("disableDataCallInEmergencyCall");
            mIsInEmergencyCall = true;
            phone.mDataConnectionTracker.setInternalDataEnabled(false);
        }
    }

    /**
     * Check and enable data call after an emergency call is dropped if it's
     * not in ECM
     */
    private void checkAndEnableDataCallAfterEmergencyCallDropped() {
        if (mIsInEmergencyCall) {
            mIsInEmergencyCall = false;
            String inEcm=SystemProperties.get(TelephonyProperties.PROPERTY_INECM_MODE, "false");
            if (Phone.DEBUG_PHONE) {
                log("checkAndEnableDataCallAfterEmergencyCallDropped,inEcm=" + inEcm);
            }
            if (inEcm.compareTo("false") == 0) {
                // Re-initiate data connection
                phone.mDataConnectionTracker.setInternalDataEnabled(true);
            }
        }
    }

    /**
     * Check the MT call to see if it's a new ring or
     * a unknown connection.
     */
    private Connection checkMtFindNewRinging( DriverCall dc, int i) {

        Connection newRinging = null;
        Call ringingCall = null;

        connections[i] = new ConnectionBase(phone.getContext(), dc, this, i);

        if (connections[i].getCall() != null) {
            PhoneBase phone = connections[i].getPhoneFromConnection();
            ringingCall = phone.getRingingCall();
        }
        // it's a ringing call
        if ((ringingCall != null) && (connections[i].getCall() == ringingCall)) {
            newRinging = connections[i];
            if (Phone.DEBUG_PHONE)
                log("Notify new ring " + dc);
        } else {
            // Something strange happened: a call which is neither
            // a ringing call nor the one we created. It could be the
            // call collision result from RIL
            Log.e(LOG_TAG,"Phantom call appeared " + dc);
            // If it's a connected call, set the connect time so that
            // it's non-zero.  It may not be accurate, but at least
            // it won't appear as a Missed Call.
            if (dc.state != DriverCall.State.ALERTING
                    && dc.state != DriverCall.State.DIALING) {
                connections[i].connectTime = System.currentTimeMillis();
            }
        }
        return newRinging;
    }

    /**
     * Check if current call is in emergency call
     *
     * @return true if it is in emergency call
     *         false if it is not in emergency call
     */
    public boolean isInEmergencyCall() {
        return mIsInEmergencyCall;
    }

    @Override
    protected void hangupAllCallsP(PhoneBase phone) throws CallStateException {
        // TODO implEMENT THIS
    }

    @Override
    protected void log(String msg) {
        Log.d(LOG_TAG, "[ImsCallTracker] " + msg);
    }

}
