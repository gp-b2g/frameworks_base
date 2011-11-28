/*
 * Copyright (c) 2011, Code Aurora Forum. All rights reserved.
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

package android.telephony;

import android.os.Parcel;
import android.os.Parcelable;
import android.telephony.EmergencyMessage.Alerts;

import com.android.internal.telephony.cdma.SmsMessage;

/**
 * Describes an Cdma Emergency message.
 *
 * {@hide}
 */
public class CdmaEmergencyMessage implements EmergencyMessage{

    private String mBody = "CdmaEmergencyMessage Uninitialized";
    private int mServiceCategory;
    private Severity mSeverity;
    private Urgency mUrgency;
    private Certainty mCertainty;
    private int mLanguageCode;

    //C.R1001-G 9.3.3 - Cmas message IDs
    public static final int CMAS_PRESIDENTIAL = 0x1000;
    public static final int CMAS_EXTREME      = 0x1001;
    public static final int CMAS_SEVERE       = 0x1002;
    public static final int CMAS_AMBER        = 0x1003;

    public static final int[][] MESSAGE_IDS = {{CMAS_PRESIDENTIAL},
                                               {CMAS_EXTREME},
                                               {CMAS_SEVERE},
                                               {CMAS_AMBER},
                                               {}, //Cdma Etws Earthquake
                                               {}, //Cdma Etws Tsunami
                                               {}};//Cdma Etws E&T
    private CdmaEmergencyMessage() {

    }

    public String getMessageBody() {
        return mBody;
    }

    public int getMessageIdentifier() {
        return mServiceCategory;
    }

    public Severity getSeverity() {
        return mSeverity;
    }

    public Urgency getUrgency() {
        return mUrgency;
    }

    public Certainty getCertainty() {
        return mCertainty;
    }

    public String getLanguageCode() {
        return (mLanguageCode == 1) ? "en" : "";
    }
    public static CdmaEmergencyMessage createFromSmsMessage(android.telephony.SmsMessage src) {
        CdmaEmergencyMessage message = new CdmaEmergencyMessage();
        message.mBody = src.getMessageBody();
        if (src.mWrappedSmsMessage instanceof com.android.internal.telephony.cdma.SmsMessage) {
            message.mServiceCategory = ((SmsMessage)src.mWrappedSmsMessage).getServiceCategory();
            message.mSeverity = ((SmsMessage)src.mWrappedSmsMessage).getSeverity();
            message.mUrgency = ((SmsMessage)src.mWrappedSmsMessage).getUrgency();
            message.mCertainty = ((SmsMessage)src.mWrappedSmsMessage).getCertainty();
            message.mLanguageCode = ((SmsMessage)src.mWrappedSmsMessage).getLanguage();
        } else {
            // This is a problem!
            // Can't create cdma emergency message out of non-cdma sms
        }
        return message;
    }

    private CdmaEmergencyMessage(Parcel in) {
        readFromParcel(in);
    }

    public static int[] getMessageIds(Alerts alertType) {
        return MESSAGE_IDS[alertType.ordinal()];
    }

    @Override
    public String toString() {
        return ("CdmaEmergencyMessage: " + mBody);
    }

    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(mBody);
        dest.writeInt(mServiceCategory);
        dest.writeInt(mSeverity.ordinal());
        dest.writeInt(mUrgency.ordinal());
        dest.writeInt(mCertainty.ordinal());
        dest.writeInt(mLanguageCode);
    }

    private void readFromParcel(Parcel in) {
        mBody = in.readString();
        mServiceCategory = in.readInt();
        mSeverity = Severity.values()[in.readInt()];
        mUrgency = Urgency.values()[in.readInt()];
        mCertainty = Certainty.values()[in.readInt()];
        mLanguageCode = in.readInt();
    }

    public int describeContents() {
        return 0;
    }

    public static final Parcelable.Creator<CdmaEmergencyMessage>
            CREATOR = new Parcelable.Creator<CdmaEmergencyMessage>() {
        public CdmaEmergencyMessage createFromParcel(Parcel in) {
            return new CdmaEmergencyMessage(in);
        }

        public CdmaEmergencyMessage[] newArray(int size) {
            return new CdmaEmergencyMessage[size];
        }
    };

}
