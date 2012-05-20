/*
 * Copyright (c) 2012 Code Aurora Forum. All rights reserved.
 * Copyright (C) 2008 The Android Open Source Project
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

package android.hardware.gesturedev;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.StringTokenizer;

import android.util.Log;

/**
 * Gesture service settings.
 * 
 * <p>
 * To make camera parameters take effect, applications have to call
 * 
 * @hide
 * 
 */
public class GestureParameters {
    // Parameter keys to communicate with the gesture driver.
    private static final String KEY_GESTURE_MODE = "gs-ctrl-mode";
    private static final String KEY_GESTURE_SUB_MODE = "gs-ctrl-submode";
    private static final String KEY_GESTURE_ENABLE_TOUCH = "gs-ctrl-enable-touch";
    private static final String KEY_GESTURE_ORIENTATION = "gs-ctrl-orientation";
    private static final String KEY_GESTURE_COORDINATE_MODE = "gs-ctrl-coord-mode";
    private static final String KEY_GESTURE_CURSOR_TYPE = "gs-ctrl-cursor-type";
    private static final String KEY_GESTURE_CLICK_MODE = "gs-ctrl-click-mode";
    private static final String KEY_GESTURE_CAMERA_INPUT = "gs-ctrl-camera-input";
    private static final String KEY_GESTURE_EXTENDED_CONFIG = "gs-ctrl-extended-config";
    private static final String KEY_GESTURE_COORDINATE_RANGE = "gs-ctrl-coord-range";

    // Values for gesture mode
    public static final int GESTURE_MODE_OFF = 0;
    public static final int GESTURE_MODE_NEAR_SWIPE = 101;
    public static final int GESTURE_MODE_HAND_DETECT = 102;
    public static final int GESTURE_MODE_ENGAGEMENT = 103;
    public static final int GESTURE_MODE_ENGAGEMENT_SWIPE = 104;
    public static final int GESTURE_MODE_HAND_TRACKING = 105;

    // Values for gesture sub mode
    public static final int GESTURE_SUB_MODE_NONE = 0;

    // Values for gesture orientation
    public static final int GESTURE_ORIENTATION_0 = 0;
    public static final int GESTURE_ORIENTATION_90 = 1;
    public static final int GESTURE_ORIENTATION_180 = 2;
    public static final int GESTURE_ORIENTATION_270 = 3;

    // Values for gesture coordinate mode
    public static final int GESTURE_COORDINATE_MODE_NORMALIZED = 0;
    public static final int GESTURE_COORDINATE_MODE_SCREEN = 1;

    // Values for gesture click mode
    public static final int GESTURE_CLICK_MODE_NONE = 0;
    public static final int GESTURE_CLICK_MODE_HOVER = 1;
    public static final int GESTURE_CLICK_MODE_POSE = 2;

    // Values for gesture cursor type
    public static final int GESTURE_CURSOR_TYPE_OFF = 0;
    public static final int GESTURE_CURSOR_TYPE_CROSS = 1;

    private static final String TAG = "GestureParameter";
    private HashMap<String, String> mMap;

    public GestureParameters() {
        mMap = new HashMap<String, String>();
    }

    /**
     * Creates a single string with all the parameters set in this Parameters
     * object.
     * <p>
     * The {@link #unflatten(String)} method does the reverse.
     * </p>
     * 
     * @return a String with all values from this Parameters object, in
     *         semi-colon delimited key-value pairs
     */
    public String flatten() {
        StringBuilder flattened = new StringBuilder();
        if (mMap.size() > 0) {
            for (String k : mMap.keySet()) {
                flattened.append(k);
                flattened.append("=");
                flattened.append(mMap.get(k));
                flattened.append(";");
            }
            // chop off the extra semicolon at the end
            flattened.deleteCharAt(flattened.length() - 1);
        }
        return flattened.toString();
    }

    /**
     * Takes a flattened string of parameters and adds each one to this
     * Parameters object.
     * <p>
     * The {@link #flatten()} method does the reverse.
     * </p>
     * 
     * @param flattened
     *            a String of parameters (key-value paired) that are semi-colon
     *            delimited
     */
    public void unflatten(String flattened) {
        mMap.clear();

        StringTokenizer tokenizer = new StringTokenizer(flattened, ";");
        while (tokenizer.hasMoreElements()) {
            String kv = tokenizer.nextToken();
            int pos = kv.indexOf('=');
            if (pos == -1) {
                continue;
            }
            String k = kv.substring(0, pos);
            String v = kv.substring(pos + 1);
            mMap.put(k, v);
        }
    }

