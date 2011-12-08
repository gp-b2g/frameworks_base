/*
 * Copyright (C) 2011 The Android Open Source Project
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

/**
 * See also RIL_SimRefresh in include/telephony/ril.h
 *
 * {@hide}
 */

public class IccRefreshResponse {

    public enum Result {
        ICC_FILE_UPDATE,                       /* Single file updated */
        ICC_INIT,
        ICC_RESET
    }

    public Result          refreshResult;      /* Sim Refresh result */
    public int             efId;               /* EFID */
    public String          aidPtr;             /* null terminated string, e.g.,
                                                  from 0xA0, 0x00 -> 0x41,
                                                  0x30, 0x30, 0x30 */
                                               /* Example: a0000000871002f310ffff89080000ff */

    public static Result
        refreshResultFromRIL(int refreshResult) throws ATParseEx {
        switch(refreshResult) {

            case 0: return Result.ICC_FILE_UPDATE;
            case 1: return Result.ICC_INIT;
            case 2: return Result.ICC_RESET;
            default:
                throw new ATParseEx("Sim Refresh response is Unknown " + refreshResult);
        }
    }

    public String toString() {
        return "{" + refreshResult + ", " + aidPtr +", " + efId + "}";
    }
}
