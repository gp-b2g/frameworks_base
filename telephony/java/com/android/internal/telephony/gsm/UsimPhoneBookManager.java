/*
 * Copyright (C) 2009 The Android Open Source Project
 * Copyright (C) 2011-2012 Code Aurora Forum. All rights reserved.
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

import android.telephony.PhoneNumberUtils;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.text.TextUtils;

import com.android.internal.telephony.AdnRecord;
import com.android.internal.telephony.AdnRecordCache;
import com.android.internal.telephony.IccConstants;
import com.android.internal.telephony.IccFileHandler;
import com.android.internal.telephony.IccUtils;
import com.android.internal.telephony.PhoneBase;
import com.android.internal.telephony.GsmAlphabet;
import org.apache.harmony.luni.lang.reflect.ListOfTypes;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * This class implements reading and parsing USIM records.
 * Refer to Spec 3GPP TS 31.102 for more details.
 *
 * {@hide}
 */
public class UsimPhoneBookManager extends Handler implements IccConstants {
    private static final String LOG_TAG = "GSM";
    private static final boolean DBG = true;
    private PbrFile mPbrFile;
    private Boolean mIsPbrPresent;
    private IccFileHandler mFh;
    private AdnRecordCache mAdnCache;
    private Object mLock = new Object();
    private ArrayList<AdnRecord> mPhoneBookRecords;
    private boolean mEmailPresentInIap = false;
    private int mEmailTagNumberInIap = 0;
    private boolean  mAnrPresentInIap = false;
    private int mAnrTagNumberInIap = 0;
    private Map<Integer,ArrayList<byte[]>> mIapFileRecord;
    private Map<Integer,ArrayList<byte[]>> mEmailFileRecord;
    private Map<Integer,ArrayList<byte[]>> mAnrFileRecord;
    private ArrayList<Integer> mAdnLengthList = null;
    private Map<Integer, ArrayList<String>> mEmailsForAdnRec;
    private Map<Integer, ArrayList<String>> mAnrsForAdnRec;
    private int pendingExtLoads;
    private boolean success = false;
    private boolean mRefreshCache = false;

    private static final int EVENT_PBR_LOAD_DONE = 1;
    private static final int EVENT_USIM_ADN_LOAD_DONE = 2;
    private static final int EVENT_IAP_LOAD_DONE = 3;
    private static final int EVENT_EMAIL_LOAD_DONE = 4;
    private static final int EVENT_ANR_LOAD_DONE = 5;
    private static final int EVENT_EF_EMAIL_RECORD_SIZE_DONE = 6;
    private static final int EVENT_EF_ANR_RECORD_SIZE_DONE = 7;
    private static final int EVENT_UPDATE_EMAIL_RECORD_DONE = 8;
    private static final int EVENT_UPDATE_ANR_RECORD_DONE = 9;
    private static final int EVENT_EF_IAP_RECORD_SIZE_DONE = 10;
    private static final int EVENT_UPDATE_IAP_RECORD_DONE = 11;

    private static final int USIM_TYPE1_TAG   = 0xA8;
    private static final int USIM_TYPE2_TAG   = 0xA9;
    private static final int USIM_TYPE3_TAG   = 0xAA;
    private static final int USIM_EFADN_TAG   = 0xC0;
    private static final int USIM_EFIAP_TAG   = 0xC1;
    private static final int USIM_EFEXT1_TAG  = 0xC2;
    private static final int USIM_EFSNE_TAG   = 0xC3;
    private static final int USIM_EFANR_TAG   = 0xC4;
    private static final int USIM_EFPBC_TAG   = 0xC5;
    private static final int USIM_EFGRP_TAG   = 0xC6;
    private static final int USIM_EFAAS_TAG   = 0xC7;
    private static final int USIM_EFGSD_TAG   = 0xC8;
    private static final int USIM_EFUID_TAG   = 0xC9;
    private static final int USIM_EFEMAIL_TAG = 0xCA;
    private static final int USIM_EFCCP1_TAG  = 0xCB;

    private static final int MAX_NUMBER_SIZE_BYTES = 11;
    private static final int ANR_DESCRIPTION_ID = 0;
    private static final int ANR_BCD_NUMBER_LENGTH = 1;
    private static final int ANR_TON_NPI_ID = 2;
    private static final int ANR_ADDITIONAL_NUMBER_START_ID = 3;
    private static final int ANR_ADDITIONAL_NUMBER_END_ID = 12;
    private static final int ANR_CAPABILITY_ID = 13;
    private static final int ANR_EXTENSION_ID = 14;
    private static final int ANR_ADN_SFI_ID = 15;
    private static final int ANR_ADN_RECORD_IDENTIFIER_ID = 16;
    public UsimPhoneBookManager(IccFileHandler fh, AdnRecordCache cache) {
        mFh = fh;
        mPhoneBookRecords = new ArrayList<AdnRecord>();
        mAdnLengthList = new ArrayList<Integer>();
        mIapFileRecord = new HashMap<Integer,ArrayList<byte[]>>();
        mEmailFileRecord = new HashMap<Integer,ArrayList<byte[]>>();
        mAnrFileRecord = new  HashMap<Integer,ArrayList<byte[]>>();
        mPbrFile = null;
        // We assume its present, after the first read this is updated.
        // So we don't have to read from UICC if its not present on subsequent reads.
        mIsPbrPresent = true;
        mAdnCache = cache;
    }

