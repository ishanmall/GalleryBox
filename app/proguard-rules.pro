##################################################
# GALLERYBOX PRODUCTION RULES
##################################################

#############################
# ATTRIBUTES
#############################
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes InnerClasses
-keepattributes EnclosingMethod

#############################
# NATIVE METHODS
#############################
-keepclasseswithmembernames class * {
    native <methods>;
}

#############################
# ROOM
#############################
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keepclassmembers class * {
    @androidx.room.PrimaryKey <fields>;
}

#############################
# HILT / DAGGER
#############################
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }

#############################
# GSON
#############################
-keepattributes Signature
-keepattributes *Annotation*

-keep class com.google.gson.** { *; }

-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

#############################
# RETROFIT
#############################
-keepattributes RuntimeVisibleAnnotations
-keepattributes RuntimeVisibleParameterAnnotations

#############################
# MEDIA3
#############################
-dontwarn androidx.media3.**

#############################
# COIL
#############################
-dontwarn coil.**

#############################
# COROUTINES
#############################
-dontwarn kotlinx.coroutines.**

#############################
# PDFBOX
#############################
-keep class com.tom_roush.pdfbox.** { *; }
-dontwarn com.tom_roush.pdfbox.**

#############################
# APACHE POI
#############################
-keep class org.apache.poi.** { *; }

-dontwarn org.apache.poi.**
-dontwarn org.apache.xmlbeans.**
-dontwarn org.openxmlformats.**
-dontwarn org.apache.commons.compress.**
-dontwarn org.bouncycastle.**

#############################
# XMLBEANS
#############################
-keep class org.apache.xmlbeans.** { *; }

#############################
# FIREBASE
#############################
-dontwarn com.google.firebase.**

#############################
# CAMERA X
#############################
-dontwarn androidx.camera.**

#############################
# WORKMANAGER
#############################
-keep class * extends androidx.work.ListenableWorker

#############################
# DESKTOP JAVA CLASSES
#############################
-dontwarn java.awt.**
-dontwarn java.beans.**
-dontwarn java.sql.**
-dontwarn java.lang.management.**

-dontwarn javax.script.**
-dontwarn javax.sql.**
-dontwarn javax.tools.**
-dontwarn javax.xml.**
-dontwarn javax.xml.stream.**
-dontwarn javax.xml.transform.**
-dontwarn javax.management.**
-dontwarn javax.transaction.**
-dontwarn javax.security.**
-dontwarn javax.naming.**

#############################
# TENSORFLOW LITE / AI EDGE
#############################
-dontwarn org.tensorflow.lite.gpu.GpuDelegateFactory$Options$GpuBackend
-dontwarn org.tensorflow.lite.gpu.GpuDelegateFactory$Options

#############################
# SHRINK / OBFUSCATE
#############################
-allowaccessmodification
-renamesourcefileattribute GalleryBox

##################################################
# END
##################################################