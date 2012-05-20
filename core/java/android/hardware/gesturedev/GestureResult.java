/* Copyright (c) 2012, Code Aurora Forum. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *     * Redistributions of source code must retain the above copyright
 *       notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above
 *       copyright notice, this list of conditions and the following
 *       disclaimer in the documentation and/or other materials provided
 *       with the distribution.
 *     * Neither the name of Code Aurora Forum, Inc. nor the names of its
 *       contributors may be used to endorse or promote products derived
 *       from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED "AS IS" AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NON-INFRINGEMENT
 * ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS
 * BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR
 * BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
 * OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN
 * IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *
 */

package android.hardware.gesturedev;

/**
 * Information about gesture processing result.
 * 
 * @see GestureDevice#GestureListener
 * @hide
 */
public class GestureResult {
    private static final String TAG = "GestureResult";

    public GestureResult() {
    }

    public static class GSVector {
        public float x = 0;
        public float y = 0;
        public float z = 0;
        public float error = 0; // radius of accuracy

        public GSVector() {
        }
    };

    /* result type */
    public static final int GESTURE_EVENT_RESULT_TYPE_NONE = 0;
    public static final int GESTURE_EVENT_RESULT_TYPE_DETECTION = 201;
    public static final int GESTURE_EVENT_RESULT_TYPE_ENGAGEMENT = 202;
    public static final int GESTURE_EVENT_RESULT_TYPE_TRACKING = 203;
    public static final int GESTURE_EVENT_RESULT_TYPE_SWIPE = 204;
    public static final int GESTURE_EVENT_RESULT_TYPE_MOUSE = 205;

    /* result subtype for detection and engagement */
    public static final int GESTURE_EVENT_RESULT_SUBTYPE_LEFT_OPEN_PALM = 301;
    public static final int GESTURE_EVENT_RESULT_SUBTYPE_RIGHT_OPEN_PALM = 302;
    public static final int GESTURE_EVENT_RESULT_SUBTYPE_LEFT_FIST = 303;
    public static final int GESTURE_EVENT_RESULT_SUBTYPE_RIGHT_FIST = 304;

    /* result subtype for tracking */
    public static final int GESTURE_EVENT_RESULT_SUBTYPE_NORMALIZED = 401;
    public static final int GESTURE_EVENT_RESULT_SUBTYPE_SCREEN = 402;

    /* result subtype for swipe */
    public static final int GESTURE_EVENT_RESULT_SUBTYPE_LEFT = 501;
    public static final int GESTURE_EVENT_RESULT_SUBTYPE_RIGHT = 502;
    public static final int GESTURE_EVENT_RESULT_SUBTYPE_UP = 503;
    public static final int GESTURE_EVENT_RESULT_SUBTYPE_DOWN = 504;

    /* result subtype for mouse */
    public static final int GESTURE_EVENT_RESULT_SUBTYPE_MOUSE_UP = 601;
    public static final int GESTURE_EVENT_RESULT_SUBTYPE_MOUSE_DOWN = 602;
    public static final int GESTURE_EVENT_RESULT_SUBTYPE_MOUSE_CLICK = 603;

    /**
     * Version level, currently 0. Determines the contents of {@link #extension}
     */
    public int version = 0;

    /**
     * Gesture result type
     * 
     * @see #GESTURE_EVENT_RESULT_TYPE_NONE
     * @see #GESTURE_EVENT_RESULT_TYPE_DETECTION
     * @see #GESTURE_EVENT_RESULT_TYPE_ENGAGEMENT
     * @see #GESTURE_EVENT_RESULT_TYPE_TRACKING
     * @see #GESTURE_EVENT_RESULT_TYPE_SWIPE
     * @see #GESTURE_EVENT_RESULT_TYPE_MOUSE
     */
    public int type = GESTURE_EVENT_RESULT_TYPE_NONE;

    /**
     * Gesture result subtype, depends on type
     * 
     * @see #GESTURE_EVENT_RESULT_SUBTYPE_LEFT_OPEN_PALM
     * @see #GESTURE_EVENT_RESULT_SUBTYPE_RIGHT_OPEN_PALM
     * @see #GESTURE_EVENT_RESULT_SUBTYPE_LEFT_FIST
     * @see #GESTURE_EVENT_RESULT_SUBTYPE_RIGHT_FIST
     * @see #GESTURE_EVENT_RESULT_SUBTYPE_NORMALIZED
     * @see #GESTURE_EVENT_RESULT_SUBTYPE_SCREEN
     * @see #GESTURE_EVENT_RESULT_SUBTYPE_LEFT
     * @see #GESTURE_EVENT_RESULT_SUBTYPE_RIGHT
     * @see #GESTURE_EVENT_RESULT_SUBTYPE_UP
     * @see #GESTURE_EVENT_RESULT_SUBTYPE_DOWN
     * @see #GESTURE_EVENT_RESULT_SUBTYPE_MOUSE_UP
     * @see #GESTURE_EVENT_RESULT_SUBTYPE_MOUSE_DOWN
     * @see #GESTURE_EVENT_RESULT_SUBTYPE_MOUSE_CLICK
     */
    public int subtype = 0;

    /**
     * Detection camera frame time in microseconds
     */
    public long timestamp = 0;

    /**
     * Identifies this outcome as the same object over time
     */
    public int id = -1;

    /**
     * The gesture detection confidence (0.0 = 0%, 1.0 = 100%)
     */
    public float confidence = 0;

    /**
     * Region for pose gesture, start position for motion gesture
     */
    public GSVector[] location = null;

    /**
     * Gesture velocity
     */
    public float velocity = 0;

    /**
     * Additional information about the gesture depending on the version level.
     * 
     * @see #version
     */
    public byte[] extension = null;
}
