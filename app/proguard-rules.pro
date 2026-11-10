# Release shrinking (22 Oct 2026, 0.14.x). Goal: a smaller APK with behaviour identical to the unshrunk build.
# So only UNUSED LIBRARY code is removed (mostly the extended Material icon set). The app's own classes are all kept,
# nothing is renamed and nothing is optimised: reflection, WebView JS bridges, Room, tool specs and crash stack traces
# behave exactly as before.
-dontobfuscate
-dontoptimize
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,SourceFile,LineNumberTable,Exceptions

# Everything the app itself defines.
-keep class com.past9.phoneaos.** { *; }

# SSH (sshj) and BouncyCastle pick algorithms, key types and providers by name.
-keep class org.bouncycastle.** { *; }
-keep class net.schmizz.** { *; }
-keep class com.hierynomus.** { *; }

# Pages call into the app through @JavascriptInterface methods.
-keepclassmembers class * { @android.webkit.JavascriptInterface <methods>; }
-keep class net.i2p.crypto.** { *; }

# sshj's Kerberos (GSSAPI) login and EdDSA refer to desktop-Java classes Android never has; that code is never reached on a phone.
-dontwarn javax.security.auth.login.LoginContext
-dontwarn org.ietf.jgss.**
-dontwarn sun.security.x509.X509Key

# Wake word (sherpa-onnx): its native library reads and fills these classes' fields by name.
-keep class com.k2fsa.sherpa.onnx.** { *; }

# ML Kit (on-device OCR) finds its components by class name from the manifest: keep them whole.
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_** { *; }
-keep class * implements com.google.firebase.components.ComponentRegistrar { <init>(); }
