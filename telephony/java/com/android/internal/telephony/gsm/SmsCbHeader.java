/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.internal.telephony.gsm;

import android.os.Parcel;
import android.os.Parcelable;
import android.telephony.SmsCbConstants;

public class SmsCbHeader implements Parcelable, SmsCbConstants {
    /**
     * Length of SMS-CB header
     */
    public static final int PDU_HEADER_LENGTH = 6;

    /**
     * GSM pdu format, as defined in 3gpp TS 23.041, section 9.4.1
     */
    public static final int FORMAT_GSM = 1;

    /**
     * UMTS pdu format, as defined in 3gpp TS 23.041, section 9.4.2
     */
    public static final int FORMAT_UMTS = 2;

    /**
     * GSM pdu format, as defined in 3gpp TS 23.041, section 9.4.1.3
     */
    public static final int FORMAT_ETWS_PRIMARY = 3;

    /**
     * Message type value as defined in 3gpp TS 25.324, section 11.1.
     */
    private static final int MESSAGE_TYPE_CBS_MESSAGE = 1;

    /**
     * Length of GSM pdus
     */
    public static final int PDU_LENGTH_GSM = 88;

    /**
     * Maximum length of ETWS primary message GSM pdus
     */
    public static final int PDU_LENGTH_ETWS = 56;

    public final int geographicalScope;

    public final int messageCode;

    public final int updateNumber;

    public final int messageIdentifier;

    public final int dataCodingScheme;

    public final int pageIndex;

    public final int nrOfPages;

    public final int format;

    public final boolean etwsEmergencyUserAlert;

    public final boolean etwsPopup;

    public final int etwsWarningType;

    public SmsCbHeader(byte[] pdu) throws IllegalArgumentException {
        if (pdu == null || pdu.length < PDU_HEADER_LENGTH) {
            throw new IllegalArgumentException("Illegal PDU");
        }

        if (pdu.length <= PDU_LENGTH_GSM) {
            // can be ETWS or GSM format.
            // Per TS23.041 9.4.1.2 and 9.4.1.3.2, GSM and ETWS format both
            // contain message identifier in same octets
            messageIdentifier = ((pdu[2] & 0xff) << 8) | (pdu[3] & 0xff);
            if (isEtwsMessage(messageIdentifier)) {
                format = FORMAT_ETWS_PRIMARY;
                geographicalScope = -1; // not applicable
                messageCode = -1;
                updateNumber = -1;
                dataCodingScheme = -1;
                pageIndex = -1;
                nrOfPages = -1;
                etwsEmergencyUserAlert = (pdu[4] & 0x1) != 0;
                etwsPopup = (pdu[5] & 0x80) != 0;
                etwsWarningType = (pdu[4] & 0xfe) >> 1;
            } else {
                // GSM pdus are no more than 88 bytes
                format = FORMAT_GSM;
                geographicalScope = (pdu[0] & 0xc0) >>> 6;
                messageCode = ((pdu[0] & 0x3f) << 4) | ((pdu[1] & 0xf0) >>> 4);
                updateNumber = pdu[1] & 0x0f;
                dataCodingScheme = pdu[4] & 0xff;

                // Check for invalid page parameter
                int pageIndex = (pdu[5] & 0xf0) >>> 4;
                int nrOfPages = pdu[5] & 0x0f;

                if (pageIndex == 0 || nrOfPages == 0 || pageIndex > nrOfPages) {
                    pageIndex = 1;
                    nrOfPages = 1;
                }

                this.pageIndex = pageIndex;
                this.nrOfPages = nrOfPages;
                etwsEmergencyUserAlert = false;
                etwsPopup = false;
                etwsWarningType = -1;
            }
        } else {
            // UMTS pdus are always at least 90 bytes since the payload includes
            // a number-of-pages octet and also one length octet per page
            format = FORMAT_UMTS;

            int messageType = pdu[0];

            if (messageType != MESSAGE_TYPE_CBS_MESSAGE) {
                throw new IllegalArgumentException("Unsupported message type " + messageType);
            }

            messageIdentifier = ((pdu[1] & 0xff) << 8) | pdu[2] & 0xff;
            geographicalScope = (pdu[3] & 0xc0) >> 6;
            messageCode = ((pdu[3] & 0x3f) << 4) | ((pdu[4] & 0xf0) >>> 4);
            updateNumber = pdu[4] & 0x0f;
            dataCodingScheme = pdu[5] & 0xff;

            // We will always consider a UMTS message as having one single page
            // since there's only one instance of the header, even though the
            // actual payload may contain several pages.
            pageIndex = 1;
            nrOfPages = 1;
            etwsEmergencyUserAlert = false;
            etwsPopup = false;
            etwsWarningType = -1;
        }
    }

