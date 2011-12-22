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

package com.android.internal.telephony.cdma;

/**
 * CdmaSmsBroadcastConfigInfo defines one configuration of Cdma Broadcast
 * Message to be received by the ME
 *
 * serviceCategory defines a Broadcast message identifier
 * whose value is 0x0000 - 0xFFFF as defined in C.R1001G 9.3.1 and 9.3.2.
 * All other values can be treated as empty message ID.
 *
 * language defines a language code of Broadcast Message
 * whose value is 0x00 - 0x07 as defined in C.R1001G 9.2.
 * All other values can be treated as empty language code.
 *
 * selected false means message types specified in serviceCategory
 * are not accepted, while true means accepted.
 *
 */
public class CdmaSmsBroadcastConfigInfo {
    private int mFromServiceCategory;
    private int mToServiceCategory;
    private int mLanguage;
    private boolean mSelected;

    /**
     * Initialize the object from rssi and cid.
     */
    public CdmaSmsBroadcastConfigInfo(int fromServiceCategory, int toServiceCategory, int language, boolean selected) {
        mFromServiceCategory = fromServiceCategory;
        mToServiceCategory = toServiceCategory;
        mLanguage = language;
        mSelected = selected;
    }

    /**
     * @return the mFromServiceCategory
     */
    public int getFromServiceCategory() {
        return mFromServiceCategory;
    }

    /**
     * @return the mToServiceCategory
     */
    public int getToServiceCategory() {
        return mToServiceCategory;
    }

    /**
     * @return the mLanguage
     */
    public int getLanguage() {
        return mLanguage;
    }

    /**
     * @return the selected
     */
    public boolean isSelected() {
        return mSelected;
    }

    @Override
    public String toString() {
        return "CdmaSmsBroadcastConfigInfo: Id [" +
            mFromServiceCategory + ", " + mToServiceCategory + "] " +
            (isSelected() ? "ENABLED" : "DISABLED");
    }
}