    public void reset() {
        mPhoneBookRecords.clear();
        mIapFileRecord.clear();
        mEmailFileRecord.clear();
        mAnrFileRecord.clear();
        mPbrFile = null;
        mAdnLengthList.clear();
        mIsPbrPresent = true;
        mRefreshCache = false;
    }

    public ArrayList<AdnRecord> loadEfFilesFromUsim() {
        synchronized (mLock) {
            if (!mPhoneBookRecords.isEmpty()) {
                if (mRefreshCache) {
                    mRefreshCache = false;
                    refreshCache();
                }
                return mPhoneBookRecords;
            }

            if (!mIsPbrPresent) return null;

            // Check if the PBR file is present in the cache, if not read it
            // from the USIM.
            if (mPbrFile == null) {
                readPbrFileAndWait();
            }

            if (mPbrFile == null) return null;

            int numRecs = mPbrFile.mFileIds.size();
            for (int i = 0; i < numRecs; i++) {
                readAdnFileAndWait(i);
                readEmailFileAndWait(i);
                readAnrFileAndWait(i);
            }
            // All EF files are loaded, post the response.
        }
        return mPhoneBookRecords;
    }

    private void refreshCache() {
        if (mPbrFile == null) return;
        mPhoneBookRecords.clear();

        int numRecs = mPbrFile.mFileIds.size();
        for (int i = 0; i < numRecs; i++) {
            readAdnFileAndWait(i);
        }
    }

    public void invalidateCache() {
        mRefreshCache = true;
    }

    private void readPbrFileAndWait() {
        mFh.loadEFLinearFixedAll(EF_PBR, obtainMessage(EVENT_PBR_LOAD_DONE));
        try {
            mLock.wait();
        } catch (InterruptedException e) {
            Log.e(LOG_TAG, "Interrupted Exception in readAdnFileAndWait");
        }
    }

    private void readEmailFileAndWait(int recNum) {
        Map <Integer,Integer> fileIds;
        fileIds = mPbrFile.mFileIds.get(recNum);
        if (fileIds == null) return;

        if (fileIds.containsKey(USIM_EFEMAIL_TAG)) {
            int efid = fileIds.get(USIM_EFEMAIL_TAG);
            // Check if the EFEmail is a Type 1 file or a type 2 file.
            // If mEmailPresentInIap is true, its a type 2 file.
            // So we read the IAP file and then read the email records.
            // instead of reading directly.
            if (mEmailPresentInIap) {
                readIapFileAndWait(fileIds.get(USIM_EFIAP_TAG),recNum);
                if (!hasRecordIn(mIapFileRecord,recNum))
                    return;
            }
            // Read the EFEmail file.
            log("readEmailFileAndWait email efid is : " + fileIds.get(USIM_EFEMAIL_TAG));
            mFh.loadEFLinearFixedAll(fileIds.get(USIM_EFEMAIL_TAG),
                    obtainMessage(EVENT_EMAIL_LOAD_DONE,recNum));
            try {
                mLock.wait();
            } catch (InterruptedException e) {
                Log.e(LOG_TAG, "Interrupted Exception in readEmailFileAndWait");
            }

            if (!hasRecordIn(mEmailFileRecord,recNum)) {
                Log.e(LOG_TAG, "Error: Email file is empty");
                return;
            }
            updatePhoneAdnRecord(recNum);
        }

    }

    private void readAnrFileAndWait(int recNum) {
        Map <Integer,Integer> fileIds;
        fileIds = mPbrFile.mFileIds.get(recNum);
        if (fileIds == null) return;
        if (fileIds.containsKey(USIM_EFANR_TAG)) {
            int efid = fileIds.get(USIM_EFANR_TAG);
            if (mAnrPresentInIap) {
                readIapFileAndWait(fileIds.get(USIM_EFIAP_TAG),recNum);
                if (!hasRecordIn(mIapFileRecord,recNum)) {
                    Log.e(LOG_TAG, "Error: IAP file is empty");
                    return;
                }
            }
            mFh.loadEFLinearFixedAll(fileIds.get(USIM_EFANR_TAG),
                    obtainMessage(EVENT_ANR_LOAD_DONE,recNum));
            try {
                mLock.wait();
            } catch (InterruptedException e) {
                Log.e(LOG_TAG, "Interrupted Exception in readEmailFileAndWait");
            }
            if (!hasRecordIn(mAnrFileRecord,recNum)) {
                Log.e(LOG_TAG, "Error: Anr file is empty");
                return;
            }
            updatePhoneAdnRecordWithAnr(recNum);
        }
    }
    private void readIapFileAndWait(int efid,int recNum) {
        mFh.loadEFLinearFixedAll(efid, obtainMessage(EVENT_IAP_LOAD_DONE, recNum));
        try {
            mLock.wait();
        } catch (InterruptedException e) {
            Log.e(LOG_TAG, "Interrupted Exception in readIapFileAndWait");
        }
    }

