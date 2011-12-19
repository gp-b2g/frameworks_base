/* Copyright (c) 2011-2012, Code Aurora Forum. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *     * Redistributions of source code must retain the above copyright
 *       notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above
 *       copyright notice, this list of conditions and the following
 *       disclaimer in the documentation and/or other materials provided
 *       with the distribution.
 *     * Neither the name of Code Aurora Forum, Inc. nor the names of its
 *       contributors may be used to endorse or promote products derived
 *       from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED "AS IS" AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NON-INFRINGEMENT
 * ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS
 * BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR
 * BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
 * OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN
 * IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */


package android.net;

import com.android.internal.net.IPVersion;
import com.android.internal.telephony.ITelephony;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.telephony.QosSpec;
import android.net.ILinkSocketMessageHandler;
import android.net.LinkCapabilities;
import android.net.ExtraLinkCapabilities;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

/** @hide
 * This class tracks all QoS requests
 * */
public class QoSTracker {

    private static final String LOG_TAG = "QoSTracker";
    private static final String LOCAL_TAG = "QoSTracker_DEBUG";
    private final boolean DBG = true;

    private int mId;
    private int mQosId;
    private ExtraLinkCapabilities myCap = null;
    private QosSpec mQosSpec;
    private ILinkSocketMessageHandler mNotifier;
    private boolean mSetupRequested;
    private boolean mTeardownRequested;
    private int mDetailedState; //Detail QoS State obtained from lower layers
    private String mState; //QoS State notified to the APP.
    private String lastState; //last state notified to the app.
    private boolean notifyQosToSocket; //flag to track if the socket needs to be notified
    private boolean isWaitingForSpecUpdate; //flag to track qosspec request/response

    private final int[] capRoKeys = new int[] {
        LinkCapabilities.Key.RO_MIN_AVAILABLE_FWD_BW,
        LinkCapabilities.Key.RO_MAX_AVAILABLE_FWD_BW,
        LinkCapabilities.Key.RO_MIN_AVAILABLE_REV_BW,
        LinkCapabilities.Key.RO_MAX_AVAILABLE_REV_BW,
        LinkCapabilities.Key.RO_CURRENT_FWD_LATENCY,
        LinkCapabilities.Key.RO_CURRENT_REV_LATENCY,
        LinkCapabilities.Key.RO_BOUND_INTERFACE,
        LinkCapabilities.Key.RO_NETWORK_TYPE,
        LinkCapabilities.Key.RO_PHYSICAL_INTERFACE,
        LinkCapabilities.Key.RO_CARRIER_ROLE,
        LinkCapabilities.Key.RO_QOS_STATE
    };

    /**
     * Constructor
     *
     * @param id
     * @param lsmh
     * @param eCap
     */
    public QoSTracker(int id, ILinkSocketMessageHandler lsmh, ExtraLinkCapabilities eCap) {
        dlogd("socket id: " + id + " QoSTracker EX");
        mId = id;
        mNotifier = lsmh;
        myCap = eCap;
        mQosId = -1;
        mDetailedState = -1;
        mState = LinkCapabilities.QosStatus.QOS_STATE_INACTIVE;
        lastState = LinkCapabilities.QosStatus.QOS_STATE_INACTIVE;
        mSetupRequested = false;
        mTeardownRequested = false;
        myCap.put(LinkCapabilities.Key.RO_QOS_STATE, mState);
        isWaitingForSpecUpdate = false;
    }

    public int getSocketId() {
        return mId;
    }

    public int getQosId() {
        return mQosId;
    }

    public LinkCapabilities getQosCapabilities() {
        return myCap;
    }

    /**
     * Requests for QoS
     *
     * @param spec
     * @param apnType
     * @param ipVersion
     * @return true if the request is successfully issued, false otherwise
     */
    public boolean startQosTransaction(QosSpec spec, String apnType) {
        if (spec == null) {
            dlogi("QoSSpec is null");
            return false;
        }
        mQosSpec = spec;
        mQosSpec.setUserData(mId);
        dlogi("startQosTransaction got called for socket: " + mId + " Is setup requested already: "
                + mSetupRequested);

        if (!mSetupRequested) {
            if (enableQoS(mQosSpec, apnType)) {
                mSetupRequested = true;
                return true;
            } else {
                //TODO implement error handlers for calls to telephony
                mSetupRequested = false;
                dlogi("Enable Qos failed");
                return false;
            }
        } else {
            //TODO do modify qos
        }
        return false;
    }