    // Copy constructor for CB Header
    public SmsCbHeader(SmsCbHeader other) {
        this.dataCodingScheme = other.dataCodingScheme;
        this.geographicalScope = other.geographicalScope;
        this.messageCode = other.messageCode;
        this.messageIdentifier = other.messageIdentifier;
        this.nrOfPages = other.nrOfPages;
        this.pageIndex = other.pageIndex;
        this.updateNumber = other.updateNumber;
        this.format = other.format;
        this.etwsEmergencyUserAlert = other.etwsEmergencyUserAlert;
        this.etwsPopup = other.etwsPopup;
        this.etwsWarningType = other.etwsWarningType;
    }

    public int describeContents() {
        return 0;
    }

    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(geographicalScope);
        dest.writeInt(messageCode);
        dest.writeInt(updateNumber);
        dest.writeInt(messageIdentifier);
        dest.writeInt(dataCodingScheme);
        dest.writeInt(pageIndex);
        dest.writeInt(nrOfPages);
        dest.writeInt(format);
        dest.writeInt(etwsEmergencyUserAlert ? 1 : 0);
        dest.writeInt(etwsPopup ? 1 : 0);
        dest.writeInt(etwsWarningType);
    }

    public static final Parcelable.Creator<SmsCbHeader> CREATOR = new Parcelable.Creator<SmsCbHeader>() {
        public SmsCbHeader createFromParcel(Parcel in) {
            return new SmsCbHeader(in);
        }

        public SmsCbHeader[] newArray(int size) {
            return new SmsCbHeader[size];
        }
    };

    private SmsCbHeader(Parcel in) {
        geographicalScope = in.readInt();
        messageCode = in.readInt();
        updateNumber = in.readInt();
        messageIdentifier = in.readInt();
        dataCodingScheme = in.readInt();
        pageIndex = in.readInt();
        nrOfPages = in.readInt();
        format = in.readInt();
        etwsEmergencyUserAlert = (in.readInt() == 1 ? true : false);
        etwsPopup = (in.readInt() == 1 ? true : false);
        etwsWarningType = in.readInt();
    }

    /**
     * Return whether the specified message ID is an emergency (PWS) message type.
     * This method is static and takes an argument so that it can be used by
     * CellBroadcastReceiver, which stores message ID's in SQLite rather than PDU.
     * @param id the message identifier to check
     * @return true if the message is emergency type; false otherwise
     */
    public static boolean isEmergencyMessage(int id) {
        return id >= MESSAGE_ID_PWS_FIRST_IDENTIFIER && id <= MESSAGE_ID_PWS_LAST_IDENTIFIER;
    }

    /**
     * Return whether the specified message ID is an ETWS emergency message type.
     * This method is static and takes an argument so that it can be used by
     * CellBroadcastReceiver, which stores message ID's in SQLite rather than PDU.
     * @param id the message identifier to check
     * @return true if the message is ETWS emergency type; false otherwise
     */
    public static boolean isEtwsMessage(int id) {
        return (id & MESSAGE_ID_ETWS_TYPE_MASK) == MESSAGE_ID_ETWS_TYPE;
    }

    /**
     * Return whether the specified message ID is a CMAS emergency message type.
     * This method is static and takes an argument so that it can be used by
     * CellBroadcastReceiver, which stores message ID's in SQLite rather than PDU.
     * @param id the message identifier to check
     * @return true if the message is CMAS emergency type; false otherwise
     */
    public static boolean isCmasMessage(int id) {
        return id >= MESSAGE_ID_CMAS_FIRST_IDENTIFIER && id <= MESSAGE_ID_CMAS_LAST_IDENTIFIER;
    }

    /**
     * Return whether the specified message code indicates an ETWS popup alert.
     * This method is static and takes an argument so that it can be used by
     * CellBroadcastReceiver, which stores message codes in SQLite rather than PDU.
     * This method assumes that the message ID has already been checked for ETWS type.
     *
     * @param messageCode the message code to check
     * @return true if the message code indicates a popup alert should be displayed
     */
    public static boolean isEtwsPopupAlert(int messageCode) {
        return (messageCode & MESSAGE_CODE_ETWS_ACTIVATE_POPUP) != 0;
    }

    /**
     * Return whether the specified message code indicates an ETWS emergency user alert.
     * This method is static and takes an argument so that it can be used by
     * CellBroadcastReceiver, which stores message codes in SQLite rather than PDU.
     * This method assumes that the message ID has already been checked for ETWS type.
     *
     * @param messageCode the message code to check
     * @return true if the message code indicates an emergency user alert
     */
    public static boolean isEtwsEmergencyUserAlert(int messageCode) {
        return (messageCode & MESSAGE_CODE_ETWS_EMERGENCY_USER_ALERT) != 0;
    }

    @Override
    public String toString() {
        return "SmsCbHeader{GS=" + geographicalScope + ", messageCode=0x" +
                Integer.toHexString(messageCode) + ", updateNumber=" + updateNumber +
                ", messageIdentifier=0x" + Integer.toHexString(messageIdentifier) +
                ", DCS=0x" + Integer.toHexString(dataCodingScheme) +
                ", page " + pageIndex + " of " + nrOfPages + '}';
    }
}
