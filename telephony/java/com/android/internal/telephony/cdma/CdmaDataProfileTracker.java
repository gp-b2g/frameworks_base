/*
 * Copyright (c) 2010-2012, Code Aurora Forum. All rights reserved.
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

package com.android.internal.telephony.cdma;

import android.os.Registrant;
import android.os.RegistrantList;
import android.os.Handler;
import android.os.AsyncResult;
import android.os.Message;
import android.os.SystemProperties;
import android.provider.Telephony;
import android.database.Cursor;
import android.text.TextUtils;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;

import com.android.internal.telephony.Phone;
import com.android.internal.telephony.DataProfile;
import com.android.internal.telephony.cdma.DataProfileCdma;
import com.android.internal.telephony.cdma.DataProfileOmh.DataProfileTypeModem;
import com.android.internal.telephony.cdma.CDMAPhone;
import com.android.internal.telephony.RILConstants;
import com.android.internal.telephony.TelephonyProperties;

/**
 * {@hide}
 */
public final class CdmaDataProfileTracker extends Handler {
    protected final String LOG_TAG = "CDMA";

    private CDMAPhone mPhone;
    private CdmaSubscriptionSourceManager mCdmaSsm;

    /**
     * mDataProfilesList holds all the Data profiles for cdma
     */
    private ArrayList<DataProfile> mDataProfilesList = new ArrayList<DataProfile>();

    private static final String[] mSupportedApnTypes = {
            Phone.APN_TYPE_DEFAULT,
            Phone.APN_TYPE_MMS,
            Phone.APN_TYPE_SUPL,
            Phone.APN_TYPE_DUN,
            Phone.APN_TYPE_HIPRI,
            Phone.APN_TYPE_FOTA,
            Phone.APN_TYPE_IMS,
            Phone.APN_TYPE_CBS };

    private static final String[] mDefaultApnTypes = {
            Phone.APN_TYPE_DEFAULT,
            Phone.APN_TYPE_MMS,
            Phone.APN_TYPE_SUPL,
            Phone.APN_TYPE_HIPRI,
            Phone.APN_TYPE_FOTA,
            Phone.APN_TYPE_IMS,
            Phone.APN_TYPE_CBS };

    // if we have no active DataProfile this is null
    protected DataProfile mActiveDp;

    /*
     * Context for read profiles for OMH.
     */
    private int mOmhReadProfileContext = 0;

    /*
     * Count to track if all read profiles for OMH are completed or not.
     */
    private int mOmhReadProfileCount = 0;

    private boolean mIsOmhEnabled =
                SystemProperties.getBoolean(TelephonyProperties.PROPERTY_OMH_ENABLED, false);

    // Enumerated list of DataProfile from the modem.
    ArrayList<DataProfile> mOmhDataProfilesList = new ArrayList<DataProfile>();

    // Temp. DataProfile list from the modem.
    ArrayList<DataProfile> mTempOmhDataProfilesList = new ArrayList<DataProfile>();

    // Map of the service type to its priority
    HashMap<String, Integer> mOmhServicePriorityMap;

    /* Registrant list for objects interested in modem profile related events */
    private RegistrantList mModemDataProfileRegistrants = new RegistrantList();

    private static final int EVENT_READ_MODEM_PROFILES = 0;
    private static final int EVENT_GET_DATA_CALL_PROFILE_DONE = 1;
    private static final int EVENT_LOAD_PROFILES = 2;

    /* Constructor */

    CdmaDataProfileTracker(CDMAPhone phone) {
        mPhone = phone;
        mCdmaSsm = CdmaSubscriptionSourceManager.getInstance (phone.getContext(), phone.mCM, this,
                EVENT_LOAD_PROFILES, null);

        mOmhServicePriorityMap = new HashMap<String, Integer>();

        sendMessage(obtainMessage(EVENT_LOAD_PROFILES));

        log("SUPPORT_OMH: " + mIsOmhEnabled);
    }

    /**
     * Load the CDMA profiles
     */
    void loadProfiles() {
        log("loadProfiles...");
        mDataProfilesList.clear();

        readNaiListFromDatabase();

        log("Got " + mDataProfilesList.size() + " profiles from database");

        if (mDataProfilesList.size() == 0) {
            // Create default cdma profiles since nothing was found in database
            createDefaultDataProfiles();
        }

        // Set the active profile as the default one
        setActiveDpToDefault();

        // Last thing - trigger reading omh profiles
        readDataProfilesFromModem();
    }

