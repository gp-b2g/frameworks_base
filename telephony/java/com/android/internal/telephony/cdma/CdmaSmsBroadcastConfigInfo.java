/*
 * Copyright (C) 2009 The Android Open Source Project
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
    private int mServiceCategory;
    private int mLanguage;
    private boolean mSelected;

    /**
     * Initialize the object from rssi and cid.
     */
    public CdmaSmsBroadcastConfigInfo(int serviceCategory, int language, boolean selected) {
        mServiceCategory = serviceCategory;
        mLanguage = language;
        mSelected = selected;
    }

    /**
     * @return the mServiceCategory
     */
    public int getServiceCategory() {
        return mServiceCategory;
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
            mServiceCategory + "] " +
            (isSelected() ? "ENABLED" : "DISABLED");
    }
}