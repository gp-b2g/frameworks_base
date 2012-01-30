/* Copyright (c) 2012, Code Aurora Forum. All rights reserved.
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
 *
 */

package android.location;

import android.app.PendingIntent;
import android.os.Binder;
import android.os.Parcel;
import android.os.Parcelable;
import java.io.PrintWriter;

/**
 * GeoFenceParams for internal use
 * {@hide}
 */
public class GeoFenceParams implements Parcelable {
    public static final int ENTERING = 1;
    public static final int LEAVING = 2;
    public final int mUid;
    public final double mLatitude;
    public final double mLongitude;
    public final float mRadius;
    public final long mExpiration;
    public final PendingIntent mIntent;

    public static final Parcelable.Creator<GeoFenceParams> CREATOR = new Parcelable.Creator<GeoFenceParams>() {
        public GeoFenceParams createFromParcel(Parcel in) {
            return new GeoFenceParams(in);
        }

        @Override
        public GeoFenceParams[] newArray(int size) {
            return new GeoFenceParams[size];
        }
    };

    public GeoFenceParams(double lat, double lon, float r,
                          long expire, PendingIntent intent) {
        this(Binder.getCallingUid(), lat, lon, r, expire, intent);
    }

    public GeoFenceParams(int uid, double lat, double lon, float r,
                          long expire, PendingIntent intent) {
        mUid = uid;
        mLatitude = lat;
        mLongitude = lon;
        mRadius = r;
        mExpiration = expire;
        mIntent = intent;
    }

    private GeoFenceParams(Parcel in) {
        mUid = in.readInt();
        mLatitude = in.readDouble();
        mLongitude = in.readDouble();
        mRadius = in.readFloat();
        mExpiration = in.readLong();
        mIntent = in.readParcelable(null);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mUid);
        dest.writeDouble(mLatitude);
        dest.writeDouble(mLongitude);
        dest.writeFloat(mRadius);
        dest.writeLong(mExpiration);
        dest.writeParcelable(mIntent, 0);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("GeoFenceParams:\n\tmUid - ");
        sb.append(mUid);
        sb.append("\n\tmLatitide - ");
        sb.append(mLatitude);
        sb.append("\n\tmLongitude - ");
        sb.append(mLongitude);
        sb.append("\n\tmRadius - ");
        sb.append(mRadius);
        sb.append("\n\tmExpiration - ");
        sb.append(mExpiration);
        sb.append("\n\tmIntent - ");
        sb.append(mIntent);
        return sb.toString();
    }

    public long getExpiration() {
        return mExpiration;
    }

    public PendingIntent getIntent() {
        return mIntent;
    }

    public int getCallerUid() {
        return mUid;
    }

    public void dump(PrintWriter pw, String prefix) {
        pw.println(prefix + this);
        pw.println(prefix + "mLatitude=" + mLatitude + " mLongitude=" + mLongitude);
        pw.println(prefix + "mRadius=" + mRadius + " mExpiration=" + mExpiration);
    }
}
