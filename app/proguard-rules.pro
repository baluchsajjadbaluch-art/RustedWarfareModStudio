# Rusted Warfare Ultimate Mod Studio — R8 rules
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod

# kotlinx.coroutines
-dontwarn kotlinx.coroutines.**

# Coil
-dontwarn coil.**

# Keep Java zip/regex metadata used by the INI parser
-keep class java.util.zip.** { *; }
