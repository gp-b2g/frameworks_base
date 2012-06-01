/*
 * Copyright (C) 2010 The Android Open Source Project
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

#include <stdint.h>
#include <math.h>
#include <sys/types.h>
#include <dlfcn.h>

#include <cutils/properties.h>

#include <utils/SortedVector.h>
#include <utils/KeyedVector.h>
#include <utils/threads.h>
#include <utils/Atomic.h>
#include <utils/Errors.h>
#include <utils/RefBase.h>
#include <utils/Singleton.h>
#include <utils/String16.h>
#include <cutils/log.h>

#include <binder/BinderService.h>
#include <binder/IServiceManager.h>
#include <binder/PermissionCache.h>

#include <gui/ISensorServer.h>
#include <gui/ISensorEventConnection.h>

#include <hardware/sensors.h>

#include "CorrectedGyroSensor.h"
#include "GravitySensor.h"
#include "LinearAccelerationSensor.h"
#include "OrientationSensor.h"
#include "RotationVectorSensor.h"
#include "RotationVectorSensor2.h"
#include "SensorFusion.h"
#include "SensorService.h"
#define LOG_TAG "lk"

namespace android {
// ---------------------------------------------------------------------------

/*
 * Notes:
 *
 * - what about a gyro-corrected magnetic-field sensor?
 * - run mag sensor from time to time to force calibration
 * - gravity sensor length is wrong (=> drift in linear-acc sensor)
 *
 */

SensorService::SensorService()
    : mInitCheck(NO_INIT)
{
	SLOGE("SensorService");
}

void SensorService::onFirstRef()
{
    LOGD("nuSensorService starting...");

    SensorDevice& dev(SensorDevice::getInstance());

    if (dev.initCheck() == NO_ERROR) {
        sensor_t const* list;
        ssize_t count = dev.getSensorList(&list);
        if (count > 0) {
            ssize_t orientationIndex = -1;
            bool hasGyro = false;
            uint32_t virtualSensorsNeeds =
                    (1<<SENSOR_TYPE_GRAVITY) |
                    (1<<SENSOR_TYPE_LINEAR_ACCELERATION) |
                    (1<<SENSOR_TYPE_ROTATION_VECTOR);

            mLastEventSeen.setCapacity(count);
            for (ssize_t i=0 ; i<count ; i++) {
                registerSensor( new HardwareSensor(list[i]) );
                switch (list[i].type) {
                    case SENSOR_TYPE_ORIENTATION:
                        orientationIndex = i;
                        break;
                    case SENSOR_TYPE_GYROSCOPE:
                        hasGyro = true;
                        break;
                    case SENSOR_TYPE_GRAVITY:
                    case SENSOR_TYPE_LINEAR_ACCELERATION:
                    case SENSOR_TYPE_ROTATION_VECTOR:
                        virtualSensorsNeeds &= ~(1<<list[i].type);
                        break;
                }
            }

            // it's safe to instantiate the SensorFusion object here
            // (it wants to be instantiated after h/w sensors have been
            // registered)
            const SensorFusion& fusion(SensorFusion::getInstance());

            if (hasGyro) {
                // Always instantiate Android's virtual sensors. Since they are
                // instantiated behind sensors from the HAL, they won't
                // interfere with applications, unless they looks specifically
                // for them (by name).

                registerVirtualSensor( new RotationVectorSensor() );
                registerVirtualSensor( new GravitySensor(list, count) );
                registerVirtualSensor( new LinearAccelerationSensor(list, count) );

                // these are optional
                registerVirtualSensor( new OrientationSensor() );
                registerVirtualSensor( new CorrectedGyroSensor(list, count) );

                // virtual debugging sensors...
                char value[PROPERTY_VALUE_MAX];
                property_get("debug.sensors", value, "0");
                if (atoi(value)) {
                    registerVirtualSensor( new GyroDriftSensor() );
                }
				SLOGE("With Gyro");
            } else if (orientationIndex != -1) {
            	SLOGE("registerVirtualSensor>");
                registerVirtualSensor( &RotationVectorSensor2::getInstance() );
				SLOGE("No Gyro");
            }

            // build the sensor list returned to users
            mUserSensorList = mSensorList;
            if (hasGyro &&
                    (virtualSensorsNeeds & (1<<SENSOR_TYPE_ROTATION_VECTOR))) {
                // if we have the fancy sensor fusion, and it's not provided by the
                // HAL, use our own (fused) orientation sensor by removing the
                // HAL supplied one form the user list.
                if (orientationIndex >= 0) {
                    mUserSensorList.removeItemsAt(orientationIndex);
                }
            }

            run("SensorService", PRIORITY_URGENT_DISPLAY);
            mInitCheck = NO_ERROR;
        }
    }
}