    /**
     * Remove a parameter by its key.
     * 
     * @param key
     *            the key name for the parameter
     */
    public void remove(String key) {
        mMap.remove(key);
    }

    /**
     * Sets a String parameter.
     * 
     * @param key
     *            the key name for the parameter
     * @param value
     *            the String value of the parameter. key and value string cannot
     *            contain predefined characters ('=' and ';') that dedicate to
     *            parameter format for key=vlaue;key=value
     */
    public void set(String key, String value) {
        if (key.indexOf('=') != -1 || key.indexOf(';') != -1) {
            Log.e(TAG, "Key \"" + key
                    + "\" contains invalid character (= or ;)");
            return;
        }
        if (value.indexOf('=') != -1 || value.indexOf(';') != -1) {
            Log.e(TAG, "Value \"" + value
                    + "\" contains invalid character (= or ;)");
            return;
        }

        mMap.put(key, value);
    }

    /**
     * Sets an integer parameter.
     * 
     * @param key
     *            the key name for the parameter
     * @param value
     *            the int value of the parameter
     */
    public void set(String key, int value) {
        mMap.put(key, Integer.toString(value));
    }

    /**
     * Returns the value of a String parameter.
     * 
     * @param key
     *            the key name for the parameter
     * @return the String value of the parameter
     */
    public String get(String key) {
        return mMap.get(key);
    }

    /**
     * Returns the value of an integer parameter.
     * 
     * @param key
     *            the key name for the parameter
     * @return the int value of the parameter
     */
    public int getInt(String key) {
        return Integer.parseInt(mMap.get(key));
    }

    /**
     * Gets the current Gesture Mode.
     * 
     * @return int value of Gesture Mode.
     * 
     * @see #GESTURE_MODE_OFF
     * @see #GESTURE_MODE_NEAR_SWIPE
     * @see #GESTURE_MODE_HAND_DETECT
     * @see #GESTURE_MODE_ENGAGEMENT
     * @see #GESTURE_MODE_ENGAGEMENT_SWIPE
     * @see #GESTURE_MODE_HAMD_TRACKING
     * @see #setGestureMode
     * 
     */
    public int getGestureMode() {
        return getInt(KEY_GESTURE_MODE);
    }

    /**
     * Sets the current Gesture Mode.
     * 
     * @see #GESTURE_MODE_OFF
     * @see #GESTURE_MODE_NEAR_SWIPE
     * @see #GESTURE_MODE_HAND_DETECT
     * @see #GESTURE_MODE_ENGAGEMENT
     * @see #GESTURE_MODE_ENGAGEMENT_SWIPE
     * @see #GESTURE_MODE_HAMD_TRACKING
     * @see #getGestureMode
     */
    public void setGestureMode(int gsMode) {
        set(KEY_GESTURE_MODE, gsMode);
    }

    /**
     * Gets the current Gesture Sub Mode. There are currently no submodes.
     * 
     * @return int value of Gesture Sub Mode.
     * 
     * @see #GESTURE_SUB_MODE_NONE
     * @see #setGestureSubMode
     */
    public int getGestureSubMode() {
        return getInt(KEY_GESTURE_SUB_MODE);
    }

    /**
     * Sets the current Gesture Sub Mode. There are currently no submodes.
     * 
     * @see #GESTURE_SUB_MODE_NONE
     * @see #getGestureSubMode
     */
    public void setGestureSubMode(int gsSubmode) {
        set(KEY_GESTURE_SUB_MODE, gsSubmode);
    }

    /**
     * Sets the flag if touch is enabled.
     * 
     * @see #getTouchEnabled
     */
    public void setTouchEnabled(boolean enabled) {
        int val = enabled ? 1 : 0;
        set(KEY_GESTURE_ENABLE_TOUCH, val);
    }

    /**
     * Gets the flag if touch is enabled.
     * 
     * @return true/false.
     * 
     * @see #setTouchEnabled
     */
    public boolean getTouchEnabled() {
        return getBoolean(KEY_GESTURE_ENABLE_TOUCH, false);
    }

    /**
     * Gets the current Gesture Orientation.
     * 
     * @return int value of Gesture Orientation.
     * 
     * @see #GESTURE_ORIENTATION_0
     * @see #GESTURE_ORIENTATION_90
     * @see #GESTURE_ORIENTATION_180
     * @see #GESTURE_ORIENTATION_270
     * @see #setOrientation
     */
    public int getOrientation() {
        return getInt(KEY_GESTURE_ORIENTATION);
    }

