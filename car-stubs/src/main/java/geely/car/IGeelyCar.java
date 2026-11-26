package geely.car;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;

/**
 * Compile-only stub for Geely OneOS binder interface.
 */
public interface IGeelyCar extends IInterface {
    abstract class Stub extends Binder implements IGeelyCar {
        public static IGeelyCar asInterface(IBinder obj) {
            return null;
        }
    }
}
