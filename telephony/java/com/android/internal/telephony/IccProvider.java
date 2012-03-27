/*
 * Copyright (C) 2006 The Android Open Source Project
 * Copyright (c) 2011-2012, Code Aurora Forum. All rights reserved.
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

package com.android.internal.telephony;

import android.content.ContentProvider;
import android.content.UriMatcher;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.text.TextUtils;
import android.util.Log;

import java.util.List;

import com.android.internal.telephony.IccConstants;
import com.android.internal.telephony.AdnRecord;
import com.android.internal.telephony.IIccPhoneBook;


/**
 * {@hide}
 */
public class IccProvider extends ContentProvider {
    private static final String TAG = "IccProvider";
    private static final boolean DBG = false;


    protected static final String[] ADDRESS_BOOK_COLUMN_NAMES = new String[] {
        "name",
        "number",
        "emails",
        "anrs",
        "_id"
    };

    private static final int ADN = 1;
    private static final int FDN = 2;
    private static final int SDN = 3;

    protected static final int SUB1 = 0;
    protected static final int SUB2 = 1;
    public static final String STR_TAG = "tag";
    public static final String STR_NUMBER = "number";
    public static final String STR_EMAILS = "emails";
    public static final String STR_ANRS = "anrs";
    public static final String STR_NEW_TAG = "newTag";
    public static final String STR_NEW_NUMBER = "newNumber";
    public static final String STR_NEW_EMAILS = "newEmails";
    public static final String STR_NEW_ANRS = "newAnrs";
    public static final String STR_PIN2 = "pin2";

    private static final UriMatcher URL_MATCHER =
                            new UriMatcher(UriMatcher.NO_MATCH);

