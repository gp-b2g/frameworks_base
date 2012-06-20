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

package com.android.server;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.ExtraLinkCapabilities;
import android.net.ILinkSocketMessageHandler;
import android.net.LinkAddress;
import android.net.LinkCapabilities;
import android.net.LinkProperties;
import android.net.QoSTracker;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.telephony.TelephonyManager;
import android.util.Log;

import java.io.FileInputStream;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Map.Entry;

import com.android.internal.net.IPVersion;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.QosSpec;
import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.telephony.QosSpec.QosPipe;

/**
 * This class that takes care of everything related to QoS. LinkManager simply
 * forwards the request to QoS Manager and QoS Manager processed the request and
 * send the response back.
 */
public class QosManager {
    private static final String LOG_TAG = "QoSManager";
    private static final int FAILURE_GENERAL = -1;
    private static final String IPV4 = "IP";
    private static final String IPV6 = "IPV6";
    private static final String TOS_MASK = "255";
    private static final String FILTER_DELIMETER = ",";
    private static final boolean DEBUG = true;
    private static final String QOS_POLICY_FILE_NAME = "/system/etc/QoSPolicy.xml";

    private Context mContext;
    private ConnectivityService mService;
    private QosProfile mQosProfile;
    private boolean mQoSProfileReady;
    private boolean mUseSrcPort = SystemProperties.getBoolean(
            "persist.radio.qos.use.src.port", true);
    Collection<QoSTracker> mQosTrackers;

    /**
     * This class stores the destination/source start value and the range value
     * to be used by to set port filters in QoS spec
     */
    private class PortRange {
        int portStartVal;
        int portRangeVal;
    }

    /**
     * Broadcast receiver to receive the QoS state indication intents
     */
    BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();

