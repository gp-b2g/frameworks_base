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

import java.util.ArrayList;


/**
 * {@hide}
 */
public final class OplRecords {
    static final String LOG_TAG = "GSM";
    static final int wildCardDigit = 0x0D;

    private static final boolean DBG = false;

    // ***** Instance Variables
    private ArrayList <OplRecord> mRecords;

    // ***** Constructor
    OplRecords(ArrayList <byte[]> records) {
        mRecords = new ArrayList<OplRecord>();

        for (byte[] record : records) {
             mRecords.add(new OplRecord(record));
             if (DBG) {
                log("Record " + mRecords.size() + ": " +
                mRecords.get(mRecords.size() - 1));
             }
        }
    }

    private void log(String s) {
        Log.d(LOG_TAG, "[OplRecords EONS] " + s);
    }

    private void loge(String s) {
        Log.e(LOG_TAG, "[OplRecords EONS] " + s);
    }

    // ***** Public Methods
    public int size() {
        return (mRecords != null) ? mRecords.size() : 0;
    }

    /**
     * Function to get PNN record number from matching OPL record for registered plmn.
     * @param operator, registered plmn (mcc+mnc)
     * @param lac, current lac
     * @param useLac, whether to match lac or not
     * @return returns PNN record number from matching OPL record.
     */
    public int getMatchingPnnRecord(String operator, int lac, boolean useLac) {
        int[] bcchPlmn = {0,0,0,0,0,0};

        if (TextUtils.isEmpty(operator)) {
            loge("No registered operator.");
            return 0;
        } else if (useLac && (lac == -1)) {
            loge("Invalid LAC");
            return 0;
        }

        int length = operator.length();
        if ((length != 5) && (length != 6)) {
            loge("Invalid registered operator length " + length);
            return 0;
        }

        // Convert operator sting into MCC/MNC digits.
        for (int i = 0; i < length; i++) {
             bcchPlmn[i] = operator.charAt(i) - '0';
        }

        for (OplRecord record : mRecords) {
             if (matchPlmn(record.mPlmn, bcchPlmn)) {
                 // While deriving EONS for Available Networks, we do
                 // not have Lac, hence just match the plmn.
                 if (!useLac || ((record.mLac1 <= lac) && (lac <= record.mLac2))) {
                      // Matching OPL record found, return PNN record number.
                      return record.getPnnRecordNumber();
                 }
             }
        }

        // No matching OPL record found, return 0 so that operator name from Ril
        // can be used.
        loge("No matching OPL record found.");
        return 0;
    }

    /**
     * Function to match plmn from EF_OPL record with the registered plmn.
     * @param simPlmn, plmn read from EF_OPL record, size will always be 6
     * @param bcchPlmn, registered plmn, size is 5 or 6
     * @return true if plmns match, otherwise false.
     */
    private boolean matchPlmn (int simPlmn[], int bcchPlmn[]) {
        boolean match = true;

        for (int i = 0; i < bcchPlmn.length; i++) {
             match = match & ((bcchPlmn[i] == simPlmn[i]) ||
                   (simPlmn[i] == wildCardDigit));
        }

        return match;
    }

    // EF_OPL record parsing as per 3GPP TS 31.102 section 4.2.59
    public static class OplRecord {
        private int[] mPlmn = {0,0,0,0,0,0};
        private int mLac1;
        private int mLac2;
        private int mPnnRecordNumber;

        OplRecord(byte[] record) {
            getPlmn(record);
            getLac(record);
            mPnnRecordNumber = 0xff & record[7];
        }

        // PLMN decoding as per 3GPP TS 24.008 section 10.5.1.13
        private void getPlmn(byte[] record) {
            mPlmn[0] = 0x0f & record[0];/*mcc1*/
            mPlmn[1] = 0x0f & (record[0] >> 4);/*mcc2*/
            mPlmn[2] = 0x0f & record[1];/*mcc3*/

            mPlmn[3] = 0x0f & record[2];/*mnc1*/
            mPlmn[4] = 0x0f & (record[2] >> 4);/*mnc2*/
            mPlmn[5] = 0x0f & (record[1] >> 4);/*mnc3*/

            // Certain operators support 2 digit MNCs. In such cases the last
            // digit is not programmed. Ideally only 2 digits of BCCH MNC
            // should be compared with corresponding digits of SIM MNC,
            // this should also match with BCCH MNC where MNC3 is zero.
            // Hence forcing SIM MNC3 to zero if it is not programmed.
            if (mPlmn[5] == 0x0f) mPlmn[5] = 0;
        }

        // LAC decoding as per 3GPP TS 24.008
        private void getLac(byte[] record) {
            // LAC bytes are in big endian. Bytes 3 and 4 are for LAC1 and
            // bytes 5 and 6 are for LAC2.
            mLac1 = ((record[3] & 0xff) << 8) | (record[4] & 0xff);
            mLac2 = ((record[5] & 0xff) << 8) | (record[6] & 0xff);
        }

        public int getPnnRecordNumber() {
            return mPnnRecordNumber;
        }

        public String toString() {
            return "PLMN=" + Integer.toHexString(mPlmn[0]) + Integer.toHexString(mPlmn[1]) +
                Integer.toHexString(mPlmn[2]) + Integer.toHexString(mPlmn[3]) +
                Integer.toHexString(mPlmn[4]) + Integer.toHexString(mPlmn[5]) +
                ", LAC1=" + mLac1 + ", LAC2=" + mLac2 + ", PNN Record=" + mPnnRecordNumber;
        }
    }
}
