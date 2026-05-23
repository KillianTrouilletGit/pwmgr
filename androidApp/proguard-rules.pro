# PwMgr ProGuard / R8 rules.
#
# The default file `proguard-android-optimize.txt` already covers Android framework keeps.
# Compose's R8 rules ship in compose-runtime's AAR (`compose-rules.pro`) and are applied
# automatically. We only add app-specific rules here.

# kotlinx.serialization — keep the generated $$serializer companions and the @Serializable
# annotated classes themselves. R8 can otherwise strip the synthesized companions because
# they're only referenced reflectively at runtime.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
-keepclassmembers class **$Companion {
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
-if @kotlinx.serialization.Serializable class ** {
    static **$Companion Companion;
}
-keepclassmembers class <2>$Companion {
    kotlinx.serialization.KSerializer serializer(...);
}

# AutofillService entry points are referenced from the Android manifest, not from Kotlin
# code that R8 sees. Keep them and their constructors.
-keep class com.pwmgr.android.autofill.PwMgrAutofillService { *; }
-keep class com.pwmgr.android.autofill.AutofillUnlockActivity { *; }
-keep class com.pwmgr.android.MainActivity { *; }

# BouncyCastle Argon2 — reflection through Argon2BytesGenerator.
-keep class org.bouncycastle.crypto.generators.Argon2BytesGenerator { *; }
-keep class org.bouncycastle.crypto.params.Argon2Parameters { *; }
-keep class org.bouncycastle.crypto.params.Argon2Parameters$Builder { *; }

# Keep our shared model classes — they're serialized to JSON and any field rename would
# break vault decode. Belt-and-braces alongside the @Serializable rules above.
-keep class com.pwmgr.core.model.** { *; }
-keep class com.pwmgr.core.format.** { *; }
-keep class com.pwmgr.storage.OAuthAccount { *; }