void SensorService::registerSensor(SensorInterface* s)
{
    sensors_event_t event;
    memset(&event, 0, sizeof(event));

    const Sensor sensor(s->getSensor());
	SLOGE("SensorHandle=%d",sensor.getHandle ());
    // add to the sensor list (returned to clients)
    mSensorList.add(sensor);
    // add to our handle->SensorInterface mapping
    mSensorMap.add(sensor.getHandle(), s);
    // create an entry in the mLastEventSeen array
    mLastEventSeen.add(sensor.getHandle(), event);
}

void SensorService::registerVirtualSensor(SensorInterface* s)
{
	SLOGE("registerVirtualSensor");
    registerSensor(s);
    mVirtualSensorList.add( s );
}

SensorService::~SensorService()
{
    for (size_t i=0 ; i<mSensorMap.size() ; i++)
        delete mSensorMap.valueAt(i);
}

static const String16 sDump("android.permission.DUMP");

status_t SensorService::dump(int fd, const Vector<String16>& args)
{
    const size_t SIZE = 1024;
    char buffer[SIZE];
    String8 result;
    if (!PermissionCache::checkCallingPermission(sDump)) {
        snprintf(buffer, SIZE, "Permission Denial: "
                "can't dump SurfaceFlinger from pid=%d, uid=%d\n",
                IPCThreadState::self()->getCallingPid(),
                IPCThreadState::self()->getCallingUid());
        result.append(buffer);
    } else {
        Mutex::Autolock _l(mLock);
        snprintf(buffer, SIZE, "Sensor List:\n");
        result.append(buffer);
        for (size_t i=0 ; i<mSensorList.size() ; i++) {
            const Sensor& s(mSensorList[i]);
            const sensors_event_t& e(mLastEventSeen.valueFor(s.getHandle()));
            snprintf(buffer, SIZE,
                    "%-48s| %-32s | 0x%08x | maxRate=%7.2fHz | "
                    "last=<%5.1f,%5.1f,%5.1f>\n",
                    s.getName().string(),
                    s.getVendor().string(),
                    s.getHandle(),
                    s.getMinDelay() ? (1000000.0f / s.getMinDelay()) : 0.0f,
                    e.data[0], e.data[1], e.data[2]);
            result.append(buffer);
        }
        SensorFusion::getInstance().dump(result, buffer, SIZE);
        SensorDevice::getInstance().dump(result, buffer, SIZE);

        snprintf(buffer, SIZE, "%d active connections\n",
                mActiveConnections.size());
        result.append(buffer);
        snprintf(buffer, SIZE, "Active sensors:\n");
        result.append(buffer);
        for (size_t i=0 ; i<mActiveSensors.size() ; i++) {
            int handle = mActiveSensors.keyAt(i);
            snprintf(buffer, SIZE, "%s (handle=0x%08x, connections=%d)\n",
                    getSensorName(handle).string(),
                    handle,
                    mActiveSensors.valueAt(i)->getNumConnections());
            result.append(buffer);
        }
    }
    write(fd, result.string(), result.size());
    return NO_ERROR;
}

