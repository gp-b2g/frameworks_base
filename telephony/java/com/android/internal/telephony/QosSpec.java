/* Copyright (c) 2011, Code Aurora Forum. All rights reserved.
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

package com.android.internal.telephony;

import android.os.Parcelable;
import android.os.Parcel;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.LinkedList;
import java.util.Set;
import java.util.Map.Entry;
import java.io.File;
import java.io.FileReader;
import java.lang.reflect.Field;

import org.xmlpull.v1.XmlPullParser;

/* QoS constants for RIL QoS Classes (matches QoS constants in ril_qos.h) */
class RIL_QosClass {
    public static final int RIL_QOS_CONVERSATIONAL  = 0;
    public static final int RIL_QOS_STREAMING       = 1;
    public static final int RIL_QOS_INTERACTIVE     = 2;
    public static final int RIL_QOS_BACKGROUND      = 3;

    public static String getName(int val) {
        switch(val) {
            case RIL_QOS_CONVERSATIONAL: return "RIL_QOS_CONVERSATIONAL";
            case RIL_QOS_STREAMING: return "RIL_QOS_STREAMING";
            case RIL_QOS_INTERACTIVE: return "RIL_QOS_INTERACTIVE";
            case RIL_QOS_BACKGROUND: return "RIL_QOS_BACKGROUND";
            default: return null;
        }
    }
}

/* QoS constants for RIL QoS Direction (matches QoS constants in ril_qos.h) */
class RIL_QosDirection {
    public static final int RIL_QOS_TX = 0;
    public static final int RIL_QOS_RX = 1;

    public static String getName(int val) {
        switch(val) {
            case RIL_QOS_TX: return "RIL_QOS_TX";
            case RIL_QOS_RX: return "RIL_QOS_RX";
            default: return null;
        }
    }
}

/* QoS constants for RIL QoS Spec (matches QoS constants in ril_qos.h) */
class RIL_QosSpecKeys {
    /* Positive numerical value */
    public static final int RIL_QOS_SPEC_INDEX = 0;

    /* RIL_QosDirection */
    public static final int RIL_QOS_FLOW_DIRECTION = 1;
    /* RIL_QosClass */
    public static final int RIL_QOS_FLOW_TRAFFIC_CLASS = 2;
    /* Positive number in kbps */
    public static final int RIL_QOS_FLOW_DATA_RATE_MIN = 3;
    /* Positive number in kbps */
    public static final int RIL_QOS_FLOW_DATA_RATE_MAX = 4;
    /* Positive number in milliseconds */
    public static final int RIL_QOS_FLOW_LATENCY = 5;

    /* Positive numerical value */
    public static final int RIL_QOS_FLOW_3GPP2_PROFILE_ID = 6;
    /* Positive numerical value */
    public static final int RIL_QOS_FLOW_3GPP2_PRIORITY = 7;

    /* Positive numerical value */
    public static final int RIL_QOS_FILTER_INDEX = 8;
    /* IP VERSION - "IP" or "IPV6" */
    public static final int RIL_QOS_FILTER_IPVERSION = 9;
    /* RIL_QosDirection */
    public static final int RIL_QOS_FILTER_DIRECTION = 10;
    /* Format: xxx.xxx.xxx.xxx/yy */
    public static final int RIL_QOS_FILTER_IPV4_SOURCE_ADDR = 11;
    /* Format: xxx.xxx.xxx.xxx/yy */
    public static final int RIL_QOS_FILTER_IPV4_DESTINATION_ADDR = 12;
    /* Positive numerical Value (max 6-bit number) */
    public static final int RIL_QOS_FILTER_IPV4_TOS = 13;
    /* Mask for the 6 bit TOS value */
    public static final int RIL_QOS_FILTER_IPV4_TOS_MASK = 14;

    /**
     * *PORT_START is the starting port number
     * *PORT_RANGE is the number of continuous ports from *PORT_START key
     */
    public static final int RIL_QOS_FILTER_TCP_SOURCE_PORT_START = 15;
    public static final int RIL_QOS_FILTER_TCP_SOURCE_PORT_RANGE = 16;
    public static final int RIL_QOS_FILTER_TCP_DESTINATION_PORT_START = 17;
    public static final int RIL_QOS_FILTER_TCP_DESTINATION_PORT_RANGE = 18;
    public static final int RIL_QOS_FILTER_UDP_SOURCE_PORT_START = 19;
    public static final int RIL_QOS_FILTER_UDP_SOURCE_PORT_RANGE = 20;
    public static final int RIL_QOS_FILTER_UDP_DESTINATION_PORT_START = 21;
    public static final int RIL_QOS_FILTER_UDP_DESTINATION_PORT_RANGE = 22;

