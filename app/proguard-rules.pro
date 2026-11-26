# Keep ZeroTier JNI bridge classes and fields intact if minification is ever enabled.
-keep class com.zerotier.sockets.** { *; }
-keepclassmembers class com.zerotier.sockets.ZeroTierSocket {
    private boolean _isConnected;
}
