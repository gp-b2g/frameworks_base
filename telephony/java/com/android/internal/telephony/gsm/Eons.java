/*
 * Copyright (C) 2010-2011 The Android Open Source Project
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

import android.util.Log;

import com.android.internal.telephony.IccUtils;
import com.android.internal.telephony.OperatorInfo;

import java.util.ArrayList;


/**
 * {@hide}
 */
public final class Eons {
    static final String LOG_TAG = "GSM";

    private static final boolean DBG = true;

    /**
     * INITING: OPL or PNN records not read yet, in this state we do not
     *          know whether EONS is enabled or not, operator name display will be
     *          supressed in this state
     * DISABLED: Exception in reading PNN and or OPL records, EONS is
     *          disabled, use operator name from ril
     * PNN_PRESENT: Only PNN is present, Operator name from first record of PNN can be used
     *          if the registered operator is HPLMN otherwise use name from ril
     * PNN_AND_OPL_PRESENT: Both PNN and OPL files are available, EONS name can be
     *          derived using both these files
     */
    public enum EonsState {
        INITING,
        DISABLED,
        PNN_PRESENT,
        PNN_AND_OPL_PRESENT;

        public boolean isIniting() {
            return (this == INITING);
        }

        public boolean isDisabled() {
            return (this == DISABLED);
        }

        public boolean isPnnPresent() {
            return (this == PNN_PRESENT);
        }

        public boolean isPnnAndOplPresent() {
            return (this == PNN_AND_OPL_PRESENT);
        }
    }

    public enum EonsControlState {
        INITING,
        PRESENT,
        ABSENT;

        public boolean isIniting() {
            return (this == INITING);
        }

        public boolean isPresent() {
            return (this == PRESENT);
        }

        public boolean isAbsent() {
            return (this == ABSENT);
        }
    }

    // ***** Instance Variables
    EonsControlState mPnnDataState = EonsControlState.INITING;
    EonsControlState mOplDataState = EonsControlState.INITING;
    OplRecords mOplRecords;
    PnnRecords mPnnRecords;

    // ***** Constructor
    Eons() {
        reset();
    }

    // ***** Public Methods
    public void reset() {
        mPnnDataState = EonsControlState.INITING;
        mOplDataState = EonsControlState.INITING;
        mOplRecords = null;
        mPnnRecords = null;
    }

    public void setOplData(ArrayList <byte[]> records) {
        mOplDataState = EonsControlState.PRESENT;
        mOplRecords = new OplRecords(records);
    }

    public void resetOplData() {
        mOplDataState = EonsControlState.ABSENT;
        mOplRecords = null;
    }

    public void setPnnData(ArrayList <byte[]> records) {
        mPnnDataState = EonsControlState.PRESENT;
        mPnnRecords = new PnnRecords(records);
    }

    public void resetPnnData() {
        mPnnDataState = EonsControlState.ABSENT;
        mPnnRecords = null;
    }

    /**
     * Get the EONS derived from EF_OPL/EF_PNN or EF_CPHS_ONS/EF_CPHS_ONS_SHORT
     * files for registered operator.
     * @return Enhanced Operator Name String (EONS) if it can be derived and
     * null otherwise.
     */
    public String getEons() {
        String name = null;

        if (mPnnRecords != null) {
            name = mPnnRecords.getCurrentEons();
        }
        return name;
    }

    /**
     * When there is a change in LAC or Service State, update EONS
     * for registered plmn.
     * @param regOperator is the registered operator PLMN
     * @param lac is current lac
     * @param hplmn from SIM
     * @return returns true if operator name display needs updation, false
     * otherwise
     */
    public boolean updateEons(String regOperator, int lac, String hplmn) {
        boolean needsOperatorNameUpdate = true;

        if ((getEonsState()).isPnnAndOplPresent()) {
            // If both PNN and OPL data is available, a match should
            // be found in OPL file for registered operator and
            // corresponding record in the PNN file should be used
            // for fetching EONS name.
            updateEonsFromOplAndPnn(regOperator, lac);
        } else if ((getEonsState()).isPnnPresent()) {
            // If only PNN data is available, update EONS name from first
            // record of PNN if registered operator is HPLMN.
            updateEonsIfHplmn(regOperator, hplmn);
        } else if ((getEonsState()).isIniting()) {
            if (DBG) log("Reading data from EF_OPL or EF_PNN is not complete. " +
                "Suppress operator name display until all EF_OPL/EF_PNN data is read.");
            needsOperatorNameUpdate = false;
        }

        // For all other cases including both EF_PNN/EF_OPL absent use
        // operator name from ril.
        return needsOperatorNameUpdate;
    }

