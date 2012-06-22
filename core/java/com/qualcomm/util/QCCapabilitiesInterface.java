/*
** Copyright (C) 2012 The Android Open Source Project
** Copyright (c) 2012 Code Aurora Forum. All rights reserved.
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
**
**     http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
** See the License for the specific language governing permissions and
** limitations under the License.
*/

package com.qualcomm.snapdragon.util;

import android.os.Bundle;

public interface QCCapabilitiesInterface
{
    // The configuration values key will be used to populate a Bundle of
    // key-value pairs // for any config items.
    // e.g.("max-num-snaps-per-shutter", 10). This list of
    // key-value pairs will be used to provide any values that are specific to a
    // chipset and or software and are used to provide information for a specific
    // device in relation to the technology area. In the current example
    // max-num-snaps-per-shutter may query the hardware for available memory and provide knowledge of how
    // many burst capture frames at a time would be the theoretical limit as a result.
    public static final String KEY_CONFIGURATION_VALUES    = "key_configuration_values";   // Maps to Bundle<String,Object>

    // The supported functionality key will be used to specify a list of Strings
    // specifying the hardware supported functionalities exposed by the class
    // implementing this interface. For e.g ("HW_SURROUND_SOUND_RECORDING_5_1",
    // "HW_NS_IN_VOICE_RECOGNITION_SOURCE", etc) This will be used to provide
    // information on functionality that is optimized under the hood for
    // existing Android APIs where more detailed information is not otherwise available.
    public static final String KEY_SUPPORTED_FUNCTIONALITY = "key_supported_functionality"; // Maps to List<String>

    // The active method names key will be used to specify the names of all the
    // active method names exposed by the class implementing this interface. This set
    // of method names will not include signature information and each method name
    // should be unique to provide expected behavior. Active method names will be a
    // list of methods on the class called that are known to be enabled and provide a
    // valid result.
    public static final String KEY_ACTIVE_METHOD_NAMES     = "key_active_method_names";    // Maps to List<String>

    // The constant field values key will be used to specify a Bundle of
    // key-value pairs // of all the public static String names exposed by the
    // class implementing this interface. For e.g. (KEY_NUM_SNAPS_PER_SHUTTER, "num-snaps-per-shutter").
    // This is mainly used to provide String and int values for Qualcomm-added values
    // that are passed into existing Android APIs. For example,
    // KEY_NUM_SNAPS_PER_SHUTTER
    // maps to a String key used by an instance of Camera.Parameters through the
    // function
    // set(String key, String value), while KEY_SENSOR_TYPE_TAP could map to an
    // integer
    // passed in to SensorManager’s getDefaultSensor(int type) method.
    public static final String KEY_CONSTANT_FIELD_VALUES   = "key_constant_field_values";  // Maps
                                                                                            // to
                                                                                            // Bundle<String,Object>

    // Function to get the hardware capabilities from the framework layer as a
    // Bundle.
    // The Bundle should have no more than four key entries (specified above)
    // which
    // map to the Bundle or ArrayList of entries as detailed above each key.
    public Bundle getCapabilities();
}