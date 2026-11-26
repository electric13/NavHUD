package geely.car;

import android.content.Context;

/**
 * Compile-only stub for Geely OneOS car SDK entry point.
 */
public class GeelyCar {
    public static final String SIGNAL_SERVICE = "car_signal";
    public static final String SERVICE_NAME = "geelycar_service";

    public static GeelyCar createCar(Context context, IGeelyCar service) {
        return null;
    }

    public Object getCarManager(String serviceName) {
        return null;
    }
}
