package com.geely.platformapi.car.sensor;

/**
 * Compile-only stub for Geely PlatformAPI sensor interface.
 */
public interface ISensor {
    int SENSOR_TYPE_EGY_LVL_ELEC_MAI = 1070592;
    int SENSOR_TYPE_EV_BATTERY_PERCENT = 4195840; // 0x400600

    float getSensorLatestValue(int sensorType);
}
