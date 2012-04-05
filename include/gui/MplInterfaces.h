
/*
 * Copyright (C) 2011 Invensense, Inc.
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

#ifndef INV_MPL_INTERFACES_H
#define INV_MPL_INTERFACES_H

class MplSys_Interface {
public:
    virtual ~MplSys_Interface() = 0;
    virtual int getBiases(float *b) = 0;
    virtual int setBiases(float *b) = 0;
    virtual int setBiasUpdateFunc(long f) = 0;
    virtual int setSensors(long s) = 0;
    virtual int getSensors(long* s) = 0;
    virtual int resetCal() = 0;
    virtual int selfTest() = 0;
    virtual int setLocalMagField(float x, float y, float z) = 0;
};

class MplSysPed_Interface {
public:
    virtual ~MplSysPed_Interface() = 0;
    virtual int rpcStartPed() = 0;
    virtual int rpcStopPed() = 0;
    virtual int rpcGetSteps() = 0;
    virtual double rpcGetWalkTime() = 0;
    virtual int rpcClearPedData() = 0;
};

#endif

