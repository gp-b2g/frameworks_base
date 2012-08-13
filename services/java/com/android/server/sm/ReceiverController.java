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
import android.security.IReceiverToken;
import android.security.SecurityRecord;
import android.security.SecurityRecord.ReceiverRecordCallback;
import android.security.SecurityResult;

/** {@hide} */
public final class ReceiverController {
    private Context mContext;

    ReceiverController(Context context) {
        mContext = context;
    }
 
    private IReceiverToken mReceiverToken;

    public void applyReceiverToken(IReceiverToken token) {
        mReceiverToken = token;
    }

    public int blockActionReceiver(String action, String pkg) {
        int result;
        try {
            result = mReceiverToken.blockAction(action, pkg);
        } catch (RemoteException e) {
            return SecurityResult.REMOTE_ERROR;
        }
        return result;
    }

    public int restoreActionReceiver(String action, String pkg) {
        int result;
        try {
            result = mReceiverToken.restoreAction(action, pkg);
        } catch (RemoteException e) {
            return SecurityResult.REMOTE_ERROR;
        }
        return result;
    }

    private class BlockActionReceiver implements ReceiverRecordCallback {
        public void apply(String action, String pkgName) {
            blockActionReceiver(action, pkgName);
        }
    }

    public void systemReady() {
        SecurityRecord r = new SecurityRecord();
        r.forEachReceiverRecord(new BlockActionReceiver());
    }
}