bool SensorService::threadLoop()
{
    SLOGE("nuSensorService thread starting...");

    const size_t numEventMax = 16 * (1 + mVirtualSensorList.size());
    sensors_event_t buffer[numEventMax];
    sensors_event_t scratch[numEventMax];
    SensorDevice& device(SensorDevice::getInstance());
    const size_t vcount = mVirtualSensorList.size();

    ssize_t count;
    do {
        count = device.poll(buffer, numEventMax);
        if (count<0) {
            SLOGE("sensor poll failed (%s)", strerror(-count));
            break;
        }

        recordLastValue(buffer, count);
		//SLOGE("count=%d, vcount=%d",count,vcount);
        // handle virtual sensors
        if (count && vcount) {
            sensors_event_t const * const event = buffer;
            const DefaultKeyedVector<int, SensorInterface*> virtualSensors(
                    getActiveVirtualSensors());
            const size_t activeVirtualSensorCount = virtualSensors.size();
			//SLOGE("activeVirtualSensorCount=%d",activeVirtualSensorCount);
            if (activeVirtualSensorCount) {
                size_t k = 0;
                SensorFusion& fusion(SensorFusion::getInstance());
                if (fusion.isEnabled()) {
                    for (size_t i=0 ; i<size_t(count) ; i++) {
                        fusion.process(event[i]);
                    }
                }
                RotationVectorSensor2& rv2(RotationVectorSensor2::getInstance());
				SLOGE("SensorService>");
                if (rv2.isEnabled()) {
					SLOGE("SensorService enabled");
                    for (size_t i=0 ; i<size_t(count) ; i++) {
                        rv2.process(event[i]);
                    }
                }
                for (size_t i=0 ; i<size_t(count) ; i++) {
                    for (size_t j=0 ; j<activeVirtualSensorCount ; j++) {
                        sensors_event_t out;
                        if (virtualSensors.valueAt(j)->process(&out, event[i])) {
                            buffer[count + k] = out;
                            k++;
                        }
                    }
                }
                if (k) {
                    // record the last synthesized values
                    recordLastValue(&buffer[count], k);
                    count += k;
                    // sort the buffer by time-stamps
                    sortEventBuffer(buffer, count);
                }
            }
        }

        // send our events to clients...
        const SortedVector< wp<SensorEventConnection> > activeConnections(
                getActiveConnections());
        size_t numConnections = activeConnections.size();
        if(numConnections == 0) {
            //why are we still getting events?
            SLOGE("unexpected event returned from HAL -- no active connections +++++++++++++++++++++");
        }
        for (size_t i=0 ; i<numConnections ; i++) {
            sp<SensorEventConnection> connection(
                    activeConnections[i].promote());
            if (connection != 0) {
                connection->sendEvents(buffer, count, scratch);
            }
        }
    } while (count >= 0 || Thread::exitPending());

    LOGW("Exiting SensorService::threadLoop => aborting...");
    abort();
    return false;
}

void SensorService::recordLastValue(
        sensors_event_t const * buffer, size_t count)
{
    Mutex::Autolock _l(mLock);

    // record the last event for each sensor
    int32_t prev = buffer[0].sensor;
    for (size_t i=1 ; i<count ; i++) {
        // record the last event of each sensor type in this buffer
        int32_t curr = buffer[i].sensor;
        if (curr != prev) {
            mLastEventSeen.editValueFor(prev) = buffer[i-1];
            prev = curr;
        }
    }
    mLastEventSeen.editValueFor(prev) = buffer[count-1];
}

void SensorService::sortEventBuffer(sensors_event_t* buffer, size_t count)
{
    struct compar {
        static int cmp(void const* lhs, void const* rhs) {
            sensors_event_t const* l = static_cast<sensors_event_t const*>(lhs);
            sensors_event_t const* r = static_cast<sensors_event_t const*>(rhs);
            return r->timestamp - l->timestamp;
        }
    };
    qsort(buffer, count, sizeof(sensors_event_t), compar::cmp);
}

SortedVector< wp<SensorService::SensorEventConnection> >
SensorService::getActiveConnections() const
{
    Mutex::Autolock _l(mLock);
    return mActiveConnections;
}

DefaultKeyedVector<int, SensorInterface*>
SensorService::getActiveVirtualSensors() const
{
    Mutex::Autolock _l(mLock);
    return mActiveVirtualSensors;
}

String8 SensorService::getSensorName(int handle) const {
    size_t count = mUserSensorList.size();
    for (size_t i=0 ; i<count ; i++) {
        const Sensor& sensor(mUserSensorList[i]);
        if (sensor.getHandle() == handle) {
            return sensor.getName();
        }
    }
    String8 result("unknown");
    return result;
}