    /**
     * - Create the default profiles.
     * - One for DUN and another for all the default profiles supported
     */
    private void createDefaultDataProfiles() {
        log("Creating default profiles...");
        String ipProto = SystemProperties.get(
                TelephonyProperties.PROPERTY_CDMA_IPPROTOCOL, "IP");
        String roamingIpProto = SystemProperties.get(
                TelephonyProperties.PROPERTY_CDMA_ROAMING_IPPROTOCOL, "IP");
        CdmaDataConnectionTracker cdmaDct =
            (CdmaDataConnectionTracker)(mPhone.mDataConnectionTracker);

        DataProfileCdma dp;
        dp = new DataProfileCdma(
                cdmaDct.apnTypeToId(Phone.APN_TYPE_DEFAULT),
                null, null, null, null,
                RILConstants.SETUP_DATA_AUTH_PAP_CHAP, mDefaultApnTypes, ipProto, roamingIpProto,
                mPhone.getServiceState().getRadioTechnology());
        dp.setProfileId(RILConstants.DATA_PROFILE_DEFAULT);
        mDataProfilesList.add((DataProfile)dp);

        String[] types = {Phone.APN_TYPE_DUN};

        dp = new DataProfileCdma(
                cdmaDct.apnTypeToId(Phone.APN_TYPE_DUN),
                null, null, null, null,
                RILConstants.SETUP_DATA_AUTH_PAP_CHAP, types, ipProto, roamingIpProto,
                mPhone.getServiceState().getRadioTechnology());
        dp.setProfileId(RILConstants.DATA_PROFILE_TETHERED);
        mDataProfilesList.add((DataProfile)dp);
    }

    private void setActiveDpToDefault() {
        mActiveDp = getDataProfile(Phone.APN_TYPE_DEFAULT);
    }

    private String getOperatorNumeric() {
        String result = null;
        CdmaDataConnectionTracker cdmaDct =
            (CdmaDataConnectionTracker)(mPhone.mDataConnectionTracker);
        if (mCdmaSsm.getCdmaSubscriptionSource() ==
                        CdmaSubscriptionSourceManager.SUBSCRIPTION_FROM_NV) {
            result = SystemProperties.get(CDMAPhone.PROPERTY_CDMA_HOME_OPERATOR_NUMERIC);
            log("operatorNumeric for NV " + result);
        } else if (cdmaDct.getIccRecords() != null) {
            result = cdmaDct.getIccRecords().getOperatorNumeric();
            log("operatorNumeric for icc " + result);
        } else {
            log("IccRecords == null -> operatorNumeric = null");
        }
        return result;
    }

    /**
     * Based on the operator, create a list of
     * cdma data profiles.
     */
    private void readNaiListFromDatabase() {
        String operator = getOperatorNumeric();
        if (operator == null || operator.length() < 2) {
            loge("operatorNumeric invalid. Won't read database");
            return;
        }

        log("Loading data profiles for operator = " + operator);
        String selection = "numeric = '" + operator + "'" + " and profile_type = 'nai'";
        // query only enabled nai.
        // carrier_enabled : 1 means enabled nai, 0 disabled nai.
        selection += " and carrier_enabled = 1";
        log("readNaiListFromDatabase: selection=" + selection);

        Cursor cursor = mPhone.getContext().getContentResolver().query(
                Telephony.Carriers.CONTENT_URI, null, selection, null, null);

        if (cursor != null) {
            if (cursor.getCount() > 0) {
                populateDataProfilesList(cursor);
            }
            cursor.close();
        }
    }

