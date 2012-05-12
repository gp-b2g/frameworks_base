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

package com.android.server.location;

import android.os.Binder;
import android.os.Parcelable;
import android.util.Log;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.location.GeoFenceParams;
import android.location.ILocationListener;
import java.io.PrintWriter;
import java.util.Map;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Collection;
import java.util.ArrayList;

/**
 * This class defines a base class for GeoFencers
 *
 * @hide
 */
public abstract class GeoFencerBase {
    private static final String TAG = "GeoFencerBase";
    private HashMap<PendingIntent,GeoFenceParams> mGeoFences;

    public GeoFencerBase() {
        mGeoFences = new HashMap<PendingIntent,GeoFenceParams>();
    }

    public void add(double latitude, double longitude,
                 float radius, long expiration, PendingIntent intent) {
        add(new GeoFenceParams(latitude, longitude, radius,
                                     expiration, intent));
    }

    public void add(GeoFenceParams geoFence) {
        synchronized(mGeoFences) {
            mGeoFences.put(geoFence.mIntent, geoFence);
        }
        if (!start(geoFence)) {
            synchronized(mGeoFences) {
                mGeoFences.remove(geoFence.mIntent);
            }
        }
    }

    public void remove(PendingIntent intent) {
        remove(intent, false);
    }

    public void remove(PendingIntent intent, boolean localOnly) {
        GeoFenceParams geoFence = null;

        synchronized(mGeoFences) {
            geoFence = mGeoFences.remove(intent);
        }

        if (geoFence != null) {
            if (!localOnly && !stop(intent)) {
                synchronized(mGeoFences) {
                    mGeoFences.put(geoFence.mIntent, geoFence);
                }
            }
        }
    }

    public int getNumbOfGeoFences() {
        return mGeoFences.size();
    }

    public Collection<GeoFenceParams> getAllGeoFences() {
        return mGeoFences.values();
    }

    public GeoFenceParams getGeoFence(PendingIntent intent) {
        return mGeoFences.get(intent);
    }

    public boolean hasCaller(int uid) {
        for (GeoFenceParams alert : mGeoFences.values()) {
            if (alert.mUid == uid) {
                return true;
            }
        }
        return false;
    }

    public void removeCaller(int uid) {
        ArrayList<PendingIntent> removedFences = null;
        for (GeoFenceParams alert : mGeoFences.values()) {
            if (alert.mUid == uid) {
                if (removedFences == null) {
                    removedFences = new ArrayList<PendingIntent>();
                }
                removedFences.add(alert.mIntent);
            }
        }
        if (removedFences != null) {
            for (int i = removedFences.size()-1; i>=0; i--) {
                mGeoFences.remove(removedFences.get(i));
            }
        }
    }

    public void transferService(GeoFencerBase geofencer) {
        for (GeoFenceParams alert : geofencer.mGeoFences.values()) {
            geofencer.stop(alert.mIntent);
            add(alert);
        }
    }

    public void dump(PrintWriter pw, String prefix) {
        if (mGeoFences.size() > 0) {
            pw.println(prefix + "  GeoFences:");
            prefix += "    ";
            for (Map.Entry<PendingIntent, GeoFenceParams> i
                     : mGeoFences.entrySet()) {
                pw.println(prefix + i.getKey() + ":");
                i.getValue().dump(pw, prefix);
            }
        }
    }

    abstract protected boolean start(GeoFenceParams geoFence);
    abstract protected boolean stop(PendingIntent intent);
}
