/*
 * Copyright (C) 2008 The Android Open Source Project
 * Copyright (c) 2012 Code Aurora Forum. All rights reserved.
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

package com.android.systemui.statusbar.policy;

import com.android.systemui.R;

class WifiIcons {
    static final int[][] WIFI_SIGNAL_STRENGTH = {
            { R.drawable.stat_sys_wifi_signal_0,
              R.drawable.stat_sys_wifi_signal_1,
              R.drawable.stat_sys_wifi_signal_2,
              R.drawable.stat_sys_wifi_signal_3,
              R.drawable.stat_sys_wifi_signal_4 },
            { R.drawable.stat_sys_wifi_signal_0,
              R.drawable.stat_sys_wifi_signal_1_fully,
              R.drawable.stat_sys_wifi_signal_2_fully,
              R.drawable.stat_sys_wifi_signal_3_fully,
              R.drawable.stat_sys_wifi_signal_4_fully }
        };

    static final int WIFI_LEVEL_COUNT = WIFI_SIGNAL_STRENGTH[0].length;

    // FMC icon
    static final int[][] sFmcDataImages = {
        { R.drawable.stat_sys_fmc_data_1,
          R.drawable.stat_sys_fmc_data_2,
          R.drawable.stat_sys_fmc_data_3,
          R.drawable.stat_sys_fmc_data_4,
          R.drawable.stat_sys_fmc_data_5},
        { R.drawable.stat_sys_fmc_data_connectd_1,
          R.drawable.stat_sys_fmc_data_connectd_2,
          R.drawable.stat_sys_fmc_data_connectd_3,
          R.drawable.stat_sys_fmc_data_connectd_4,
          R.drawable.stat_sys_fmc_data_connectd_5}
    };

    static final int FMC_LEVEL_COUNT = sFmcDataImages[0].length;

    static final int sFmcInitImage = R.drawable.stat_sys_fmc_data_init;
}