    public boolean updateEmailFile(int recNum, String oldEmail, String newEmail) {
        int pbrIndex = getPbrIndexBy(recNum -1);
        int efid = getEfidByTag(pbrIndex, USIM_EFEMAIL_TAG);
        if (oldEmail == null) oldEmail = "";
        if (newEmail == null) newEmail = "";
        String emails = oldEmail + "," + newEmail;
        success = false;
        if (efid == -1) return success;
        if (mEmailPresentInIap &&
            (TextUtils.isEmpty(oldEmail) && !TextUtils.isEmpty(newEmail))) {
            success = updateIapFile(recNum, oldEmail, newEmail, USIM_EFEMAIL_TAG);
        } else {
            success = true;
        }
        if (success) {
            log("updateEmailFile oldEmail : " + oldEmail+ " newEmail:"
                +newEmail+ " emails:"+emails + " efid" + efid +" recNum: " + recNum);
            synchronized(mLock) {
                mFh.getEFLinearRecordSize( efid,
                    obtainMessage(EVENT_EF_EMAIL_RECORD_SIZE_DONE, recNum,efid,emails));
                try {
                    mLock.wait();
                } catch (InterruptedException e) {
                    log("interrupted while trying to update by search");
                }
            }
        }
        if (mEmailPresentInIap && success &&
            (!TextUtils.isEmpty(oldEmail) && TextUtils.isEmpty(newEmail))) {
            success = updateIapFile(recNum, oldEmail, newEmail, USIM_EFEMAIL_TAG);
        }
        return success;
    }
    public boolean updateAnrFile(int recNum, String oldAnr, String newAnr) {
        int pbrIndex = getPbrIndexBy(recNum-1);
        int efid = getEfidByTag(pbrIndex, USIM_EFANR_TAG);
        if (oldAnr == null) oldAnr = "";
        if (newAnr == null) newAnr = "";
        String anrs = oldAnr + "," + newAnr;
        success = false;
        if (efid == -1) return success;
        if (mAnrPresentInIap &&
            (TextUtils.isEmpty(oldAnr) && !TextUtils.isEmpty(newAnr))) {
            success = updateIapFile(recNum, oldAnr, newAnr, USIM_EFANR_TAG);
        } else {
            success = true;
        }
        log("updateEmailFile oldAnr : " + oldAnr+ ", newAnr:"
            +newAnr+ " anrs:"+anrs + ", efid" + efid +", recNum: " + recNum);
        synchronized(mLock) {
            mFh.getEFLinearRecordSize( efid,
                obtainMessage(EVENT_EF_ANR_RECORD_SIZE_DONE, recNum,efid,anrs));
            try {
                mLock.wait();
            } catch (InterruptedException e) {
                log("interrupted while trying to update by search");
            }
        }
        if (mAnrPresentInIap && success &&
            (!TextUtils.isEmpty(oldAnr) && TextUtils.isEmpty(newAnr))) {
            success = updateIapFile(recNum, oldAnr, newAnr, USIM_EFANR_TAG);
        }
        return success;
    }
    private boolean updateIapFile(int recNum, String oldValue, String newValue, int tag) {
        int pbrIndex = getPbrIndexBy(recNum-1);
        int efid = getEfidByTag(pbrIndex, USIM_EFIAP_TAG);
        success = false;
        int index = -1;
        if (efid == -1) return success;
        switch (tag) {
            case USIM_EFEMAIL_TAG:
                index = getEmailRecIndex(recNum-1,mPhoneBookRecords.size(),oldValue);
                break;
            case USIM_EFANR_TAG:
                index = getAnrRecIndex(recNum-1,mPhoneBookRecords.size(),oldValue);
                break;
        }
        if (TextUtils.isEmpty(newValue)) {
            index = -1;
        }
        log("updateIapFile  efid=" + efid +", index= " + index
            + ", recNum=" + recNum);
        synchronized(mLock) {
            mFh.getEFLinearRecordSize( efid,
                obtainMessage(EVENT_EF_IAP_RECORD_SIZE_DONE, recNum,index,tag));
            try {
                mLock.wait();
            } catch (InterruptedException e) {
                log("interrupted while trying to update by search");
            }
        }
        return success;
    }
    private int getEfidByTag(int recNum, int tag) {
        Map <Integer,Integer> fileIds;
        int efid = -1;
        fileIds = mPbrFile.mFileIds.get(recNum);
        if (fileIds == null) return -1;
        if (fileIds.containsKey(tag)) {
           efid = fileIds.get(tag);
        }
        return efid;
    }
    private int getPbrIndexBy(int adnIndex) {
        int len = mAdnLengthList.size();
        int size = 0;
        for (int i=0; i < len; i++) {
            size += mAdnLengthList.get(i);
            if ( adnIndex < size) {
                log("getPbrIndexBy  adnIndex: " +adnIndex + " PbrIndex: " + i);
                return i;
            }
        }
        return -1;
    }
    private int getInitIndexBy(int pbrIndex) {
        int index = 0;
        while (pbrIndex != 0) {
            index += mAdnLengthList.get(pbrIndex-1);
            pbrIndex--;
        }
        return index;
    }
    private boolean hasRecordIn
        (Map<Integer,ArrayList<byte[]>> record, int pbrIndex) {
        if (record == null)
            return false;
        try {
            record.get(pbrIndex);
        } catch (IndexOutOfBoundsException e) {
            Log.e(LOG_TAG, "record is empty in pbrIndex" + pbrIndex);
            return false;
        }
        return true;
    }
    private void updatePhoneAdnRecord(int pbrIndex) {
        if (!hasRecordIn(mEmailFileRecord,pbrIndex)) return;
        int numAdnRecs = mAdnLengthList.get(pbrIndex);

        if (mEmailPresentInIap && hasRecordIn(mIapFileRecord,pbrIndex)) {
            // The number of records in the IAP file is same as the number of records in ADN file.
            // The order of the pointers in an EFIAP shall be the same as the order of file IDs
            // that appear in the TLV object indicated by Tag 'A9' in the reference file record.
            // i.e value of mEmailTagNumberInIap

            for (int i = 0; i < numAdnRecs; i++) {
                byte[] record = null;
                try {
                    record = mIapFileRecord.get(pbrIndex).get(i);
                } catch (IndexOutOfBoundsException e) {
                    Log.e(LOG_TAG, "Error: Improper ICC card: No IAP record for ADN, continuing");
                    break;
                }
                int recNum = record[mEmailTagNumberInIap];

                if (recNum > 0) {
                    String[] emails = new String[1];
                    // SIM record numbers are 1 based
                    emails[0] = readEmailRecord(recNum - 1,pbrIndex);
                    AdnRecord rec = mPhoneBookRecords.get(i);
                    if (rec != null &&
                        (!TextUtils.isEmpty(rec.getAlphaTag()) ||
                        !TextUtils.isEmpty(rec.getNumber())) &&
                        (!TextUtils.isEmpty(emails[0]))) {
                        rec.setEmails(emails);
                        mPhoneBookRecords.set(i, rec);
                    }

                }
            }
        }

        // ICC cards can be made such that they have an IAP file but all
        // records are empty. So we read both type 1 and type 2 file
        // email records, just to be sure.

        int len = mAdnLengthList.get(pbrIndex);
        // Type 1 file, the number of records is the same as the number of
        // records in the ADN file.
        if (mEmailsForAdnRec == null) {
            parseType1EmailFile(len,pbrIndex);
        }
        for (int i = 0; i < numAdnRecs; i++) {
            ArrayList<String> emailList = null;
            try {
                emailList = mEmailsForAdnRec.get(i);
            } catch (IndexOutOfBoundsException e) {
                break;
            }
            if (emailList == null) continue;

            AdnRecord rec = mPhoneBookRecords.get(i);
            if (rec != null &&
                (!TextUtils.isEmpty(rec.getAlphaTag()) ||
                !TextUtils.isEmpty(rec.getNumber()))) {
                String[] emails = new String[emailList.size()];
                System.arraycopy(emailList.toArray(), 0, emails, 0, emailList.size());
                rec.setEmails(emails);
                mPhoneBookRecords.set(i, rec);
            }
        }
    }

