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

package com.qualcomm.camera;

import android.hardware.Camera;

/**
 * {@hide}
 * Information about a Qaulcomm face identified through camera
 * face detection.
 *
 * <p>When face detection is used with a camera, the {@link FaceDetectionListener} returns a
 * list of face objects for use in focusing and metering.</p>
 *
 * @see FaceDetectionListener
 */
public class QCFace extends android.hardware.Camera.Face {
    public QCFace() {
        super();
    }

    private int smileDegree = 0;
    private int smileScore = 0;
    private int blinkDetected = 0;
    private int faceRecognized = 0;
    private int gazeAngle = 0;
    private int updownDir = 0;
    private int leftrightDir = 0;
    private int rollDir = 0;
    private int leyeBlink = 0;
    private int reyeBlink = 0;
    private int leftrightGaze = 0;
    private int topbottomGaze = 0;

    /**
     * The smilie degree for the detection of the face.
     *
     * @see #startFaceDetection()
     */
    public int getSmileDegree() {
        return smileDegree;
    }

    /**
     * The smilie score for the detection of the face.
     *
     * @see #startFaceDetection()
     */
    public int getSmileScore() {
        return smileScore;
    }

    /**
     * The smilie degree for the detection of the face.
     *
     * @see #startFaceDetection()
     */
    public int getBlinkDetected() {
        return blinkDetected;
    }

    /**
     * If face is recognized.
     *
     * @see #startFaceDetection()
     */
    public int getFaceRecognized() {
        return faceRecognized;
    }

    /**
     * The gaze angle for the detected face.
     *
     * @see #startFaceDetection()
     */
    public int getGazeAngle() {
        return gazeAngle;
    }

    /**
     * The up down direction for the detected face.
     *
     * @see #startFaceDetection()
     */
    public int getUpDownDirection() {
        return updownDir;
    }

    /**
     * The left right direction for the detected face.
     *
     * @see #startFaceDetection()
     */
    public int getLeftRightDirection() {
        return leftrightDir;
    }

    /**
     * The roll direction for the detected face.
     *
     * @see #startFaceDetection()
     */
    public int getRollDirection() {
        return rollDir;
    }

    /**
     * The degree of left eye blink for the detected face.
     *
     * @see #startFaceDetection()
     */
    public int getLeftEyeBlinkDegree() {
        return leyeBlink;
    }

    /**
     * The degree of right eye blink for the detected face.
     *
     * @see #startFaceDetection()
     */
    public int getRightEyeBlinkDegree() {
        return reyeBlink;
    }

    /**
     * The gaze degree of left-right direction for the detected 
     * face. 
     *
     * @see #startFaceDetection()
     */
    public int getLeftRightGazeDegree() {
        return leftrightGaze;
    }

    /**
     * The gaze degree of up-down direction for the detected face.
     *
     * @see #startFaceDetection()
     */
    public int getTopBottomGazeDegree() {
        return topbottomGaze;
    }
}