    /* TBD: For future implemenations based on requirements */
    public static final int RIL_QOS_FILTER_IP_NEXT_HEADER_PROTOCOL = 23;
    /* Format: xxxx:xxxx:xxxx:xxxx:xxxx:xxxx:xxxx:xxxx/yyy */
    public static final int RIL_QOS_FILTER_IPV6_SOURCE_ADDR = 24;
    /* Format: xxxx:xxxx:xxxx:xxxx:xxxx:xxxx:xxxx:xxxx/yyy */
    public static final int RIL_QOS_FILTER_IPV6_DESTINATION_ADDR = 25;
    public static final int RIL_QOS_FILTER_IPV6_TRAFFIC_CLASS = 26;
    public static final int RIL_QOS_FILTER_IPV6_FLOW_LABEL = 27;

    public static String getName(int val) {
        switch(val) {
            case RIL_QOS_SPEC_INDEX:
                return "RIL_QOS_SPEC_INDEX";
            case RIL_QOS_FLOW_DIRECTION:
                return "RIL_QOS_FLOW_DIRECTION";
            case RIL_QOS_FLOW_TRAFFIC_CLASS:
                return "RIL_QOS_FLOW_TRAFFIC_CLASS";
            case RIL_QOS_FLOW_DATA_RATE_MIN:
                return "RIL_QOS_FLOW_DATA_RATE_MIN";
            case RIL_QOS_FLOW_DATA_RATE_MAX:
                return "RIL_QOS_FLOW_DATA_RATE_MAX";
            case RIL_QOS_FLOW_LATENCY:
                return "RIL_QOS_FLOW_LATENCY";
            case RIL_QOS_FLOW_3GPP2_PROFILE_ID:
                return "RIL_QOS_FLOW_3GPP2_PROFILE_ID";
            case RIL_QOS_FLOW_3GPP2_PRIORITY:
                return "RIL_QOS_FLOW_3GPP2_PRIORITY";
            case RIL_QOS_FILTER_INDEX:
                return "RIL_QOS_FILTER_INDEX";
            case RIL_QOS_FILTER_IPVERSION:
                return "RIL_QOS_FILTER_IPVERSION";
            case RIL_QOS_FILTER_DIRECTION:
                return "RIL_QOS_FILTER_DIRECTION";
            case RIL_QOS_FILTER_IPV4_SOURCE_ADDR:
                return "RIL_QOS_FILTER_IPV4_SOURCE_ADDR";
            case RIL_QOS_FILTER_IPV4_DESTINATION_ADDR:
                return "RIL_QOS_FILTER_IPV4_DESTINATION_ADDR";
            case RIL_QOS_FILTER_IPV4_TOS:
                return "RIL_QOS_FILTER_IPV4_TOS";
            case RIL_QOS_FILTER_IPV4_TOS_MASK:
                return "RIL_QOS_FILTER_IPV4_TOS_MASK";
            case RIL_QOS_FILTER_TCP_SOURCE_PORT_START:
                return "RIL_QOS_FILTER_TCP_SOURCE_PORT_START";
            case RIL_QOS_FILTER_TCP_SOURCE_PORT_RANGE:
                return "RIL_QOS_FILTER_TCP_SOURCE_PORT_RANGE";
            case RIL_QOS_FILTER_TCP_DESTINATION_PORT_START:
                return "RIL_QOS_FILTER_TCP_DESTINATION_PORT_START";
            case RIL_QOS_FILTER_TCP_DESTINATION_PORT_RANGE:
                return "RIL_QOS_FILTER_TCP_DESTINATION_PORT_RANGE";
            case RIL_QOS_FILTER_UDP_SOURCE_PORT_START:
                return "RIL_QOS_FILTER_UDP_SOURCE_PORT_START";
            case RIL_QOS_FILTER_UDP_SOURCE_PORT_RANGE:
                return "RIL_QOS_FILTER_UDP_SOURCE_PORT_RANGE";
            case RIL_QOS_FILTER_UDP_DESTINATION_PORT_START:
                return "RIL_QOS_FILTER_UDP_DESTINATION_PORT_START";
            case RIL_QOS_FILTER_UDP_DESTINATION_PORT_RANGE:
                return "RIL_QOS_FILTER_UDP_DESTINATION_PORT_RANGE";
            case RIL_QOS_FILTER_IP_NEXT_HEADER_PROTOCOL:
                return "RIL_QOS_FILTER_IP_NEXT_HEADER_PROTOCOL";
            case RIL_QOS_FILTER_IPV6_SOURCE_ADDR:
                return "RIL_QOS_FILTER_IPV6_SOURCE_ADDR";
            case RIL_QOS_FILTER_IPV6_DESTINATION_ADDR:
                return "RIL_QOS_FILTER_IPV6_DESTINATION_ADDR";
            case RIL_QOS_FILTER_IPV6_TRAFFIC_CLASS:
                return "RIL_QOS_FILTER_IPV6_TRAFFIC_CLASS";
            case RIL_QOS_FILTER_IPV6_FLOW_LABEL:
                return "RIL_QOS_FILTER_IPV6_FLOW_LABEL";
            default:
                return null;
        }
    }
}

