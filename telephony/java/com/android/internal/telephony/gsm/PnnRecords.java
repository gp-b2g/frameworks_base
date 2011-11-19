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

import android.text.TextUtils;
import android.util.Log;

import com.android.internal.telephony.IccUtils;

import java.util.ArrayList;


/**
 * {@hide}
 */
public final class PnnRecords {
    static final String LOG_TAG = "GSM";

    private static final boolean DBG = false;

    // ***** Instance Variables
    private ArrayList <PnnRecord> mRecords;
    private String mCurrentEons;

    // ***** Constructor
    PnnRecords(ArrayList <byte[]> records) {
        mRecords = new ArrayList<PnnRecord>();
        mCurrentEons = null;

        for (byte[] record : records) {
             mRecords.add(new PnnRecord(record));
             if (DBG) {
                log("Record " + mRecords.size() + ": " +
                mRecords.get(mRecords.size() - 1));
             }
        }
    }

    public static void log(String s) {
        Log.d(LOG_TAG, "[PnnRecords EONS] " + s);
    }

    public static void loge(String s) {
        Log.e(LOG_TAG, "[PnnRecords EONS] " + s);
    }

    // ***** Public Methods
    public int size() {
        return (mRecords != null) ? mRecords.size() : 0;
    }

    public String getCurrentEons() {
        return mCurrentEons;
    }

    /**
     * Function to get Full Name from given PNN record number.
     * @param pnnRecord, PNN record number
     * @param update, specifies whether to update currentEons or not
     * @return returns Full Name from given PNN record.
     */
    public String getNameFromPnnRecord(int recordNumber, boolean update) {
        String fullName = null;

        if (recordNumber < 1 || recordNumber > mRecords.size()) {
            loge("Invalid PNN record number " + recordNumber);
        } else {
            fullName = mRecords.get(recordNumber - 1).getFullName();
        }

        // When deriving name for Available Networks, current EONS name should
        // not be updated.
        if (update) mCurrentEons = fullName;
        return fullName;
    }

    // EF_PNN record parsing as per 3GPP TS 31.102 section 4.2.58
    public static class PnnRecord {
        static final int TAG_FULL_NAME_IEI = 0x43;
        static final int TAG_SHORT_NAME_IEI = 0x45;
        static final int TAG_ADDL_INFO = 0x80;

        private String mFullName;
        private String mShortName;
        private String mAddlInfo;

        PnnRecord(byte[] record) {
            mFullName = null;
            mShortName = null;
            mAddlInfo = null;

            SimTlv tlv = new SimTlv(record, 0, record.length);

            if (tlv.isValidObject() && tlv.getTag() == TAG_FULL_NAME_IEI) {
                mFullName = IccUtils.networkNameToString(tlv.getData(), 0,
                      tlv.getData().length);
            } else {
                if(DBG) log("Invalid tlv Object for Full Name, tag= " +
                      tlv.getTag() + ", valid=" + tlv.isValidObject());
            }

            tlv.nextObject();
            if (tlv.isValidObject() && tlv.getTag() == TAG_SHORT_NAME_IEI) {
                mShortName = IccUtils.networkNameToString(tlv.getData(), 0,
                      tlv.getData().length);
            } else {
                if(DBG) log("Invalid tlv Object for Short Name, tag= " +
                      tlv.getTag() + ", valid=" + tlv.isValidObject());
            }

            tlv.nextObject();
            if (tlv.isValidObject() && tlv.getTag() == TAG_ADDL_INFO) {
                mAddlInfo = IccUtils.networkNameToString(tlv.getData(), 0,
                      tlv.getData().length);
            } else {
                if(DBG) log("Invalid tlv Object for Addl Info, tag= " +
                      tlv.getTag() + ", valid=" + tlv.isValidObject());
            }
        }

        public String getFullName() {
            return mFullName;
        }

        public String getShortName() {
            return mShortName;
        }

        public String getAddlInfo() {
            return mAddlInfo;
        }

        public String toString() {
            return "Full Name=" + mFullName + ", Short Name=" + mShortName +
               ", Additional Info=" + mAddlInfo;
        }
    }
}
