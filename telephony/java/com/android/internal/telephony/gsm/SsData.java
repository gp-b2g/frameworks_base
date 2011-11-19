/*
 * Copyright (C) 2006 The Android Open Source Project
 * Copyright (c) 2011, Code Aurora Forum. All rights reserved.
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

import com.android.internal.telephony.CallForwardInfo;

import java.util.ArrayList;

/**
 * See also RIL_StkCcUnsolSsResponse in include/telephony/ril.h
 *
 * {@hide}
 */
public class SsData {
    public enum ServiceType {
        SS_CFU,
        SS_CF_BUSY,
        SS_CF_NO_REPLY,
        SS_CF_NOT_REACHABLE,
        SS_CF_ALL,
        SS_CF_ALL_CONDITIONAL,
        SS_CLIP,
        SS_CLIR,
        SS_COLP,
        SS_COLR,
        SS_WAIT,
        SS_BAOC,
        SS_BAOIC,
        SS_BAOIC_EXC_HOME,
        SS_BAIC,
        SS_BAIC_ROAMING,
        SS_ALL_BARRING,
        SS_OUTGOING_BARRING,
        SS_INCOMING_BARRING;

        public boolean isTypeCF() {
            return (this == SS_CFU || this == SS_CF_BUSY || this == SS_CF_NO_REPLY ||
                  this == SS_CF_NOT_REACHABLE || this == SS_CF_ALL || this == SS_CF_ALL_CONDITIONAL);
        }

        public boolean isTypeUnConditional() {
            return (this == SS_CFU || this == SS_CF_ALL);
        }

        public boolean isTypeCW() {
            return (this == SS_WAIT);
        }

        public boolean isTypeClip() {
            return (this == SS_CLIP);
        }

        public boolean isTypeClir() {
            return (this == SS_CLIR);
        }

        public boolean isTypeBarring() {
            return (this == SS_BAOC || this == SS_BAOIC || this == SS_BAOIC_EXC_HOME ||
                  this == SS_BAIC || this == SS_BAIC_ROAMING || this == SS_ALL_BARRING ||
                  this == SS_OUTGOING_BARRING || this == SS_INCOMING_BARRING);
        }
    };

    public enum RequestType {
        SS_ACTIVATION,
        SS_DEACTIVATION,
        SS_INTERROGATION,
        SS_REGISTRATION,
        SS_ERASURE;

        public boolean isTypeInterrogation() {
            return (this == SS_INTERROGATION);
        }
    };

    public enum TeleserviceType {
        SS_ALL_TELE_AND_BEARER_SERVICES,
        SS_ALL_TELESEVICES,
        SS_TELEPHONY,
        SS_ALL_DATA_TELESERVICES,
        SS_SMS_SERVICES,
        SS_ALL_TELESERVICES_EXCEPT_SMS;
    };

    public ServiceType serviceType;
    public RequestType requestType;
    public TeleserviceType teleserviceType;
    public int serviceClass;
    public int result;

    public int[] ssInfo; /* This is the response data for most of the SS GET/SET
                            RIL requests. E.g. RIL_REQUSET_GET_CLIR returns
                            two ints, so first two values of ssInfo[] will be
                            used for respone if serviceType is SS_CLIR and
                            requestType is SS_INTERROGATION */

    public CallForwardInfo[] cfInfo; /* This is the response data for SS request
                                        to query call forward status. see
                                        RIL_REQUEST_QUERY_CALL_FORWARD_STATUS */

    public ServiceType ServiceTypeFromRILInt(int type) {
        ServiceType newType;
        /* RIL_SsServiceType ril.h */
        switch(type) {
            case 0: newType = ServiceType.SS_CFU; break;
            case 1: newType = ServiceType.SS_CF_BUSY; break;
            case 2: newType = ServiceType.SS_CF_NO_REPLY; break;
            case 3: newType = ServiceType.SS_CF_NOT_REACHABLE; break;
            case 4: newType = ServiceType.SS_CF_ALL; break;
            case 5: newType = ServiceType.SS_CF_ALL_CONDITIONAL; break;
            case 6: newType = ServiceType.SS_CLIP; break;
            case 7: newType = ServiceType.SS_CLIR; break;
            case 8: newType = ServiceType.SS_COLP; break;
            case 9: newType = ServiceType.SS_COLR; break;
            case 10: newType = ServiceType.SS_WAIT; break;
            case 11: newType = ServiceType.SS_BAOC; break;
            case 12: newType = ServiceType.SS_BAOIC; break;
            case 13: newType = ServiceType.SS_BAOIC_EXC_HOME; break;
            case 14: newType = ServiceType.SS_BAIC; break;
            case 15: newType = ServiceType.SS_BAIC_ROAMING; break;
            case 16: newType = ServiceType.SS_ALL_BARRING; break;
            case 17: newType = ServiceType.SS_OUTGOING_BARRING; break;
            case 18: newType = ServiceType.SS_INCOMING_BARRING; break;
            default:
                throw new RuntimeException(
                            "Unrecognized SS ServiceType " + type);
        }

        return newType;
    }

    public RequestType RequestTypeFromRILInt(int type) {
        RequestType newType;
        /* RIL_SsRequestType ril.h */
        switch(type) {
            case 0: newType = RequestType.SS_ACTIVATION; break;
            case 1: newType = RequestType.SS_DEACTIVATION; break;
            case 2: newType = RequestType.SS_INTERROGATION; break;
            case 3: newType = RequestType.SS_REGISTRATION; break;
            case 4: newType = RequestType.SS_ERASURE; break;
            default:
                throw new RuntimeException(
                            "Unrecognized SS RequestType " + type);
        }

        return newType;
    }

    public TeleserviceType TeleserviceTypeFromRILInt(int type) {
        TeleserviceType newType;
        /* RIL_SsTeleserviceType ril.h */
        switch(type) {
            case 0: newType = TeleserviceType.SS_ALL_TELE_AND_BEARER_SERVICES; break;
            case 1: newType = TeleserviceType.SS_ALL_TELESEVICES; break;
            case 2: newType = TeleserviceType.SS_TELEPHONY; break;
            case 3: newType = TeleserviceType.SS_ALL_DATA_TELESERVICES; break;
            case 4: newType = TeleserviceType.SS_SMS_SERVICES; break;
            case 5: newType = TeleserviceType.SS_ALL_TELESERVICES_EXCEPT_SMS; break;
            default:
                throw new RuntimeException(
                            "Unrecognized SS TeleserviceType " + type);
        }

        return newType;
    }

    public String toString() {
        return "[SsData] " + "ServiceType: " + serviceType
            + " RequestType: " + requestType
            + " TeleserviceType: " + teleserviceType
            + " ServiceClass: " + serviceClass
            + " Result: " + result
            + " Is Service Type CF: " + serviceType.isTypeCF();
    }
}