Vector<Sensor> SensorService::getSensorList()
{
    return mUserSensorList;
}

sp<ISensorEventConnection> SensorService::createSensorEventConnection()
{
    sp<SensorEventConnection> result(new SensorEventConnection(this));
    return result;
}

void SensorService::cleanupConnection(SensorEventConnection* c)
{
    Mutex::Autolock _l(mLock);
    const wp<SensorEventConnection> connection(c);
    size_t size = mActiveSensors.size();
    LOGD_IF(DEBUG_CONNECTIONS, "%d active sensors", size);
    for (size_t i=0 ; i<size ; ) {
        int handle = mActiveSensors.keyAt(i);
        if (c->hasSensor(handle)) {
            LOGD_IF(DEBUG_CONNECTIONS, "%i: disabling handle=0x%08x", i, handle);
            SensorInterface* sensor = mSensorMap.valueFor( handle );
            LOGE_IF(!sensor, "mSensorMap[handle=0x%08x] is null!", handle);
            if (sensor) {
                sensor->activate(c, false);
            }
        }
        SensorRecord* rec = mActiveSensors.valueAt(i);
        LOGE_IF(!rec, "mActiveSensors[%d] is null (handle=0x%08x)!", i, handle);
        LOGD_IF(DEBUG_CONNECTIONS,
                "removing connection %p for sensor[%d].handle=0x%08x",
                c, i, handle);

        if (rec && rec->removeConnection(connection)) {
            LOGD_IF(DEBUG_CONNECTIONS, "... and it was the last connection");
            mActiveSensors.removeItemsAt(i, 1);
            mActiveVirtualSensors.removeItem(handle);
            delete rec;
            size--;
        } else {
            i++;
        }
    }
    mActiveConnections.remove(connection);
}

status_t SensorService::enable(const sp<SensorEventConnection>& connection,
        int handle)
{
	SLOGE("enable %d",handle);
    if (mInitCheck != NO_ERROR)
        return mInitCheck;

    Mutex::Autolock _l(mLock);
    SensorInterface* sensor = mSensorMap.valueFor(handle);
	
    status_t err = sensor ? sensor->activate(connection.get(), true) : status_t(BAD_VALUE);
    if (err == NO_ERROR) {
		SLOGE("NO_ERROR");
        SensorRecord* rec = mActiveSensors.valueFor(handle);
        if (rec == 0) {
            rec = new SensorRecord(connection);
            mActiveSensors.add(handle, rec);
			SLOGE("to getSensor>");
			sensor->getSensor ();
            if (sensor->isVirtual()) {
				SLOGE("isVirtual");
                mActiveVirtualSensors.add(handle, sensor);
            }
        } else {
        	SLOGE("else");
            if (rec->addConnection(connection)) {
                // this sensor is already activated, but we are adding a
                // connection that uses it. Immediately send down the last
                // known value of the requested sensor if it's not a
                // "continuous" sensor.
                if (sensor->getSensor().getMinDelay() == 0) {
                    sensors_event_t scratch;
                    sensors_event_t& event(mLastEventSeen.editValueFor(handle));
                    if (event.version == sizeof(sensors_event_t)) {
                        connection->sendEvents(&event, 1);
                    }
                }
            }
        }
        if (err == NO_ERROR) {
            // connection now active
            if (connection->addSensor(handle)) {
                // the sensor was added (which means it wasn't already there)
                // so, see if this connection becomes active
                if (mActiveConnections.indexOf(connection) < 0) {
                    mActiveConnections.add(connection);
                }
            }
        }
    }
    return err;
}

status_t SensorService::disable(const sp<SensorEventConnection>& connection,
        int handle)
{
    if (mInitCheck != NO_ERROR)
        return mInitCheck;

    status_t err = NO_ERROR;
    Mutex::Autolock _l(mLock);
    SensorRecord* rec = mActiveSensors.valueFor(handle);
    if (rec) {
        // see if this connection becomes inactive
        connection->removeSensor(handle);
        if (connection->hasAnySensor() == false) {
            mActiveConnections.remove(connection);
        }
        // see if this sensor becomes inactive
        if (rec->removeConnection(connection)) {
            mActiveSensors.removeItem(handle);
            mActiveVirtualSensors.removeItem(handle);
            delete rec;
        }
        SensorInterface* sensor = mSensorMap.valueFor(handle);
        err = sensor ? sensor->activate(connection.get(), false) : status_t(BAD_VALUE);
    }
    return err;
}

