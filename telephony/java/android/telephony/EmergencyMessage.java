/*
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

package android.telephony;

import android.os.Parcelable;

/**
 * Interface for Emergency message.
 *
 * {@hide}
 */
public interface EmergencyMessage extends Parcelable {
    enum Severity {
        EXTREME,
        SEVERE,
        UNDEFINED,
    };

    enum Urgency {
        IMMEDIATE,
        EXPECTED,
        UNDEFINED,
    }

    enum Certainty {
        OBSERVED,
        LIKELY,
        UNDEFINED,
    }

    enum Alerts {
        CMAS_PRESIDENTIAL,
        CMAS_EXTREME,
        CMAS_SEVERE,
        CMAS_AMBER,
        ETWS_EARTHQUAKE,
        ETWS_TSUNAMI,
        ETWS_EARTHQUAKE_AND_TSUNAMI
    }


    String getMessageBody();
    int getMessageIdentifier();
    Severity getSeverity();
    Urgency getUrgency();
    Certainty getCertainty();
    String getLanguageCode();
}