    static {
        URL_MATCHER.addURI("icc", "adn", ADN);
        URL_MATCHER.addURI("icc", "fdn", FDN);
        URL_MATCHER.addURI("icc", "sdn", SDN);
    }


    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri url, String[] projection, String selection,
            String[] selectionArgs, String sort) {
        switch (URL_MATCHER.match(url)) {
            case ADN:
                return loadFromEf(IccConstants.EF_ADN);

            case FDN:
                return loadFromEf(IccConstants.EF_FDN);

            case SDN:
                return loadFromEf(IccConstants.EF_SDN);

            default:
                throw new IllegalArgumentException("Unknown URL " + url);
        }
    }

    @Override
    public String getType(Uri url) {
        switch (URL_MATCHER.match(url)) {
            case ADN:
            case FDN:
            case SDN:
                return "vnd.android.cursor.dir/sim-contact";

            default:
                throw new IllegalArgumentException("Unknown URL " + url);
        }
    }

    @Override
    public Uri insert(Uri url, ContentValues initialValues) {
        Uri resultUri;
        int efType;
        String pin2 = null;

        if (DBG) log("insert");

        int match = URL_MATCHER.match(url);
        switch (match) {
            case ADN:
                efType = IccConstants.EF_ADN;
                break;

            case FDN:
                efType = IccConstants.EF_FDN;
                pin2 = initialValues.getAsString("pin2");
                break;

            default:
                throw new UnsupportedOperationException(
                        "Cannot insert into URL: " + url);
        }

        String tag = initialValues.getAsString("tag");
        String number = initialValues.getAsString("number");
        String emails = initialValues.getAsString("emails");
        String anrs = initialValues.getAsString("anrs");
        if (DBG) log("insert values , [" + tag + ", " + number + "]");
        // TODO(): Read email instead of sending null.
        ContentValues mValues = new ContentValues();
        mValues.put(STR_TAG,"");
        mValues.put(STR_NUMBER,"");
        mValues.put(STR_EMAILS,"");
        mValues.put(STR_ANRS,"");
        mValues.put(STR_NEW_TAG,tag);
        mValues.put(STR_NEW_NUMBER,number);
        mValues.put(STR_NEW_EMAILS,emails);
        mValues.put(STR_NEW_ANRS,anrs);
        boolean success = updateIccRecordInEf(efType, mValues, pin2);

        if (!success) {
            return null;
        }

        StringBuilder buf = new StringBuilder("content://icc/");
        switch (match) {
            case ADN:
                buf.append("adn/");
                break;

            case FDN:
                buf.append("fdn/");
                break;
        }

        // TODO: we need to find out the rowId for the newly added record
        buf.append(0);

        resultUri = Uri.parse(buf.toString());

        /*
        // notify interested parties that an insertion happened
        getContext().getContentResolver().notifyInsert(
                resultUri, rowID, null);
        */

        return resultUri;
    }

    protected String normalizeValue(String inVal) {
        int len = inVal.length();
        String retVal = inVal;

        if (inVal.charAt(0) == '\'' && inVal.charAt(len-1) == '\'') {
            retVal = inVal.substring(1, len-1);
        }

        return retVal;
    }

    @Override
    public int delete(Uri url, String where, String[] whereArgs) {
        int efType;

        if (DBG) log("delete");

        int match = URL_MATCHER.match(url);
        switch (match) {
            case ADN:
                efType = IccConstants.EF_ADN;
                break;

            case FDN:
                efType = IccConstants.EF_FDN;
                break;

            default:
                throw new UnsupportedOperationException(
                        "Cannot insert into URL: " + url);
        }

        // parse where clause
        String tag = null;
        String number = null;
        String emails = null;
        String anrs = null;
        String pin2 = null;

        String[] tokens = where.split("AND");
        int n = tokens.length;

        while (--n >= 0) {
            String param = tokens[n];
            if (DBG) log("parsing '" + param + "'");

            String[] pair = param.split("=");

            if (pair.length != 2) {
                Log.e(TAG, "resolve: bad whereClause parameter: " + param);
                continue;
            }

            String key = pair[0].trim();
            String val = pair[1].trim();

            if (STR_TAG.equals(key)) {
                tag = normalizeValue(val);
            } else if (STR_NUMBER.equals(key)) {
                number = normalizeValue(val);
            } else if (STR_EMAILS.equals(key)) {
                emails = normalizeValue(val);
            } else if (STR_ANRS.equals(key)) {
                anrs = normalizeValue(val);
            } else if (STR_PIN2.equals(key)) {
                pin2 = normalizeValue(val);
            }
        }

        if (TextUtils.isEmpty(number)) {
            return 0;
        }

        ContentValues mValues = new ContentValues();
        mValues.put(STR_TAG,tag);
        mValues.put(STR_NUMBER,number);
        mValues.put(STR_EMAILS,emails);
        mValues.put(STR_ANRS,anrs);
        mValues.put(STR_NEW_TAG,"");
        mValues.put(STR_NEW_NUMBER,"");
        mValues.put(STR_NEW_EMAILS,"");
        mValues.put(STR_NEW_ANRS,"");
        if ((efType == FDN) && TextUtils.isEmpty(pin2)) {
            return 0;
        }

        if (DBG) log("delete mvalues= " + mValues);
        boolean success = updateIccRecordInEf(efType, mValues, pin2);
        if (!success) {
            return 0;
        }

        return 1;
    }

    @Override
    public int update(Uri url, ContentValues values, String where, String[] whereArgs) {
        int efType;
        String pin2 = null;

        if (DBG) log("update");

        int match = URL_MATCHER.match(url);
        switch (match) {
            case ADN:
                efType = IccConstants.EF_ADN;
                break;

            case FDN:
                efType = IccConstants.EF_FDN;
                pin2 = values.getAsString("pin2");
                break;

            default:
                throw new UnsupportedOperationException(
                        "Cannot insert into URL: " + url);
        }

        // TODO(): Update for email.
        boolean success = updateIccRecordInEf(efType, values, pin2);

        if (!success) {
            return 0;
        }

        return 1;
    }

    private MatrixCursor loadFromEf(int efType) {
        if (DBG) log("loadFromEf: efType=" + efType);

        List<AdnRecord> adnRecords = null;
        try {
            IIccPhoneBook iccIpb = IIccPhoneBook.Stub.asInterface(
                    ServiceManager.getService("simphonebook"));
            if (iccIpb != null) {
                adnRecords = iccIpb.getAdnRecordsInEf(efType);
            }
        } catch (RemoteException ex) {
            // ignore it
        } catch (SecurityException ex) {
            if (DBG) log(ex.toString());
        }

        if (adnRecords != null) {
            // Load the results
            final int N = adnRecords.size();
            final MatrixCursor cursor = new MatrixCursor(ADDRESS_BOOK_COLUMN_NAMES, N);
            if (DBG) log("adnRecords.size=" + N);
            for (int i = 0; i < N ; i++) {
                loadRecord(adnRecords.get(i), cursor, i);
            }
            return cursor;
        } else {
            // No results to load
            Log.w(TAG, "Cannot load ADN records");
            return new MatrixCursor(ADDRESS_BOOK_COLUMN_NAMES);
        }
    }

    private boolean
    addIccRecordToEf(int efType, String name, String number, String[] emails, String pin2) {
        if (DBG) log("addIccRecordToEf: efType=" + efType + ", name=" + name +
            ", number=" + number + ", emails=" + emails);
        boolean success = false;

        // TODO: do we need to call getAdnRecordsInEf() before calling
        // updateAdnRecordsInEfBySearch()? In any case, we will leave
        // the UI level logic to fill that prereq if necessary. But
        // hopefully, we can remove this requirement.
        ContentValues values = new ContentValues();
        values.put(STR_TAG,"");
        values.put(STR_NUMBER,"");
        values.put(STR_NEW_TAG,name);
        values.put(STR_NEW_NUMBER,number);
        success = updateIccRecordInEf(efType, values, pin2);
        if (DBG) log("addIccRecordToEf: " + success);
        return success;
    }

    private boolean
    updateIccRecordInEf(int efType, String oldName, String oldNumber,
            String newName, String newNumber, String pin2) {
        if (DBG) log("updateIccRecordInEf: efType=" + efType + ", oldname=" + oldName + ", oldnumber=" + oldNumber +
            ", newname=" + newName + ", newnumber=" + newNumber );
        boolean success = false;
        ContentValues values = new ContentValues();
        values.put(STR_TAG,oldName);
        values.put(STR_NUMBER,oldNumber);
        values.put(STR_NEW_TAG,newName);
        values.put(STR_NEW_NUMBER,newNumber);
        success = updateIccRecordInEf(efType, values, pin2);

        if (DBG) log("updateIccRecordInEf: " + success);
        return success;
    }


    private boolean
    updateIccRecordInEf(int efType, ContentValues values, String pin2) {
        if (DBG) log("updateIccRecordInEf: efType=" + efType + ", values: "+ values);
        boolean success = false;

        try {
            IIccPhoneBook iccIpb = IIccPhoneBook.Stub.asInterface(
                    ServiceManager.getService("simphonebook"));
            if (iccIpb != null) {
                success = iccIpb.updateAdnRecordsInEfBySearch(efType,
                        values, pin2);
            }
        } catch (RemoteException ex) {
            // ignore it
        } catch (SecurityException ex) {
            if (DBG) log(ex.toString());
        }
        if (DBG) log("updateIccRecordInEf: " + success);
        return success;
    }

    private boolean deleteIccRecordFromEf(int efType, String name, String number, String[] emails,
        String pin2) {
        if (DBG) log("deleteIccRecordFromEf: efType=" + efType +
                ", name=" + name + ", number=" + number + ", emails=" + emails + ", pin2=" + pin2);

        boolean success = false;

        ContentValues values = new ContentValues();
        values.put(STR_TAG,name);
        values.put(STR_NUMBER,number);
        values.put(STR_NEW_TAG,"");
        values.put(STR_NEW_NUMBER,"");
        success = updateIccRecordInEf(efType, values, pin2);
        if (DBG) log("deleteIccRecordFromEf: " + success);
        return success;
    }


    /**
     * Loads an AdnRecord into a MatrixCursor. Must be called with mLock held.
     *
     * @param record the ADN record to load from
     * @param cursor the cursor to receive the results
     */
    protected void loadRecord(AdnRecord record, MatrixCursor cursor, int id) {
        if (!record.isEmpty()) {
            Object[] contact = new Object[5];
            String alphaTag = record.getAlphaTag();
            String number = record.getNumber();
            String[] emails = record.getEmails();
            String[] anrs =record.getAdditionalNumbers();
            if (DBG) log("loadRecord: " + alphaTag + ", " + number + ",");
            contact[0] = alphaTag;
            contact[1] = number;

            if (emails != null) {
                StringBuilder emailString = new StringBuilder();
                for (String email: emails) {
                    if (DBG) log("Adding email:" + email);
                    emailString.append(email);
                    emailString.append(",");
                }
                contact[2] = emailString.toString();
            }

            if (anrs != null) {
                StringBuilder anrString = new StringBuilder();
                for (String anr: anrs) {
                    if (DBG) log("Adding anr:" + anr);
                    anrString.append(anr);
                    anrString.append(",");
                }
                contact[3] = anrString.toString();
            }

            contact[4] = id;
            cursor.addRow(contact);
        }
    }

    protected void log(String msg) {
        Log.d(TAG, "[IccProvider] " + msg);
    }

}
