/*
 * Copyright (C) 2008 The Android Open Source Project
 * Copyright (c) 2011-2012 Code Aurora Forum. All rights reserved.
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

public class TelephonyIcons {
    //***** Signal strength icons

    //GSM/UMTS
    static final int[][] TELEPHONY_SIGNAL_STRENGTH = {
        { R.drawable.stat_sys_signal_0,
          R.drawable.stat_sys_signal_1,
          R.drawable.stat_sys_signal_2,
          R.drawable.stat_sys_signal_3,
          R.drawable.stat_sys_signal_4 },
        { R.drawable.stat_sys_signal_0_fully,
          R.drawable.stat_sys_signal_1_fully,
          R.drawable.stat_sys_signal_2_fully,
          R.drawable.stat_sys_signal_3_fully,
          R.drawable.stat_sys_signal_4_fully }
    };

    static final int[][] TELEPHONY_SIGNAL_STRENGTH_ROAMING = {
        { R.drawable.stat_sys_signal_0,
          R.drawable.stat_sys_signal_1,
          R.drawable.stat_sys_signal_2,
          R.drawable.stat_sys_signal_3,
          R.drawable.stat_sys_signal_4 },
        { R.drawable.stat_sys_signal_0_fully,
          R.drawable.stat_sys_signal_1_fully,
          R.drawable.stat_sys_signal_2_fully,
          R.drawable.stat_sys_signal_3_fully,
          R.drawable.stat_sys_signal_4_fully }
    };

    // CU dualsim statusbar style icons.
    public static final int[] MULTI_NO_SIM_CU = {
        R.drawable.stat_sys_no_sim1_new,
        R.drawable.stat_sys_no_sim2_new
    };

    public static final int[] MULTI_SIGNAL_NULL_CU = {
        R.drawable.stat_sys_signal_null_sim1,
        R.drawable.stat_sys_signal_null_sim2
    };

    private static final int[][] TELEPHONY_SIGNAL_STRENGTH_SIM1_G_CU = {
        { R.drawable.stat_sys_signal_0_sim1_g,
          R.drawable.stat_sys_signal_1_sim1_g,
          R.drawable.stat_sys_signal_2_sim1_g,
          R.drawable.stat_sys_signal_3_sim1_g,
          R.drawable.stat_sys_signal_4_sim1_g },
        { R.drawable.stat_sys_signal_0_fully_sim1_g,
          R.drawable.stat_sys_signal_1_fully_sim1_g,
          R.drawable.stat_sys_signal_2_fully_sim1_g,
          R.drawable.stat_sys_signal_3_fully_sim1_g,
          R.drawable.stat_sys_signal_4_fully_sim1_g }
    };

    private static final int[][] TELEPHONY_SIGNAL_STRENGTH_SIM2_G_CU = {
        { R.drawable.stat_sys_signal_0_sim2_g,
          R.drawable.stat_sys_signal_1_sim2_g,
          R.drawable.stat_sys_signal_2_sim2_g,
          R.drawable.stat_sys_signal_3_sim2_g,
          R.drawable.stat_sys_signal_4_sim2_g },
        { R.drawable.stat_sys_signal_0_fully_sim2_g,
          R.drawable.stat_sys_signal_1_fully_sim2_g,
          R.drawable.stat_sys_signal_2_fully_sim2_g,
          R.drawable.stat_sys_signal_3_fully_sim2_g,
          R.drawable.stat_sys_signal_4_fully_sim2_g }
    };

    private static final int[][] TELEPHONY_SIGNAL_STRENGTH_ROAMING_SIM1_G_CU = {
        { R.drawable.stat_sys_r_signal_0_sim1_g,
          R.drawable.stat_sys_r_signal_1_sim1_g,
          R.drawable.stat_sys_r_signal_2_sim1_g,
          R.drawable.stat_sys_r_signal_3_sim1_g,
          R.drawable.stat_sys_r_signal_4_sim1_g },
        { R.drawable.stat_sys_r_signal_0_fully_sim1_g,
          R.drawable.stat_sys_r_signal_1_fully_sim1_g,
          R.drawable.stat_sys_r_signal_2_fully_sim1_g,
          R.drawable.stat_sys_r_signal_3_fully_sim1_g,
          R.drawable.stat_sys_r_signal_4_fully_sim1_g }
    };

    private static final int[][] TELEPHONY_SIGNAL_STRENGTH_ROAMING_SIM2_G_CU = {
        { R.drawable.stat_sys_r_signal_0_sim2_g,
          R.drawable.stat_sys_r_signal_1_sim2_g,
          R.drawable.stat_sys_r_signal_2_sim2_g,
          R.drawable.stat_sys_r_signal_3_sim2_g,
          R.drawable.stat_sys_r_signal_4_sim2_g },
        { R.drawable.stat_sys_r_signal_0_fully_sim2_g,
          R.drawable.stat_sys_r_signal_1_fully_sim2_g,
          R.drawable.stat_sys_r_signal_2_fully_sim2_g,
          R.drawable.stat_sys_r_signal_3_fully_sim2_g,
          R.drawable.stat_sys_r_signal_4_fully_sim2_g }
    };

    private static final int[][] TELEPHONY_SIGNAL_STRENGTH_SIM1_3G_CU = {
        { R.drawable.stat_sys_signal_0_sim1_3g,
          R.drawable.stat_sys_signal_1_sim1_3g,
          R.drawable.stat_sys_signal_2_sim1_3g,
          R.drawable.stat_sys_signal_3_sim1_3g,
          R.drawable.stat_sys_signal_4_sim1_3g },
        { R.drawable.stat_sys_signal_0_fully_sim1_3g,
          R.drawable.stat_sys_signal_1_fully_sim1_3g,
          R.drawable.stat_sys_signal_2_fully_sim1_3g,
          R.drawable.stat_sys_signal_3_fully_sim1_3g,
          R.drawable.stat_sys_signal_4_fully_sim1_3g }
    };

    private static final int[][] TELEPHONY_SIGNAL_STRENGTH_SIM2_3G_CU = {
        { R.drawable.stat_sys_signal_0_sim2_3g,
          R.drawable.stat_sys_signal_1_sim2_3g,
          R.drawable.stat_sys_signal_2_sim2_3g,
          R.drawable.stat_sys_signal_3_sim2_3g,
          R.drawable.stat_sys_signal_4_sim2_3g },
        { R.drawable.stat_sys_signal_0_fully_sim2_3g,
          R.drawable.stat_sys_signal_1_fully_sim2_3g,
          R.drawable.stat_sys_signal_2_fully_sim2_3g,
          R.drawable.stat_sys_signal_3_fully_sim2_3g,
          R.drawable.stat_sys_signal_4_fully_sim2_3g }
    };

    private static final int[][] TELEPHONY_SIGNAL_STRENGTH_ROAMING_SIM1_3G_CU = {
        { R.drawable.stat_sys_r_signal_0_sim1_3g,
          R.drawable.stat_sys_r_signal_1_sim1_3g,
          R.drawable.stat_sys_r_signal_2_sim1_3g,
          R.drawable.stat_sys_r_signal_3_sim1_3g,
          R.drawable.stat_sys_r_signal_4_sim1_3g },
        { R.drawable.stat_sys_r_signal_0_fully_sim1_3g,
          R.drawable.stat_sys_r_signal_1_fully_sim1_3g,
          R.drawable.stat_sys_r_signal_2_fully_sim1_3g,
          R.drawable.stat_sys_r_signal_3_fully_sim1_3g,
          R.drawable.stat_sys_r_signal_4_fully_sim1_3g }
    };

    private static final int[][] TELEPHONY_SIGNAL_STRENGTH_ROAMING_SIM2_3G_CU = {
        { R.drawable.stat_sys_r_signal_0_sim2_3g,
          R.drawable.stat_sys_r_signal_1_sim2_3g,
          R.drawable.stat_sys_r_signal_2_sim2_3g,
          R.drawable.stat_sys_r_signal_3_sim2_3g,
          R.drawable.stat_sys_r_signal_4_sim2_3g },
        { R.drawable.stat_sys_r_signal_0_fully_sim2_3g,
          R.drawable.stat_sys_r_signal_1_fully_sim2_3g,
          R.drawable.stat_sys_r_signal_2_fully_sim2_3g,
          R.drawable.stat_sys_r_signal_3_fully_sim2_3g,
          R.drawable.stat_sys_r_signal_4_fully_sim2_3g }
    };

    private static final int[][] TELEPHONY_SIGNAL_STRENGTH_SIM1_H_CU = {
        { R.drawable.stat_sys_signal_0_sim1_h,
          R.drawable.stat_sys_signal_1_sim1_h,
          R.drawable.stat_sys_signal_2_sim1_h,
          R.drawable.stat_sys_signal_3_sim1_h,
          R.drawable.stat_sys_signal_4_sim1_h },
        { R.drawable.stat_sys_signal_0_fully_sim1_h,
          R.drawable.stat_sys_signal_1_fully_sim1_h,
          R.drawable.stat_sys_signal_2_fully_sim1_h,
          R.drawable.stat_sys_signal_3_fully_sim1_h,
          R.drawable.stat_sys_signal_4_fully_sim1_h }
    };

    private static final int[][] TELEPHONY_SIGNAL_STRENGTH_SIM2_H_CU = {
        { R.drawable.stat_sys_signal_0_sim2_h,
          R.drawable.stat_sys_signal_1_sim2_h,
          R.drawable.stat_sys_signal_2_sim2_h,
          R.drawable.stat_sys_signal_3_sim2_h,
          R.drawable.stat_sys_signal_4_sim2_h },
        { R.drawable.stat_sys_signal_0_fully_sim2_h,
          R.drawable.stat_sys_signal_1_fully_sim2_h,
          R.drawable.stat_sys_signal_2_fully_sim2_h,
          R.drawable.stat_sys_signal_3_fully_sim2_h,
          R.drawable.stat_sys_signal_4_fully_sim2_h }
    };

    private static final int[][] TELEPHONY_SIGNAL_STRENGTH_ROAMING_SIM1_H_CU = {
        { R.drawable.stat_sys_r_signal_0_sim1_h,
          R.drawable.stat_sys_r_signal_1_sim1_h,
          R.drawable.stat_sys_r_signal_2_sim1_h,
          R.drawable.stat_sys_r_signal_3_sim1_h,
          R.drawable.stat_sys_r_signal_4_sim1_h },
        { R.drawable.stat_sys_r_signal_0_fully_sim1_h,
          R.drawable.stat_sys_r_signal_1_fully_sim1_h,
          R.drawable.stat_sys_r_signal_2_fully_sim1_h,
          R.drawable.stat_sys_r_signal_3_fully_sim1_h,
          R.drawable.stat_sys_r_signal_4_fully_sim1_h }
    };

    private static final int[][] TELEPHONY_SIGNAL_STRENGTH_ROAMING_SIM2_H_CU = {
        { R.drawable.stat_sys_r_signal_0_sim2_h,
          R.drawable.stat_sys_r_signal_1_sim2_h,
          R.drawable.stat_sys_r_signal_2_sim2_h,
          R.drawable.stat_sys_r_signal_3_sim2_h,
          R.drawable.stat_sys_r_signal_4_sim2_h },
        { R.drawable.stat_sys_r_signal_0_fully_sim2_h,
          R.drawable.stat_sys_r_signal_1_fully_sim2_h,
          R.drawable.stat_sys_r_signal_2_fully_sim2_h,
          R.drawable.stat_sys_r_signal_3_fully_sim2_h,
          R.drawable.stat_sys_r_signal_4_fully_sim2_h }
    };

    static final int[][] DATA_SIGNAL_STRENGTH = TELEPHONY_SIGNAL_STRENGTH;

    public static final int[][][] MULTI_SIGNAL_IMAGES_G = {TELEPHONY_SIGNAL_STRENGTH_SIM1_G_CU,TELEPHONY_SIGNAL_STRENGTH_SIM2_G_CU};
    public static final int[][][] MULTI_SIGNAL_IMAGES_R_G = {TELEPHONY_SIGNAL_STRENGTH_ROAMING_SIM1_G_CU,TELEPHONY_SIGNAL_STRENGTH_ROAMING_SIM2_G_CU};
    public static final int[][][] MULTI_SIGNAL_IMAGES_3G = {TELEPHONY_SIGNAL_STRENGTH_SIM1_3G_CU,TELEPHONY_SIGNAL_STRENGTH_SIM2_3G_CU};
    public static final int[][][] MULTI_SIGNAL_IMAGES_R_3G = {TELEPHONY_SIGNAL_STRENGTH_ROAMING_SIM1_3G_CU,TELEPHONY_SIGNAL_STRENGTH_ROAMING_SIM2_3G_CU};
    public static final int[][][] MULTI_SIGNAL_IMAGES_H = {TELEPHONY_SIGNAL_STRENGTH_SIM1_H_CU,TELEPHONY_SIGNAL_STRENGTH_SIM2_H_CU};
    public static final int[][][] MULTI_SIGNAL_IMAGES_R_H = {TELEPHONY_SIGNAL_STRENGTH_ROAMING_SIM1_H_CU,TELEPHONY_SIGNAL_STRENGTH_ROAMING_SIM2_H_CU};

    //***** Data connection icons

    //GSM/UMTS
    static final int[][] DATA_G = {
            { R.drawable.stat_sys_data_connected_g,
              R.drawable.stat_sys_data_connected_g,
              R.drawable.stat_sys_data_connected_g,
              R.drawable.stat_sys_data_connected_g },
            { R.drawable.stat_sys_data_fully_connected_g,
              R.drawable.stat_sys_data_fully_connected_g,
              R.drawable.stat_sys_data_fully_connected_g,
              R.drawable.stat_sys_data_fully_connected_g }
        };

    static final int[][] DATA_3G = {
            { R.drawable.stat_sys_data_connected_3g,
              R.drawable.stat_sys_data_connected_3g,
              R.drawable.stat_sys_data_connected_3g,
              R.drawable.stat_sys_data_connected_3g },
            { R.drawable.stat_sys_data_fully_connected_3g,
              R.drawable.stat_sys_data_fully_connected_3g,
              R.drawable.stat_sys_data_fully_connected_3g,
              R.drawable.stat_sys_data_fully_connected_3g }
        };

    static final int[][] DATA_E = {
            { R.drawable.stat_sys_data_connected_e,
              R.drawable.stat_sys_data_connected_e,
              R.drawable.stat_sys_data_connected_e,
              R.drawable.stat_sys_data_connected_e },
            { R.drawable.stat_sys_data_fully_connected_e,
              R.drawable.stat_sys_data_fully_connected_e,
              R.drawable.stat_sys_data_fully_connected_e,
              R.drawable.stat_sys_data_fully_connected_e }
        };

    //3.5G
    static final int[][] DATA_H = {
            { R.drawable.stat_sys_data_connected_h,
              R.drawable.stat_sys_data_connected_h,
              R.drawable.stat_sys_data_connected_h,
              R.drawable.stat_sys_data_connected_h },
            { R.drawable.stat_sys_data_fully_connected_h,
              R.drawable.stat_sys_data_fully_connected_h,
              R.drawable.stat_sys_data_fully_connected_h,
              R.drawable.stat_sys_data_fully_connected_h }
    };

    //CDMA
    // Use 3G icons for EVDO data and 1x icons for 1XRTT data
    static final int[][] DATA_1X = {
            { R.drawable.stat_sys_data_connected_1x,
              R.drawable.stat_sys_data_connected_1x,
              R.drawable.stat_sys_data_connected_1x,
              R.drawable.stat_sys_data_connected_1x },
            { R.drawable.stat_sys_data_fully_connected_1x,
              R.drawable.stat_sys_data_fully_connected_1x,
              R.drawable.stat_sys_data_fully_connected_1x,
              R.drawable.stat_sys_data_fully_connected_1x }
            };

    // LTE and eHRPD
    static final int[][] DATA_4G = {
            { R.drawable.stat_sys_data_connected_4g,
              R.drawable.stat_sys_data_connected_4g,
              R.drawable.stat_sys_data_connected_4g,
              R.drawable.stat_sys_data_connected_4g },
            { R.drawable.stat_sys_data_fully_connected_4g,
              R.drawable.stat_sys_data_fully_connected_4g,
              R.drawable.stat_sys_data_fully_connected_4g,
              R.drawable.stat_sys_data_fully_connected_4g }
        };

    public static final int SIGNAL_LEVEL_0 = 0;
    public static final int SIGNAL_LEVEL_1 = 1;
    public static final int SIGNAL_LEVEL_2 = 2;
    public static final int SIGNAL_LEVEL_3 = 3;
    public static final int SIGNAL_LEVEL_4 = 4;

    public static final int DATA_CONNECTIVITY_NOT_CONNECTED = 0;
    public static final int DATA_CONNECTIVITY_CONNECTED     = 1;
}

