/*
 * Copyright (C) 2006 The Android Open Source Project
 * Copyright (c) 2012, Code Aurora Forum. All rights reserved.
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

package android.util;

public class jTestFramework {
    //event type
    public static final int  TF_EVENT_JAVA_START = 0x08;
    public static final int  TF_EVENT_JAVA_STOP = 0x10;
    public static final int  TF_EVENT_JAVA = 0x20;

    public static native int print(int eventtype,  String eventgrp,
                                   String eventid, String msg);
    public static native int print_if(boolean cond, int eventtype,
                                      String eventgrp, String eventid,
                                      String msg);
    public static native int write(String msg);
    public static native int write_if(boolean cond, String msg);
    public static native int turnon();
    public static native int turnoff();
}