status_t SensorService::setEventRate(const sp<SensorEventConnection>& connection,
        int handle, nsecs_t ns)
{
    if (mInitCheck != NO_ERROR)
        return mInitCheck;

    SensorInterface* sensor = mSensorMap.valueFor(handle);
    if (!sensor)
        return BAD_VALUE;

    if (ns < 0)
        return BAD_VALUE;

    nsecs_t minDelayNs = sensor->getSensor().getMinDelayNs();
    if (ns < minDelayNs) {
        ns = minDelayNs;
    }

    if (ns < MINIMUM_EVENTS_PERIOD)
        ns = MINIMUM_EVENTS_PERIOD;

    return sensor->setDelay(connection.get(), handle, ns);
}

// ---------------------------------------------------------------------------

SensorService::SensorRecord::SensorRecord(
        const sp<SensorEventConnection>& connection)
{
    mConnections.add(connection);
}

bool SensorService::SensorRecord::addConnection(
        const sp<SensorEventConnection>& connection)
{
    if (mConnections.indexOf(connection) < 0) {
        mConnections.add(connection);
        return true;
    }
    return false;
}

bool SensorService::SensorRecord::removeConnection(
        const wp<SensorEventConnection>& connection)
{
    ssize_t index = mConnections.indexOf(connection);
    if (index >= 0) {
        mConnections.removeItemsAt(index, 1);
    }
    return mConnections.size() ? false : true;
}

// ---------------------------------------------------------------------------

SensorService::SensorEventConnection::SensorEventConnection(
        const sp<SensorService>& service)
    : mService(service), mChannel(new SensorChannel())
{
}

SensorService::SensorEventConnection::~SensorEventConnection()
{
    LOGD_IF(DEBUG_CONNECTIONS, "~SensorEventConnection(%p)", this);
    mService->cleanupConnection(this);
}

void SensorService::SensorEventConnection::onFirstRef()
{
}

bool SensorService::SensorEventConnection::addSensor(int32_t handle) {
    Mutex::Autolock _l(mConnectionLock);
    if (mSensorInfo.indexOf(handle) <= 0) {
        mSensorInfo.add(handle);
        return true;
    }
    return false;
}

bool SensorService::SensorEventConnection::removeSensor(int32_t handle) {
    Mutex::Autolock _l(mConnectionLock);
    if (mSensorInfo.remove(handle) >= 0) {
        return true;
    }
    return false;
}

bool SensorService::SensorEventConnection::hasSensor(int32_t handle) const {
    Mutex::Autolock _l(mConnectionLock);
    return mSensorInfo.indexOf(handle) >= 0;
}

bool SensorService::SensorEventConnection::hasAnySensor() const {
    Mutex::Autolock _l(mConnectionLock);
    return mSensorInfo.size() ? true : false;
}

status_t SensorService::SensorEventConnection::sendEvents(
        sensors_event_t const* buffer, size_t numEvents,
        sensors_event_t* scratch)
{
    // filter out events not for this connection
    size_t count = 0;
    if (scratch) {
        Mutex::Autolock _l(mConnectionLock);
        size_t i=0;
        while (i<numEvents) {
            const int32_t curr = buffer[i].sensor;
            if (mSensorInfo.indexOf(curr) >= 0) {
                do {
                    scratch[count++] = buffer[i++];
                } while ((i<numEvents) && (buffer[i].sensor == curr));
            } else {
                i++;
            }
        }
    } else {
        scratch = const_cast<sensors_event_t *>(buffer);
        count = numEvents;
    }

    if (count == 0)
        return 0;

    ssize_t size = mChannel->write(scratch, count*sizeof(sensors_event_t));
    if (size == -EAGAIN) {
        // the destination doesn't accept events anymore, it's probably
        // full. For now, we just drop the events on the floor.
        //LOGW("dropping %d events on the floor", count);
        return size;
    }

    //LOGE_IF(size<0, "dropping %d events on the floor (%s)",
    //        count, strerror(-size));

    return size < 0 ? status_t(size) : status_t(NO_ERROR);
}