    /**
     * Releases QoS
     */
    public void stopQosTransaction() {
        dlogd("stopQosTransaction got called for sid: " + mId);
        if (!mTeardownRequested) {
            disableQos(mQosId);
            mTeardownRequested = true;
        }
    }

    /**
     * Suspends QoS if available previously
     *
     * @return
     */
    public boolean suspendQosTransaction() {
        boolean result = false;

        dlogi( "Suspending qos for id: " + mQosId);
        ITelephony mPhone = getPhone();
        if (mPhone == null) {
            logw("Telephony service is unavailable");
            return result;
        }

        // Check if the state of QoS is available before sending the
        // suspend request
        if (mState != LinkCapabilities.QosStatus.QOS_STATE_ACTIVE) {
            loge("QoS state " + mState + " is not the correct state to suspend QoS");
            return result;
        }

        try {
            result = (mPhone.suspendQos(mQosId) == Phone.QOS_REQUEST_SUCCESS);
        } catch (RemoteException re) {
            logw("Remote exception while using telephony service: " + re);
        } catch (Exception e) {
            logw("Exception while using telephony service: " + e);
        }

        return result;
    }

    /**
     * Resumes QoS if it is suspended previously
     *
     * @return
     */
    public boolean resumeQosTransaction() {
        boolean result = false;

        dlogi( "Resuming qos for id: " + mQosId);
        ITelephony mPhone = getPhone();
        if (mPhone == null) {
            logw("Telephony service is unavailable");
            return result;
        }

        // Check if the state of QoS is available before sending the
        // suspend request
        if (mState != LinkCapabilities.QosStatus.QOS_STATE_SUSPENDED) {
            loge("QoS state " + mState + " is not the correct state to resume QoS");
            return result;
        }

        try {
            result = (mPhone.resumeQos(mQosId) == Phone.QOS_REQUEST_SUCCESS);
        } catch (RemoteException re) {
            logw("Remote exception while using telephony service: " + re);
        } catch (Exception e) {
            logw("Exception while using telephony service: " + e);
        }

        return result;
    }

    /**
     * Handles QoS events received by QoSManager in response to QoS operations
     * performed by {@code QoSTracker}
     *
     * @param qosId
     * @param qosIndState
     * @param qosState
     * @param spec
     */
    public void handleQosEvent(int qosId, int qosIndState, int qosState, QosSpec spec) {
        mQosId = qosId;
        if (myCap == null) {
            dlogw("handleQosEvent failed due to null capabilities... aborting");
            return;
        }

        if (qosState == -1) { // event is an unsolicited indication
            handleQosIndEvent(qosIndState);
            //do not go further if a spec update is needed.
            if (isWaitingForSpecUpdate) return;
        } else { //event is a response to getQosStatus
            if (spec == null) {
                //remove current flow spec entries from the capabilities if spec is null
                myCap.remove(LinkCapabilities.Key.RO_MIN_AVAILABLE_REV_BW);
                myCap.remove(LinkCapabilities.Key.RO_MAX_AVAILABLE_REV_BW);
                myCap.remove(LinkCapabilities.Key.RO_MIN_AVAILABLE_FWD_BW);
                myCap.remove(LinkCapabilities.Key.RO_MAX_AVAILABLE_FWD_BW);
                myCap.remove(LinkCapabilities.Key.RO_CURRENT_REV_LATENCY);
                myCap.remove(LinkCapabilities.Key.RO_CURRENT_FWD_LATENCY);
            } else {
                updateCapabilitiesFromSpec(spec);
            }
            isWaitingForSpecUpdate = false; //received spec update so reset the flag
        }

        if (notifyQosToSocket) {
            ExtraLinkCapabilities sendCap = new ExtraLinkCapabilities();
            for (int roKey : capRoKeys) {
                if (myCap.containsKey(roKey))
                    sendCap.put(roKey, myCap.get(roKey));
            }
            try {
                dlogi("notifying socket of updated capabilities: " + sendCap);
                mNotifier.onCapabilitiesChanged(sendCap);
                notifyQosToSocket = false;
            } catch (RemoteException re) {
                dlogd(" oncapabilitieschanged failed for sid: "
                    + mId + " with exception: " + re);
            } catch (NullPointerException npe) {
                dlogd(" onCapabilitiesChgd got null notifier " + npe);
            }
        }
    }