    private void updatePhoneAdnRecordWithAnr(int pbrIndex) {
        if (!hasRecordIn(mAnrFileRecord,pbrIndex)) return;
        int numAdnRecs = mAdnLengthList.get(pbrIndex);
        if (mAnrPresentInIap && hasRecordIn(mIapFileRecord,pbrIndex)) {
            // The number of records in the IAP file is same as the number of records in ADN file.
            // The order of the pointers in an EFIAP shall be the same as the order of file IDs
            // that appear in the TLV object indicated by Tag 'A9' in the reference file record.
            // i.e value of mAnrTagNumberInIap

            for (int i = 0; i < numAdnRecs; i++) {
                byte[] record = null;
                try {
                    record = mIapFileRecord.get(pbrIndex).get(i);
                } catch (IndexOutOfBoundsException e) {
                    Log.e(LOG_TAG, "Error: Improper ICC card: No IAP record for ADN, continuing");
                    break;
                }
                int recNum = record[mAnrTagNumberInIap];
                if (recNum > 0) {
                    String[] anrs = new String[1];
                    // SIM record numbers are 1 based
                    anrs[0] = readAnrRecord(recNum - 1,pbrIndex);
                    AdnRecord rec = mPhoneBookRecords.get(i);
                    if (rec != null &&
                        (!TextUtils.isEmpty(rec.getAlphaTag()) ||
                        !TextUtils.isEmpty(rec.getNumber())) &&
                        (!TextUtils.isEmpty(anrs[0]))) {
                        rec.setAdditionalNumbers(anrs);
                        mPhoneBookRecords.set(i, rec);
                    }

                }
            }
        }

        // ICC cards can be made such that they have an IAP file but all
        // records are empty. So we read both type 1 and type 2 file
        // anr records, just to be sure.

        //int len = mPhoneBookRecords.size();
        // Type 1 file, the number of records is the same as the number of
        // records in the ADN file.
        if (mAnrsForAdnRec == null) {
            parseType1AnrFile(numAdnRecs,pbrIndex);
        }
        for (int i = 0; i < numAdnRecs; i++) {
            ArrayList<String> anrList = null;
            try {
                anrList = mAnrsForAdnRec.get(i);
            } catch (IndexOutOfBoundsException e) {
                break;
            }
            if (anrList == null) continue;
            AdnRecord rec = mPhoneBookRecords.get(i);
            if (rec != null &&
                (!TextUtils.isEmpty(rec.getAlphaTag()) ||
                !TextUtils.isEmpty(rec.getNumber()))) {
                String[] anrs = new String[anrList.size()];
                System.arraycopy(anrList.toArray(), 0, anrs, 0, anrList.size());
                rec.setAdditionalNumbers(anrs);
                mPhoneBookRecords.set(i, rec);
            }
        }
    }
    void parseType1EmailFile(int numRecs, int pbrIndex) {
        mEmailsForAdnRec = new HashMap<Integer, ArrayList<String>>();
        byte[] emailRec = null;
        if (!hasRecordIn(mEmailFileRecord,pbrIndex))
            return;
        for (int i = 0; i < numRecs; i++) {
            try {
                emailRec = mEmailFileRecord.get(pbrIndex).get(i);
            } catch (IndexOutOfBoundsException e) {
                Log.e(LOG_TAG, "Error: Improper ICC card: No email record for ADN, continuing");
                break;
            }
            int adnRecIndex = emailRec[emailRec.length - 1];

            if (adnRecIndex == -1) {
                continue;
            }

            String email = readEmailRecord(i,pbrIndex);

            if (email == null || email.equals("")) {
                continue;
            }

            // SIM record numbers are 1 based.
            ArrayList<String> val = mEmailsForAdnRec.get(adnRecIndex - 1);
            if (val == null) {
                val = new ArrayList<String>();
            }
            val.add(email);
            // SIM record numbers are 1 based.
            mEmailsForAdnRec.put(adnRecIndex - 1, val);
        }
    }