sp<SensorChannel> SensorService::SensorEventConnection::getSensorChannel() const
{
    return mChannel;
}

status_t SensorService::SensorEventConnection::enableDisable(
        int handle, bool enabled)
{
    status_t err;
    if (enabled) {
        err = mService->enable(this, handle);
    } else {
        err = mService->disable(this, handle);
    }
    return err;
}

status_t SensorService::SensorEventConnection::setEventRate(
        int handle, nsecs_t ns)
{
    return mService->setEventRate(this, handle, ns);
}

// ---------------------------------------------------------------------------
extern "C" {

//This struct *must* match the one defined in MPLSensor.cpp
typedef struct {
 int (*fpAddGlyph)(unsigned short GlyphID);
 int (*fpBestGlyph)(unsigned short *finalGesture);
 int (*fpSetGlyphSpeedThresh)(unsigned short speed);
 int (*fpStartGlyph)(void);
 int (*fpStopGlyph)(void);
 int (*fpGetGlyph)(int index, int *x, int *y);
 int (*fpGetGlyphLength)(unsigned short *length);
 int (*fpClearGlyph)(void);
 int (*fpLoadGlyphs)(unsigned char *libraryData);
 int (*fpStoreGlyphs)(unsigned char *libraryData, unsigned short *length);
 int (*fpSetGlyphProbThresh)(unsigned short prob);
 int (*fpGetLibraryLength)(unsigned short *length);
} tMplGlyphApi;



tMplGlyphApi* mplGlyphApi_l;

}

/* MPLSysConnection ***************************************************** */

sp<IMplSysConnection> SensorService::createMplSysConnection()
{
    sp<MplSysConnection> result(new MplSysConnection(this));
    return result;
}

SensorService::MplSysConnection::MplSysConnection(
        const sp<SensorService>& service)
        : mService(service)
{
    ;
}

void SensorService::MplSysConnection::onFirstRef()
{
    MplSys_Interface* (*pgsiof)() = (MplSys_Interface*(*)())dlsym(RTLD_DEFAULT, "getSysInterfaceObject");
    if(pgsiof == NULL) {
        SLOGE("could not find symbol for getSysInterfaceObject");
    }

    sys_iface = pgsiof();
    if(sys_iface != NULL) {
        LOGV("getSysInterfaceObject found at %p", &sys_iface);
    }
}

SensorService::MplSysConnection::~MplSysConnection()
{
    ;
}

status_t SensorService::MplSysConnection::test()
{
    LOGV("Test MPL Sys Connection");
    return 4242;
}

status_t SensorService::MplSysConnection::getBiases(float *f)
{
    LOGV("server side getBiases %p %p %p", mplSysApi_l, sys_iface->getBiases, f);
    return (status_t)(sys_iface->getBiases(f));
}

status_t SensorService::MplSysConnection::setBiases(float *f)
{
    return (status_t)(sys_iface->setBiases(f));
}

status_t SensorService::MplSysConnection::setBiasUpdateFunc(long f)
{
    return (status_t)(sys_iface->setBiasUpdateFunc(f));
}

status_t SensorService::MplSysConnection::setSensors(long s)
{
    return (status_t)(sys_iface->setSensors(s));
}

status_t SensorService::MplSysConnection::getSensors(long* s)
{
    return (status_t)(sys_iface->getSensors(s));
}

status_t SensorService::MplSysConnection::resetCal()
{
    return (status_t)(sys_iface->resetCal());
}

status_t SensorService::MplSysConnection::selfTest()
{
    return (status_t)(sys_iface->selfTest());
}

status_t SensorService::MplSysConnection::rpcSetLocalMagField(float x, float y, float z) {
    return (status_t)(sys_iface->setLocalMagField(x, y, z));
}


/* MPLSysPedConnection ***************************************************** */

sp<IMplSysPedConnection> SensorService::createMplSysPedConnection()
{
    sp<MplSysPedConnection> result(new MplSysPedConnection(this));
    return result;
}

