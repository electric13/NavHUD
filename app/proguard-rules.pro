# NavHUD R8 optimization & shrinking rules.
# Entry points (MainActivity, HudService, BootReceiver, NavAccessibilityService, NavNotificationListener)
# are preserved automatically via AAPT2 generated rules (aapt_rules.txt).

-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Compile-only Geely OneOS / PlatformAPI / hidden Android ServiceManager stubs live on device bootclasspath.
-dontwarn android.os.ServiceManager
-dontwarn com.geely.platformapi.**
-dontwarn geely.car.**
