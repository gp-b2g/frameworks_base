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
 *
 */

package android.telephony;

import com.android.internal.telephony.gsm.SmsCbHeader;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * Describes Gsm Emergency message.
 *
 * {@hide}
 */
public class GsmEmergencyMessage implements EmergencyMessage{

    private String mBody = "GsmEmergencyMessage Uninitialized";
    private int mMessageId;
    private String mLanguageCode;

    //TS 23.041 9.4.1.2.2 - Emergency alerts message IDs
    public static final int ETWS_EARTHQUAKE                 = 4352;
    public static final int ETWS_TSUNAMI                    = 4353;
    public static final int ETWS_EARTHQUAKE_AND_TSUNAMI     = 4354;
    public static final int CMAS_PRESIDENTIAL               = 4370;
    public static final int CMAS_EXTREME_IMMEDIATE_OBSERVED = 4371;
    public static final int CMAS_EXTREME_IMMEDIATE_LIKELY   = 4372;
    public static final int CMAS_EXTREME_EXPECTED_OBSERVED  = 4373;
    public static final int CMAS_EXTREME_EXPECTED_LIKELY    = 4374;
    public static final int CMAS_SEVERE_IMMEDIATE_OBSERVED  = 4375;
    public static final int CMAS_SEVERE_IMMEDIATE_LIKELY    = 4376;
    public static final int CMAS_SEVERE_EXPECTED_OBSERVED   = 4377;
    public static final int CMAS_SEVERE_EXPECTED_LIKELY     = 4378;
    public static final int CMAS_AMBER                      = 4379;

    public static final int[][] MESSAGE_IDS = {{CMAS_PRESIDENTIAL},
                                               {CMAS_EXTREME_IMMEDIATE_OBSERVED,
                                                CMAS_EXTREME_IMMEDIATE_LIKELY,
                                                CMAS_EXTREME_EXPECTED_OBSERVED,
                                                CMAS_EXTREME_EXPECTED_LIKELY},
                                               {CMAS_SEVERE_IMMEDIATE_OBSERVED,
                                                CMAS_SEVERE_IMMEDIATE_LIKELY,
                                                CMAS_SEVERE_EXPECTED_OBSERVED,
                                                CMAS_SEVERE_EXPECTED_LIKELY},
                                               {CMAS_AMBER},
                                               {ETWS_EARTHQUAKE},
                                               {ETWS_TSUNAMI},
                                               {ETWS_EARTHQUAKE_AND_TSUNAMI}};

    private GsmEmergencyMessage() {

    }

    public String getMessageBody() {
        return mBody;
    }

    public int getMessageIdentifier() {
        return mMessageId;
    }

    /**
     * TS 23.041 9.4.1.2.2
     */
    public Severity getSeverity() {
        switch (mMessageId) {
            case CMAS_EXTREME_IMMEDIATE_OBSERVED:
            case CMAS_EXTREME_IMMEDIATE_LIKELY:
            case CMAS_EXTREME_EXPECTED_OBSERVED:
            case CMAS_EXTREME_EXPECTED_LIKELY:
                return Severity.EXTREME;
            case CMAS_SEVERE_IMMEDIATE_OBSERVED:
            case CMAS_SEVERE_IMMEDIATE_LIKELY:
            case CMAS_SEVERE_EXPECTED_OBSERVED:
            case CMAS_SEVERE_EXPECTED_LIKELY:
                return Severity.SEVERE;
            default:
                return Severity.UNDEFINED;
        }
    }

    /**
     * TS 23.041 9.4.1.2.2
     */
    public Urgency getUrgency() {
        switch (mMessageId) {
            case CMAS_EXTREME_IMMEDIATE_OBSERVED:
            case CMAS_EXTREME_IMMEDIATE_LIKELY:
            case CMAS_SEVERE_IMMEDIATE_OBSERVED:
            case CMAS_SEVERE_IMMEDIATE_LIKELY:
                return Urgency.IMMEDIATE;
            case CMAS_EXTREME_EXPECTED_OBSERVED:
            case CMAS_EXTREME_EXPECTED_LIKELY:
            case CMAS_SEVERE_EXPECTED_OBSERVED:
            case CMAS_SEVERE_EXPECTED_LIKELY:
                return Urgency.EXPECTED;
            default:
                return Urgency.UNDEFINED;
        }
    }

    /**
     * TS 23.041 9.4.1.2.2
     */
    public Certainty getCertainty() {
        switch (mMessageId) {
            case CMAS_EXTREME_IMMEDIATE_OBSERVED:
            case CMAS_SEVERE_IMMEDIATE_OBSERVED:
            case CMAS_EXTREME_EXPECTED_OBSERVED:
            case CMAS_SEVERE_EXPECTED_OBSERVED:
                return Certainty.OBSERVED;
            case CMAS_EXTREME_IMMEDIATE_LIKELY:
            case CMAS_SEVERE_IMMEDIATE_LIKELY:
            case CMAS_EXTREME_EXPECTED_LIKELY:
            case CMAS_SEVERE_EXPECTED_LIKELY:
                return Certainty.LIKELY;
            default:
                return Certainty.UNDEFINED;
        }
    }

    public String getLanguageCode() {
        return mLanguageCode;
    }
    public static GsmEmergencyMessage createFromSmsCbMessage(SmsCbMessage src) {
        GsmEmergencyMessage message = new GsmEmergencyMessage();
        if (src.getMessageFormat() == SmsCbHeader.FORMAT_ETWS_PRIMARY) {
            // Etws primary message doesn't have text associated with it
            // so populate text with type of etws message.
            switch (src.getMessageIdentifier()) {
                case ETWS_EARTHQUAKE:
                    message.mBody = "EARTHQUAKE";
                    break;
                case ETWS_TSUNAMI:
                    message.mBody = "TSUNAMI";
                    break;
                case ETWS_EARTHQUAKE_AND_TSUNAMI:
                    message.mBody = "EARTHQUAKE and TSUNAMI";
                    break;
                default:
                    message.mBody = "ETWS Primary message";
                    break;
            }
        } else {
            message.mBody = src.getMessageBody();
        }
        message.mMessageId = src.getMessageIdentifier();
        message.mLanguageCode = src.getLanguageCode();
        return message;
    }

    private GsmEmergencyMessage(Parcel in) {
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
        dest.writeInt(mMessageId);
        dest.writeString(mLanguageCode);
    }

    private void readFromParcel(Parcel in) {
        mBody = in.readString();
        mMessageId = in.readInt();
        mLanguageCode = in.readString();
    }

    public int describeContents() {
        return 0;
    }

    public static final Parcelable.Creator<GsmEmergencyMessage>
            CREATOR = new Parcelable.Creator<GsmEmergencyMessage>() {
        public GsmEmergencyMessage createFromParcel(Parcel in) {
            return new GsmEmergencyMessage(in);
        }

        public GsmEmergencyMessage[] newArray(int size) {
            return new GsmEmergencyMessage[size];
        }
    };

}
