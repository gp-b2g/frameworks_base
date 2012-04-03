/*
 * Copyright (c) 2012, Code Aurora Forum. All rights reserved.
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

package android.net.wifi.p2p;

import android.os.Parcelable;
import android.os.Parcel;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import android.util.Log;

/**
 * A class representing Wireless Display Service Capability information about a
 * Wi-Fi p2p device {@see WifiP2pDevice}
 */
public class WfdInfo implements Parcelable {

    private static final String TAG = "WfdInfo";

    public WfdInfo() {
        deviceType = DEVICETYPE_INVALID;
        preferredConnectivity = PC_P2P;
        coupledSinkStatus = NOT_COUPLED_AVAILABLE;
        sessionMgmtCtrlPort = DEFAULT_SESSION_MGMT_CTRL_PORT;
    };

    /**
     * @param String input with WFD parameters Note: The events formats can be
     *            looked up in the wpa_supplicant code
     * @hide
     */
    public WfdInfo(String deviceInfo) throws IllegalArgumentException {
        this();

        if (deviceInfo == null) {
            throw new IllegalArgumentException("Malformed supplicant event");
        }

        String line;
        BufferedReader reader = new BufferedReader(new StringReader(deviceInfo));
        try {
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("wfd_device_type")) {
                    String devStr = line.substring(line.indexOf("=") + 1);
                    if (devStr.compareToIgnoreCase("source") == 0) {
                        deviceType = DEVICETYPE_SOURCE;
                    } else if (devStr.compareToIgnoreCase("primary_sink") == 0) {
                        deviceType = DEVICETYPE_PRIMARYSINK;
                    } else if (devStr.compareToIgnoreCase("secondary_sink") == 0) {
                        deviceType = DEVICETYPE_SECONDARYSINK;
                    } else if (devStr.compareToIgnoreCase("source_primary_sink") == 0) {
                        deviceType = DEVICETYPE_SOURCE_PRIMARYSINK;
                    } else {
                        deviceType = DEVICETYPE_INVALID;
                    }
                } else if (line.startsWith("wfd_coupled_sink_supported_by_source")) {
                    String devStr = line.substring(line.indexOf("=") + 1);
                    if (devStr.compareToIgnoreCase("y") == 0) {
                        isCoupledSinkSupportedBySource = true;
                    } else {
                        isCoupledSinkSupportedBySource = false;
                    }
                } else if (line.startsWith("wfd_coupled_sink_supported_by_sink")) {
                    String devStr = line.substring(line.indexOf("=") + 1);
                    if (devStr.compareToIgnoreCase("y") == 0) {
                        isCoupledSinkSupportedBySink = true;
                    } else {
                        isCoupledSinkSupportedBySink = false;
                    }
                } else if (line.startsWith("wfd_available_for_session")) {
                    String devStr = line.substring(line.indexOf("=") + 1);
                    if (devStr.compareToIgnoreCase("y") == 0) {
                        isAvailableForSession = true;
                    } else {
                        isAvailableForSession = false;
                    }
                } else if (line.startsWith("wfd_service_discovery_supported")) {
                    String devStr = line.substring(line.indexOf("=") + 1);
                    if (devStr.compareToIgnoreCase("y") == 0) {
                        isServiceDiscoverySupported = true;
                    } else {
                        isServiceDiscoverySupported = false;
                    }
                } else if (line.startsWith("wfd_preferred_connectivity")) {
                    if (line.substring(line.indexOf("=") + 1).compareToIgnoreCase("p2p") == 0) {
                        preferredConnectivity = PC_P2P;
                    } else {
                        preferredConnectivity = PC_TDLS;
                    }
                } else if (line.startsWith("wfd_content_protection_supported")) {
                    if (line.substring(line.indexOf("=") + 1).compareToIgnoreCase("y") == 0) {
                        isContentProtectionSupported = true;
                    } else {
                        isContentProtectionSupported = false;
                    }
                } else if (line.startsWith("wfd_time_sync_supported")) {
                    if (line.substring(line.indexOf("=") + 1).compareToIgnoreCase("y") == 0) {
                        isTimeSynchronizationSupported = true;
                    } else {
                        isTimeSynchronizationSupported = false;
                    }
                } else if (line.startsWith("wfd_primarysink_audio_notsupported")) {
                    if (line.substring(line.indexOf("=") + 1).compareToIgnoreCase("y") == 0) {
                        isAudioUnspportedAtPrimarySink = true;
                    } else {
                        isAudioUnspportedAtPrimarySink = false;
                    }
                } else if (line.startsWith("wfd_source_audio_only_supported")) {
                    if (line.substring(line.indexOf("=") + 1).compareToIgnoreCase("y") == 0) {
                        isAudioOnlySupportedAtSource = true;
                    } else {
                        isAudioOnlySupportedAtSource = false;
                    }
                } else if (line.startsWith("wfd_tdls_persistent_group_intended")) {
                    if (line.substring(line.indexOf("=") + 1).compareToIgnoreCase("y") == 0) {
                        isTDLSPersistentGroupIntended = true;
                    } else {
                        isTDLSPersistentGroupIntended = false;
                    }
                } else if (line.startsWith("wfd_tdls_persistent_group_reinvoke")) {
                    if (line.substring(line.indexOf("=") + 1).compareToIgnoreCase("y") == 0) {
                        isTDLSReInvokePersistentGroupReq = true;
                    } else {
                        isTDLSReInvokePersistentGroupReq = false;
                    }
                } else if (line.startsWith("wfd_session_management_control_port")) {
                    sessionMgmtCtrlPort = Integer.parseInt(line.substring(line.indexOf("=") + 1));
                } else if (line.startsWith("wfd_coupled_sink_status")) {
                    String cssStr = line.substring(line.indexOf("=") + 1);
                    if (cssStr.compareToIgnoreCase("not_coupled") == 0) {
                        coupledSinkStatus = NOT_COUPLED_AVAILABLE;
                    } else if (cssStr.compareToIgnoreCase("coupled") == 0) {
                        coupledSinkStatus = COUPLED;
                    } else {
                        coupledSinkStatus = TEARDOWN_COUPLING;
                    }
                }
            }

        } catch (IOException e) {
            throw new IllegalArgumentException("Malformed supplicant event");
        }
        Log.d(TAG, "Created WfdInfo \n" + toString());
    };

    /** Device Type is WFD Source */
    public static final int DEVICETYPE_SOURCE = 0;

    /** Device Type is Primary Sink */
    public static final int DEVICETYPE_PRIMARYSINK = 1;

    /** Device Type is Secondary Sink */
    public static final int DEVICETYPE_SECONDARYSINK = 2;

    /** Device Type is Source and Primary Sink */
    public static final int DEVICETYPE_SOURCE_PRIMARYSINK = 3;

    /** Device Type Invalid */
    public static final int DEVICETYPE_INVALID = 4;

    /** Preferred Connectivity (PC) is P2P */
    public static final int PC_P2P = 0;

    /** Preferred Connectivity (PC) is TDLS */
    public static final int PC_TDLS = 1;

    /** Device is not coupled and available */
    public static final int NOT_COUPLED_AVAILABLE = 0;

    /** Device is coupled */
    public static final int COUPLED = 1;

    /** Teardown coupling */
    public static final int TEARDOWN_COUPLING = 2;

    /** Default RTSP Control Port */
    public static final int DEFAULT_SESSION_MGMT_CTRL_PORT = 554;

    /**
     * RTSP Control Port. Valid values are between 1-65535. Default value is
     * DEFAULT_SESSION_MGMT_CTRL_PORT
     */
    public int sessionMgmtCtrlPort;

    /**
     * Maximum average throughput capability of the WFDDevice represented in
     * multiples of 1Mbps
     */
    public int maxThroughput;

    /**
     * DEVICETYPE_SOURCE: WFD source, DEVICETYPE_PRIMARYSINK: Primary sink
     * DEVICETYPE_SECONDARYSINK: Secondary sink, DEVICETYPE_SOURCE_PRIMARYSINK:
     * WFD source/Primary sink Default value is DEVICETYPE_INVALID
     */
    public int deviceType;

    /**
     * PC_P2P: Preferred Connectivity: P2P PC_TDLS: Preferred Connectivity: TDLS
     * Default value is PC_INVALID
     */
    public int preferredConnectivity;

    /**
     * NOT_COUPLED_AVAILABLE: Not Coupled, Available for coupling COUPLED:
     * Coupled TEARDOWN_COUPLING: Teardown coupling Default value is
     * NOT_COUPLED_AVAILABLE
     */
    public int coupledSinkStatus;

    /** Mac Address of coupled sink */
    public String coupledDeviceAdress;

    /**
     * false: coupled sink operation not supported by WFD source. true : coupled
     * sink operation supported by WFD source This bit is valid when WFD Device
     * Type is either DEVICETYPE_SOURCE or DEVICETYPE_SOURCE_PRIMARYSINK
     */
    public boolean isCoupledSinkSupportedBySource;

    /**
     * false: coupled sink operation not supported by WFD Sink. true : coupled
     * sink operation supported by WFD Sink This bit is valid when WFD Device
     * Type is either DEVICETYPE_PRIMARYSINK, DEVICETYPE_SECONDARYSINK or
     * DEVICETYPE_SOURCE_PRIMARYSINK
     */
    public boolean isCoupledSinkSupportedBySink;

    /**
     * false: Not available for WFD Session true: Available for WFD Session
     */
    public boolean isAvailableForSession;

    /**
     * false: Service Discovery not supported true: Service Discovery supported
     */
    public boolean isServiceDiscoverySupported;

    /**
     * false: Content Protection not supported true: Content Protection
     * supported
     */
    public boolean isContentProtectionSupported;

    /**
     * false: Time Synchronization not supported true: Time Synchronization
     * supported
     */
    public boolean isTimeSynchronizationSupported;

    /**
     * false: Audio Supported at Primary Sink true: Audio Unsupported at
     * supported
     */
    public boolean isAudioUnspportedAtPrimarySink;

    /**
     * false: Audio only WFD Session not supported by Source true: Audio only
     * WFD Session supported by Source
     */
    public boolean isAudioOnlySupportedAtSource;

    /**
     * false: TDLS Persistent Group not intended by device true: TDLS Persistent
     * Group intended by device
     */
    public boolean isTDLSPersistentGroupIntended;

    /**
     * false: Not a request to reinvoke a TDLS persistent group true: This is a
     * request to reinvoke TDLS persistent group
     */
    public boolean isTDLSReInvokePersistentGroupReq;

    /** copy constructor */
    public WfdInfo(WfdInfo source) {
        if (source != null) {
            sessionMgmtCtrlPort = source.sessionMgmtCtrlPort;
            maxThroughput = source.maxThroughput;
            deviceType = source.deviceType;
            preferredConnectivity = source.preferredConnectivity;
            coupledSinkStatus = source.coupledSinkStatus;
            coupledDeviceAdress = source.coupledDeviceAdress;
            isCoupledSinkSupportedBySource = source.isCoupledSinkSupportedBySource;
            isCoupledSinkSupportedBySink = source.isCoupledSinkSupportedBySink;
            isAvailableForSession = source.isAvailableForSession;
            isCoupledSinkSupportedBySource = source.isCoupledSinkSupportedBySource;
            isContentProtectionSupported = source.isContentProtectionSupported;
            isTimeSynchronizationSupported = source.isTimeSynchronizationSupported;
            isAudioUnspportedAtPrimarySink = source.isAudioUnspportedAtPrimarySink;
            isAudioOnlySupportedAtSource = source.isAudioOnlySupportedAtSource;
            isTDLSPersistentGroupIntended = source.isTDLSPersistentGroupIntended;
            isTDLSReInvokePersistentGroupReq = source.isTDLSReInvokePersistentGroupReq;
        }
    }

    /** Implement the Parcelable interface */
    public int describeContents() {
        return 0;
    }

    /** Implement the Parcelable interface */
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(sessionMgmtCtrlPort);
        dest.writeInt(maxThroughput);
        dest.writeInt(deviceType);
        dest.writeInt(preferredConnectivity);
        dest.writeInt(coupledSinkStatus);
        dest.writeString(coupledDeviceAdress);
        dest.writeByte((byte) (isCoupledSinkSupportedBySource ? 1 : 0));
        dest.writeByte((byte) (isCoupledSinkSupportedBySink ? 1 : 0));
        dest.writeByte((byte) (isAvailableForSession ? 1 : 0));
        dest.writeByte((byte) (isServiceDiscoverySupported ? 1 : 0));
        dest.writeByte((byte) (isContentProtectionSupported ? 1 : 0));
        dest.writeByte((byte) (isTimeSynchronizationSupported ? 1 : 0));
        dest.writeByte((byte) (isAudioUnspportedAtPrimarySink ? 1 : 0));
        dest.writeByte((byte) (isAudioOnlySupportedAtSource ? 1 : 0));
        dest.writeByte((byte) (isTDLSPersistentGroupIntended ? 1 : 0));
        dest.writeByte((byte) (isTDLSReInvokePersistentGroupReq ? 1 : 0));
    }

    /** Implement the Parcelable interface */
    public static final Creator<WfdInfo> CREATOR =
            new Creator<WfdInfo>() {
                public WfdInfo createFromParcel(Parcel in) {
                    WfdInfo wfdInfo = new WfdInfo();
                    wfdInfo.sessionMgmtCtrlPort = in.readInt();
                    wfdInfo.maxThroughput = in.readInt();
                    wfdInfo.deviceType = in.readInt();
                    wfdInfo.preferredConnectivity = in.readInt();
                    wfdInfo.coupledSinkStatus = in.readInt();
                    wfdInfo.coupledDeviceAdress = in.readString();
                    wfdInfo.isCoupledSinkSupportedBySource = in.readByte() != 0;
                    wfdInfo.isCoupledSinkSupportedBySink = in.readByte() != 0;
                    wfdInfo.isAvailableForSession = in.readByte() != 0;
                    wfdInfo.isCoupledSinkSupportedBySource = in.readByte() != 0;
                    wfdInfo.isContentProtectionSupported = in.readByte() != 0;
                    wfdInfo.isTimeSynchronizationSupported = in.readByte() != 0;
                    wfdInfo.isAudioUnspportedAtPrimarySink = in.readByte() != 0;
                    wfdInfo.isAudioOnlySupportedAtSource = in.readByte() != 0;
                    wfdInfo.isTDLSPersistentGroupIntended = in.readByte() != 0;
                    wfdInfo.isTDLSReInvokePersistentGroupReq = in.readByte() != 0;
                    return wfdInfo;
                }

                public WfdInfo[] newArray(int size) {
                    return new WfdInfo[size];
                }
            };

    public String toString() {
        StringBuffer sbuf = new StringBuffer();
        sbuf.append("Control Port: ").append(sessionMgmtCtrlPort);
        sbuf.append("\n MaxThroughput: ").append(maxThroughput);
        sbuf.append("\n DeviceType: ").append(deviceType);
        sbuf.append("\n PreferredConnectivity: ").append(preferredConnectivity);
        sbuf.append("\n CoupledSinkStatus: ").append(coupledSinkStatus);
        sbuf.append("\n CoupledDeviceAddress: ").append(coupledDeviceAdress);
        sbuf.append("\n IsCoupledSinkSupportedBySource: ").append(isCoupledSinkSupportedBySource);
        sbuf.append("\n IsCoupledSinkSupportedBySink: ").append(isCoupledSinkSupportedBySink);
        sbuf.append("\n IsAvailableForSession: ").append(isAvailableForSession);
        sbuf.append("\n IsCoupledSinkSupportedBySource: ").append(isCoupledSinkSupportedBySource);
        sbuf.append("\n IsContentProtectionSupported: ").append(isContentProtectionSupported);
        sbuf.append("\n IsTimeSynchronizationSupported: ").append(isTimeSynchronizationSupported);
        sbuf.append("\n IsAudioUnspportedAtPrimarySink: ").append(isAudioUnspportedAtPrimarySink);
        sbuf.append("\n IsAudioOnlySupportedAtSource: ").append(isAudioOnlySupportedAtSource);
        sbuf.append("\n IsTDLSPersistentGroupIntended: ").append(isTDLSPersistentGroupIntended);
        sbuf.append("\n IsTDLSReInvokePersistentGroupReq: ").append(
                isTDLSReInvokePersistentGroupReq);
        return sbuf.toString();
    }

    /** Returns true if the device is a group owner */
    public boolean isWFDDevice() {

        return (deviceType != DEVICETYPE_INVALID);
    }

}