    void parseType1AnrFile(int numRecs,int pbrIndex) {
        mAnrsForAdnRec = new HashMap<Integer, ArrayList<String>>();
        byte[] anrRec = null;
        if (!hasRecordIn(mAnrFileRecord,pbrIndex))
            return;
        for (int i = 0; i < numRecs; i++) {
            try {
                anrRec = mAnrFileRecord.get(pbrIndex).get(i);
            } catch (IndexOutOfBoundsException e) {
                Log.e(LOG_TAG, "Error: Improper ICC card: No anr record for ADN, continuing");
                break;
            }
            String anr = readAnrRecord(i,pbrIndex);
            if (anr == null || anr.equals("")) {
                continue;
            }
            ArrayList<String> val = mAnrsForAdnRec.get(i);
            if (val == null) {
                val = new ArrayList<String>();
            }
            val.add(anr);
            mAnrsForAdnRec.put(i, val);
        }
    }
    private String readEmailRecord(int recNum, int pbrIndex) {
        byte[] emailRec = null;
        if (!hasRecordIn(mEmailFileRecord,pbrIndex))
            return null;
        try {
            emailRec = mEmailFileRecord.get(pbrIndex).get(recNum);
        } catch (IndexOutOfBoundsException e) {
            return null;
        }

        // The length of the record is X+2 byte, where X bytes is the email address
        String email = IccUtils.adnStringFieldToString(emailRec, 0, emailRec.length - 2);
        return email;
    }
    private String readAnrRecord(int recNum, int pbrIndex) {
        byte[] anrRec = null;
        if (!hasRecordIn(mAnrFileRecord,pbrIndex))
            return null;
        try {
            anrRec = mAnrFileRecord.get(pbrIndex).get(recNum);
        } catch (IndexOutOfBoundsException e) {
            return null;
        }
        int numberLength = 0xff & anrRec[1];
        if (numberLength > MAX_NUMBER_SIZE_BYTES) {
            Log.e(LOG_TAG, "Invalid number length in anr record");
            return "";
        }
        String anr = PhoneNumberUtils.calledPartyBCDToString(
                            anrRec,  2, numberLength);
        return anr;
    }