            if (action.equals(TelephonyIntents.ACTION_QOS_STATE_IND)) {
                int qosIndState = intent
                        .getIntExtra(QosSpec.QosIntentKeys.QOS_INDICATION_STATE, -1);
                int qosState = intent.getIntExtra(QosSpec.QosIntentKeys.QOS_STATUS, -1);
                int qosId = intent.getIntExtra(QosSpec.QosIntentKeys.QOS_ID, -1);
                int txId = intent.getIntExtra(QosSpec.QosIntentKeys.QOS_USERDATA, -1);
                QosSpec myQos = new QosSpec();
                myQos = (QosSpec) intent.getExtras().getParcelable(QosSpec.QosIntentKeys.QOS_SPEC);
                updateQosStatus(txId, qosId, qosIndState, qosState, myQos);
            } else {
                logw("Received unexpected action: " + action);
            }
        }
    };

    /**
     * Constructor
     *
     * @param context
     * @param connectivityService
     */
    public QosManager(Context context, ConnectivityService connectivityService) {
        mContext = context;
        mService = connectivityService;

        // Maintain separate container to track QoS specific registrations. Does
        // not distinguish between LinkSocket and LinkDatagramSocket.
        mQosTrackers = Collections.synchronizedCollection(new ArrayList<QoSTracker>());

        // Create new QoSProfile object and parse the QoS policy file
        mQosProfile = new QosProfile();
        mQoSProfileReady = false;
        String qosPolicyFilename = SystemProperties.get("persist.qos.policy.loc",
                QOS_POLICY_FILE_NAME);
        logd("QoS Policy file name: " + qosPolicyFilename);
        logd("Use source port and address filtering: " + mUseSrcPort);
        try {
            mQoSProfileReady = mQosProfile.parse(new FileInputStream(qosPolicyFilename));
        } catch (Exception e) {
            loge("QoS Policy file not found: " + qosPolicyFilename);
        }

        // Register for QoS indications
        IntentFilter filter = new IntentFilter();
        filter.addAction(TelephonyIntents.ACTION_QOS_STATE_IND);
        context.registerReceiver(mIntentReceiver, filter);
    }

    /**
     * Checks if the link request by user is available and notify accordingly
     *
     * @param id : id to use to tie the request to a specific application. This
     *            id is generated by CNE service and passed it to
     *            {@code QoSManager} so it can return this id to the socket
     *            class. This id is used by the socket class for all future
     *            operations that request to modify the link
     * @param cap
     * @param remoteIPAddress
     * @param binder
     * @return id
     */
    public int requestLink(int id, LinkCapabilities cap, String remoteIPAddress, IBinder binder)
            throws RemoteException {
        ILinkSocketMessageHandler callback = ILinkSocketMessageHandler.Stub.asInterface(binder);
        LinkAddress linkAddress = null;
        String networkType = null;
        IPVersion ipVersion = IPVersion.INET;
        LinkAddress v6DefaultAddr = null;

        // Create ExtraLinkCapabilities from LinkCapabilities to be
        // used by QoSTracker
        ExtraLinkCapabilities xcap = new ExtraLinkCapabilities();
        xcap.put(LinkCapabilities.Key.RO_QOS_STATE, LinkCapabilities.QosStatus.QOS_STATE_INACTIVE);
        for (Entry<Integer, String> need : cap.entrySet()) {
            xcap.put(need.getKey(), need.getValue());
        }

        // Check if QoS is already requested on this socket
        if (findQosBySocketId(id) == null) {
            synchronized (mQosTrackers) {
                try {
                    if (!mQosTrackers.add(new QoSTracker(id, callback, xcap))) {
                        loge("Failed to track qos request for socket: " + id);
                        callback.onGetLinkFailure(FAILURE_GENERAL);
                        return id;
                    }
                } catch (Exception e) {
                    loge("Error while tracking qos request from the socket:" + id + " "
                            + e.toString());
                    callback.onGetLinkFailure(FAILURE_GENERAL);
                    return id;
                }
            }
        } else {
            logd("Ignoring duplicate request link of qos role for socket: " + id);
        }

        // Get the network type, If no network is specified the use the default
        // network
        networkType = xcap.get(LinkCapabilities.Key.RW_NETWORK_TYPE);
        if (networkType == null) {
            networkType = Integer.toString(ConnectivityManager.TYPE_MOBILE);
            xcap.put(LinkCapabilities.Key.RW_NETWORK_TYPE, networkType);
        }

        // Get the IP version
        ipVersion = getIPVersion(remoteIPAddress);
        if (ipVersion == null) {
            callback.onGetLinkFailure(FAILURE_GENERAL);
            return id;
        }

        // Get the link properties for the given network Type
        LinkProperties prop = mService.getLinkProperties(Integer.parseInt(networkType));

        // If link properties is null then the data call on the given network is
        // not active
        if (prop == null) {
            loge("Link not available for network type " + networkType);
            callback.onGetLinkFailure(FAILURE_GENERAL);
            return id;
        }

        // Find the IP address from the list of addresses for the given IP
        // version
        Collection<LinkAddress> linkAddresses = prop.getLinkAddresses();
        linkAddress = null;
        for (LinkAddress linkAddr : linkAddresses) {
            InetAddress addr = linkAddr.getAddress();

            if ((addr instanceof Inet4Address) && (ipVersion == IPVersion.INET)) {
                linkAddress = linkAddr;
                break;
            }
            if ((addr instanceof Inet6Address) && (ipVersion == IPVersion.INET6)) {
                // If the address is link local or loopback address then QoS
                // is not applied to those addresses so continue looking for
                // global routable IP address and use the link local or loopback
                // address if there is no routable address
                if (addr.isLinkLocalAddress() || addr.isLoopbackAddress()) {
                    v6DefaultAddr = linkAddr;
                    continue;
                }

                linkAddress = linkAddr;
                break;
            }
        }

        // If ipAddress is null then the data call on the given network is not
        // active
        if (linkAddress == null) {
            if (v6DefaultAddr == null) {
                loge("No IP address available for network type " + networkType
                        + " and IP version: " + ipVersion);
                callback.onGetLinkFailure(FAILURE_GENERAL);
                return id;
            }
            else {
                linkAddress = v6DefaultAddr;
            }
        }

        // IP address retrieved successfully, notify user
        logd("IPaddress for network type: " + networkType + " and IP version: "
                + ipVersion + " is: " + linkAddress.getAddress().getHostAddress());
        prop = new LinkProperties();
        prop.addLinkAddress(linkAddress);
        callback.onLinkAvail(prop);

        return id;
    }

    /**
     * @hide Start QoS transaction request for the specified socket id and port
     * @param id
     * @param aport
     * @param localAddress
     * @return true if request for qos is initiates successfully, false
     *         otherwise
     */
    public boolean requestQoS(int id, int port, String localAddress) {
        QoSTracker qt = null;
        LinkCapabilities cap = null;
        String apnType = null;
        QosSpec mySpec = null;

        qt = findQosBySocketId(id);
        if (qt == null) return false;
        cap = qt.getQosCapabilities();
        if (cap == null) return false;

        // Get the APN type
        apnType = networkTypeToApnType(Integer.parseInt(cap
                .get(LinkCapabilities.Key.RW_NETWORK_TYPE)));

        // If user specified the custom QoS role then create the spec from link
        // capabilities or else get the QoSSpec from the QoS policy file
        String roleString = cap.get(LinkCapabilities.Key.RW_ROLE);
        if (roleString.equals(LinkCapabilities.Role.QOS_CUSTOM)) {
            mySpec = prepareQoSSpec(port, localAddress, cap);
        } else {
            mySpec = prepareQoSSpecFromQoSPolicy(port, localAddress, cap);
        }

        if (mySpec == null) {
            loge("Failed to prepare qos spec for socket: " + id);
            return false;
        }
        return qt.startQosTransaction(mySpec, apnType);
    }

    /**
     * This method suspends the QoS if available for the specified socket id
     *
     * @param id
     * @return
     */
    public boolean suspendQoS(int id) {
        QoSTracker qt = null;

        qt = findQosBySocketId(id);
        if (qt == null) return false;

        return qt.suspendQosTransaction();
    }

    /**
     * This method resume the QoS if suspended for the specified socket id
     *
     * @param id
     * @return
     */
    public boolean resumeQoS(int id) {
        QoSTracker qt = null;

        qt = findQosBySocketId(id);
        if (qt == null) return false;

        return qt.resumeQosTransaction();
    }

    /**
     * @hide
     * Stop QoS transaction for the specified socket id
     *
     * @param id
     * @return true on success, false on error
     */
    public boolean releaseQos(int id) {
        QoSTracker qt = findQosBySocketId(id);
        if (qt != null) {
            qt.stopQosTransaction(); // release associated QoS
            synchronized (mQosTrackers) {
                try {
                    mQosTrackers.remove(qt);
                    logd("Stopped tracking qos for id: " + id);
                } catch (Exception e) {
                    loge("Error while removing qos tracker: " + e.toString());
                    return false;
                }
                mService.removeQosRegistration(id);
            }
        } else {
            logw("No QoSTracker available for id: " + id);
        }
        return true;
    }

    /** @hide
     * Returns current state for Quality of Service for this socket
     *
     * @param id
     * @return
     */
    public String getQosState(int id) {
        QoSTracker qt = findQosBySocketId(id);
        LinkCapabilities cap;
        if ((qt != null) && ((cap = qt.getQosCapabilities()) != null)) {
            return cap.get(LinkCapabilities.Key.RO_QOS_STATE);
        } else {
            return null;
        }
    }

    /** @hide
     * Minimum available reverse link (upload) bandwidth for the socket.
     * This value is in kilobits per second (kbps).
     *
     * @param id
     * @return
     */
    public String getMinAvailableForwardBandwidth(int id) {
        QoSTracker qt = findQosBySocketId(id);
        LinkCapabilities cap;
        if ((qt != null) && ((cap = qt.getQosCapabilities()) != null)) {
            return cap.get(LinkCapabilities.Key.RO_MIN_AVAILABLE_FWD_BW);
        } else {
            return null;
        }
    }

    /** @hide
     * Maximum available forward link (download) bandwidth for the socket.
     * This value is in kilobits per second (kbps).
     *
     * @param id
     * @return
     */
    public String getMaxAvailableForwardBandwidth(int id) {
        QoSTracker qt = findQosBySocketId(id);
        LinkCapabilities cap;
        if ((qt != null) && ((cap = qt.getQosCapabilities()) != null)) {
            return cap.get(LinkCapabilities.Key.RO_MAX_AVAILABLE_FWD_BW);
        } else {
            return null;
        }
    }

    /** @hide
     * Minimum available reverse link (upload) bandwidth for the socket.
     * This value is in kilobits per second (kbps).
     *
     * @param id
     * @return
     */
    public String getMinAvailableReverseBandwidth(int id) {
        QoSTracker qt = findQosBySocketId(id);
        LinkCapabilities cap;
        if ((qt != null) && ((cap = qt.getQosCapabilities()) != null)) {
            return cap.get(LinkCapabilities.Key.RO_MIN_AVAILABLE_REV_BW);
        } else {
            return null;
        }
    }

    /** @hide
     * Maximum available reverse link (upload) bandwidth for the socket.
     * This value is in kilobits per second (kbps).
     *
     * @param id
     * @return
     */
    public String getMaxAvailableReverseBandwidth(int id) {
        QoSTracker qt = findQosBySocketId(id);
        LinkCapabilities cap;
        if ((qt != null) && ((cap = qt.getQosCapabilities()) != null)) {
            return cap.get(LinkCapabilities.Key.RO_MAX_AVAILABLE_REV_BW);
        } else {
            return null;
        }
    }

    /** @hide
     * Current estimated downlink latency of the socket, in milliseconds.
     *
     * @param id
     * @return
     */
    public String getCurrentFwdLatency(int id) {
        QoSTracker qt = findQosBySocketId(id);
        LinkCapabilities cap;
        if ((qt != null) && ((cap = qt.getQosCapabilities()) != null)) {
            return cap.get(LinkCapabilities.Key.RO_CURRENT_FWD_LATENCY);
        } else {
            return null;
        }
    }

    /** @hide
     * Current estimated uplink latency of the socket, in milliseconds.
     *
     * @param id
     * @return
     */
    public String getCurrentRevLatency(int id) {
        QoSTracker qt = findQosBySocketId(id);
        LinkCapabilities cap;
        if ((qt != null) && ((cap = qt.getQosCapabilities()) != null)) {
            return cap.get(LinkCapabilities.Key.RO_CURRENT_REV_LATENCY);
        } else {
            return null;
        }
    }

    /**
     * Calls QoSTracker to handle QoS event
     *
     * @param txId
     * @param qosId
     * @param qosIndState
     * @param qosState
     * @param myQos
     */
    private void updateQosStatus(int txId, int qosId, int qosIndState, int qosState,
            QosSpec myQos) {
        // Lookup using txid only when status is initiated since we don't have a
        // qosId yet.
        QoSTracker qt = (qosIndState == QosSpec.QosIndStates.INITIATED) ?
                findQosBySocketId(txId) : findQosByQosId(qosId);
        logd("updateQosStatus got indication: " + qosIndState
                + " qosState: " + qosState
                + " txId: " + txId
                + " qosId: " + qosId);
        if (qt != null) {
            qt.handleQosEvent(qosId, qosIndState, qosState, myQos);
        }
        else {
            loge("updateQosStatus did not find a handle to sid: " + txId + " qid: "
                    + qosId);
        }
    }

    /**
     * Create QoS spec based on link capabilities as specified by the user
     *
     * @param localPort is currently not used. Would be used in future to
     *            specify src port filter
     * @param localAddress is used to get the IP version to use for filter.
     *            Would be used in future to specify src ip address filter
     * @param myCap
     * @return QoSSpec is successfully created, null on error
     */
    private QosSpec prepareQoSSpec(int localPort, String localAddress, LinkCapabilities myCap) {
        // Prepare QoSSpec as per the role defined.
        logd("Preparing qos spec");
        QosSpec mQosSpec = new QosSpec();
        QosSpec.QosPipe txPipe = null;
        QosSpec.QosPipe rxPipe = null;
        String value = null;

        if (myCap == null) {
            loge("prepareQosSpec failed because needs is null");
            return null;
        }

        /*
         * lookup needs and translate to QosSpec
         * The Flow and filter information is translated from capabilities
         * to QoS Spec as follows:
         * ------------------------------------------------------------------------------------
         * Capabilities key                     QosSpec direction and key combo
         * ------------------------------------------------------------------------------------
         * RW_DESIRED_FWD_BW            ==>     QOS_RX, FLOW_DATA_RATE_MAX
         * RW_REQUIRED_FWD_BW           ==>     QOS_RX, FLOW_DATA_RATE_MIN
         * RW_DESIRED_REV_BW            ==>     QOS_TX, FLOW_DATA_RATE_MAX
         * RW_REQUIRED_REV_BW           ==>     QOS_TX, FLOW_DATA_RATE_MIN
         * RW_MAX_ALLOWED_FWD_LATENCY   ==>     QOS_RX, FLOW_LATENCY
         * RW_MAX_ALLOWED_REV_LATENCY   ==>     QOS_TX, FLOW_LATENCY
         * RO_TRANSPORT_PROTO_TYPE,
         *     RW_REMOTE_DEST_PORTS     ==>     QOS_TX,
         *                                          FILTER_[PROTO]_DESTINATION_PORT_START,
         *                                          FILTER_[PROTO]_DESTINATION_PORT_RANGE
         *     localPort (local)        ==>         FILTER_[PROTO]_SOURCE_PORT_START,
         *     SOURCE_PORT_RANGE(local) ==>         FILTER_[PROTO]_SOURCE_PORT_RANGE
         *
         * RW_DEST_IP_ADDRESSES         ==>     QOS_TX, FILTER_IPV4_DESTINATION_ADDR
         * localAddress (local)         ==>     QOS_TX, FILTER_IPV4_SOURCE_ADDR,
         *                              ==>     QOS_RX, FILTER_IPV4_DESTINATION_ADDR
         * RW_FILTERSPEC_IP_TOS         ==>     QOS_TX, FILTER_IPV4_TOS
         * TOS_MASK (local)             ==>     QOS_TX, FILTER_IPV4_TOS_MASK
         * ------------------------------------------------------------------------------------
         *
         * *******************************************************************
         *  IMPORTANT: if the capability is not defined then do not populate
         *  the spec with null values, the modem will reject it.
         * *******************************************************************
         */

        /* Add TX flow */
        if ((value = myCap.get(LinkCapabilities.Key.RW_DESIRED_REV_BW)) != null) {
            txPipe = addQosTxFlow(mQosSpec, txPipe, QosSpec.QosSpecKey.FLOW_DATA_RATE_MAX, value);
        }

        if ((value = myCap.get(LinkCapabilities.Key.RW_REQUIRED_REV_BW)) != null) {
            txPipe = addQosTxFlow(mQosSpec, txPipe, QosSpec.QosSpecKey.FLOW_DATA_RATE_MIN, value);
        }

        if ((value = myCap.get(LinkCapabilities.Key.RW_MAX_ALLOWED_REV_LATENCY)) != null) {
            txPipe = addQosTxFlow(mQosSpec, txPipe, QosSpec.QosSpecKey.FLOW_LATENCY, value);
        }

        if ((value = myCap.get(LinkCapabilities.Key.RW_REV_TRAFFIC_CLASS)) != null) {
            txPipe = addQosTxFlow(mQosSpec, txPipe, QosSpec.QosSpecKey.FLOW_TRAFFIC_CLASS, value);
        }

        if ((value = myCap.get(LinkCapabilities.Key.RW_REV_3GPP2_PROFILE_ID)) != null) {
            txPipe = addQosTxFlow(mQosSpec, txPipe, QosSpec.QosSpecKey.FLOW_3GPP2_PROFILE_ID,
                    value);
        }

        if ((value = myCap.get(LinkCapabilities.Key.RW_REV_3GPP2_PRIORITY)) != null) {
            txPipe = addQosTxFlow(mQosSpec, txPipe, QosSpec.QosSpecKey.FLOW_3GPP2_PRIORITY, value);
        }

        /* Add RX flow */
        if ((value = myCap.get(LinkCapabilities.Key.RW_DESIRED_FWD_BW)) != null) {
            rxPipe = addQosRxFlow(mQosSpec, rxPipe, QosSpec.QosSpecKey.FLOW_DATA_RATE_MAX, value);
        }

        if ((value = myCap.get(LinkCapabilities.Key.RW_REQUIRED_FWD_BW)) != null) {
            rxPipe = addQosRxFlow(mQosSpec, rxPipe, QosSpec.QosSpecKey.FLOW_DATA_RATE_MIN, value);
        }

        if ((value = myCap.get(LinkCapabilities.Key.RW_MAX_ALLOWED_FWD_LATENCY)) != null) {
            rxPipe = addQosRxFlow(mQosSpec, rxPipe, QosSpec.QosSpecKey.FLOW_LATENCY, value);
        }

        if ((value = myCap.get(LinkCapabilities.Key.RW_FWD_3GPP2_PROFILE_ID)) != null) {
            rxPipe = addQosRxFlow(mQosSpec, rxPipe, QosSpec.QosSpecKey.FLOW_3GPP2_PROFILE_ID, value);
        }

        if ((value = myCap.get(LinkCapabilities.Key.RW_FWD_3GPP2_PRIORITY)) != null) {
            rxPipe = addQosRxFlow(mQosSpec, rxPipe, QosSpec.QosSpecKey.FLOW_3GPP2_PRIORITY, value);
        }

        if ((value = myCap.get(LinkCapabilities.Key.RW_FWD_TRAFFIC_CLASS)) != null) {
            rxPipe = addQosRxFlow(mQosSpec, rxPipe, QosSpec.QosSpecKey.FLOW_TRAFFIC_CLASS, value);
        }

        // Set filter spec
        if (prepareQoSFilter(myCap, txPipe, rxPipe, localPort, localAddress) == false) {
            return null;
        }

        logd("Prepared qos spec: " + mQosSpec);
        return mQosSpec;
    }

    /**
     * Get QoS spec from the QoS policy file based on the role id and the rat id
     *
     * @param localPort
     * @param localAddress
     * @param cap
     * @return QosSpec instance on success and null on error
     */
    private QosSpec prepareQoSSpecFromQoSPolicy(int localPort, String localAddress,
            LinkCapabilities cap) {
        String role = null;
        QosSpec mQosSpec = null;
        QosSpec.QosPipe txPipe = null;
        QosSpec.QosPipe rxPipe = null;

        // If there is an error reading the QoS profile file then return null
        if (mQoSProfileReady == false) {
            loge("Error while parsing QoS policy file");
            return null;
        }

        // Get qos spec based on role id and rat id
        role = cap.get(LinkCapabilities.Key.RW_ROLE);
        TelephonyManager telephonyManager = (TelephonyManager) mContext
                .getSystemService(Context.TELEPHONY_SERVICE);
        if (telephonyManager == null) {
            loge("TelephonyManager is null");
            return null;
        }
        int ratId = telephonyManager.getNetworkType();
        mQosSpec = mQosProfile.getQoSSpec(role, ratId);

        // If no QoS spec is found then return null
        if (mQosSpec == null) {
            loge("No QoS spec matching role Id:" + role
                    + ", rat Id:" + ratId + " found in QoSPolicy file");
            return null;
        }

        /**
         * The QoS spec in the QoS policy file would usually contain only the
         * flow spec. Add the filter spec based on QoS filter keys specified by
         * user using LinkCapabilities But, first we need to find the pipe
         * corresponding to Tx and Rx direction
         */
        txPipe = findQoSPipeByDirection(mQosSpec, QosSpec.QosDirection.QOS_TX);
        rxPipe = findQoSPipeByDirection(mQosSpec, QosSpec.QosDirection.QOS_RX);

        // Set filter spec
        if (prepareQoSFilter(cap, txPipe, rxPipe, localPort, localAddress) == false) {
            return null;
        }

        logd("Prepared qos spec: " + mQosSpec);
        return mQosSpec;
    }

    /**
     * This method sets the filter spec in the Tx and Rx QoS pipes based on link
     * capabilities
     *
     * @param cap
     * @param txPipe
     * @param rxPipe
     * @param localAddress
     * @param localPort
     * @return true on success and false on error
     */
    private boolean prepareQoSFilter(LinkCapabilities cap, QosPipe txPipe, QosPipe rxPipe,
            int localPort, String localAddress) {
        String value = null;
        String values[] = null;
        int srcPortStartKey, srcPortRangeKey;
        int dstPortStartKey, dstPortRangeKey;
        PortRange dstPorts = new PortRange();
        PortRange srcPorts = new PortRange();
        IPVersion ipVersion;
        int filterIndex = 0;

        logd("Preparing QoS filter");
        int numTxFilters = getMaxTxFilterCount(cap);
        int numRxFilters = getMaxRxFilterCount(cap);

        // If the LinkSocket source port is set automatically then there is at
        // least one filter that will always be present in the QoS Spec
        if (mUseSrcPort) {
            if (numTxFilters == -1) {
                numTxFilters = 1;
            }
            if (numRxFilters == -1) {
                numRxFilters= 1;
            }
        }

        // No filter specified
        if ((numTxFilters == -1) && (numRxFilters == -1)) {
            loge("User didn't specify any QoS filter keys");
            return false;
        }

        // Get the IP version
        ipVersion = getIPVersion(localAddress);
        if (ipVersion == null) {
            return false;
        }

        // Create transport specific port keys, and use them to apply
        // the filter spec for ports
        if ((value = cap.get(LinkCapabilities.Key.RO_TRANSPORT_PROTO_TYPE)) == null) {
            logd("prepareQosSpec failed - transport protocol is not set");
            return false;
        }
        if (value.equals("udp")) {
            srcPortStartKey = QosSpec.QosSpecKey.FILTER_UDP_SOURCE_PORT_START;
            srcPortRangeKey = QosSpec.QosSpecKey.FILTER_UDP_SOURCE_PORT_RANGE;
            dstPortStartKey = QosSpec.QosSpecKey.FILTER_UDP_DESTINATION_PORT_START;
            dstPortRangeKey = QosSpec.QosSpecKey.FILTER_UDP_DESTINATION_PORT_RANGE;
        } else if (value.equals("tcp")) {
            srcPortStartKey = QosSpec.QosSpecKey.FILTER_TCP_SOURCE_PORT_START;
            srcPortRangeKey = QosSpec.QosSpecKey.FILTER_TCP_SOURCE_PORT_RANGE;
            dstPortStartKey = QosSpec.QosSpecKey.FILTER_TCP_DESTINATION_PORT_START;
            dstPortRangeKey = QosSpec.QosSpecKey.FILTER_TCP_DESTINATION_PORT_RANGE;
        } else {
            logw("prepareQosSpec failed - unrecognized transport: " + value);
            return false;
        }

        // Set the filters for Tx pipe
        if (txPipe != null) {
            // Loop to create multiple filters
            for (filterIndex = 0; filterIndex < numTxFilters; filterIndex++) {
                // Apply filter index
                txPipe.put(QosSpec.QosSpecKey.FILTER_INDEX, Integer.toString(filterIndex));

                // Apply IP version
                if (ipVersion == IPVersion.INET) {
                    txPipe.put(QosSpec.QosSpecKey.FILTER_IPVERSION, IPV4);
                } else {
                    txPipe.put(QosSpec.QosSpecKey.FILTER_IPVERSION, IPV6);
                }

                // Apply filter direction
                txPipe.put(QosSpec.QosSpecKey.FILTER_DIRECTION, "0");

                // Specify the local port for the QoS so no other application
                // can use this QoS
                if (mUseSrcPort) {
                    // Set the port passed by linkSocket after it is bound as
                    // source port for tx flow
                    txPipe.put(srcPortStartKey, Integer.toString(localPort));
                    txPipe.put(srcPortRangeKey, "0");
                }

                // Parse the destination port/range
                if (false == parsePortRange(cap, LinkCapabilities.Key.RW_REMOTE_DEST_PORTS,
                        filterIndex, dstPorts)) {
                    return false;
                }

                // Apply the destination port/range. The value of 0 means either
                // user didn't specify the key or specified the key with no
                // value. Either way ignore setting the filter
                if (dstPorts.portStartVal != 0) {
                    txPipe.put(dstPortStartKey, Integer.toString(dstPorts.portStartVal));
                    txPipe.put(dstPortRangeKey, Integer.toString(dstPorts.portRangeVal));
                }

                // Apply destination ip address filter spec as dst for tx
                if (((value = cap.get(LinkCapabilities.Key.RW_REMOTE_DEST_IP_ADDRESSES)) != null)
                        && ((values = value.split(FILTER_DELIMETER)).length > filterIndex)) {
                    value = values[filterIndex].replaceAll("\\s", "");
                    if (!value.isEmpty()) {
                        if (ipVersion == IPVersion.INET) {
                            txPipe.put(QosSpec.QosSpecKey.FILTER_IPV4_DESTINATION_ADDR, value);
                        } else {
                            txPipe.put(QosSpec.QosSpecKey.FILTER_IPV6_DESTINATION_ADDR, value);
                        }
                    }
                }

                // Apply TOS
                if (((value = cap.get(LinkCapabilities.Key.RW_FILTERSPEC_REV_IP_TOS)) != null)
                        && ((values = value.split(FILTER_DELIMETER)).length > filterIndex)) {
                    value = values[filterIndex].replaceAll("\\s", "");
                    if (!value.isEmpty()) {
                        txPipe.put(QosSpec.QosSpecKey.FILTER_IPV4_TOS, value);
                        txPipe.put(QosSpec.QosSpecKey.FILTER_IPV4_TOS_MASK, TOS_MASK);
                    }
                }
            }
        }

        // Set the filters for Rx pipe
        if (rxPipe != null) {
            // Loop to create multiple filters
            for (filterIndex = 0; filterIndex < numRxFilters; filterIndex++) {
                // Apply filter index
                rxPipe.put(QosSpec.QosSpecKey.FILTER_INDEX, Integer.toString(filterIndex));

                // Apply IP version
                if (ipVersion == IPVersion.INET) {
                    rxPipe.put(QosSpec.QosSpecKey.FILTER_IPVERSION, IPV4);
                } else {
                    rxPipe.put(QosSpec.QosSpecKey.FILTER_IPVERSION, IPV6);
                }

                // Apply filter direction
                rxPipe.put(QosSpec.QosSpecKey.FILTER_DIRECTION, "1");

                // Specify the local port for the QoS so no other application
                // can use this QoS
                if (mUseSrcPort) {
                    // Set the port passed by linkSocket after it is bound as
                    // the destination port for rx flow
                    rxPipe.put(dstPortStartKey, Integer.toString(localPort));
                    rxPipe.put(dstPortRangeKey, "0");
                }

                // Parse the source port/range
                if (false == parsePortRange(cap, LinkCapabilities.Key.RW_REMOTE_SRC_PORTS,
                        filterIndex, srcPorts)) {
                    return false;
                }

                // Apply the source port/range. The value of 0 means either
                // user didn't specify the key or specified the key with no
                // value. Either way ignore setting the filter
                if (srcPorts.portStartVal != 0) {
                    rxPipe.put(srcPortStartKey, Integer.toString(srcPorts.portStartVal));
                    rxPipe.put(srcPortRangeKey, Integer.toString(srcPorts.portRangeVal));
                }

                // Apply destination ip address filter spec as src for rx flow
                if (((value = cap.get(LinkCapabilities.Key.RW_REMOTE_SRC_IP_ADDRESSES)) != null)
                        && ((values = value.split(FILTER_DELIMETER)).length > filterIndex)) {
                    value = values[filterIndex].replaceAll("\\s", "");
                    if (!value.isEmpty()) {
                        if (ipVersion == IPVersion.INET) {
                            rxPipe.put(QosSpec.QosSpecKey.FILTER_IPV4_SOURCE_ADDR, value);
                        } else {
                            rxPipe.put(QosSpec.QosSpecKey.FILTER_IPV6_SOURCE_ADDR, value);
                        }
                    }
                }

                // Apply TOS
                if (((value = cap.get(LinkCapabilities.Key.RW_FILTERSPEC_FWD_IP_TOS)) != null)
                        && ((values = value.split(FILTER_DELIMETER)).length > filterIndex)) {
                    value = values[filterIndex].replaceAll("\\s", "");
                    if (!value.isEmpty()) {
                        rxPipe.put(QosSpec.QosSpecKey.FILTER_IPV4_TOS, value);
                        rxPipe.put(QosSpec.QosSpecKey.FILTER_IPV4_TOS_MASK, TOS_MASK);
                    }
                }
            }
        }

        return true;
    }

    /**
     * Find the QoSTracker object for a given socket id
     *
     * @param id
     * @return
     */
    private QoSTracker findQosBySocketId(int id) {
        QoSTracker tracker = null;
        synchronized (mQosTrackers) {
            for (QoSTracker qt : mQosTrackers) {
                if (qt == null) continue;
                if (id == qt.getSocketId()) {
                    tracker = qt;
                    break;
                }
            }
        }
        if (tracker == null) {
            loge("No QoSTracker available with id: " + id);
        }
        return tracker;
    }

    /**
     * Find the QoSTracker object for a given QoS id
     *
     * @param id
     * @return
     */
    private QoSTracker findQosByQosId(int id) {
        QoSTracker tracker = null;
        synchronized (mQosTrackers) {
            for (QoSTracker qt : mQosTrackers) {
                if (qt == null) continue;
                if (id == qt.getQosId()) {
                    tracker = qt;
                    break;
                }
            }
        }
        return tracker;
    }

    /**
     * This method finds a pipe in the given direction in a QosSpec
     *
     * @param qosSpec
     * @param direction
     * @return
     */
    private QosSpec.QosPipe findQoSPipeByDirection(QosSpec qosSpec, int direction) {
        Collection<QosPipe> qosPipes = qosSpec.getQosPipes();

        for (QosPipe pipe : qosPipes) {
            String directionValue = pipe.get(QosSpec.QosSpecKey.FLOW_DIRECTION);
            if ((directionValue != null) && (directionValue.equals(Integer.toString(direction)))) {
                if (DEBUG) logd("Found pipe with direction: " + direction);
                return pipe;
            }
        }
        return null;
    }

    /**
     * This method returns if the given IP address is of type IPv4 or IPv6
     *
     * @param remoteIPAddress
     * @return
     */
    private IPVersion getIPVersion(String remoteIPAddress) {
        // If no address is specified
        if (remoteIPAddress == null) {
            loge("remoteIPAddress is null, returning IPv4 version");
            // prepareQoSFilter method uses this to set IP version.
            // If user doesn't specify the hostname then default to IPv4
            return IPVersion.INET;
        }

        // Get the IP version from the InetAddress
        InetAddress anAddress;
        try {
            anAddress = InetAddress.getByName(remoteIPAddress);
            if (anAddress instanceof Inet6Address) {
                return IPVersion.INET6;
            } else {
                return IPVersion.INET;
            }
        } catch (UnknownHostException e1) {
            loge("IPAddress is invalid");
            return null;
        }
    }

    /**
     * This method parses the port key to find the port number and the port
     * range. The format of port range is "port-range". For eg., 1020-1040,
     * which means that the port value specified by user is 1020 with a range
     * of 20.
     *
     * @param cap
     * @param key
     * @param filterIndex
     * @param ports port[0] is the start value and ports[1] is the range value
     * @return true if port number is formatted correctly, false otherwise
     */
    private boolean parsePortRange(LinkCapabilities cap, int key, int filterIndex,
            PortRange ports) {
        String value;
        String values[] = null;
        ports.portStartVal = 0;
        ports.portRangeVal = 0;

        // If user specified the key at the given filter index then parse the
        // port/range
        if (((value = cap.get(key)) != null)
                && ((values = value.split(FILTER_DELIMETER)).length > filterIndex)) {
            value = values[filterIndex].replaceAll("\\s", "");

            try {
                // Validate if port is formatted correctly.
                if (value.contains("-")) {
                    String[] tok = value.split("-");
                    if ((tok.length != 2) || ((ports.portStartVal = Integer.parseInt(tok[0])) >
                            (ports.portRangeVal = Integer.parseInt(tok[1])))) {
                        loge("startQos failed due to invalid port format: " + value);
                        return false;
                    }
                    ports.portRangeVal = ports.portRangeVal - ports.portStartVal;
                } else if (value.isEmpty()) {
                    ports.portStartVal = 0;
                    ports.portRangeVal = 0;
                } else {
                    ports.portStartVal = Integer.parseInt(value);
                    ports.portRangeVal = 0;
                }
            } catch (NumberFormatException e) {
                loge("startQos failed due to invalid port, exception: " + e.toString());
                return false;
            }
        }

        return true;
    }

    /**
     * This method parses the LinkCapabilities keys to find the maximum number
     * of filters specified by the user in any TX filter key.
     *
     * @param cap
     * @return number of filters on success and -1 on error
     */
    private int getMaxTxFilterCount(LinkCapabilities cap) {
        int numFilters = -1;
        int numStrings = -1;

        if ((cap.get(LinkCapabilities.Key.RW_REMOTE_DEST_PORTS)) != null) {
            numStrings = cap.get(LinkCapabilities.Key.RW_REMOTE_DEST_PORTS).
                    split(FILTER_DELIMETER).length;
            if (DEBUG) {
                logd("Number of filters in RW_REMOTE_DEST_PORTS is: " + numStrings);
            }
            if (numStrings > numFilters) {
                numFilters = numStrings;
            }
        }

        if ((cap.get(LinkCapabilities.Key.RW_REMOTE_DEST_IP_ADDRESSES)) != null) {
            numStrings = cap.get(LinkCapabilities.Key.RW_REMOTE_DEST_IP_ADDRESSES).
                    split(FILTER_DELIMETER).length;
            if (DEBUG) {
                logd("Number of filters in RW_REMOTE_DEST_IP_ADDRESSES is: " + numStrings);
            }
            if (numStrings > numFilters) {
                numFilters = numStrings;
            }
        }

        if ((cap.get(LinkCapabilities.Key.RW_FILTERSPEC_REV_IP_TOS)) != null) {
            numStrings = cap.get(LinkCapabilities.Key.RW_FILTERSPEC_REV_IP_TOS).
                    split(FILTER_DELIMETER).length;
            if (DEBUG) {
                logd("Number of filters in RW_FILTERSPEC_REV_IP_TOS is: " + numStrings);
            }
            if (numStrings > numFilters) {
                numFilters = numStrings;
            }
        }
        return numFilters;
    }

    /**
     * This method parses the LinkCapabilities keys to find the maximum number
     * of filters specified by the user in any RX filter key.
     *
     * @param cap
     * @return number of filters on success and -1 on error
     */
    private int getMaxRxFilterCount(LinkCapabilities cap) {
        int numFilters = -1;
        int numStrings = -1;

        if ((cap.get(LinkCapabilities.Key.RW_REMOTE_SRC_PORTS)) != null) {
            numStrings = cap.get(LinkCapabilities.Key.RW_REMOTE_SRC_PORTS).
                    split(FILTER_DELIMETER).length;
            if (DEBUG) {
                logd("Number of filters in RW_REMOTE_SRC_PORTS is: " + numStrings);
            }
            if (numStrings > numFilters) {
                numFilters = numStrings;
            }
        }

        if ((cap.get(LinkCapabilities.Key.RW_REMOTE_SRC_IP_ADDRESSES)) != null) {
            numStrings = cap.get(LinkCapabilities.Key.RW_REMOTE_SRC_IP_ADDRESSES).
                    split(FILTER_DELIMETER).length;
            if (DEBUG) {
                logd("Number of filters in RW_REMOTE_SRC_IP_ADDRESSES is: " + numStrings);
            }
            if (numStrings > numFilters) {
                numFilters = numStrings;
            }
        }

        if ((cap.get(LinkCapabilities.Key.RW_FILTERSPEC_FWD_IP_TOS)) != null) {
            numStrings = cap.get(LinkCapabilities.Key.RW_FILTERSPEC_FWD_IP_TOS).
                    split(FILTER_DELIMETER).length;
            if (DEBUG) {
                logd("Number of filters in RW_FILTERSPEC_FWD_IP_TOS is: " + numStrings);
            }
            if (numStrings > numFilters) {
                numFilters = numStrings;
            }
        }
        return numFilters;
    }

    /**
     * Add the TX flow parameter to the QoS TX pipe. Create
     * the TX pipe if not available already.
     *
     * @return TX pipe
     */
    private QosPipe addQosTxFlow(QosSpec qosSpec, QosPipe txPipe, int key, String value) {
        if (txPipe == null) {
            txPipe = qosSpec.createPipe();
            txPipe.put(QosSpec.QosSpecKey.SPEC_INDEX, "0");
            txPipe.put(QosSpec.QosSpecKey.FLOW_DIRECTION,
                    Integer.toString(QosSpec.QosDirection.QOS_TX));
        }
        txPipe.put(key, value);
        return txPipe;
    }

    /**
     * Add the RX flow parameter to the QoS RX pipe. Create
     * the RX pipe if not available already.
     *
     * @return RX pipe
     */
    private QosPipe addQosRxFlow(QosSpec qosSpec, QosPipe rxPipe, int key, String value) {
        if (rxPipe == null) {
            rxPipe = qosSpec.createPipe();
            rxPipe.put(QosSpec.QosSpecKey.SPEC_INDEX, "0");
            rxPipe.put(QosSpec.QosSpecKey.FLOW_DIRECTION,
                    Integer.toString(QosSpec.QosDirection.QOS_RX));
        }
        rxPipe.put(key, value);
        return rxPipe;
    }

    /**
     * Converts the ConnectivityManager network type to
     * Phone APN type
     *
     * @param netType
     * @return
     */
    private static String networkTypeToApnType(int netType) {
        switch (netType) {
            case ConnectivityManager.TYPE_MOBILE:
                return Phone.APN_TYPE_DEFAULT;
            case ConnectivityManager.TYPE_MOBILE_MMS:
                return Phone.APN_TYPE_MMS;
            case ConnectivityManager.TYPE_MOBILE_SUPL:
                return Phone.APN_TYPE_SUPL;
            case ConnectivityManager.TYPE_MOBILE_DUN:
                return Phone.APN_TYPE_DUN;
            case ConnectivityManager.TYPE_MOBILE_HIPRI:
                return Phone.APN_TYPE_HIPRI;
            case ConnectivityManager.TYPE_MOBILE_FOTA:
                return Phone.APN_TYPE_FOTA;
            case ConnectivityManager.TYPE_MOBILE_IMS:
                return Phone.APN_TYPE_IMS;
            case ConnectivityManager.TYPE_MOBILE_CBS:
                return Phone.APN_TYPE_CBS;
            default:
                Log.e(LOG_TAG, "Error mapping networkType " + netType + " to apnType.");
                return null;
        }
    }

    private void logd(String s) {
        Log.d(LOG_TAG, s);
    }

    private void loge(String s) {
        Log.e(LOG_TAG, s);
    }

    private void logw(String s) {
        Log.w(LOG_TAG, s);
    }

}
