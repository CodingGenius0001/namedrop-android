# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.keshav.daptap.**$$serializer { *; }
-keepclassmembers class com.keshav.daptap.** { *** Companion; }
-keepclasseswithmembers class com.keshav.daptap.** { kotlinx.serialization.KSerializer serializer(...); }
# OkHttp
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# NameDrop exchange stack (kwik QUIC/TLS, QPACK, dd-plist): keep it whole; it is small and partly reflective.
-keep class tech.kwik.** { *; }
-keep class com.dd.plist.** { *; }
-keep class at.favre.lib.hkdf.** { *; }
-dontwarn tech.kwik.**
-dontwarn com.dd.plist.**
-dontwarn java.net.http.**
-dontwarn javax.xml.**
-dontwarn org.xml.**

# The X25519 JCA provider's engines are instantiated by name through reflection.
-keep class com.keshav.daptap.namedrop.XdhProvider { *; }
-keep class com.keshav.daptap.namedrop.XdhProvider$* { *; }

# The iPhone link helper is started by name through app_process (see applink.AppleLink).
-keep class com.keshav.daptap.helper.LinkHelper { public static void main(java.lang.String[]); }
