/*
 * Copyright (c) 2012, Code Aurora Forum. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *        * Redistributions of source code must retain the above copyright
 *          notice, this list of conditions and the following disclaimer.
 *        * Redistributions in binary form must reproduce the above copyright
 *          notice, this list of conditions and the following disclaimer in the
 *          documentation and/or other materials provided with the distribution.
 *        * Neither the name of Code Aurora nor
 *          the names of its contributors may be used to endorse or promote
 *          products derived from this software without specific prior written
 *          permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NON-INFRINGEMENT ARE DISCLAIMED.    IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS;
 * OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR
 * OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF
 * ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package android.server;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattAppConfiguration;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.IBluetoothGattCallback;
import android.content.Context;
import android.os.Handler;
import android.os.Message;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.ParcelUuid;
import android.os.RemoteException;
import android.util.Log;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * This handles all the operations on the Bluetooth Gatt profile (server side).
 *
 * @hide
 */
final class BluetoothGattProfileHandler {
    private static final String TAG = "BluetoothGattProfileHandler";
    private static final boolean DBG = false;

    private static BluetoothGattProfileHandler sInstance;
    private BluetoothService mBluetoothService;
    private HashMap <BluetoothGattAppConfiguration, String> mAppConfigs;
    private HashMap <BluetoothGattAppConfiguration, IBluetoothGattCallback> mCallbacks;

    private static final int MESSAGE_REGISTER_APPLICATION = 0;
    private static final int MESSAGE_UNREGISTER_APPLICATION = 1;
    private static final int MESSAGE_ADD_PRIMARY_SDP = 2;
    private static final int MESSAGE_SEND_INDICATION = 4;
    private static final int MESSAGE_DISCOVER_PRIMARY_SERVICE_RESP = 5;
    private static final int MESSAGE_DISCOVER_PRIMARY_SERVICE_BY_UUID_RESP = 6;
    private static final int MESSAGE_FIND_INCLUDED_SERVICE_RESP = 7;
    private static final int MESSAGE_DISCOVER_CHARACTERISTICS_RESP = 8;
    private static final int MESSAGE_DISCOVER_CHARACTERISTIC_DESC_RESP = 9;
    private static final int MESSAGE_READ_BY_TYPE_RESP = 10;
    private static final int MESSAGE_READ_RESP = 11;
    private static final int MESSAGE_WRITE_RESP = 12;

    private static final String UUID = "uuid";
    private static final String HANDLE = "handle";
    private static final String END = "end";
    private static final String START = "start";
    private static final String REQUEST_HANDLE = "request_handle";
    private static final String ERROR = "error";
    private static final String VALUE_HANDLE = "value_handle";
    private static final String PROPERTY = "property";
    private static final String PAYLOAD = "payload";
    private static final String SESSION = "session";
    private static final String NOTIFY = "notify";

    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            BluetoothGattAppConfiguration config = (BluetoothGattAppConfiguration) msg.obj;
            int status, handle, start, end, reqHandle, errorCode, valueHandle;
            byte[] payload;
            byte property;
            String uuid;
            boolean result = true;
            String path = config.getPath();
            int payloadLen = 0;

