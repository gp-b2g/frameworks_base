/**
 * Copyright (C) 2012, Code Aurora Forum. All rights reserved.
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

package android.security;

import android.os.Parcel;
import android.os.Parcelable;

public class FirewallEntry implements Parcelable {
    public int uid;
    public boolean mobileBlocked;
    public boolean wifiBlocked;

    public FirewallEntry() {
    }

    public boolean isChanged(int type, boolean blockedValue) {
        switch (type) {
            case SecurityManager.FIREWALL_TYPE_MOBILE: {
                if (mobileBlocked != blockedValue)
                    return true;
                else
                    return false;
            }
            case SecurityManager.FIREWALL_TYPE_WIFI: {
                if (wifiBlocked != blockedValue)
                    return true;
                else
                    return false;
            }
            default:
                return false;
        }
    }

    public void setBlockedValue(int type, boolean blockedValue) {
        switch (type) {
            case SecurityManager.FIREWALL_TYPE_MOBILE: {
                mobileBlocked = blockedValue;
                break;
            }
            case SecurityManager.FIREWALL_TYPE_WIFI: {
                wifiBlocked = blockedValue;
                break;
            }
            case SecurityManager.FIREWALL_TYPE_ALL: {
                mobileBlocked = blockedValue;
                wifiBlocked = blockedValue;
                break;
            }
            default:
                return;
        }
    }

    public boolean isAllEnabled() {
        return !wifiBlocked && !mobileBlocked;
    }

    public String toString() {
        return "FirewallEntry{"
            + uid + ", " + mobileBlocked + ", " + wifiBlocked + "}";
    }

    public int describeContents() {
        return 0;
    }

    public void writeToParcel(Parcel dest, int parcelableFlags) {
        dest.writeInt(uid);
        dest.writeInt(mobileBlocked ? 1 : 0);
        dest.writeInt(wifiBlocked ? 1 : 0);
    }

    public static final Parcelable.Creator<FirewallEntry> CREATOR
            = new Parcelable.Creator<FirewallEntry>() {
        public FirewallEntry createFromParcel(Parcel source) {
            return new FirewallEntry(source);
        }

        public FirewallEntry[] newArray(int size) {
            return new FirewallEntry[size];
        }
    };

    private FirewallEntry(Parcel source) {
        uid = source.readInt();
        mobileBlocked = (source.readInt() != 0);
        wifiBlocked = (source.readInt() != 0);
    }
}
