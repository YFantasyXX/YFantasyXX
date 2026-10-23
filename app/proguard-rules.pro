# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in $ANDROID_HOME/tools/proguard/proguard-android.txt

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**

# Keep Room entities (used with KSP)
-keep class com.yfantasyxx.data.db.** { *; }

# 保留 Room 生成的实现
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }

# DataStore
-keep class androidx.datastore.** { *; }
-dontwarn androidx.datastore.**

# 保留崩溃日志里的堆栈行号
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