    /**
     * Fetch EONS for Available Networks from EF_PNN data.
     * @param avlNetworks, ArrayList of Available Networks
     * @return ArrayList Available Networks with EONS if
     * success,otherwise null
     */
    public ArrayList<OperatorInfo> getEonsForAvailableNetworks(ArrayList<OperatorInfo> avlNetworks) {
        ArrayList<OperatorInfo> eonsNetworkNames = null;

        if (!(getEonsState()).isPnnAndOplPresent()) {
            loge("OPL/PNN data is not available. Use the network names from Ril.");
            return null;
        }

        if ((avlNetworks != null) && (avlNetworks.size() > 0)) {
            int size = avlNetworks.size();
            eonsNetworkNames = new ArrayList<OperatorInfo>(size);
            if (DBG) log("Available Networks List Size = " + size);
            for (int i = 0; i < size; i++) {
                 int pnnRecord = 0;
                 String pnnName = null;
                 OperatorInfo ni;

                 ni = avlNetworks.get(i);
                 // Get EONS for this operator from OPL/PNN data.
                 pnnRecord = mOplRecords.getMatchingPnnRecord(ni.getOperatorNumeric(), -1, false);
                 pnnName = mPnnRecords.getNameFromPnnRecord(pnnRecord, false);
                 if (DBG) log("PLMN = " + ni.getOperatorNumeric() + ", ME Name = "
                   + ni.getOperatorAlphaLong() + ", PNN Name = " + pnnName);
                 // EONS could not be derived for this operator. Use the
                 // same name in the list.
                 if (pnnName == null) {
                     pnnName = ni.getOperatorAlphaLong();
                 }
                 eonsNetworkNames.add (new OperatorInfo(pnnName,ni.getOperatorAlphaShort(),
                        ni.getOperatorNumeric(),ni.getState()));
            }
        } else {
            loge("Available Networks List is empty");
        }

        return eonsNetworkNames;
    }

    // ***** Private Methods
    /**
     * Derive EONS from matching OPL and PNN record for registered
     * operator.
     * @param regOperator is the registered operator PLMN
     * @param lac is current lac
     */
    private void updateEonsFromOplAndPnn(String regOperator, int lac) {
        int pnnRecord;
        String pnnName;

        pnnRecord = mOplRecords.getMatchingPnnRecord(regOperator, lac, true);
        pnnName = mPnnRecords.getNameFromPnnRecord(pnnRecord, true);
        if (DBG) log("Fetched EONS name from EF_PNN record = " + pnnRecord +
              ", name = " + pnnName);
    }

    /**
     * Derive EONS from EF_PNN first record if registered PLMN is HPLMN.
     * @param regOperator is the registered operator PLMN
     * @param hplmn from SIM
     */
    private void updateEonsIfHplmn(String regOperator, String hplmn) {
        if (DBG) log ("Comparing hplmn, " + hplmn +
                       " with registered plmn " + regOperator);
        // If the registered PLMN is HPLMN, then derive EONS name
        // from first record of EF_PNN
        if ((hplmn != null) && hplmn.equals(regOperator)) {
            String pnnName = mPnnRecords.getNameFromPnnRecord(1, true);
            if (DBG) log("Fetched EONS name from EF_PNN's first record, name = " + pnnName);
        }
    }

    /**
     * Determines how to apply EONS algorithm based on OPL/PNN data
     * availability
     * If both OPL and PNN data is available, then EONS operator name
     * should be derived using both these files
     * If only PNN data is available, use name from first record of PNN as
     * EONS name if registered PLMN is HPLMN
     * If only OPL data is available, do not use EONS algorithm
     * Suppress operator name display until OPL and PNN data is read
     * This should not take much and should not cause any issue
     * If operator name display is allowed during this window, there will be a
     * blip in operator name, momentarily displaying name from ril and then
     * switching to EONS name, this is not acceptable.
     */
    private EonsState getEonsState() {
        // OPL or PNN data read is not complete.
        if ((mPnnDataState.isIniting()) || (mOplDataState.isIniting())) {
            return EonsState.INITING;
        } else if (mPnnDataState.isPresent()) {
            if(mOplDataState.isPresent()) {
               return EonsState.PNN_AND_OPL_PRESENT;
            } else {
               return EonsState.PNN_PRESENT;
            }
        } else {
            // If PNN is not present, disable EONS algorithm.
            return EonsState.DISABLED;
        }
    }

    private void log(String s) {
        Log.d(LOG_TAG, "[EONS] " + s);
    }

    private void loge(String s) {
        Log.e(LOG_TAG, "[EONS] " + s);
    }
}
