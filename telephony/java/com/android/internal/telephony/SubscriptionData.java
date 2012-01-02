/*
 * Copyright (c) 2010-2011, Code Aurora Forum. All rights reserved.
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

import java.util.Arrays;

/**
 * Class holding a list of subscriptions
 */
public class SubscriptionData {
    public Subscription [] subscription;

    public SubscriptionData(int numSub) {
        subscription = new Subscription[numSub];
        for (int i = 0; i < numSub; i++) {
            subscription[i] = new Subscription();
        }
    }

    public int getLength() {
        if (subscription != null) {
            return subscription.length;
        }
        return 0;
    }

    public SubscriptionData copyFrom(SubscriptionData from) {
        if (from != null) {
            subscription = new Subscription[from.getLength()];
            for (int i = 0; i < from.getLength(); i++) {
                subscription[i] = new Subscription();
                subscription[i].copyFrom(from.subscription[i]);
            }
        }
        return this;
    }

    public String getIccId() {
        if (subscription.length > 0 && subscription[0] != null) {
            return subscription[0].iccId;
        }
        return null;
    }

    public boolean hasSubscription(Subscription sub){
        for (int i = 0; i < subscription.length; i++) {
            if (subscription[i].isSame(sub)) {
                return true;
            }
        }
        return false;
    }

    public Subscription getSubscription(Subscription sub){
        for (int i = 0; i < subscription.length; i++) {
            if (subscription[i].isSame(sub)) {
                return subscription[i];
            }
        }
        return null;
    }

    public String toString() {
        return Arrays.toString(subscription);
    }
}