    /**
     * Sets the current Gesture Orientation.
     * 
     * @see #GESTURE_ORIENTATION_0
     * @see #GESTURE_ORIENTATION_90
     * @see #GESTURE_ORIENTATION_180
     * @see #GESTURE_ORIENTATION_270
     * @see #getOrientation
     */
    public void setOrientation(int gsOrientation) {
        set(KEY_GESTURE_ORIENTATION, gsOrientation);
    }

    /**
     * Gets the current Coordinate Mode.
     * 
     * @return int value of coordinate Mode.
     * 
     * @see #GESTURE_COORDINATE_MODE_NORMALIZED
     * @see #GESTURE_COORDINATE_MODE_SCREEN
     * @see #setCoordinateMode
     */
    public int getCoordinateMode() {
        return getInt(KEY_GESTURE_COORDINATE_MODE);
    }

    /**
     * Sets the current Coordinate Mode.
     * 
     * @see #GESTURE_COORDINATE_MODE_NORMALIZED
     * @see #GESTURE_COORDINATE_MODE_SCREEN
     * @see #getCoordinateMode
     */
    public void setCoordinateMode(int coordMode) {
        set(KEY_GESTURE_COORDINATE_MODE, coordMode);
    }

    /**
     * Gets the current Cursor Type.
     * 
     * @return int value of cursor type.
     * 
     * @see #GESTURE_CURSOR_TYPE_OFF
     * @see #GESTURE_CURSOR_TYPE_CROSS
     * @see #setCursorType
     */
    public int getCursorType() {
        return getInt(KEY_GESTURE_CURSOR_TYPE);
    }

    /**
     * Sets the current Cursor Type.
     * 
     * @see #GESTURE_CURSOR_TYPE_OFF
     * @see #GESTURE_CURSOR_TYPE_CROSS
     * @see #getCursorType
     */
    public void setCursorType(int cursor) {
        set(KEY_GESTURE_CURSOR_TYPE, cursor);
    }

    /**
     * Gets the current Click Mode.
     * 
     * @return int value of click mode.
     * 
     * @see #GESTURE_CLICK_MODE_NONE
     * @see #GESTURE_CLICK_MODE_HOVER
     * @see #GESTURE_CLICK_MODE_POSE
     * @see #setClickMode
     */
    public int getClickMode() {
        return getInt(KEY_GESTURE_CLICK_MODE);
    }

    /**
     * Sets the current Click Mode.
     * 
     * @see #GESTURE_CLICK_MODE_NONE
     * @see #GESTURE_CLICK_MODE_HOVER
     * @see #GESTURE_CLICK_MODE_POSE
     * @see #getClickMode
     */
    public void setClickMode(int clickMode) {
        set(KEY_GESTURE_CLICK_MODE, clickMode);
    }

    /**
     * Sets the camera input device id.
     */
    public void setCameraInput(int camId) {
        set(KEY_GESTURE_CAMERA_INPUT, camId);
    }

    /**
     * Gets the current camera input device id.
     * 
     * @return int value of camera id.
     */
    public int getCameraInput() {
        return getInt(KEY_GESTURE_CAMERA_INPUT);
    }

    /**
     * Sets the extended config field.
     *
     * @param ext   String of extended configuration, cannot contain 
     *              '=' and ';' that dedicate to parameter key/value
     *              format
     * @return null
     */
    public void setExtendedConfig(String ext) {
        set(KEY_GESTURE_EXTENDED_CONFIG, ext);
    }

    /**
     * Gets the extended config field.
     *
     * @return String value of extended config.
     */
    public String getExtendedConfig() {
        return get(KEY_GESTURE_EXTENDED_CONFIG);
    }

    /**
     * Gets the coordinate ranges. Each range
     * contains a minimum and maximum coordinate.
     *
     * @return a list of coordinate ranges. This method returns a
     *         list with three elements. Every element is a float
     *         array of two values - minimum and maximum . The list
     *         is in the order of x, y, z axis.
     */
    public CoordinateRange getCoordinateRange() {
        String str = get(KEY_GESTURE_COORDINATE_RANGE);
        return splitRange(str);
    }

