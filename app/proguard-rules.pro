-keep class com.enderthor.kvpartner.datatype.** { *; }
-keepclassmembers enum * { public static **[] values(); public static ** valueOf(java.lang.String); }

# ─── kotlinx.serialization ───────────────────────────────────────────────────
# R8 strips the @Serializable annotations and the generated $$serializer classes
# unless explicitly kept, which makes a release build fail to encode/decode config.
-keepattributes *Annotation*, InnerClasses

# Keep the data model and its generated serializers.
-keep class com.enderthor.kvpartner.data.** { *; }

# Standard kotlinx.serialization keeps.
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    *** serializer(...);
}
-keepclasseswithmembers class ** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.enderthor.kvpartner.**$$serializer { *; }
