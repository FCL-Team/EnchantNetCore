package org.fcl.enchantnetcore.easytier;

public class NativeBridge {

    static {
        System.loadLibrary("enchantnet");
    }

    public static final class NetworkInfo {

        public final String key;
        public final String value;

        public NetworkInfo(String key, String value) {
            this.key = key;
            this.value = value;
        }
    }

    public static native int parseConfig(String cfg);
    public static native int runNetworkInstance(String cfg);
    public static native int setTunFd(String instanceName, int fd);
    public static native int retainNetworkInstance(String[] names);
    public static native NetworkInfo[] getNetworkInfos(int maxLen);
    public static native String getLastError();
}
