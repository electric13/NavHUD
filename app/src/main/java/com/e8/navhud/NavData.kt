package com.e8.navhud

/**
 * Snapshot of navigation route and speed fields extracted from Yandex.Navigator.
 */
data class NavData(
    val maneuverDesc: String = "",
    val distToManeuver: String = "",
    val currentStreet: String = "",
    val nextStreet: String = "",
    val speed: String = "",
    val speedLimit: String = "",
    val eta: String = "",
    val totalDistance: String = "",
    val travelTime: String = ""
) {
    val hasRoute: Boolean
        get() = maneuverDesc.isNotBlank() || distToManeuver.isNotBlank() || eta.isNotBlank()

    val hasSpeed: Boolean
        get() = speed.isNotBlank()
}
