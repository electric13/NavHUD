package com.geely.platformapi.binder;

/**
 * Compile-only stub for Geely PlatformAPI connectable binder interface.
 */
public interface IConnectable {
    interface IConnectWatcher {
        void onConnected();

        void onDisConnected();
    }

    void connect();

    void disconnect();

    void registerConnectWatcher(IConnectWatcher watcher);

    void unregisterConnectWatcher();
}