            switch (msg.what) {
            case MESSAGE_REGISTER_APPLICATION:
                int range = config.getRange();
                result  = mBluetoothService.registerGattServerNative(path, range);

                if (!result) {
                    callGattApplicationStatusCallback(config,
                           BluetoothGatt.GATT_CONFIG_REGISTRATION_FAILURE);
                    mCallbacks.remove(config);
                } else {
                    mAppConfigs.put(config, path);
                    callGattApplicationStatusCallback(config,
                           BluetoothGatt.GATT_CONFIG_REGISTRATION_SUCCESS);
                }

                break;
            case MESSAGE_UNREGISTER_APPLICATION:
                Log.d(TAG, "GATT: MESSAGE_UNREGISTER_APPLICATION");

                result = mBluetoothService.unregisterGattServerNative(path);

                if (!result) {
                    callGattApplicationStatusCallback(config,
                           BluetoothGatt.GATT_CONFIG_UNREGISTRATION_FAILURE);
                } else {
                    mCallbacks.remove(config);
                    mAppConfigs.remove(config);
                    callGattApplicationStatusCallback(config,
                           BluetoothGatt.GATT_CONFIG_UNREGISTRATION_SUCCESS);
                }

                break;
            case MESSAGE_ADD_PRIMARY_SDP:
                Log.d(TAG, "GATT: MESSAGE_ADD_PRIMARY_SDP");

                                                                //TODO
                //mBluetoothService.addPrimarySdpNative();
                // return success or failure code to the app

                status = BluetoothGatt.GATT_SUCCESS;
                callGattActionCompleteCallback(config, "ADD_PRIMARY_SDP", status);

                break;

            case MESSAGE_SEND_INDICATION:
                boolean notify;
                int sessionHandle;
                Log.d(TAG, "GATT: MESSAGE_SEND_INDICATION");

                sessionHandle = msg.getData().getInt(SESSION);
                handle = msg.getData().getInt(HANDLE);
                payload = msg.getData().getByteArray(PAYLOAD);
                notify = msg.getData().getBoolean(NOTIFY);

                if (notify)
                    result = mBluetoothService.notifyNative(path, sessionHandle, handle, payload, payload.length);
                else
                    result = mBluetoothService.indicateNative(path, sessionHandle, handle, payload, payload.length);

                if (!result)
                    status = BluetoothGatt.GATT_FAILURE;
                else
                    status = BluetoothGatt.GATT_SUCCESS;

                callGattActionCompleteCallback(config, "SEND_INDICATION", status);
                break;

            case MESSAGE_DISCOVER_PRIMARY_SERVICE_RESP:
                uuid = msg.getData().getString(UUID);
                handle = msg.getData().getInt(HANDLE);
                end = msg.getData().getInt(END);
                reqHandle = msg.getData().getInt(REQUEST_HANDLE);
                errorCode = msg.getData().getInt(ERROR);

                result  = mBluetoothService.discoverPrimaryResponseNative(uuid, handle, end, errorCode, reqHandle);

                if (!result) {
                    status = BluetoothGatt.GATT_SUCCESS;

                } else {
                    status = BluetoothGatt.GATT_FAILURE;
                }

                break;
            case MESSAGE_DISCOVER_PRIMARY_SERVICE_BY_UUID_RESP:
                handle = msg.getData().getInt(HANDLE);
                end = msg.getData().getInt(END);
                reqHandle = msg.getData().getInt(REQUEST_HANDLE);
                errorCode = msg.getData().getInt(ERROR);

                result  = mBluetoothService.discoverPrimaryByUuidResponseNative(handle, end,
                                                                                errorCode,
                                                                                reqHandle);

                if (!result) {
                    status = BluetoothGatt.GATT_SUCCESS;

                } else {
                    status = BluetoothGatt.GATT_FAILURE;
                }

                break;

            case MESSAGE_FIND_INCLUDED_SERVICE_RESP:
                uuid = msg.getData().getString(UUID);
                handle = msg.getData().getInt(HANDLE);
                start = msg.getData().getInt(START);
                end = msg.getData().getInt(END);
                reqHandle = msg.getData().getInt(REQUEST_HANDLE);
                errorCode = msg.getData().getInt(ERROR);

                result  = mBluetoothService.findIncludedResponseNative(uuid, handle, start, end,
                                                                       errorCode, reqHandle);

                if (!result) {
                    status = BluetoothGatt.GATT_SUCCESS;

                } else {
                    status = BluetoothGatt.GATT_FAILURE;
                }

                break;

             case MESSAGE_DISCOVER_CHARACTERISTICS_RESP:
                uuid = msg.getData().getString(UUID);
                handle = msg.getData().getInt(HANDLE);
                valueHandle = msg.getData().getInt(VALUE_HANDLE);
                reqHandle = msg.getData().getInt(REQUEST_HANDLE);
                errorCode = msg.getData().getInt(ERROR);
                property = msg.getData().getByte(PROPERTY);

                result  = mBluetoothService.discoverCharacteristicsResponseNative(uuid, handle, (int) property,
                                                                                  valueHandle,
                                                                                  errorCode, reqHandle);

                if (!result) {
                    status = BluetoothGatt.GATT_SUCCESS;

                } else {
                    status = BluetoothGatt.GATT_FAILURE;
                }

                break;

            case MESSAGE_DISCOVER_CHARACTERISTIC_DESC_RESP:
                uuid = msg.getData().getString(UUID);
                handle = msg.getData().getInt(HANDLE);
                reqHandle = msg.getData().getInt(REQUEST_HANDLE);
                errorCode = msg.getData().getInt(ERROR);

                result  = mBluetoothService.discoverCharacteristicDescriptorResponseNative(uuid, handle,
                                                                                  errorCode,
                                                                                  reqHandle);

                if (!result) {
                    status = BluetoothGatt.GATT_SUCCESS;

                } else {
                    status = BluetoothGatt.GATT_FAILURE;
                }

                break;

            case MESSAGE_READ_BY_TYPE_RESP:
                uuid = msg.getData().getString(UUID);
                handle = msg.getData().getInt(HANDLE);
                reqHandle = msg.getData().getInt(REQUEST_HANDLE);
                errorCode = msg.getData().getInt(ERROR);
                payload = msg.getData().getByteArray(PAYLOAD);
                if(payload != null) {
                    payloadLen = payload.length;
                }
                else {
                    payloadLen = 0;
                }
                result  = mBluetoothService.readByTypeResponseNative(uuid, handle, payload,
                                                                     payloadLen,
                                                                     errorCode, reqHandle);

                if (!result) {
                    status = BluetoothGatt.GATT_SUCCESS;

                } else {
                    status = BluetoothGatt.GATT_FAILURE;
                }

                break;

            case MESSAGE_READ_RESP:
                uuid = msg.getData().getString(UUID);
                reqHandle = msg.getData().getInt(REQUEST_HANDLE);
                errorCode = msg.getData().getInt(ERROR);
                payload = msg.getData().getByteArray(PAYLOAD);
                if(payload != null) {
                    payloadLen = payload.length;
                }
                else {
                    payloadLen = 0;
                }
                result  = mBluetoothService.readResponseNative(uuid, payload,
                                                               payloadLen,
                                                               errorCode,
                                                               reqHandle);
                if (!result) {
                    status = BluetoothGatt.GATT_SUCCESS;

                } else {
                    status = BluetoothGatt.GATT_FAILURE;
                }

                break;

            case MESSAGE_WRITE_RESP:
                uuid = msg.getData().getString(UUID);
                reqHandle = msg.getData().getInt(REQUEST_HANDLE);
                errorCode = msg.getData().getInt(ERROR);

                result  = mBluetoothService.writeResponseNative(uuid, errorCode, reqHandle);
                if (!result) {
                    status = BluetoothGatt.GATT_SUCCESS;

                } else {
                    status = BluetoothGatt.GATT_FAILURE;
                }

                break;

            }
        }
    };

    private BluetoothGattProfileHandler(Context context, BluetoothService service) {
        mBluetoothService = service;
        mAppConfigs = new HashMap<BluetoothGattAppConfiguration, String>();
        mCallbacks = new HashMap<BluetoothGattAppConfiguration, IBluetoothGattCallback>();
    }

    static synchronized BluetoothGattProfileHandler getInstance(Context context,
            BluetoothService service) {
        if (sInstance == null) sInstance = new BluetoothGattProfileHandler(context, service);
        return sInstance;
    }

    boolean registerAppConfiguration(BluetoothGattAppConfiguration config,
                                     IBluetoothGattCallback callback) {
        Message msg = mHandler.obtainMessage(MESSAGE_REGISTER_APPLICATION);
        msg.obj = config;
        mHandler.sendMessage(msg);
        mCallbacks.put(config, callback);
        return true;
    }

   boolean unregisterAppConfiguration(BluetoothGattAppConfiguration config) {
        String path = mAppConfigs.get(config);
        if (path == null) {
           Log.e(TAG, "unregisterAppConfiguration: GATT app not registered");
            return false;
        }

        Message msg = mHandler.obtainMessage(MESSAGE_UNREGISTER_APPLICATION);
        msg.obj = config;
        mHandler.sendMessage(msg);
        return true;
    }


    boolean addPrimarySdp(BluetoothGattAppConfiguration config,
                                 ParcelUuid uuid, int start, int end, boolean eir) {
        String path = mAppConfigs.get(config);
        if (path == null) {
            Log.e(TAG, "addPrimarySdp: GATT app not registered");
            return false;
        }

        Bundle b = new Bundle();
        String stringUuid = uuid.toString();
        b.putString("UUID", stringUuid);
        b.putInt("Start", start);
        b.putInt("End", end);
        b.putBoolean("EIR", eir);

        Message msg = mHandler.obtainMessage(MESSAGE_ADD_PRIMARY_SDP);
        msg.obj = config;
        msg.setData(b);
        mHandler.sendMessage(msg);

        return true;
    }



    boolean sendIndication(BluetoothGattAppConfiguration config,
                           int handle, byte[] value, boolean notify, int sessionHandle) {

       String path = mAppConfigs.get(config);
        if (path == null) {
            Log.e(TAG, "addPrimarySdp: GATT app not registered");
            return false;
        }

        Bundle b = new Bundle();
        b.putInt(SESSION, sessionHandle);
        b.putInt(HANDLE, handle);
        b.putByteArray(PAYLOAD, value);
        b.putBoolean(NOTIFY, notify);

        Message msg = mHandler.obtainMessage(MESSAGE_SEND_INDICATION);
        msg.obj = config;
        msg.setData(b);
        mHandler.sendMessage(msg);

        return true;
    }

    boolean discoverPrimaryResponse(BluetoothGattAppConfiguration config,
                                       String uuid, int handle, int end, int status, int reqHandle) {
       String path = mAppConfigs.get(config);
        if (path == null) {
            Log.e(TAG, "discoverPrimaryResponse: GATT app not registered");
            return false;
        }
       Log.d(TAG, "discoverPrimaryResponse uuid : " + uuid +
             " handle : " + handle + " end: " + end + " reqHandle : " + reqHandle);

        Bundle b = new Bundle();

        b.putString(UUID, uuid);
        b.putInt(HANDLE, handle);
        b.putInt(END, end);
        b.putInt(ERROR, status);
        b.putInt(REQUEST_HANDLE, reqHandle);

        Message msg = mHandler.obtainMessage(MESSAGE_DISCOVER_PRIMARY_SERVICE_RESP);
        msg.obj = config;
        msg.setData(b);
        mHandler.sendMessage(msg);

        return true;
    }

    boolean discoverPrimaryByUuidResponse(BluetoothGattAppConfiguration config,
                                          int handle, int end, int status, int reqHandle) {
       String path = mAppConfigs.get(config);
        if (path == null) {
            Log.e(TAG, "discoverPrimaryByUuidResponse: GATT app not registered");
            return false;
        }
       Log.d(TAG, "discoverPrimaryByUuidResponse " + " handle : " + handle
             + " end: " + end + " reqHandle : " + reqHandle);

        Bundle b = new Bundle();
        b.putInt(HANDLE, handle);
        b.putInt(END, end);
        b.putInt(ERROR, status);
        b.putInt(REQUEST_HANDLE, reqHandle);

        Message msg = mHandler.obtainMessage(MESSAGE_DISCOVER_PRIMARY_SERVICE_BY_UUID_RESP);
        msg.obj = config;
        msg.setData(b);
        mHandler.sendMessage(msg);

        return true;
    }

    boolean findIncludedResponse(BluetoothGattAppConfiguration config, String uuid,
                                 int handle, int start, int end, int status, int reqHandle) {
       String path = mAppConfigs.get(config);
        if (path == null) {
            Log.e(TAG, "findIncludedResponse: GATT app not registered");
            return false;
        }
       Log.d(TAG, "findIncludedResponse uuid : " + uuid +
             " handle : " + handle + " end: " + end + " reqHandle : " + reqHandle);

        Bundle b = new Bundle();
        b.putString(UUID, uuid);
        b.putInt(HANDLE, handle);
        b.putInt(START, start);
        b.putInt(END, end);
        b.putInt(ERROR, status);
        b.putInt(REQUEST_HANDLE, reqHandle);

        Message msg = mHandler.obtainMessage(MESSAGE_FIND_INCLUDED_SERVICE_RESP);
        msg.obj = config;
        msg.setData(b);
        mHandler.sendMessage(msg);

        return true;
    }

    boolean discoverCharacteristicsResponse(BluetoothGattAppConfiguration config, String uuid,
                                            int handle, byte property, int valueHandle,
                                            int status, int reqHandle) {
       String path = mAppConfigs.get(config);
        if (path == null) {
            Log.e(TAG, "discoverCharacteristicsResponse: GATT app not registered");
            return false;
        }
       Log.d(TAG, " discoverCharacteristicsResponse uuid : " + uuid + " handle : " + handle
             + " property : " + property +  " valHandle : " + valueHandle +
             " reqHandle : " + reqHandle);

        Bundle b = new Bundle();
        b.putString(UUID, uuid);
        b.putInt(HANDLE, handle);
        b.putByte(PROPERTY, property);
        b.putInt(VALUE_HANDLE, valueHandle);
        b.putInt(ERROR, status);
        b.putInt(REQUEST_HANDLE, reqHandle);

        Message msg = mHandler.obtainMessage(MESSAGE_DISCOVER_CHARACTERISTICS_RESP);
        msg.obj = config;
        msg.setData(b);
        mHandler.sendMessage(msg);

        return true;
    }

    boolean discoverCharacteristicDescriptorResponse(BluetoothGattAppConfiguration config, String uuid,
                                                     int handle, int status, int reqHandle) {
       String path = mAppConfigs.get(config);
        if (path == null) {
            Log.e(TAG, "discoverCharacteristicDescriptorResponse: GATT app not registered");
            return false;
        }
       Log.d(TAG, " discoverCharacteristicDescriptorResponse uuid : " + uuid + " handle : " + handle
             + " reqHandle : " + reqHandle);

        Bundle b = new Bundle();
        b.putString(UUID, uuid);
        b.putInt(HANDLE, handle);
        b.putInt(ERROR, status);
        b.putInt(REQUEST_HANDLE, reqHandle);

        Message msg = mHandler.obtainMessage(MESSAGE_DISCOVER_CHARACTERISTIC_DESC_RESP);
        msg.obj = config;
        msg.setData(b);
        mHandler.sendMessage(msg);

        return true;
    }

    boolean readByTypeResponse(BluetoothGattAppConfiguration config, String uuid, int handle,
                               byte[] payload, int status, int reqHandle) {
       String path = mAppConfigs.get(config);
        if (path == null) {
            Log.e(TAG, "readByTypeResponse: GATT app not registered");
            return false;
        }
       Log.d(TAG, " readByTypeResponse uuid : " + uuid + " handle : " + handle
             + " reqHandle : " + reqHandle);

        Bundle b = new Bundle();
        b.putString(UUID, uuid);
        b.putInt(HANDLE, handle);
        b.putByteArray(PAYLOAD, payload);
        b.putInt(ERROR, status);
        b.putInt(REQUEST_HANDLE, reqHandle);

        Message msg = mHandler.obtainMessage(MESSAGE_READ_BY_TYPE_RESP);
        msg.obj = config;
        msg.setData(b);
        mHandler.sendMessage(msg);

        return true;
    }

    boolean readResponse(BluetoothGattAppConfiguration config, String uuid,
                         byte[] payload, int status, int reqHandle) {
       String path = mAppConfigs.get(config);
        if (path == null) {
            Log.e(TAG, "readResponse: GATT app not registered");
            return false;
        }
       Log.d(TAG, " readResponse uuid : " + uuid + " reqHandle : " + reqHandle);

        Bundle b = new Bundle();
        b.putString(UUID, uuid);
        b.putByteArray(PAYLOAD, payload);
        b.putInt(ERROR, status);
        b.putInt(REQUEST_HANDLE, reqHandle);

        Message msg = mHandler.obtainMessage(MESSAGE_READ_RESP);
        msg.obj = config;
        msg.setData(b);
        mHandler.sendMessage(msg);

        return true;
    }

    boolean writeResponse(BluetoothGattAppConfiguration config, String uuid, int status,
                          int reqHandle) {
       String path = mAppConfigs.get(config);
        if (path == null) {
            Log.e(TAG, "writeResponse: GATT app not registered");
            return false;
        }
       Log.d(TAG, " writeResponse uuid : " + uuid + " reqHandle : " + reqHandle);

        Bundle b = new Bundle();
        b.putString(UUID, uuid);
        b.putInt(ERROR, status);
        b.putInt(REQUEST_HANDLE, reqHandle);

        Message msg = mHandler.obtainMessage(MESSAGE_WRITE_RESP);
        msg.obj = config;
        msg.setData(b);
        mHandler.sendMessage(msg);

        return true;
    }

    /*package*/ synchronized void onGattDiscoverPrimaryRequest(String gattObjPath, int start, int end, int reqHandle) {
         Log.d(TAG, "Gatt object path : "  + gattObjPath + "start :  " + start + " end : " + end );
         BluetoothGattAppConfiguration config = getConfigFromPath(gattObjPath);
         Log.d(TAG, "Config " + config);
         if (config != null) {
             IBluetoothGattCallback callback = mCallbacks.get(config);
             if (callback != null) {
                try {
                    callback.onGattDiscoverPrimaryServiceRequest(config, start, end, reqHandle);
                } catch (RemoteException e) {
                    Log.e(TAG, "Remote Exception:" + e);
                }
             }
         }
     }

     /*package*/ synchronized void onGattDiscoverPrimaryByUuidRequest(String gattObjPath,
                                                                      int start, int end,
                                                                      String uuidStr,
                                                                      int reqHandle) {
         Log.d(TAG, "Gatt object path : "  + gattObjPath + "uuid : " + uuidStr +
               "start :  " + start + " end : " + end );
         BluetoothGattAppConfiguration config = getConfigFromPath(gattObjPath);
         Log.d(TAG, "Config " + config);
         if (config != null) {
             IBluetoothGattCallback callback = mCallbacks.get(config);
             if (callback != null) {
                try {
                    ParcelUuid uuid = ParcelUuid.fromString(uuidStr);
                    Log.d(TAG, "Convert string to parceluuid : " + uuid);
                    callback.onGattDiscoverPrimaryServiceByUuidRequest(config, start, end, uuid, reqHandle);
                } catch (RemoteException e) {
                    Log.e(TAG, "Remote Exception:" + e);
                }
             }
         }
     }

     /*package*/ synchronized void onGattDiscoverIncludedRequest(String gattObjPath,
                                                                 int start, int end,
                                                                 int reqHandle) {
         Log.d(TAG, "Gatt object path : "  + gattObjPath +
               "start :  " + start + " end : " + end );
         BluetoothGattAppConfiguration config = getConfigFromPath(gattObjPath);
         Log.d(TAG, "Config " + config);
         if (config != null) {
             IBluetoothGattCallback callback = mCallbacks.get(config);
             if (callback != null) {
                try {
                    callback.onGattFindIncludedServiceRequest(config, start, end, reqHandle);
                } catch (RemoteException e) {
                    Log.e(TAG, "Remote Exception:" + e);
                }
             }
         }
     }

     /*package*/ synchronized void onGattDiscoverCharacteristicsRequest(String gattObjPath,
                                                                 int start, int end,
                                                                 int reqHandle) {
         Log.d(TAG, "Gatt object path : "  + gattObjPath +
               "start :  " + start + " end : " + end );
         BluetoothGattAppConfiguration config = getConfigFromPath(gattObjPath);
         Log.d(TAG, "Config " + config);
         if (config != null) {
             IBluetoothGattCallback callback = mCallbacks.get(config);
             if (callback != null) {
                try {
                    callback.onGattDiscoverCharacteristicRequest(config, start, end, reqHandle);
                } catch (RemoteException e) {
                    Log.e(TAG, "Remote Exception:" + e);
                }
             }
         }
     }

     /*package*/ synchronized void onGattDiscoverCharacteristicDescriptorRequest(String gattObjPath,
                                                                 int start, int end,
                                                                 int reqHandle) {
         Log.d(TAG, "Gatt object path : "  + gattObjPath +
               "start :  " + start + " end : " + end );
         BluetoothGattAppConfiguration config = getConfigFromPath(gattObjPath);
         Log.d(TAG, "Config " + config);
         if (config != null) {
             IBluetoothGattCallback callback = mCallbacks.get(config);
             if (callback != null) {
                try {
                    callback.onGattDiscoverCharacteristicDescriptorRequest(config, start,
                                                                           end, reqHandle);
                } catch (RemoteException e) {
                    Log.e(TAG, "Remote Exception:" + e);
                }
             }
         }
     }

     /*package*/ synchronized void onGattReadByTypeRequest(String gattObjPath, int start, int end,
                                                           String uuidStr, String auth, int reqHandle) {
         Log.d(TAG, "Gatt object path : "  + gattObjPath + "uuid : " + uuidStr +
               "start :  " + start + " end : " + end + " auth : " + auth);
         BluetoothGattAppConfiguration config = getConfigFromPath(gattObjPath);
         Log.d(TAG, "Config " + config);
         if (config != null) {
             IBluetoothGattCallback callback = mCallbacks.get(config);
             if (callback != null) {
                try {
                    ParcelUuid uuid = ParcelUuid.fromString(uuidStr);
                    Log.d(TAG, "Convert string to parceluuid : " + uuid);
                    callback.onGattReadByTypeRequest(config, uuid, start, end, auth, reqHandle);
                } catch (RemoteException e) {
                    Log.e(TAG, "Remote Exception:" + e);
                }
             }
         }
     }

     /*package*/ synchronized void onGattReadRequest(String gattObjPath, String auth,
                                                     int handle, int reqHandle) {
         Log.d(TAG, "Gatt object path : "  + "handle :  " + handle + " auth : " + auth);
         BluetoothGattAppConfiguration config = getConfigFromPath(gattObjPath);
         Log.d(TAG, "Config " + config);
         if (config != null) {
             IBluetoothGattCallback callback = mCallbacks.get(config);
             if (callback != null) {
                try {
                    callback.onGattReadRequest(config, handle, auth, reqHandle);
                } catch (RemoteException e) {
                    Log.e(TAG, "Remote Exception:" + e);
                }
             }
         }
     }

     /*package*/ synchronized void onGattWriteRequest(String gattObjectPath, String auth,
                                                      int attrHandle, byte[] value) {
         BluetoothGattAppConfiguration config = getConfigFromPath(gattObjectPath);
         Log.d(TAG, "Gatt object path : "  + gattObjectPath + ", config " + config);

         if (config != null) {
             IBluetoothGattCallback callback = mCallbacks.get(config);
             if (callback != null) {
                try {
                    callback.onGattWriteRequest(config, attrHandle, value, auth);
                } catch (RemoteException e) {
                    Log.e(TAG, "Remote Exception:" + e);
                }
             }
         }
     }

     /*package*/ synchronized void onGattReliableWriteRequest(String gattObjectPath, String auth,
                                                              int attrHandle, byte[] value, int sessionHandle, int reqHandle) {
         BluetoothGattAppConfiguration config = getConfigFromPath(gattObjectPath);
         Log.d(TAG, "Gatt object path : "  + gattObjectPath + ", config " + config);

         if (config != null) {
             IBluetoothGattCallback callback = mCallbacks.get(config);
             if (callback != null) {
                try {
                    callback.onGattReliableWriteRequest(config, attrHandle, value, auth, sessionHandle, reqHandle);
                } catch (RemoteException e) {
                    Log.e(TAG, "Remote Exception:" + e);
                }
             }
         }
     }

    /*package*/ synchronized void onGattSetClientConfigDescriptor(String gattObjPath,
                                                                  int sessionHandle, int attrHandle, byte[] value) {
         BluetoothGattAppConfiguration config = getConfigFromPath(gattObjPath);
         Log.d(TAG, "Gatt object path : "  + gattObjPath + ", config " + config);

         if (config != null) {
             IBluetoothGattCallback callback = mCallbacks.get(config);
             if (callback != null) {
                try {
                    callback.onGattSetClientConfigDescriptor(config, attrHandle, value, sessionHandle);
                } catch (RemoteException e) {
                    Log.e(TAG, "Remote Exception:" + e);
                }
             }
         }
     }

    private BluetoothGattAppConfiguration getConfigFromPath(String path) {
        BluetoothGattAppConfiguration config = null;
        for(Entry<BluetoothGattAppConfiguration, String> entry : mAppConfigs.entrySet()) {
             if (path.equals(entry.getValue())) {
                 return entry.getKey();
             }
        }
        return config;
    }

    private void callGattApplicationStatusCallback(
            BluetoothGattAppConfiguration config, int status) {
        Log.d(TAG, "GATT Application: " + config + " State Change: status:"
                + status);
        IBluetoothGattCallback callback = mCallbacks.get(config);
        if (callback != null) {
            try {
                callback.onGattAppConfigurationStatusChange(config, status);
            } catch (RemoteException e) {
                Log.e(TAG, "Remote Exception:" + e);
            }
        }
    }

   private void callGattActionCompleteCallback(
                                               BluetoothGattAppConfiguration config, String action, int status) {
        Log.d(TAG, "GATT Action: " + action + " status:" + status);
        IBluetoothGattCallback callback = mCallbacks.get(config);
        if (callback != null) {
            try {
                callback.onGattActionComplete(config, action, status);
            } catch (RemoteException e) {
                Log.e(TAG, "Remote Exception:" + e);
            }
        }
    }
}
