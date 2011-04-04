/* Copyright (c) 2010,2011, Code Aurora Forum. All rights reserved.
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
 */


package com.android.internal.atfwd;

import android.os.Parcel;
import android.os.Parcelable;

public class AtCmd implements Parcelable {

    public static final int AT_OPCODE_NA = 1;
    public static final int AT_OPCODE_EQ = 2;
    public static final int AT_OPCODE_QU = 4;
    public static final int AT_OPCODE_AR = 8;

    private int mOpcode;
    private String mName;
    private String mTokens[];

    public int getOpcode() {
        return mOpcode;
    }

    public void setOpcode(int mOpcode) {
        this.mOpcode = mOpcode;
    }

    public String getName() {
        return mName;
    }

    public void setName(String mName) {
        this.mName = mName;
    }

    public String[] getTokens() {
        return mTokens;
    }

    public void setTokens(String[] mTokens) {
        this.mTokens = mTokens;
    }

    public AtCmd(int opcode, String name, String tokens [])
    {
        init(opcode,name,tokens);
    }

    private AtCmd(Parcel source) {
        int opcode = source.readInt();
        String name = source.readString();
        String []tokens = source.readStringArray();
        init(opcode,name,tokens);
    }

    private void init(int opcode, String name, String tokens[]) {
        mOpcode = opcode;
        mName = name;
        mTokens = tokens;
    }

    public int describeContents() {
        // TODO Auto-generated method stub
        return 0;
    }

    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mOpcode);
        dest.writeString(mName);
        dest.writeStringArray(mTokens);
    }

    public String toString() {
        String ret = "AtCmd { opcode = " + mOpcode + ", name = " + mName + " mTokens = {";
        for (String token : mTokens) {
            ret += " " + token + ",";
        }
        ret += "}";
        return ret;
    }

    public static final Parcelable.Creator<AtCmd> CREATOR = new Parcelable.Creator<AtCmd>() {

        public AtCmd createFromParcel(Parcel source) {
            AtCmd ret = new AtCmd(source);
            return ret;
        }

        public AtCmd[] newArray(int size) {
            return new AtCmd[size];
        }

    };
}