    private void readAdnFileAndWait(int recNum) {
        Map <Integer,Integer> fileIds;
        fileIds = mPbrFile.mFileIds.get(recNum);
        if (fileIds == null || fileIds.isEmpty()) return;


        int extEf = 0;
        // Only call fileIds.get while EFEXT1_TAG is available
        if (fileIds.containsKey(USIM_EFEXT1_TAG)) {
            extEf = fileIds.get(USIM_EFEXT1_TAG);
        }

        mAdnCache.requestLoadAllAdnLike(fileIds.get(USIM_EFADN_TAG),
            extEf, obtainMessage(EVENT_USIM_ADN_LOAD_DONE,recNum));
        try {
            mLock.wait();
        } catch (InterruptedException e) {
            Log.e(LOG_TAG, "Interrupted Exception in readAdnFileAndWait");
        }
    }
    private int getEmailRecIndex(int adnRecIndex, int numRecs, String oldEmail) {
        int pbrIndex = getPbrIndexBy(adnRecIndex);
        if ( !hasRecordIn(mEmailFileRecord, pbrIndex)) {
            return -1;
        }
        if (mEmailPresentInIap && hasRecordIn(mIapFileRecord, pbrIndex)) {
            byte[] record = null;
            int index = -1;
            try {
                record = mIapFileRecord.get(pbrIndex).get(adnRecIndex);
            } catch(IndexOutOfBoundsException e) {
                Log.e(LOG_TAG, "IndexOutOfBoundsException in getEmailRecIndex");
            }
            if (record != null && record[mEmailTagNumberInIap] > 0 ) {
                index = record[mEmailTagNumberInIap];
                return index;
            } else {
                int recsSize = mEmailFileRecord.get(pbrIndex).size();
                for (int i=0; i<recsSize; i++) {
                    if ("".equals(oldEmail)) {
                        String emailRecord = readEmailRecord(i,pbrIndex);
                        if (emailRecord!=null && emailRecord.equals(oldEmail)) {
                            Log.d(LOG_TAG,"the email record index is :" + (i+1));
                            return i + 1 ;
                        }
                    }
                }
            }
        }else {
            return adnRecIndex + 1;
        }
        Log.e(LOG_TAG, "no email record index found");
        return -1;
    }
    private int getAnrRecIndex(int adnRecIndex, int numRecs, String oldAnr) {
        int pbrIndex = getPbrIndexBy(adnRecIndex);
        if ( !hasRecordIn(mAnrFileRecord, pbrIndex)) {
            return -1;
        }
        if (mAnrPresentInIap && hasRecordIn(mIapFileRecord, pbrIndex)) {
            byte[] record = null;
            int index = -1;
            try {
                record = mIapFileRecord.get(pbrIndex).get(adnRecIndex);
            } catch(IndexOutOfBoundsException e) {
                Log.e(LOG_TAG, "IndexOutOfBoundsException in getAnrRecIndex");
            }
            if (record != null && record[mAnrTagNumberInIap] > 0) {
                index = record[mAnrTagNumberInIap];
                return index;
            } else {
                int recsSize = mAnrFileRecord.get(pbrIndex).size();
                if ("".equals(oldAnr)) {
                    for (int i=0; i<recsSize; i++) {
                        String anrRecord = readAnrRecord(i,pbrIndex);
                        if (anrRecord!=null && anrRecord.equals(oldAnr)) {
                            Log.d(LOG_TAG,"the anr record index is :" + (i+1));
                            return i+1;
                        }
                    }
                }
            }
        }else {
           return adnRecIndex + 1;
        }
        Log.e(LOG_TAG, "no anr record index found");
        return -1;
    }
    private byte[] buildEmailData(int length, int adnRecIndex, String email) {
        byte[] data = new byte[length];
        for (int i=0; i<length; i++ ) {
            data[i]= (byte)0xff;
        }
        if (TextUtils.isEmpty(email)) {
            Log.w(LOG_TAG, "[buildEmailData] Empty email record");
            return data;   // return the empty record (for delete)
        }
        byte[] byteEmail = GsmAlphabet.stringToGsm8BitPacked(email);
        System.arraycopy(byteEmail, 0, data, 0 , byteEmail.length);
        int pbrIndex = getPbrIndexBy(adnRecIndex);
        if (mEmailPresentInIap) {
            data[length-1] = (byte)(adnRecIndex+1);
        }
        Log.w(LOG_TAG," buildEmailData: data is" +IccUtils.bytesToHexString(data));
        return data;
    }
    private byte[] buildAnrData(int length, int adnRecIndex, String anr) {
        byte[] data = new byte[length];
        for (int i=0; i<length; i++ ) {
            data[i]=(byte)0xff;
        }
        if (TextUtils.isEmpty(anr)) {
            Log.w(LOG_TAG, "[buildAnrData] Empty anr record");
            return data;   // return the empty record (for delete)
        }
        data[ANR_DESCRIPTION_ID] = (byte) (0x0);
        byte[] byteAnr = PhoneNumberUtils.numberToCalledPartyBCD(anr);
        int maxlength = ANR_ADDITIONAL_NUMBER_END_ID - ANR_ADDITIONAL_NUMBER_START_ID + 1;
        if (byteAnr.length > maxlength) {
            System.arraycopy(byteAnr, 0, data, ANR_TON_NPI_ID, maxlength);
            data[ANR_BCD_NUMBER_LENGTH] = (byte) (maxlength);
        } else {
            System.arraycopy(byteAnr, 0, data, ANR_TON_NPI_ID, byteAnr.length);
            data[ANR_BCD_NUMBER_LENGTH] = (byte) (byteAnr.length);
        }
        data[ANR_CAPABILITY_ID] = (byte) 0xFF;
        data[ANR_EXTENSION_ID]= (byte) 0xFF;
        if (length == 17) {
        // TODO: should update the SFI at the same time???
            data[ANR_ADN_RECORD_IDENTIFIER_ID] = (byte)(adnRecIndex+1);
        }
        Log.w(LOG_TAG," buildAnrData: data is" +IccUtils.bytesToHexString(data));
        return data;
    }

    private void createPbrFile(ArrayList<byte[]> records) {
        if (records == null) {
            mPbrFile = null;
            mIsPbrPresent = false;
            return;
        }
        mPbrFile = new PbrFile(records);
    }

