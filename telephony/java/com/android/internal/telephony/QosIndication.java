/*
 * Copyright (c) 2009-2011, Code Aurora Forum. All rights reserved.
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

package com.android.internal.telephony;

import android.os.SystemProperties;

import android.content.Context;
import android.content.Intent;

import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.telephony.QosSpec;

public class QosIndication {
    Intent mIntent = new Intent(TelephonyIntents.ACTION_QOS_STATE_IND);

    QosIndication() {
        // If QoS indications are to be unicast, set the class name.
        // QOS: This system property is useful when using test tools
        // to perform direct testing of QoS using Android Telephony.
        // This can be removed if/when CNE based testing tools are developed
        if (SystemProperties.getBoolean("persist.telephony.qosUnicast", false)) {
            mIntent.setClassName("com.android.server",
                    "com.android.server.LinkManager");
        }
    }

    void setIndState(int state, String error) {
        if (error != null) {
            // mIntent.putExtra(QosSpec.QosIntentKeys.QOS_ERROR, error);
            state = QosSpec.QosIndStates.REQUEST_FAILED;
        }
        mIntent.putExtra(QosSpec.QosIntentKeys.QOS_INDICATION_STATE, state);
    }

    void setQosState(int state) {
        mIntent.putExtra(QosSpec.QosIntentKeys.QOS_STATUS, state);
    }

    void setUserData(int userData) {
        mIntent.putExtra(QosSpec.QosIntentKeys.QOS_USERDATA, userData);
    }

    void setQosId(int qosId) {
        mIntent.putExtra(QosSpec.QosIntentKeys.QOS_ID, qosId);
    }

    void setQosSpec(QosSpec spec) {
        mIntent.putExtra(QosSpec.QosIntentKeys.QOS_SPEC, spec);
    }

    Intent getIndication() {
        return mIntent;
    }
}