    /**
     * Sets the coordinate ranges.
     *
     * @return null
     */
    public void setCoordinateRange(CoordinateRange range) {
        if (range == null) {
            Log.e(TAG, "Invalid input for coordinate range");
            return;
        }
        String str = "";
        str += "(" + Float.toString(range.x_min) + "," + Float.toString(range.x_max) + "),";
        str += "(" + Float.toString(range.y_min) + "," + Float.toString(range.y_max) + "),";
        str += "(" + Float.toString(range.z_min) + "," + Float.toString(range.z_max) + ")";
        set(KEY_GESTURE_COORDINATE_RANGE, str);
    }

    // Splits a comma delimited string to an ArrayList of String.
    // Return null if the passing string is null or the size is 0.
    private ArrayList<String> split(String str) {
        if (str == null)
            return null;

        // Use StringTokenizer because it is faster than split.
        StringTokenizer tokenizer = new StringTokenizer(str, ",");
        ArrayList<String> substrings = new ArrayList<String>();
        while (tokenizer.hasMoreElements()) {
            substrings.add(tokenizer.nextToken());
        }
        return substrings;
    }

    // Splits a comma delimited string to an ArrayList of Integer.
    // Return null if the passing string is null or the size is 0.
    private ArrayList<Integer> splitInt(String str) {
        if (str == null)
            return null;

        StringTokenizer tokenizer = new StringTokenizer(str, ",");
        ArrayList<Integer> substrings = new ArrayList<Integer>();
        while (tokenizer.hasMoreElements()) {
            String token = tokenizer.nextToken();
            substrings.add(Integer.parseInt(token));
        }
        if (substrings.size() == 0)
            return null;
        return substrings;
    }

    private void splitInt(String str, int[] output) {
        if (str == null)
            return;

        StringTokenizer tokenizer = new StringTokenizer(str, ",");
        int index = 0;
        while (tokenizer.hasMoreElements()) {
            String token = tokenizer.nextToken();
            output[index++] = Integer.parseInt(token);
        }
    }

    // Splits a comma delimited string to an ArrayList of Float.
    private void splitFloat(String str, float[] output) {
        if (str == null)
            return;

        StringTokenizer tokenizer = new StringTokenizer(str, ",");
        int index = 0;
        while (tokenizer.hasMoreElements()) {
            String token = tokenizer.nextToken();
            output[index++] = Float.parseFloat(token);
        }
    }

    // Returns the value of a float parameter.
    private float getFloat(String key, float defaultValue) {
        try {
            return Float.parseFloat(mMap.get(key));
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    // Returns the value of a integer parameter.
    private int getInt(String key, int defaultValue) {
        try {
            return Integer.parseInt(mMap.get(key));
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    // Returns the value of a boolean parameter.
    private boolean getBoolean(String key, boolean defaultValue) {
        try {
            int val = Integer.parseInt(mMap.get(key));
            if (val == 0) {
                return false;
            } else {
                return true;
            }
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    // Splits a comma delimited string.
    // Example string: "(1.0000,2.6623),(10.000,30.000)". Return null if the
    // passing string is null.
    private CoordinateRange splitRange(String str) {
        if (str == null || str.charAt(0) != '('
                || str.charAt(str.length() - 1) != ')') {
            Log.e(TAG, "Invalid range list string=" + str);
            return null;
        }

        CoordinateRange coordRange = new CoordinateRange();
        int endIndex, fromIndex = 1;
        int axisIndex = 0;
        do {
            float[] range = new float[2];
            endIndex = str.indexOf("),(", fromIndex);
            if (endIndex == -1)
                endIndex = str.length() - 1;
            splitFloat(str.substring(fromIndex, endIndex), range);
            if (axisIndex == 0) {
                coordRange.x_min = range[0];
                coordRange.x_max = range[1];
            } else if (axisIndex == 1) {
                coordRange.y_min = range[0];
                coordRange.y_max = range[1];
            } else {
                coordRange.z_min = range[0];
                coordRange.z_max = range[1];
            }
            axisIndex++;
            fromIndex = endIndex + 3;
        } while (endIndex != str.length() - 1);

        if (axisIndex < 3)
            return null;
        return coordRange;
    }

    public static class CoordinateRange {
        public float x_min = 0;
        public float x_max = 0;
        public float y_min = 0;
        public float y_max = 0;
        public float z_min = 0;
        public float z_max = 0;

        public CoordinateRange() {
        }

        public CoordinateRange(
                    float x_min, float x_max,
                    float y_min, float y_max,
                    float z_min, float z_max) {
            this.x_min = x_min;
            this.x_max = x_max;
            this.y_min = y_min;
            this.y_max = y_max;
            this.z_min = z_min;
            this.z_max = z_max;
        }
    }
}
