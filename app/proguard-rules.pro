# Add project specific ProGuard rules here.
-keep class com.dailydiary.** { *; }
-keep class javax.mail.** { *; }
-keep class com.sun.mail.** { *; }
-dontwarn javax.mail.**
-dontwarn com.sun.mail.**
-dontwarn javax.activation.**