    private void handleQosIndEvent(int qosIndState) {
        mDetailedState = qosIndState;

        /*
         * Convert detailed state to coarse state that will be conveyed to
         * the socket per the following table
         * ==================================================
         *  Detailed State          |       State
         * --------------------------------------------------
         *  REQUEST_FAILED          |   QOS_STATE_FAILED
         * --------------------------------------------------
         *  INITIATED, RELEASED,    |
         *  RELEASED_NETWORK,       |   QOS_STATE_INACTIVE
         *  NONE                    |
         * -------------------------|------------------------
         *  ACTIVATED,              |
         *  MODIFYING, MODIFIED,    |
         *  MODIFIED_NETWORK,       |   QOS_STATE_ACTIVE
         *  RELEASING,              |
         *  RESUMED_NETWORK,        |
         *  SUSPENDING              |
         * -------------------------|------------------------
         *  SUSPENDED,              |   QOS_STATE_SUSPENDED
         * --------------------------------------------------
         */
        switch (mDetailedState) {
            case QosSpec.QosIndStates.REQUEST_FAILED:
                mSetupRequested = false;
                notifyQosToSocket = true;
                mState = LinkCapabilities.QosStatus.QOS_STATE_FAILED;
                break;
            case QosSpec.QosIndStates.RELEASED_NETWORK:
            case QosSpec.QosIndStates.RELEASED:
            case QosSpec.QosIndStates.NONE:
                mSetupRequested = false;
                mState = LinkCapabilities.QosStatus.QOS_STATE_INACTIVE;
                //sometimes we need to update even if the coarse QOS_STATE does not change
                notifyQosToSocket = true;
                break;
            case QosSpec.QosIndStates.INITIATED:
                mSetupRequested = true;
                mState = LinkCapabilities.QosStatus.QOS_STATE_INACTIVE;
                break;
            case QosSpec.QosIndStates.ACTIVATED:
            case QosSpec.QosIndStates.MODIFIED:
            case QosSpec.QosIndStates.MODIFIED_NETWORK:
            case QosSpec.QosIndStates.RESUMED_NETWORK:
                //sometimes we need to update even if the coarse QOS_STATE does not change
                notifyQosToSocket = true;
            case QosSpec.QosIndStates.MODIFYING:
            case QosSpec.QosIndStates.SUSPENDING:
            case QosSpec.QosIndStates.RELEASING:
                mState = LinkCapabilities.QosStatus.QOS_STATE_ACTIVE;
                break;
            case QosSpec.QosIndStates.SUSPENDED:
                mState = LinkCapabilities.QosStatus.QOS_STATE_SUSPENDED;
                break;
            default:
                dlogd("CnE got invalid qos indication: " + mDetailedState);
        }
        myCap.put(LinkCapabilities.Key.RO_QOS_STATE, mState);
        //FIXME Querying for a spec for every indication for now.
        //TODO find out for which indications the spec is expected to change
        //and query the spec for those indications only.
        isWaitingForSpecUpdate = getQos(mQosId);

        if (!mState.equals(lastState)) {
            notifyQosToSocket = true;
            lastState = mState;
        }
    }

    private void updateCapabilitiesFromSpec (QosSpec spec) {
        //Only extract flow information for bandiwdth and latency
        //FIXME hardcoding to two flows per spec, viz one fwd and reverse.
        //TODO extract flows as per qos role definition in the config file
        if (spec == null) return;
        dlogi("updateCapabilities got spec: " + spec);

        String temp = null;
        QosSpec.QosPipe txPipe = null;
        QosSpec.QosPipe rxPipe = null;

        for (QosSpec.QosPipe p : spec.getQosPipes()) {
            if (p.get(QosSpec.QosSpecKey.FLOW_DIRECTION).equals(
                  Integer.toString(QosSpec.QosDirection.QOS_TX))) txPipe = p;
            if (p.get(QosSpec.QosSpecKey.FLOW_DIRECTION).equals(
                  Integer.toString(QosSpec.QosDirection.QOS_TX))) rxPipe = p;
        }

        if (txPipe == null && rxPipe == null) {
            dlogw("updateCapabilities expected tx and rx pipes but did not find them");
            return;
        }

        if ((temp = txPipe.get(QosSpec.QosSpecKey.FLOW_DATA_RATE_MIN)) != null) {
            myCap.put(LinkCapabilities.Key.RO_MIN_AVAILABLE_REV_BW, temp);
        }
        if ((temp = txPipe.get(QosSpec.QosSpecKey.FLOW_DATA_RATE_MAX)) != null) {
            myCap.put(LinkCapabilities.Key.RO_MAX_AVAILABLE_REV_BW, temp);
        }
        if ((temp = rxPipe.get(QosSpec.QosSpecKey.FLOW_DATA_RATE_MIN)) != null) {
            myCap.put(LinkCapabilities.Key.RO_MIN_AVAILABLE_FWD_BW, temp);
        }
        if ((temp = rxPipe.get(QosSpec.QosSpecKey.FLOW_DATA_RATE_MAX)) != null) {
            myCap.put(LinkCapabilities.Key.RO_MAX_AVAILABLE_FWD_BW, temp);
        }
        if ((temp = txPipe.get(QosSpec.QosSpecKey.FLOW_LATENCY)) != null) {
            myCap.put(LinkCapabilities.Key.RO_CURRENT_REV_LATENCY, temp);
        }
        if ((temp = rxPipe.get(QosSpec.QosSpecKey.FLOW_LATENCY)) != null) {
            myCap.put(LinkCapabilities.Key.RO_CURRENT_FWD_LATENCY, temp);
        }
        dlogi("updated capabilities to: " + myCap);
    }

