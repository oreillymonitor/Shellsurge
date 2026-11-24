# R8 keep rules.
#
# sshj and BouncyCastle pick implementations by name at runtime, so a class reached only that way
# looks unused to R8 and gets shrunk away. It fails silently: an auth failure, a missing algorithm
# or a hang, never a clean ClassNotFoundException. Keep the rules narrow and tied to something that
# actually broke.

# Transport, cipher, kex, mac, compression, signature and key-file formats are all resolved by
# algorithm name through NamedFactory registries, never by a `new` R8 can trace.
-keep class net.schmizz.sshj.** { *; }
-keep interface net.schmizz.sshj.** { *; }

# The JCA provider is resolved by name through java.security.Security, and the PEM/OpenSSH key
# parsers (including passphrase-encrypted keys) are instantiated the same way.
-keep class org.bouncycastle.** { *; }
-keep interface org.bouncycastle.** { *; }

# minifyRelease failed outright with "Missing classes detected" for javax.naming.* (BouncyCastle's
# JNDI/LDAP cert stores), org.ietf.jgss.* and javax.security.auth.login.LoginContext (sshj's GSSAPI
# auth). None of it exists on Android and none of it is reachable from here, so -dontwarn rather
# than -keep: there is nothing to keep.
-dontwarn javax.naming.**
-dontwarn javax.security.auth.login.LoginContext
-dontwarn org.ietf.jgss.**
-keepclassmembers class * extends androidx.work.InputMerger {
    <init>();
}

# ML Kit GenAI Prompt (Gemini Nano via Android AICore) relies on SafeParcelable Binder IPC,
# shaded Gson, and ML Kit ComponentRegistrar reflection. Without keeping these packages and
# generic signatures, R8 full mode strips/obfuscates internal AICore client classes and causes
# checkStatus()/generateContent() to fail at runtime in release builds.
-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*
-keep class com.google.mlkit.genai.** { *; }
-keep interface com.google.mlkit.genai.** { *; }
-keep class com.google.mlkit.common.** { *; }
-keep interface com.google.mlkit.common.** { *; }
-keep class com.google.android.gms.internal.mlkit_genai_prompt.** { *; }
-keep interface com.google.android.gms.internal.mlkit_genai_prompt.** { *; }
-keep class com.google.android.gms.internal.mlkit_genai_common.** { *; }
-keep interface com.google.android.gms.internal.mlkit_genai_common.** { *; }
-keep class com.google.android.gms.internal.mlkit_common.** { *; }
-keep interface com.google.android.gms.internal.mlkit_common.** { *; }
-dontwarn com.google.mlkit.genai.**
-dontwarn com.google.android.gms.internal.mlkit_genai_prompt.**
-dontwarn com.google.android.gms.internal.mlkit_genai_common.**