/* Overall QoS status */
class RIL_QosStatus {
    /* Qos not active */
    public static final int RIL_QOS_STATUS_NONE      = 0;
    /* Qos currently active */
    public static final int RIL_QOS_STATUS_ACTIVATED = 1;
    /* Qos Suspended */
    public static final int RIL_QOS_STATUS_SUSPENDED = 2;
}

class RIL_QosIndStates {
    /* QoS operation complete */
    public static final int RIL_QOS_ACTIVATED         = 0;
    /* QoS (NW initiated) setup complete */
    public static final int RIL_QOS_ACTIVATED_NETWORK = 1;
    /* QoS released by the user */
    public static final int RIL_QOS_USER_RELEASE      = 2;
    /* QoS released by the network */
    public static final int RIL_QOS_NETWORK_RELEASE   = 3;
    /* QoS suspended */
    public static final int RIL_QOS_SUSPENDED         = 4;
    /* QoS modified */
    public static final int RIL_QOS_MODIFIED          = 5;
    /* Any other error */
    public static final int RIL_QOS_ERROR_UNKNOWN     = 6;
}

/**
 * A class containing all the QoS parameters
 *
 * This class exposes all the QoS (flow/filter parameters to the higher layers.
 * The various flow/filter parameters will be mapped to the corresponding RIL
 * interface parameters. This is used by the higher layers when creating a QoS Request.
 *
 * @hide
 */
public class QosSpec implements Parcelable {
    static final String TAG = "QosSpec";

    public static class QosDirection{
        public static final int QOS_TX = RIL_QosDirection.RIL_QOS_TX;
        public static final int QOS_RX = RIL_QosDirection.RIL_QOS_RX;
    }

    public static class QosClass{
        public static final int CONVERSATIONAL = RIL_QosClass.RIL_QOS_CONVERSATIONAL;
        public static final int STREAMING = RIL_QosClass.RIL_QOS_STREAMING;
        public static final int INTERACTIVE = RIL_QosClass.RIL_QOS_INTERACTIVE;
        public static final int BACKGROUND = RIL_QosClass.RIL_QOS_BACKGROUND;
    }

    /**
     * The set of keys defined for QoS params.
     * A QosSpec object can contain multiple flow/filter parameter set and
     * each group will be identified by a unique index. This enables bundling of
     * many QoS flows in one QoS Spec.
     *
     *  For e.g:
     *  A QoS Spec with one TX flow
     *  SPEC_INDEX=0,FLOW_DIRECTION=0[,FLOW_DATA_RATE_MIN=64000,
     *                      FLOW_DATA_RATE_MAX=128000,FLOW_LATENCY=100]
     */
    public static class QosSpecKey {
        // Index of a particular spec. This is required to support bundling of
        // multiple QoS specs in one request. [Mandatory]
        public static final int SPEC_INDEX =
                            RIL_QosSpecKeys.RIL_QOS_SPEC_INDEX;

        /* Values from QosDirection [Mandatory] */
        public static final int FLOW_DIRECTION =
                            RIL_QosSpecKeys.RIL_QOS_FLOW_DIRECTION;

        /* Values from QosClass */
        public static final int FLOW_TRAFFIC_CLASS =
                            RIL_QosSpecKeys.RIL_QOS_FLOW_TRAFFIC_CLASS;

