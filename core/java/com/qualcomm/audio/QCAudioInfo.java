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

package com.qualcomm.audio;

import java.util.ArrayList;

import android.os.Bundle;

import com.qualcomm.snapdragon.util.QCCapabilitiesInterface;

public class QCAudioInfo implements QCCapabilitiesInterface
{
    // QC added vocoders
    public static final int     QC_VOCODER_BASE     = 100;   // Replace with whatever VOCODER_BASE that you will have
    public static final int     VOCODER_AMR_NB      = QC_VOCODER_BASE;
    public static final int     VOCODER_AMR_WB      = QC_VOCODER_BASE + 1;
    public static final int     VOCODER_EVRC        = QC_VOCODER_BASE + 2;
    public static final int     VOCODER_EVRCB       = QC_VOCODER_BASE + 3;
    public static final int     VOCODER_EVRCWB      = QC_VOCODER_BASE + 4;

    // QC added audio codecs
    public static final int     QC_AUDIO_CODEC_BASE = 6; // Replace with whatever QC_AUDIO_CODEC_BASE that you will have
    public static final int     AUDIO_CODEC_EVRC    = QC_AUDIO_CODEC_BASE;
    public static final int     AUDIO_CODEC_QCELP   = QC_AUDIO_CODEC_BASE + 1;
    public static final int     AUDIO_CODEC_LPCM    = QC_AUDIO_CODEC_BASE + 2;

    // QC Added file format
    public static final int     QC_FILE_FORMAT_BASE     = 9;
    public static final int     FILE_FORMAT_QCP         = QC_FILE_FORMAT_BASE;
    public static final int     FILE_FORMAT_WAVE        = QC_FILE_FORMAT_BASE + 2;

    // Keys for the return bundle in getCapabilities
    private static final String KEY_AUDIO_CODECS          = "key_audio_codecs";
    private static final String KEY_VOCODERS              = "key_vocoders";
    private static final String KEY_FILE_FORMATS          = "key_file_formats";

    /**
     * Returns QC Audio Formats supported by the hardware
     * @param None
     * @return a Bundle which looks like -
     *
     * Bundle(KEY_CONSTANT_FIELD_VALUES,[Bundle{(KEY_AUDIO_CODECS, ArrayList<String>), (KEY_VOCODERS, ArrayList<String>)}])
     *
     * KEY_CONSTANT_FIELD_VALUES => |KEY_AUDIO_CODECS=> | AUDIO_CODEC_EVRC,
     *                              |                   | AUDIO_CODEC_QCELP,
     *                              |                   | AUDIO_CODEC_LPCM,
     *                              |                   | ...
     *                              |------------------------------------------------
     *                              |KEY_VOCODERS   =>  | VOCODER_AMR_NB,
     *                              |                   | VOCODER_AMR_WB,
     *                              |                   | ...
     *                              |------------------------------------------------
     *                              |KEY_FILE_FORMATS=> | FILE_FORMAT_QCP,
     *                              |                   | FILE_FORMAT_WAVE,
     *                              |                   | ...
     *                              |------------------------------------------------
     *
     *
     */
    @Override
    public Bundle getCapabilities()
    {
        Bundle constantFieldBundle = new Bundle();
        ArrayList<String> vocodersList = new ArrayList<String>();
        vocodersList.add("VOCODER_AMR_NB");
        vocodersList.add("VOCODER_AMR_WB");
        vocodersList.add("VOCODER_EVRC");
        vocodersList.add("VOCODER_EVRCB");
        vocodersList.add("VOCODER_EVRCWB");

        constantFieldBundle.putStringArrayList(KEY_VOCODERS, vocodersList);

        ArrayList<String> audioCodecsList = new ArrayList<String>();
        audioCodecsList.add("AUDIO_CODEC_EVRC");
        audioCodecsList.add("AUDIO_CODEC_QCELP");
        audioCodecsList.add("AUDIO_CODEC_LPCM");

        constantFieldBundle.putStringArrayList(KEY_AUDIO_CODECS, audioCodecsList);

        ArrayList<String> fileFormatList = new ArrayList<String>();
        fileFormatList.add("FILE_FORMAT_QCP");
        fileFormatList.add("FILE_FORMAT_WAVE");

        constantFieldBundle.putStringArrayList(KEY_FILE_FORMATS, fileFormatList);

        Bundle capabilitiesBundle = new Bundle();
        capabilitiesBundle.putBundle(QCCapabilitiesInterface.KEY_CONSTANT_FIELD_VALUES, constantFieldBundle);

        return capabilitiesBundle;

    }

}