SensorService::MplSysPedConnection::MplSysPedConnection(
        const sp<SensorService>& service)
    : mService(service)
{
}

void SensorService::MplSysPedConnection::onFirstRef()
{
    MplSysPed_Interface* (*pgsiof)() = (MplSysPed_Interface*(*)())dlsym(RTLD_DEFAULT, "getSysPedInterfaceObject");
    if(pgsiof == NULL) {
        SLOGE("could not find symbol for getSysPedInterfaceObject");
    } else {
        LOGV("getSysPedInterfaceObject found at %p", mplSysApi_l);
    }

    sysped_iface = pgsiof();
}

SensorService::MplSysPedConnection::~MplSysPedConnection()
{
}

status_t SensorService::MplSysPedConnection::rpcStartPed(void) {
    return sysped_iface->rpcStartPed();
}

status_t SensorService::MplSysPedConnection::rpcStopPed(void) {
    return sysped_iface->rpcStopPed();
}

status_t SensorService::MplSysPedConnection::rpcGetSteps(void) {
    return sysped_iface->rpcGetSteps();
}

double SensorService::MplSysPedConnection::rpcGetWalkTime(void) {
    return sysped_iface->rpcGetWalkTime();
}

status_t SensorService::MplSysPedConnection::rpcClearPedData(void) {
    return sysped_iface->rpcClearPedData();
}

/* ** MPLConnection ********************************************************* */
sp<IMplConnection> SensorService::createMplConnection()
{
    sp<MplConnection> result(new MplConnection(this));
    return result;
}

SensorService::MplConnection::MplConnection(
        const sp<SensorService>& service)
    : mService(service)
{
}

void SensorService::MplConnection::onFirstRef()
{
    mplGlyphApi_l = (tMplGlyphApi*)dlsym(RTLD_DEFAULT, "mplGlyphApi");
    if(mplGlyphApi_l == NULL) {
        SLOGE("could not find symbol for mplGlyphApi");
    } else {
        LOGV("mplGlyphApi found at %p", mplGlyphApi_l);
    }
}

SensorService::MplConnection::~MplConnection()
{
}

/* Glyph apis */
int SensorService::MplConnection::rpcAddGlyph(unsigned short GlyphID)
{
    return mplGlyphApi_l->fpAddGlyph(GlyphID);
}

int SensorService::MplConnection::rpcBestGlyph(unsigned short *finalGesture)
{
    return mplGlyphApi_l->fpBestGlyph(finalGesture);
}
int SensorService::MplConnection::rpcSetGlyphSpeedThresh(unsigned short speed)
{
    return mplGlyphApi_l->fpSetGlyphSpeedThresh(speed);
}
int SensorService::MplConnection::rpcStartGlyph(void)
{
    return mplGlyphApi_l->fpStartGlyph();
}
int SensorService::MplConnection::rpcStopGlyph(void)
{
    return mplGlyphApi_l->fpStopGlyph();
}
int SensorService::MplConnection::rpcGetGlyph(int index, int *x, int *y)
{
    return mplGlyphApi_l->fpGetGlyph(index, x, y);
}
int SensorService::MplConnection::rpcGetGlyphLength(unsigned short *length)
{
    return mplGlyphApi_l->fpGetGlyphLength(length);
}

int SensorService::MplConnection::rpcClearGlyph(void)
{
    return mplGlyphApi_l->fpClearGlyph();
}

int SensorService::MplConnection::rpcLoadGlyphs(unsigned char *libraryData)
{
    return mplGlyphApi_l->fpLoadGlyphs(libraryData);
}

int SensorService::MplConnection::rpcStoreGlyphs(unsigned char *libraryData, unsigned short *length)
{
    return mplGlyphApi_l->fpStoreGlyphs(libraryData, length);
}

int SensorService::MplConnection::rpcSetGlyphProbThresh(unsigned short prob)
{
    return mplGlyphApi_l->fpSetGlyphProbThresh(prob);
}

int SensorService::MplConnection::rpcGetLibraryLength(unsigned short *length)
{
    return mplGlyphApi_l->fpGetLibraryLength(length);
}

}; // namespace android