    //update Active Capabilities
    //TODO Move this out of here and do this when requestLink is received

    private boolean enableQoS (QosSpec spec, String apnType) {
        boolean res = false;

        if ((apnType == null) || (spec == null)) {
            dloge( "Input parameter(s) is null");
            return res;
        }

        dlogi("requesting qos with spec: " + spec.toString()
                            + " for txId: " + spec.getUserData()
                            + " on apn: " + apnType);

        ITelephony mPhone = getPhone();
        if (mPhone == null) {
            logw("telephony service is unavailable");
            return res;
        }

        try {
            res = (mPhone.enableQos(spec, apnType) == Phone.QOS_REQUEST_SUCCESS);
        } catch (RemoteException re) {
            logw("remote exception while using telephony service: " + re);
        } catch (Exception e) {
            logw("exception while using telephony service: " + e);
        }

        return res;
    }

    private boolean disableQos(int qosId) {
        boolean res = false;

        dlogi( "disabling qos for id: " + qosId);
        ITelephony mPhone = getPhone();
        if (mPhone == null) {
            logw("telephony service is unavailable");
            return res;
        }

        try {
            res = (mPhone.disableQos(qosId) == Phone.QOS_REQUEST_SUCCESS);
        } catch (RemoteException re) {
            logw("remote exception while using telephony service: " + re);
        } catch (Exception e) {
            logw("exception while using telephony service: " + e);
        }

        return res;
    }

    private boolean getQos (int qosId) {
        boolean res = false;

        dlogi( "requesting qos spec for id: " + qosId);
        ITelephony mPhone = getPhone();
        if (mPhone == null) {
            logw("telephony service is unavailable");
            return res;
        }

        try {
            res = (mPhone.getQosStatus(qosId) == Phone.QOS_REQUEST_SUCCESS);
        } catch (RemoteException re) {
            logw("remote exception while using telephony service: " + re);
        } catch (Exception e) {
            logw("exception while using telephony service: " + e);
        }

        dlogi("getQoS returned: " + res);
        return res;
    }

    private boolean modifyQos (int qosId, QosSpec spec) {
        boolean res = false;

        if (spec == null) {
            dlogw( "qos spec is null");
            return res;
        }
        dlogi( "modifying qos spec for id: " + qosId);
        ITelephony mPhone = getPhone();
        if (mPhone == null) {
            logw("telephony service is unavailable");
            return res;
        }

        try {
            res = (mPhone.modifyQos(qosId, spec) == Phone.QOS_REQUEST_SUCCESS);
        } catch (RemoteException re) {
            logw("remote exception while using telephony service: " + re);
        } catch (Exception e) {
            logw("exception while using telephony service: " + e);
        }

        return res;
    }

    private ITelephony getPhone() {
      return ITelephony.Stub.asInterface(ServiceManager.getService("phone"));
    }

    /* logging macros */
    private void logd (String s) {
        Log.d(LOG_TAG,s);
    }
    private void loge (String s) {
        Log.e(LOG_TAG,s);
    }
    private void logw (String s) {
        Log.w(LOG_TAG,s);
    }
    private void logv (String s) {
        Log.v(LOG_TAG,s);
    }
    private void logi (String s) {
        Log.i(LOG_TAG,s);
    }

    private void dlogd (String s) {
        if (DBG) Log.d(LOCAL_TAG,s);
    }
    private void dloge (String s) {
        if (DBG) Log.e(LOCAL_TAG,s);
    }
    private void dlogw (String s) {
        if (DBG) Log.w(LOCAL_TAG,s);
    }
    private void dlogv (String s) {
        if (DBG) Log.v(LOCAL_TAG,s);
    }
    private void dlogi (String s) {
        if (DBG) Log.i(LOCAL_TAG,s);
    }
}