    @Override
    public void handleMessage(Message msg) {
        AsyncResult ar;
        byte data[];
        int efid;
        int adnRecIndex;
        int[] recordSize;
        int recordNumber;
        String oldAnr = null;
        String newAnr = null;
        String oldEmail = null;
        String newEmail = null;
        Message response =null;
        int pbrIndex;

        switch(msg.what) {
        case EVENT_PBR_LOAD_DONE:
            ar = (AsyncResult) msg.obj;
            if (ar.exception == null) {
                createPbrFile((ArrayList<byte[]>)ar.result);
            }
            synchronized (mLock) {
                mLock.notify();
            }
            break;
        case EVENT_USIM_ADN_LOAD_DONE:
            log("Loading USIM ADN records done");
            ar = (AsyncResult) msg.obj;
                pbrIndex = (Integer) ar.userObj;
            if (ar.exception == null) {
                mPhoneBookRecords.addAll((ArrayList<AdnRecord>)ar.result);
                    mAdnLengthList.add(pbrIndex, ((ArrayList<AdnRecord>)ar.result).size());
            }
            synchronized (mLock) {
                mLock.notify();
            }
            break;
        case EVENT_IAP_LOAD_DONE:
            log("Loading USIM IAP records done");
            ar = (AsyncResult) msg.obj;
                pbrIndex = (Integer) ar.userObj;
            if (ar.exception == null) {
                    mIapFileRecord.put(pbrIndex,(ArrayList<byte[]>)ar.result);
            }
            synchronized (mLock) {
                mLock.notify();
            }
            break;
        case EVENT_EMAIL_LOAD_DONE:
            log("Loading USIM Email records done");
            ar = (AsyncResult) msg.obj;
                pbrIndex = (Integer) ar.userObj;
            if (ar.exception == null) {
                    mEmailFileRecord.put(pbrIndex,(ArrayList<byte[]>)ar.result);
            }

            synchronized (mLock) {
                mLock.notify();
            }
            break;
            case EVENT_ANR_LOAD_DONE:
                log("Loading USIM Anr records done");
                ar = (AsyncResult) msg.obj;
                pbrIndex = (Integer) ar.userObj;
                if (ar.exception == null) {
                    mAnrFileRecord.put(pbrIndex,(ArrayList<byte[]>)ar.result);
                }
                synchronized (mLock) {
                    mLock.notify();
                }
                break;
            case EVENT_EF_EMAIL_RECORD_SIZE_DONE:
                ar = (AsyncResult)(msg.obj);
                String emails = (String)(ar.userObj);
                adnRecIndex = ((int) msg.arg1)-1;
                efid = (int)msg.arg2;
                String email[] = emails.split(",");
                if (email.length == 1) {
                    oldEmail = email[0];
                    newEmail = "";
                } else if (email.length > 1) {
                    oldEmail = email[0];
                    newEmail = email[1];
                }
                if (ar.exception != null) {
                    success = false;
                    synchronized (mLock) {
                        mLock.notify();
                    }
                    return;
                }
                recordSize = (int[])ar.result;
                recordNumber = getEmailRecIndex(adnRecIndex, mPhoneBookRecords.size(),oldEmail);
                if (recordSize.length != 3 || recordNumber > recordSize[2] || recordNumber <= 0) {
                    success = false;
                    synchronized (mLock) {
                        mLock.notify();
                    }
                    return;
                }
                data = buildEmailData(recordSize[0],adnRecIndex,newEmail);
                mFh.updateEFLinearFixed(efid, recordNumber,
                    data, null, obtainMessage(EVENT_UPDATE_EMAIL_RECORD_DONE,recordNumber,adnRecIndex,data));
                pendingExtLoads=1;
                break;
            case EVENT_EF_ANR_RECORD_SIZE_DONE:
                ar = (AsyncResult)(msg.obj);
                String anrs = (String)(ar.userObj);
                adnRecIndex = ((int) msg.arg1)-1;
                efid = (int) msg.arg2;
                String[] anr = anrs.split(",");
                if (anr.length == 1) {
                    oldAnr = anr[0];
                    newAnr = "";
                } else if (anr.length > 1) {
                    oldAnr = anr[0];
                    newAnr = anr[1];
                }
                if (ar.exception != null) {
                    success = false;
                    synchronized (mLock) {
                        mLock.notify();
                    }
                    return;
                }
                recordSize = (int[])ar.result;
                recordNumber = getAnrRecIndex(adnRecIndex, mPhoneBookRecords.size(),oldAnr);
                if (recordSize.length != 3 || recordNumber > recordSize[2] || recordNumber <= 0) {
                    success = false;
                    synchronized (mLock) {
                        mLock.notify();
                    }
                    return;
                }
                data = buildAnrData(recordSize[0],adnRecIndex,newAnr);
                mFh.updateEFLinearFixed(efid, recordNumber,
                    data, null, obtainMessage(EVENT_UPDATE_ANR_RECORD_DONE,recordNumber,adnRecIndex,data));
                pendingExtLoads = 1;
                break;
            case EVENT_UPDATE_EMAIL_RECORD_DONE:
                ar = (AsyncResult)(msg.obj);
                if (ar.exception != null) {
                    success = false;
                }
                data = (byte[])(ar.userObj);
                recordNumber = (int)msg.arg1;
                adnRecIndex = (int)msg.arg2;
                pbrIndex = getPbrIndexBy(adnRecIndex);
                log("EVENT_UPDATE_EMAIL_RECORD_DONE");
                pendingExtLoads = 0;
                success = true;
                mEmailFileRecord.get(pbrIndex).set(recordNumber-1, data);
                synchronized (mLock) {
                    mLock.notify();
                }
                break;
            case EVENT_UPDATE_ANR_RECORD_DONE:
                ar = (AsyncResult)(msg.obj);
                data = (byte[] )(ar.userObj);
                recordNumber = (int)msg.arg1;
                adnRecIndex = (int)msg.arg2;
                pbrIndex = getPbrIndexBy(adnRecIndex);
                if (ar.exception != null) {
                    success = false;
                }
                log("EVENT_UPDATE_ANR_RECORD_DONE");
                pendingExtLoads = 0;
                success = true;
                mAnrFileRecord.get(pbrIndex).set(recordNumber-1, data);
                synchronized (mLock) {
                    mLock.notify();
                }
                break;
            case EVENT_EF_IAP_RECORD_SIZE_DONE:
                log("EVENT_EF_IAP_RECORD_SIZE_DONE");
                ar = (AsyncResult)(msg.obj);
                recordNumber = (int)msg.arg2;
                adnRecIndex = ((int)msg.arg1)-1;
                pbrIndex = getPbrIndexBy(adnRecIndex);
                efid = getEfidByTag(pbrIndex,USIM_EFIAP_TAG);
                int tag = (Integer)ar.userObj;
                if (ar.exception != null) {
                    success = false;
                    synchronized (mLock) {
                        mLock.notify();
                    }
                    return;
                }
                recordSize = (int[])ar.result;
                data = null;
                if (recordSize.length != 3 || adnRecIndex+1 > recordSize[2] || recordNumber == 0) {
                    success = false;
                    synchronized (mLock) {
                        mLock.notify();
                    }
                    return;
                }
                if (hasRecordIn(mIapFileRecord,pbrIndex)) {
                    data = mIapFileRecord.get(pbrIndex).get(adnRecIndex);
                    switch(tag) {
                        case USIM_EFEMAIL_TAG:
                            data[mEmailTagNumberInIap] = (byte)recordNumber;
                            break;
                        case USIM_EFANR_TAG:
                            data[mAnrTagNumberInIap] = (byte)recordNumber;
                            break;
                    }
                    pendingExtLoads = 1;
                    Log.d(LOG_TAG, " IAP  efid= " +efid + ", update IAP index= " + (adnRecIndex+1) +
                        " with value= " + IccUtils.bytesToHexString(data));
                    mFh.updateEFLinearFixed(efid, adnRecIndex+1,
                        data, null, obtainMessage(EVENT_UPDATE_IAP_RECORD_DONE,adnRecIndex,recordNumber,data));
                }
                break;
            case EVENT_UPDATE_IAP_RECORD_DONE:
                ar = (AsyncResult)(msg.obj);
                if (ar.exception != null) {
                    success = false;
                }
                data = (byte[] )(ar.userObj);
                adnRecIndex = (int)msg.arg1;
                pbrIndex = getPbrIndexBy(adnRecIndex);
                log("EVENT_UPDATE_IAP_RECORD_DONE");
                pendingExtLoads = 0;
                success = true;
                mIapFileRecord.get(pbrIndex).set(adnRecIndex, data);
                synchronized (mLock) {
                    mLock.notify();
                }
                break;
        }
    }

