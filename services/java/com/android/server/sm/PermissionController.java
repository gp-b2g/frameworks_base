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

package com.android.server.sm;

import android.content.Context;
import android.os.RemoteException;
import android.security.IPermissionToken;
import android.security.SecurityRecordBase;
import android.security.SecurityRecord;
import android.security.SecurityResult;
import android.util.Log;

import java.util.List;

/** {@hide} */
public final class PermissionController {
    private Context mContext;
    private final static String TAG = "PermissionController";
    private final static boolean DEBUG = true;

    PermissionController(Context context) {
        mContext = context;
    }
    
    private IPermissionToken mPermToken;

    public void applyPermissionToken(IPermissionToken token) {
        mPermToken = token;
        try {
            mPermToken.onEnablePermissionController();
        } catch (RemoteException e) {

        }
    }

    public int enablePermissionController(boolean enable) {
        try {
            if (enable) {
                mPermToken.onEnablePermissionController();
            } else {
                mPermToken.onDisablePermissionController();
            }
        } catch (RemoteException e) {
            return SecurityResult.REMOTE_ERROR;
        }
        return SecurityResult.REMOTE_NO_ERROR;
    }

    public int revokePermission(String perm, String pkg, int type) {
        int result;
        try {
            LOG("revokePermission", "perm: " + perm + ", pkg: " + pkg);
            result = mPermToken.revokePackagePermission(perm, pkg, type);
        } catch (RemoteException e) {
            return SecurityResult.REMOTE_ERROR;
        }
        return result;
    }

    public int grantPermission(String perm, String pkg) {
        int result;
        try {
            LOG("grantPermission", "perm: " + perm + ", pkg: " + pkg);
            result = mPermToken.grantPackagePermission(perm, pkg);
        } catch (RemoteException e) {
            return SecurityResult.REMOTE_ERROR;
        }
        return result;
    }

    public int revokePermission(List<String> permList, String pkg, int type) {
        int result;
        try {
            LOG("revokePermission", "permList: " + permList + ", pkg: " + pkg);
            result = mPermToken.revokePackagePermissionList(permList, pkg, type);
        } catch (RemoteException e) {
            return SecurityResult.REMOTE_ERROR;
        }
        return result;
    }

    public int grantPermission(List<String> permList, String pkg) {
        int result;
        try {
            LOG("grantPermission", "permList: " + permList + ", pkg: " + pkg);
            result = mPermToken.grantPackagePermissionList(permList, pkg);
        } catch (RemoteException e) {
            return SecurityResult.REMOTE_ERROR;
        }
        return result;
    }

    public int clearPermissionSettings() {
        try {
            mPermToken.clearPermissionSettings();
        } catch (RemoteException e) {
            return SecurityResult.REMOTE_ERROR;
        }
        return SecurityResult.REMOTE_NO_ERROR;
    }

    public int clearPermissionSettingsByPkg(String pkg) {
        try {
            mPermToken.clearPermissionSettingsByPkg(pkg);
        } catch (RemoteException e) {
            return SecurityResult.REMOTE_ERROR;
        }
        return SecurityResult.REMOTE_NO_ERROR;
    }

    private void LOG(String function, String msg) {
        if (DEBUG) {
            Log.d(TAG, "[" + function + "] " + msg);
        }
    }

    private class systemReadyCallback implements SecurityRecordBase.Callback {
        public void apply(String pkg, String perm) {
            revokePermission(perm, pkg, 0);
        }
    }

    public void systemReady(SecurityRecordBase r) {
        r.forEach(new systemReadyCallback());
    }
}

