/* Copyright (c) 2012, Code Aurora Forum. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you
 * may not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 *
 */

package com.android.server;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.os.SystemProperties;
import com.android.internal.app.SuspendThread;

public class SuspendReceiver extends BroadcastReceiver {
    private static final String TAG = "SuspendReceiver";

    /** Called when the activity is first created. */
    @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if(action.equals(Intent.ACTION_BOOT_COMPLETED))
                bootConpleteHandler(context);
            else if(action.equals(Intent.ACTION_POWER_CONNECTED)) {
                powerConnectionHandler(context);
            }
        }

    void bootConpleteHandler(Context context) {
        if(SuspendThread.getSuspendStatus(context)) {
            //Suspend was interupted during last bootup. Need to restore device state.
            SuspendThread.wakeup(context);
        }
    }

    void powerConnectionHandler(Context context) {
        if(SuspendThread.getSuspendStatus(context)) {
            //Wake up device.
            SuspendThread.wakeup(context);
        }
    }
}