        /* Data rate to be specified in bits/sec */
        public static final int FLOW_DATA_RATE_MIN =
                            RIL_QosSpecKeys.RIL_QOS_FLOW_DATA_RATE_MIN;
        public static final int FLOW_DATA_RATE_MAX =
                            RIL_QosSpecKeys.RIL_QOS_FLOW_DATA_RATE_MAX;

        /* Latency to be specified in milliseconds */
        public static final int FLOW_LATENCY =
                            RIL_QosSpecKeys.RIL_QOS_FLOW_LATENCY;

        public static final int FLOW_3GPP2_PROFILE_ID =
                            RIL_QosSpecKeys.RIL_QOS_FLOW_3GPP2_PROFILE_ID;
        public static final int FLOW_3GPP2_PRIORITY =
                            RIL_QosSpecKeys.RIL_QOS_FLOW_3GPP2_PRIORITY;

        /* Filter Index [Mandatory] */
        public static final int FILTER_INDEX =
                            RIL_QosSpecKeys.RIL_QOS_FILTER_INDEX;

        /* IP Version [Mandatory], values are "IP" or "IPV6" */
        public static final int FILTER_IPVERSION =
                            RIL_QosSpecKeys.RIL_QOS_FILTER_IPVERSION;

        /* Values from QosDirection [Mandatory] */
        public static final int FILTER_DIRECTION =
                            RIL_QosSpecKeys.RIL_QOS_FILTER_DIRECTION;

        /* Format: xxx.xxx.xxx.xxx/yy */
        public static final int FILTER_IPV4_SOURCE_ADDR =
                            RIL_QosSpecKeys.RIL_QOS_FILTER_IPV4_SOURCE_ADDR;
        public static final int FILTER_IPV4_DESTINATION_ADDR =
                            RIL_QosSpecKeys.RIL_QOS_FILTER_IPV4_DESTINATION_ADDR;

        /* TOS byte value, Mask is mandatory if TOS value is used.
         * e.g. A mask for one TOS byte should be 255 */
        public static final int FILTER_IPV4_TOS =
                            RIL_QosSpecKeys.RIL_QOS_FILTER_IPV4_TOS;
        public static final int FILTER_IPV4_TOS_MASK =
                            RIL_QosSpecKeys.RIL_QOS_FILTER_IPV4_TOS_MASK;

        /* Port Range is mandatory if port is included. RANGE for a single PORT is 0 */
        public static final int FILTER_TCP_SOURCE_PORT_START =
                            RIL_QosSpecKeys.RIL_QOS_FILTER_TCP_SOURCE_PORT_START;
        public static final int FILTER_TCP_SOURCE_PORT_RANGE =
                            RIL_QosSpecKeys.RIL_QOS_FILTER_TCP_SOURCE_PORT_RANGE;
        public static final int FILTER_TCP_DESTINATION_PORT_START =
                            RIL_QosSpecKeys.RIL_QOS_FILTER_TCP_DESTINATION_PORT_START;
        public static final int FILTER_TCP_DESTINATION_PORT_RANGE =
                            RIL_QosSpecKeys.RIL_QOS_FILTER_TCP_DESTINATION_PORT_RANGE;
        public static final int FILTER_UDP_SOURCE_PORT_START =
                            RIL_QosSpecKeys.RIL_QOS_FILTER_UDP_SOURCE_PORT_START;
        public static final int FILTER_UDP_SOURCE_PORT_RANGE =
                            RIL_QosSpecKeys.RIL_QOS_FILTER_UDP_SOURCE_PORT_RANGE;
        public static final int FILTER_UDP_DESTINATION_PORT_START =
                            RIL_QosSpecKeys.RIL_QOS_FILTER_UDP_DESTINATION_PORT_START;
        public static final int FILTER_UDP_DESTINATION_PORT_RANGE =
                            RIL_QosSpecKeys.RIL_QOS_FILTER_UDP_DESTINATION_PORT_RANGE;

        /* Format: xxxx:xxxx:xxxx:xxxx:xxxx:xxxx:xxxx:xxxx/yyy */
        public static final int FILTER_IPV6_SOURCE_ADDR =
                            RIL_QosSpecKeys.RIL_QOS_FILTER_IPV6_SOURCE_ADDR;
        public static final int FILTER_IPV6_DESTINATION_ADDR =
                            RIL_QosSpecKeys.RIL_QOS_FILTER_IPV6_DESTINATION_ADDR;
        public static final int FILTER_IPV6_TRAFFIC_CLASS =
                            RIL_QosSpecKeys.RIL_QOS_FILTER_IPV6_TRAFFIC_CLASS;
        public static final int FILTER_IPV6_FLOW_LABEL =
                            RIL_QosSpecKeys.RIL_QOS_FILTER_IP_NEXT_HEADER_PROTOCOL;