    private void populateDataProfilesList(Cursor cursor) {
        if (cursor.moveToFirst()) {
            do {
                String[] types = parseTypes(
                        cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Carriers.TYPE)));
                DataProfileCdma nai = new DataProfileCdma(
                        cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Carriers._ID)),
                        cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Carriers.NUMERIC)),
                        cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Carriers.APN)),
                        cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Carriers.USER)),
                        cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Carriers.PASSWORD)),
                        cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Carriers.AUTH_TYPE)),
                        types,
                        cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Carriers.PROTOCOL)),
                        cursor.getString(cursor.getColumnIndexOrThrow(
                                Telephony.Carriers.ROAMING_PROTOCOL)),
                        cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Carriers.BEARER)));
                 mDataProfilesList.add(nai);

            } while (cursor.moveToNext());
        }
    }

    /**
     * @param types comma delimited list of data service types
     * @return array of data service types
     */
    private String[] parseTypes(String types) {
        String[] result;
        // If unset, set to DEFAULT.
        if (types == null || types.equals("")) {
            result = new String[1];
            result[0] = Phone.APN_TYPE_ALL;
        } else {
            result = types.split(",");
        }
        return result;
    }

    public void dispose() {
    }

    protected void finalize() {
        Log.d(LOG_TAG, "CdmaDataProfileTracker finalized");
    }

    void registerForModemProfileReady(Handler h, int what, Object obj) {
        Registrant r = new Registrant(h, what, obj);
        mModemDataProfileRegistrants.add(r);
    }

    void unregisterForModemProfileReady(Handler h) {
        mModemDataProfileRegistrants.remove(h);
    }

    public void handleMessage (Message msg) {

        if (!mPhone.mIsTheCurrentActivePhone) {
            Log.d(LOG_TAG, "Ignore CDMA msgs since CDMA phone is inactive");
            return;
        }

        switch (msg.what) {
            case EVENT_LOAD_PROFILES:
                loadProfiles();
                break;
            case EVENT_READ_MODEM_PROFILES:
                onReadDataProfilesFromModem();
                break;

            case EVENT_GET_DATA_CALL_PROFILE_DONE:
                onGetDataCallProfileDone((AsyncResult) msg.obj, (int)msg.arg1);
                break;

            default:
                // handle the message in the super class DataConnectionTracker
                super.handleMessage(msg);
                break;
        }
    }

    /*
     * Trigger modem read for data profiles
     */
    private void readDataProfilesFromModem() {
        if (mIsOmhEnabled) {
            sendMessage(obtainMessage(EVENT_READ_MODEM_PROFILES));
        } else {
            log("OMH is disabled, ignoring request!");
        }
    }

    /*
     * Reads all the data profiles from the modem
     */
    private void onReadDataProfilesFromModem() {
        log("OMH: onReadDataProfilesFromModem()");
        mOmhReadProfileContext++;

        mOmhReadProfileCount = 0; // Reset the count and list(s)
        /* Clear out the modem profiles lists (main and temp) which were read/saved */
        mOmhDataProfilesList.clear();
        mTempOmhDataProfilesList.clear();
        mOmhServicePriorityMap.clear();

        // For all the service types known in modem, read the data profies
        for (DataProfileTypeModem p : DataProfileTypeModem.values()) {
            log("OMH: Reading profiles for:" + p.getid());
            mOmhReadProfileCount++;
            mPhone.mCM.getDataCallProfile(p.getid(),
                            obtainMessage(EVENT_GET_DATA_CALL_PROFILE_DONE, //what
                            mOmhReadProfileContext, //arg1
                            0 , //arg2  -- ignore
                            p));//userObj
        }

    }

    /*
     * Process the response for the RIL request GET_DATA_CALL_PROFILE.
     * Save the profile details received.
     */
    private void onGetDataCallProfileDone(AsyncResult ar, int context) {
        if (ar.exception != null) {
            log("OMH: Exception in onGetDataCallProfileDone:" + ar.exception);
            return;
        }

        if (context != mOmhReadProfileContext) {
            //we have other onReadOmhDataprofiles() on the way.
            return;
        }

        // DataProfile list from the modem for a given SERVICE_TYPE. These may
        // be from RUIM in case of OMH
        ArrayList<DataProfile> dataProfileListModem = new ArrayList<DataProfile>();
        dataProfileListModem = (ArrayList<DataProfile>)ar.result;

        DataProfileTypeModem modemProfile = (DataProfileTypeModem)ar.userObj;

        mOmhReadProfileCount--;

        if (dataProfileListModem != null && dataProfileListModem.size() > 0) {
            String serviceType;

            /* For the modem service type, get the android DataServiceType */
            serviceType = modemProfile.getDataServiceType();

            log("OMH: # profiles returned from modem:" + dataProfileListModem.size()
                    + " for " + serviceType);

            mOmhServicePriorityMap.put(serviceType,
                    omhListGetArbitratedPriority(dataProfileListModem, serviceType));

            for (DataProfile dp : dataProfileListModem) {

                /* Store the modem profile type in the data profile */
                ((DataProfileOmh)dp).setDataProfileTypeModem(modemProfile);

                /* Look through mTempOmhDataProfilesList for existing profile id's
                 * before adding it. This implies that the (similar) profile with same
                 * priority already exists.
                 */
                DataProfileOmh omhDuplicatedp = getDuplicateProfile(dp);
                if(null == omhDuplicatedp) {
                    mTempOmhDataProfilesList.add(dp);
                    ((DataProfileOmh)dp).addServiceType(DataProfileTypeModem.
                            getDataProfileTypeModem(serviceType));
                } else {
                    /*  To share the already established data connection
                     * (say between SUPL and DUN) in cases such as below:
                     *  Ex:- SUPL+DUN [profile id 201, priority 1]
                     *  'dp' instance is found at this point. Add the non-provisioned
                     *   service type to this 'dp' instance
                     */
                    log("OMH: Duplicate Profile " + omhDuplicatedp);
                    ((DataProfileOmh)omhDuplicatedp).addServiceType(DataProfileTypeModem.
                            getDataProfileTypeModem(serviceType));
                }
            }
        }

        //(Re)Load APN List
        if(mOmhReadProfileCount == 0) {
            log("OMH: Modem omh profile read complete.");
            addServiceTypeToUnSpecified();
            mDataProfilesList.addAll(mTempOmhDataProfilesList);
            mModemDataProfileRegistrants.notifyRegistrants();
        }

        return;
    }

    /*
     * returns the object 'OMH dataProfile' if a match with the same profile id
     * exists in the enumerated list of OMH profile list
     */
    private DataProfileOmh getDuplicateProfile(DataProfile dp) {
        for (DataProfile dataProfile : mTempOmhDataProfilesList) {
            if (((DataProfileOmh)dp).getProfileId() ==
                ((DataProfileOmh)dataProfile).getProfileId()){
                return (DataProfileOmh)dataProfile;
            }
        }
        return null;
    }

    public DataProfile getDataProfile(String serviceType) {
        DataProfile profile = null;

        // Go through all the profiles to find one
        for (DataProfile dp: mDataProfilesList) {
            if (dp.canHandleType(serviceType)) {
                profile = dp;
                if (mIsOmhEnabled &&
                    dp.getDataProfileType() != DataProfile.DataProfileType.PROFILE_TYPE_OMH) {
                    // OMH enabled - Keep looking for OMH profile
                    continue;
                }
                break;
            }
        }
        return profile;
    }

    /* For all the OMH service types not present in the card, add them to the
     * UNSPECIFIED/DEFAULT data profile.
     */
    private void addServiceTypeToUnSpecified() {
        for (String apntype : mSupportedApnTypes) {
            if(!mOmhServicePriorityMap.containsKey(apntype)) {

                // ServiceType :apntype is not provisioned in the card,
                // Look through the profiles read from the card to locate
                // the UNSPECIFIED profile and add the service type to it.
                for (DataProfile dp : mTempOmhDataProfilesList) {
                    if (((DataProfileOmh)dp).getDataProfileTypeModem() ==
                                DataProfileTypeModem.PROFILE_TYPE_UNSPECIFIED) {
                        ((DataProfileOmh)dp).addServiceType(DataProfileTypeModem.
                                getDataProfileTypeModem(apntype));
                        log("OMH: Service Type added to UNSPECIFIED is : " +
                                DataProfileTypeModem.getDataProfileTypeModem(apntype));
                        break;
                    }
                }
            }
        }
    }

    /*
     * Retrieves the highest priority for all APP types except SUPL. Note that
     * for SUPL, retrieve the least priority among its profiles.
     */
    private int omhListGetArbitratedPriority(
            ArrayList<DataProfile> dataProfileListModem,
            String serviceType) {
        DataProfile profile = null;

        for (DataProfile dp : dataProfileListModem) {
            if (!((DataProfileOmh) dp).isValidPriority()) {
                log("[OMH] Invalid priority... skipping");
                continue;
            }

            if (profile == null) {
                profile = dp; // first hit
            } else {
                if (serviceType == Phone.APN_TYPE_SUPL) {
                    // Choose the profile with lower priority
                    profile = ((DataProfileOmh) dp).isPriorityLower(((DataProfileOmh) profile)
                            .getPriority()) ? dp : profile;
                } else {
                    // Choose the profile with higher priority
                    profile = ((DataProfileOmh) dp).isPriorityHigher(((DataProfileOmh) profile)
                            .getPriority()) ? dp : profile;
                }
            }
        }
        return ((DataProfileOmh) profile).getPriority();
    }

    public void clearActiveDataProfile() {
        mActiveDp = null;
    }

    public boolean isApnTypeActive(String type) {
        return mActiveDp != null && mActiveDp.canHandleType(type);
    }

    public boolean isOmhEnabled() {
        return mIsOmhEnabled;
    }

    protected boolean isApnTypeAvailable(String type) {
        for (String s : mSupportedApnTypes) {
            if (TextUtils.equals(type, s)) {
                return true;
            }
        }
        return false;
    }

    protected String[] getActiveApnTypes() {
        String[] result;
        if (mActiveDp != null) {
            result = mActiveDp.getServiceTypes();
        } else {
            result = new String[1];
            result[0] = Phone.APN_TYPE_DEFAULT;
        }
        return result;
    }

    protected void log(String s) {
        Log.d(LOG_TAG, "[CdmaDataProfileTracker] " + s);
    }

    protected void loge(String s) {
        Log.e(LOG_TAG, "[CdmaDataProfileTracker] " + s);
    }
}
