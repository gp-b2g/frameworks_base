/*
 * Copyright (c) 2011, Code Aurora Forum. All rights reserved.
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

public abstract class DataProfile {

    protected final static String LOG_TAG = "DataProfile";

    public final int id;
    // TODO: Keeping this public will mean 'apn' will be accessed in CdmaDCT etc.
    public final String apn;
    public final String user;
    public final String password;
    public final int    authType;
    public final String protocol;
    public final String roamingProtocol;
    public final String numeric;
    /**
     * Radio Access Technology info
     * To check what values can hold, refer to ServiceState.java.
     * This should be spread to other technologies,
     * but currently only used for LTE(14) and EHRPD(13).
     */
    public final int bearer;

    public final String[] types;

    public enum DataProfileType {
        PROFILE_TYPE_APN(0),
        PROFILE_TYPE_CDMA(1),
        PROFILE_TYPE_OMH(2);

        int id;

        private DataProfileType(int i) {
            this.id = i;
        }

        public int getid() {
            return id;
        }
    }

    private DataConnection mDc = null;

    public DataProfile (int id, String numeric, String apn, String user, String password,
            int authType, String[] types, String protocol, String roamingProtocol, int bearer) {
        this.id = id;
        this.numeric = numeric;
        this.apn = apn;
        this.types = types;
        this.user = user;
        this.password = password;
        this.authType = authType;
        this.protocol = protocol;
        this.roamingProtocol = roamingProtocol;
        this.bearer = bearer;
    }

    /* package */ boolean isActive() {
      return mDc != null;
    }

    /* package */void setAsActive(DataConnection dc) {
        mDc = dc;
    }

    /* package */void setAsInactive() {
        mDc = null;
    }

    public int getAuthType() {
        return authType;
    }

    public String[] getServiceTypes() {
        return types;
    }

    public String toString() {
        return "[dpt=" + getDataProfileType() + ", active=" + isActive() + ", ";
    }

    /* some way to identify this data profile uniquely */
    public abstract String toHash();

    public abstract String toShortString();

    public abstract boolean canHandleType(String type);

    public abstract DataProfileType getDataProfileType();

    public abstract int getProfileId();
}
