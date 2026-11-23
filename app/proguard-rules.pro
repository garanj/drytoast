# Dry Toast R8 / ProGuard Configuration

# Preserve line numbers for local crash diagnostics while stripping absolute host paths
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Navigation 3 @Serializable routes & Kotlinx Serialization
-keep @kotlinx.serialization.Serializable class com.garan.drytoast.** { *; }
-keepclassmembers class com.garan.drytoast.** {
    *** Companion;
}
-keepclasseswithmembers class com.garan.drytoast.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep class com.garan.drytoast.DryToastRoute** { *; }

# DPC & System Broadcast Entry Points
-keep class com.garan.drytoast.dpc.DryToastDeviceAdminReceiver { *; }
-keep class com.garan.drytoast.dpc.DryToastProvisioningActivity { *; }
-keep class com.garan.drytoast.settings.SettingsLockReceiver { *; }
-keep class com.garan.drytoast.schedule.ScheduleAlarmReceiver { *; }