        public static boolean isValid(int key) {
            boolean retVal = false;
            try {
                for (Field k : QosSpecKey.class.getFields()) {
                    if (k.getInt(k) == key)
                        return true;
                }
            } catch(java.lang.IllegalAccessException e) {
                retVal = false;
            }
            return retVal;
        }

        public static int getKey(String key) throws IllegalArgumentException {
            try {
                for (Field k : QosSpecKey.class.getFields()) {
                    if (k.getName().equals(key))
                        return k.getInt(k);
                }
            } catch(java.lang.IllegalAccessException e) {
                throw new IllegalArgumentException();
            }
            return 0;
        }

        public static String getKeyName(int key) {
            String retVal = null;
            try {
                for (Field k : QosSpecKey.class.getFields()) {
                    if (k.getInt(k) == key)
                        return k.getName();
                }
            } catch(java.lang.IllegalAccessException e) {
                Log.w(TAG, "Warning: Invalid key:" + key);
            }
            return retVal;
        }
    }

    /* Overall status of a QoS specification */
    public static class QosStatus {
        public static final int NONE =
                            RIL_QosStatus.RIL_QOS_STATUS_NONE;
        public static final int ACTIVATED =
                            RIL_QosStatus.RIL_QOS_STATUS_ACTIVATED;
        public static final int SUSPENDED =
                            RIL_QosStatus.RIL_QOS_STATUS_SUSPENDED;
    }

    /* Values of QoS Indication Status */
    public static class QosIndStates {
        public static final int INITIATED = 0;
        public static final int ACTIVATED = 1;
        public static final int RELEASING = 2;
        public static final int RELEASED = 3;
        public static final int RELEASED_NETWORK = 4;
        public static final int MODIFIED = 5;
        public static final int MODIFYING = 6;
        public static final int MODIFIED_NETWORK = 7;
        public static final int SUSPENDED = 8;
        public static final int SUSPENDING = 9;
        public static final int RESUMING = 10;
        public static final int RESUMED_NETWORK = 11;
        public static final int REQUEST_FAILED = 12;
        public static final int NONE = 13;
    }

    public static class QosIntentKeys {
        /* Status of the QoS operation from QosIndStates. Type of Value: Integer */
        public static final String QOS_INDICATION_STATE = "QosIndicationState";

        /* Status of the QoS Flow (identified by a QoS ID) from QosStatus */
        public static final String QOS_STATUS = "QosStatus";

        /* String error returned from the modem for any failure.
         * Optional param. Type of Value: Integer */
        public static final String QOS_ERROR = "QosError";

        /* User Data that is echoed back to the higher layers.
         * Used only for IND following setup qos request. Type of Value: Integer */
        public static final String QOS_USERDATA = "QosUserData";

        /* Unique QoS ID. Type of Value: Integer */
        public static final String QOS_ID = "QosId";

        /* Parcelable QosSpec object. Used in the indication following getQosStatus */
        public static final String QOS_SPEC = "QosSpec";
    }

    LinkedHashMap<Integer, QosPipe> mQosPipes;

    /* Unique token used to identify a QosSpec returned in a response */
    private int mUserData = 0;

    private static int mPipeId = 0;


    public class QosPipe {

        public class QosKeyValue {
            int qosKey;
            String qosValue;

            QosKeyValue(int key, String value) {
                qosKey = key;
                qosValue = value;
            }
        }

        /** The List of Key/Value objects. Can contain duplicate keys */
        LinkedList<QosKeyValue> mQosParams;

        public QosPipe() {
            mQosParams = new LinkedList<QosKeyValue>();
        }

        /**
         * Remove all capabilities
         */
        public void clear() {
            mQosParams.clear();
        }

        /**
         * Returns whether this map is empty.
         */
        public boolean isEmpty() {
            return mQosParams.isEmpty();
        }

        /**
         * Returns the number of elements in this map.
         *
         * @return the number of elements in this map.
         */
        public int size() {
            return mQosParams.size();
        }

