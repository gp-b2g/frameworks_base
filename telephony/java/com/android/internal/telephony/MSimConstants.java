/*
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

package com.android.internal.telephony;

public class MSimConstants {
    public static final int RIL_MAX_CARDS        = 2;

    public static final int NUM_SUBSCRIPTIONS    = 2;

    public static final int RIL_CARD_MAX_APPS    = 8;

    public static final int DEFAULT_SUBSCRIPTION = 0;

    public static final int DEFAULT_CARD_INDEX   = 0;

    public static final int MAX_PHONE_COUNT_DS   = 2;

    public static final String SUBSCRIPTION_KEY  = "subscription";

    public static final int SUB1 = 0;
    public static final int SUB2 = 1;

    public static final int EVENT_SUBSCRIPTION_ACTIVATED   = 500;
    public static final int EVENT_SUBSCRIPTION_DEACTIVATED = 501;

    public enum CardUnavailableReason {
        REASON_CARD_REMOVED,
        REASON_RADIO_UNAVAILABLE,
        REASON_SIM_REFRESH_RESET
    };
}
