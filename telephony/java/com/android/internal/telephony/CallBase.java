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

import com.android.internal.telephony.Call;
import com.android.internal.telephony.CallStateException;
import com.android.internal.telephony.Connection;
import com.android.internal.telephony.DriverCall;
import com.android.internal.telephony.Phone;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * {@hide}
 */
public class CallBase extends Call {
    /*************************** Instance Variables **************************/
    /*package*/ PhoneBase owner;

    /****************************** Constructors *****************************/
    public CallBase(PhoneBase owner) {
        this.owner = owner;
    }

    public void dispose() {
    }

    /************************** Overridden from Call *************************/
    public List<Connection>
            getConnections() {
        return Collections.unmodifiableList(connections);
    }

    public Phone getPhone() {
        return owner;
    }

    public boolean isMultiparty() {
        return connections.size() > 1;
    }

    /**
     * Please note: if this is the foreground call and a background call exists,
     * the background call will be resumed because an AT+CHLD=1 will be sent
     */
    public void hangup() throws CallStateException {
        owner.getCallTracker().hangup(this);
    }

    public void hangupAllCalls() throws CallStateException {
        owner.getCallTracker().hangupAllCalls(owner);
    }

    public String toString() {
        return state.toString();
    }

    // ***** Called from GsmConnection

    public void attach(Connection conn, DriverCall dc) {
        connections.add(conn);
        state = stateFromDCState(dc.state);
    }

    public void attachFake(Connection conn, State state) {
        connections.add(conn);
        this.state = state;
    }

    /**
     * Called by Connection when it has disconnected
     */
    public void connectionDisconnected(Connection conn) {
        if (state != State.DISCONNECTED) {
            /* If only disconnected connections remain, we are disconnected */

            boolean hasOnlyDisconnectedConnections = true;

            for (int i = 0, s = connections.size(); i < s; i++) {
                if (connections.get(i).getState()
                != State.DISCONNECTED) {
                    hasOnlyDisconnectedConnections = false;
                    break;
                }
            }

            if (hasOnlyDisconnectedConnections) {
                state = State.DISCONNECTED;
            }
        }
    }

    public void detach(Connection conn) {
        connections.remove(conn);

        if (connections.size() == 0) {
            state = State.IDLE;
        }
    }

    public boolean update(Connection conn, DriverCall dc) {
        State newState;
        boolean changed = false;

        newState = stateFromDCState(dc.state);

        if (newState != state) {
            state = newState;
            changed = true;
        }

        return changed;
    }

    /**
     * @return true if there's no space in this call for additional connections
     *         to be added via "conference"
     */
    public boolean isFull() {
        return connections.size() == owner.getMaxConnectionsPerCall();
    }

    /**
     * Called when this Call is being hung up locally (eg, user pressed "end")
     * Note that at this point, the hangup request has been dispatched to the
     * radio but no response has yet been received so update() has not yet been
     * called
     */
    public void onHangupLocal() {
        for (int i = 0, s = connections.size(); i < s; i++) {
            ConnectionBase cn = (ConnectionBase) connections.get(i);

            cn.onHangupLocal();
        }
        state = State.DISCONNECTING;
    }

    /**
     * Called when it's time to clean up disconnected Connection objects
     */
    public void clearDisconnected() {
        for (int i = connections.size() - 1; i >= 0; i--) {
            Connection cn = (Connection) connections.get(i);

            if (cn.getState() == State.DISCONNECTED) {
                connections.remove(i);
            }
        }

        if (connections.size() == 0) {
            state = State.IDLE;
        }
    }
}