        /**
         * Returns the value of the capability string with the specified key
         * if the key was unique.
         *
         * @param key
         * @return the value of QosS Param key if the key was unique.
         * or {@code null} if key was not found or had duplicates.
         */
        public String get(int key) {
            int count = 0;
            String value = null;
            QosKeyValue kv = null;

            ListIterator itr = mQosParams.listIterator();
            while(itr.hasNext()) {
                kv = (QosKeyValue)itr.next();
                if (kv.qosKey == key) {
                    value = kv.qosValue;
                    count++;
                }
            }

            return count == 1 ? value : null;
        }


        /**
         * Returns the list of values for the specified key
         *
         * @param key
         * @return the list of values of QosS Param key
         * or {@code null} if key was not found
         */
        public List<String> getValues(int key) {
            List<String> values = new ArrayList<String>();
            QosKeyValue kv = null;

            ListIterator itr = mQosParams.listIterator();
            while(itr.hasNext()) {
                kv = (QosKeyValue)itr.next();
                if (kv.qosKey == key) {
                    values.add(kv.qosValue);
                }
            }

            return values;
        }

        /**
         * Store the key/value capability pair.
         *
         * @param key
         * @param value
         * @throws IllegalArgumentException if QosParams does not recognize the key:value pair
         */
        public void put(int key, String value) {

            // check to make sure input is valid, otherwise ignore
            if (QosSpec.QosSpecKey.isValid(key) == false) {
                Log.d(QosSpec.TAG, "Ignoring invalid key:" + key);
                throw new IllegalArgumentException("Invalid Key:" + key);
            }

            mQosParams.add(new QosKeyValue(key, value));
        }

        /**
         * Returns the list of Keys in the pipe.
         *
         * @return the list of keys
         */
        public List<Integer> getKeys() {
            List<Integer> keys = new ArrayList<Integer>();
            for (QosKeyValue qkv : mQosParams.toArray(new QosKeyValue[0])) {
                keys.add(qkv.qosKey);
            }

            return keys;
        }

        /**
         * Returns the list of values in the pipe.
         *
         * @return the list of values
         */
        public List<String> getValues() {
            List<String> values = new ArrayList<String>();
            for (QosKeyValue qkv : mQosParams.toArray(new QosKeyValue[0])) {
                values.add(qkv.qosValue);
            }

            return values;
        }

        private String getRilPipeSpec() {
            String rilPipeSpec = "";
            String keyValue = "";

            for (QosKeyValue qkv : mQosParams.toArray(new QosKeyValue[0])) {
                keyValue = RIL_QosSpecKeys.getName(qkv.qosKey)
                                 + "=" + qkv.qosValue;

                rilPipeSpec += keyValue + ",";
            }

            // Remove last comma
            rilPipeSpec = rilPipeSpec.substring(0, rilPipeSpec.length() - 1);

            return rilPipeSpec;
        }

