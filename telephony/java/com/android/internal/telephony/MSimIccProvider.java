/*
 * Copyright (C) 2006 The Android Open Source Project
 * Copyright (c) 2012 Code Aurora Forum. All rights reserved.
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

import android.content.UriMatcher;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.telephony.MSimTelephonyManager;
import android.net.Uri;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.text.TextUtils;
import android.util.Log;

import java.util.List;

import com.android.internal.telephony.IccConstants;
import com.android.internal.telephony.AdnRecord;
import com.android.internal.telephony.IIccPhoneBookMSim;
import android.telephony.MSimTelephonyManager;

/**
 * {@hide}
 */
public class MSimIccProvider extends IccProvider {
    private static final String TAG = "MSimIccProvider";
    private static final boolean DBG = true;

    private static final int ADN_SUB1 = 1;
    private static final int ADN_SUB2 = 2;
    private static final int FDN_SUB1 = 3;
    private static final int FDN_SUB2 = 4;
    private static final int SDN      = 5;


    private static final UriMatcher URL_MATCHER =
                            new UriMatcher(UriMatcher.NO_MATCH);

    static {
        URL_MATCHER.addURI("iccmsim", "adn", ADN_SUB1);
        URL_MATCHER.addURI("iccmsim", "adn_sub2", ADN_SUB2);
        URL_MATCHER.addURI("iccmsim", "fdn", FDN_SUB1);
        URL_MATCHER.addURI("iccmsim", "fdn_sub2", FDN_SUB2);
        URL_MATCHER.addURI("iccmsim", "sdn", SDN);
    }

    @Override
    public Cursor query(Uri url, String[] projection, String selection,
            String[] selectionArgs, String sort) {
        switch (URL_MATCHER.match(url)) {
            case ADN_SUB1:
                return loadFromEf(IccConstants.EF_ADN, MSimConstants.SUB1);

            case ADN_SUB2:
                return loadFromEf(IccConstants.EF_ADN, MSimConstants.SUB2);

            case FDN_SUB1:
                return loadFromEf(IccConstants.EF_FDN, MSimConstants.SUB1);

            case FDN_SUB2:
                return loadFromEf(IccConstants.EF_FDN, MSimConstants.SUB2);

            case SDN:
                return loadFromEf(IccConstants.EF_SDN,
                    MSimTelephonyManager.getDefault().getDefaultSubscription());

            default:
                throw new IllegalArgumentException("Unknown URL " + url);
        }
    }

    @Override
    public String getType(Uri url) {
        switch (URL_MATCHER.match(url)) {
            case ADN_SUB1:
            case ADN_SUB2:
            case FDN_SUB1:
            case FDN_SUB2:
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
        int subscription = 0;

        if (DBG) log("insert");

        int match = URL_MATCHER.match(url);
        switch (match) {
            case ADN_SUB1:
                efType = IccConstants.EF_ADN;
                subscription = SUB1;
                break;
            case ADN_SUB2:
                efType = IccConstants.EF_ADN;
                subscription = SUB2;
                break;

            case FDN_SUB1:
            case FDN_SUB2:
                efType = IccConstants.EF_FDN;
                pin2 = initialValues.getAsString("pin2");
                subscription = initialValues.getAsInteger(MSimConstants.SUBSCRIPTION_KEY);
                break;

            default:
                throw new UnsupportedOperationException(
                        "Cannot insert into URL: " + url);
        }

        String tag = initialValues.getAsString("tag");
        String number = initialValues.getAsString("number");
        String emails = initialValues.getAsString("emails");
        String anrs = initialValues.getAsString("anrs");
        if (DBG) log("insert into subscription " + subscription + ", [" + tag + ", " + number + "]");
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
        boolean success = updateIccRecordInEf(efType, mValues, pin2, subscription);

        if (!success) {
            return null;
        }

        StringBuilder buf = new StringBuilder("content://iccmsim/");
        switch (match) {
            case ADN_SUB1:
                buf.append("adn/");
                break;

            case ADN_SUB2:
                buf.append("adn_sub2/");
                break;

            case FDN_SUB1:
                buf.append("fdn/");
                break;

            case FDN_SUB2:
                buf.append("fdn_sub2/");
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

    @Override
    public int delete(Uri url, String where, String[] whereArgs) {
        int efType;
        int subscription = 0;

        if (DBG) log("delete");

        int match = URL_MATCHER.match(url);
        switch (match) {
            case ADN_SUB1:
                efType = IccConstants.EF_ADN;
                subscription = SUB1;
                break;
            case ADN_SUB2:
                efType = IccConstants.EF_ADN;
                subscription = SUB2;
                break;

            case FDN_SUB1:
                efType = IccConstants.EF_FDN;
                subscription = SUB1;
                break;
            case FDN_SUB2:
                efType = IccConstants.EF_FDN;
                subscription = SUB2;
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
        if ((efType == FDN_SUB1 || efType == FDN_SUB2) && TextUtils.isEmpty(pin2)) {
            return 0;
        }

        if (DBG) log("delete from subscription " + subscription + "mvalues= " + mValues);
        boolean success = updateIccRecordInEf(efType, mValues, pin2, subscription);
        if (!success) {
            return 0;
        }

        return 1;
    }

    @Override
    public int update(Uri url, ContentValues values, String where, String[] whereArgs) {
        int efType;
        String pin2 = null;
        int subscription = 0;

        if (DBG) log("update");

        int match = URL_MATCHER.match(url);
        switch (match) {
            case ADN_SUB1:
                efType = IccConstants.EF_ADN;
                subscription = SUB1;
                break;
            case ADN_SUB2:
                efType = IccConstants.EF_ADN;
                subscription = SUB2;
                break;

            case FDN_SUB1:
            case FDN_SUB2:
                efType = IccConstants.EF_FDN;
                pin2 = values.getAsString("pin2");
                subscription = values.getAsInteger(MSimConstants.SUBSCRIPTION_KEY);
                break;

            default:
                throw new UnsupportedOperationException(
                        "Cannot insert into URL: " + url);
        }

        boolean success = updateIccRecordInEf(efType, values, pin2, subscription);

        if (!success) {
            return 0;
        }

        return 1;
    }

    protected MatrixCursor loadFromEf(int efType, int subscription) {
        List<AdnRecord> adnRecords = null;

        if (DBG) log("loadFromEf: efType=" + efType + "subscription = " + subscription);

        try {
            IIccPhoneBookMSim iccIpb = IIccPhoneBookMSim.Stub.asInterface(
                    ServiceManager.getService("simphonebook_msim"));
            if (iccIpb != null) {
                adnRecords = iccIpb.getAdnRecordsInEfOnSubscription(efType, subscription);
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
    updateIccRecordInEf(int efType, ContentValues values, String pin2, int subscription) {
        if (DBG) log("updateIccRecordInEf: efType=" + efType + ", values: "+ values
            + ", subscription=" + subscription);
        boolean success = false;

        try {
            IIccPhoneBookMSim iccIpb = IIccPhoneBookMSim.Stub.asInterface(
                    ServiceManager.getService("simphonebook_msim"));
            if (iccIpb != null) {
                success = iccIpb.updateAdnRecordsInEfBySearchOnSubscription(efType,
                        values, pin2, subscription);
            }
        } catch (RemoteException ex) {
            // ignore it
        } catch (SecurityException ex) {
            if (DBG) log(ex.toString());
        }
        if (DBG) log("updateIccRecordInEf: " + success);
        return success;
    }


    @Override
    protected void log(String msg) {
        Log.d(TAG, "[MSimIccProvider] " + msg);
    }

}
