# Regras aplicadas automaticamente aos apps que consomem a biblioteca.
# Mantém as classes dos leitores e os SDKs de fabricantes (usam reflexão/JNI).
-keep class com.smartx.rfid.** { *; }
-keep class com.rscja.** { *; }
-keep class com.honeywell.** { *; }
-keep class com.zebra.** { *; }
-keep class com.uk.tsl.** { *; }
-keep class com.ubx.** { *; }
-keep class android.device.** { *; }
-dontwarn com.rscja.**
-dontwarn com.honeywell.**
-dontwarn com.zebra.**
-dontwarn com.uk.tsl.**
-dontwarn com.ubx.**
