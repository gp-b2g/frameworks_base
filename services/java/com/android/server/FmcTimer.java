/* Copyright (c) 2011 Code Aurora Forum. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *     * Redistributions of source code must retain the above copyright
 *       notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above copyright
 *       notice, this list of conditions and the following disclaimer in the
 *       documentation and/or other materials provided with the distribution.
 *     * Neither the name of Code Aurora nor
 *       the names of its contributors may be used to endorse or promote
 *       products derived from this software without specific prior written
 *       permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NON-INFRINGEMENT ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS;
 * OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR
 * OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF
 * ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *
 */

package com.android.server;

import java.util.Timer;
import java.util.TimerTask;

import android.util.Log;

public class FmcTimer {

	/* Private */
    private static final boolean DBG = true;

    private static final String TAG = "FmcTimer";

    private static int timerId = 0;

    private static FmcTimer instance = null;
    private static Timer timer = null;

    /* Constructor */
    private FmcTimer() {
        if (DBG) Log.d(TAG, "constructor");
    }

    /* Create singleton */
    protected static FmcTimer create() {
        if (DBG) Log.d(TAG, "create");

        timerId = 0;

        if (instance == null) {
            if (DBG) Log.d(TAG, "create instance is null");
            instance = new FmcTimer();
        } else {
            if (DBG) Log.d(TAG, "create instance is not null");
        }

        return instance;
    }

    protected boolean startTimer (
        TimerTask callback,
        int timeout
    )
    {
        if (DBG) Log.d(TAG, "startTimer");
        if (timer == null) {
            if (DBG) Log.d(TAG, "startTimer creating new timer id=" + timerId++ + " timeout=" + timeout);

            timer = new Timer();
        } else if (callback == null) {
            Log.e(TAG, "startTimer callback is null");

            return false;
        } else if (timeout == 0) {
            Log.e(TAG, "startTimer timeout is 0");

            return false;
        }

        try {
            if (DBG) Log.d(TAG, "startTimer scheduling timer timeout=" + timeout);

            timer.schedule(callback, timeout);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "IllegalArgumentException=" + e.getMessage());
        } catch (IllegalStateException e) {
            Log.e(TAG, "IllegalStateException=" + e.getMessage());
        } catch (Exception e) {
            Log.e(TAG, "Exception=" + e.getMessage());
        }

        return true;
    }

    protected boolean clearTimer() {
        if (DBG) Log.d(TAG, "clearTimer");

        if (timer == null) {
            if (DBG) Log.d(TAG, "clearTimer timer object is null");

            return false;
        } else {
            if (DBG) Log.d(TAG, "clearTimer canceling timer");

            timer.cancel();
            timer = null;
        }

        return true;
    }
};
