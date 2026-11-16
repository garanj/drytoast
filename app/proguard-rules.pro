# Threshold R8 / ProGuard Configuration

# Preserve line numbers for local crash diagnostics while stripping absolute host paths
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Navigation 3 @Serializable routes & Kotlinx Serialization
-keep @kotlinx.serialization.Serializable class com.garan.threshold.** { *; }
-keepclassmembers class com.garan.threshold.** {
    *** Companion;
}
-keepclasseswithmembers class com.garan.threshold.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep class com.garan.threshold.ThresholdRoute** { *; }

# DPC & System Broadcast Entry Points
-keep class com.garan.threshold.dpc.ThresholdDeviceAdminReceiver { *; }
-keep class com.garan.threshold.dpc.ThresholdProvisioningActivity { *; }
-keep class com.garan.threshold.settings.SettingsLockReceiver { *; }
-keep class com.garan.threshold.schedule.ScheduleAlarmReceiver { *; }
