/*
 * Copyright (C) 2011-2012 Code Aurora Forum. All rights reserved.
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

package android.net;

import android.os.Parcelable;
import android.os.Parcel;
import android.util.Log;

import java.util.HashMap;
import java.util.Map;

/** @hide */
public class ExtraLinkCapabilities extends LinkCapabilities {
    @Override
    public void put (int key, String value) {
        mCapabilities.put(key, value);
    }

    public void putAll (Map cap) {
        mCapabilities.putAll(cap);
    }

    public void remove (int key) {
        mCapabilities.remove(key);
    }
}