    private class PbrFile {
        // RecNum <EF Tag, efid>
        HashMap<Integer,Map<Integer,Integer>> mFileIds;

        PbrFile(ArrayList<byte[]> records) {
            mFileIds = new HashMap<Integer, Map<Integer, Integer>>();
            SimTlv recTlv;
            int recNum = 0;
            for (byte[] record: records) {
                recTlv = new SimTlv(record, 0, record.length);
                parseTag(recTlv, recNum);
                recNum ++;
            }
        }

        void parseTag(SimTlv tlv, int recNum) {
            SimTlv tlvEf;
            int tag;
            byte[] data;
            Map<Integer, Integer> val = new HashMap<Integer, Integer>();
            do {
                tag = tlv.getTag();
                switch(tag) {
                case USIM_TYPE1_TAG: // A8
                case USIM_TYPE3_TAG: // AA
                case USIM_TYPE2_TAG: // A9
                    data = tlv.getData();
                    tlvEf = new SimTlv(data, 0, data.length);
                    parseEf(tlvEf, val, tag);
                    break;
                }
            } while (tlv.nextObject());
            mFileIds.put(recNum, val);
        }

        void parseEf(SimTlv tlv, Map<Integer, Integer> val, int parentTag) {
            int tag;
            byte[] data;
            int tagNumberWithinParentTag = 0;
            do {
                tag = tlv.getTag();
                if (parentTag == USIM_TYPE2_TAG && tag == USIM_EFEMAIL_TAG) {
                    mEmailPresentInIap = true;
                    mEmailTagNumberInIap = tagNumberWithinParentTag;
                }
                if (parentTag == USIM_TYPE2_TAG && tag == USIM_EFANR_TAG) {
                    mAnrPresentInIap = true;
                    mAnrTagNumberInIap = tagNumberWithinParentTag;
                }
                switch(tag) {
                    case USIM_EFEMAIL_TAG:
                    case USIM_EFADN_TAG:
                    case USIM_EFEXT1_TAG:
                    case USIM_EFANR_TAG:
                    case USIM_EFPBC_TAG:
                    case USIM_EFGRP_TAG:
                    case USIM_EFAAS_TAG:
                    case USIM_EFGSD_TAG:
                    case USIM_EFUID_TAG:
                    case USIM_EFCCP1_TAG:
                    case USIM_EFIAP_TAG:
                    case USIM_EFSNE_TAG:
                        data = tlv.getData();
                        int efid = ((data[0] & 0xFF) << 8) | (data[1] & 0xFF);
                        val.put(tag, efid);
                        break;
                }
                tagNumberWithinParentTag ++;
            } while(tlv.nextObject());
        }
    }

    private void log(String msg) {
        if(DBG) Log.d(LOG_TAG, msg);
    }
}
