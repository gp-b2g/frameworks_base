/*
 * Copyright (c) 2012, Code Aurora Forum. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *  * Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 *  * Redistributions in binary form must reproduce the above
 *    copyright notice, this list of conditions and the following
 *    disclaimer in the documentation and/or other materials provided
 *    with the distribution.
 *  * Neither the name of Code Aurora Forum, Inc. nor the names of its
 *    contributors may be used to endorse or promote products derived
 *    from this software without specific prior written permission.
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

package org.codeaurora.qrdinside;

import java.lang.reflect.Method;
import java.lang.reflect.Constructor;

public class QIComponent{
    Class mJarClass;
    Object mComponentObj;
    public QIComponent(String ComponentName, Object[] consArgs, Class[] argTypes) {
        try{
            mJarClass = Class.forName(ComponentName);
            if(mJarClass != null) {
                Constructor cons = mJarClass.getConstructor(argTypes);
                mComponentObj = cons.newInstance(consArgs);
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    public Object CallMethod(String MethodName, Object[] invokeArgs, Class[] argTypes) {
        Object retObj = null;
        try{
            Method method = mJarClass.getMethod(MethodName, argTypes);
            retObj = method.invoke(mComponentObj, invokeArgs);
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        return retObj;
    }
}