        /**
         * Convert to string for debugging
         */
        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("{");
            boolean firstTime = true;
            for (QosKeyValue qkv : mQosParams.toArray(new QosKeyValue[0])) {
                if (firstTime) {
                    firstTime = false;
                } else {
                    sb.append(", ");
                }
                sb.append(qkv.qosKey);
                sb.append(":\"");
                sb.append(qkv.qosValue);
                sb.append("\"");
            }
            sb.append("}");
            return sb.toString();
        }
    }

    public QosSpec() {
        mQosPipes = new LinkedHashMap<Integer, QosPipe>();
    }

    /**
     * Copy constructor
     */
    public QosSpec(QosSpec qosSpec) {
        this();
        for(QosPipe qosPipe : qosSpec.mQosPipes.values()) {
            QosPipe pipe = createPipe();
            for (QosPipe.QosKeyValue qkv : qosPipe.mQosParams.toArray(new QosPipe.QosKeyValue[0])) {
                pipe.put(qkv.qosKey, qkv.qosValue);
            }
        }
    }

    public void clear() {
        for(QosPipe pipe : mQosPipes.values()) {
            pipe.clear();
        }
        mQosPipes.clear();
    }

    public boolean isValid(int pipeId) {
        return mQosPipes.containsKey(pipeId);
    }

    public QosPipe createPipe() {
        int pipeId = mPipeId++;

        QosPipe pipe = new QosPipe();
        mQosPipes.put(pipeId, pipe);
        return pipe;
    }

    public QosPipe createPipe(String flowFilterSpec) {
        int pipeId = mPipeId++;

        QosPipe pipe = null;

        if (flowFilterSpec == null) {
            return pipe;
        }

        pipe = new QosPipe();
        mQosPipes.put(pipeId, pipe);

        //Parse the flow/filter spec and add it to pipe
        String keyvalues[] = flowFilterSpec.split(",");
        String kvpair[] = null;
        String keyStr = null;
        String value = null;

        int key;
        for (String kv: keyvalues) {
            try {
                kvpair = kv.split("=");
                keyStr = kvpair[0];
                value = kvpair[1];
                key = RIL_QosSpecKeys.class.getField(keyStr).getInt(
                                                 RIL_QosSpecKeys.class);
                pipe.put(key, value);
            } catch (Throwable t) {
                Log.e(TAG, "Warning: Invalid key:" + keyStr);
            }
        }
        return pipe;
    }

    public Collection<QosPipe> getQosPipes() {
        return mQosPipes.values();
    }

    /* Search for the QosPipe with spec index */
    public QosPipe getQosPipes(String specIndex) {
        for (QosPipe pipe : mQosPipes.values()) {
            if (pipe.get(QosSpec.QosSpecKey.SPEC_INDEX).equals(specIndex))
                return pipe;
        }
        return null;
    }

    public String getQosSpec(int pipeId, int key) {
        String value = null;
        if (isValid(pipeId));
            value = mQosPipes.get(pipeId).get(key);
        return value;
    }

    public List<Integer> pipeKeys(int pipeId) {
        return isValid(pipeId) ? mQosPipes.get(pipeId).getKeys() : null;
    }

    public List<String> pipeValues(int pipeId) {
        return isValid(pipeId) ? mQosPipes.get(pipeId).getValues() : null;
    }

    public int pipeSize(int pipeId) {
        int size = 0;
        if (isValid(pipeId))
            size = mQosPipes.get(pipeId).mQosParams.size();
        else
            Log.e(TAG, "Warning: Invalid pipeId:" + pipeId);
        return size;
    }

    public boolean isEmpty(int pipeId) {
        boolean flag = false;
        if (isValid(pipeId))
            flag = mQosPipes.get(pipeId).mQosParams.isEmpty();
        else
            Log.e(TAG, "Warning: Invalid pipeId:" + pipeId);
        return flag;
    }

    public ArrayList<String> getRilQosSpec() {
        ArrayList<String> rilQosSpec = new ArrayList<String>();
        for (QosPipe pipe : mQosPipes.values()) {
            rilQosSpec.add(pipe.getRilPipeSpec());
        }
        return rilQosSpec;
    }

    /**
     * Implement the Parcelable interface
     *
     * @hide
     */
    public int describeContents() {
        return 0;
    }

    /**
     * Implement the Parcelable interface.
     * @hide
     */
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mUserData);
        dest.writeInt(mQosPipes.size());
        for(QosPipe pipe : mQosPipes.values()) {
            dest.writeInt(pipe.mQosParams.size());

            for (QosPipe.QosKeyValue qkv : pipe.mQosParams.toArray(new QosPipe.QosKeyValue[0])) {
                dest.writeInt(qkv.qosKey);
                dest.writeString(qkv.qosValue);
            }
        }
    }

    /**
     * Implement the Parcelable interface.
     *
     * @hide
     */
    public static final Creator<QosSpec> CREATOR = new Creator<QosSpec>() {
        public QosSpec createFromParcel(Parcel in) {
            QosSpec qosSpec = new QosSpec();
            qosSpec.setUserData(in.readInt());
            int nPipes = in.readInt();
            while (nPipes-- != 0) {
                int mapSize = in.readInt();
                QosPipe pipe = qosSpec.createPipe();
                while (mapSize-- != 0) {
                    int key = in.readInt();
                    String value = in.readString();
                    pipe.put(key, value);
                }
            }
            return qosSpec;
        }

        /**
         * Required function for implementing Parcelable interface
         */
        public QosSpec[] newArray(int size) {
            return new QosSpec[size];
        }
    };

    public void setUserData(int userData) {
        mUserData = userData;
    }

    public int getUserData() {
        return mUserData;
    }

    /**
     * Debug logging
     */
    protected static void log(String s) {
        Log.d(TAG, s);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        for (Entry<Integer, QosPipe> entry : mQosPipes.entrySet()) {
            sb.append(entry.toString());
        }
        sb.append("}");
        return sb.toString();
    }
}
