# Jakarta Mail / Angus load providers and handlers reflectively.
-keep class org.eclipse.angus.** { *; }
-keep class jakarta.mail.** { *; }
-keep class jakarta.activation.** { *; }
-keep class com.sun.mail.** { *; }
-dontwarn java.awt.**
-dontwarn javax.security.sasl.**
-dontwarn javax.naming.**
-dontwarn org.eclipse.angus.**
-dontwarn jakarta.**
-dontwarn org.jspecify.**
-dontwarn com.google.re2j.**

# Bouncy Castle (S/MIME). The BouncyCastleProvider instance used by the CMS engine (never registered globally) loads its
# algorithm tables by class name ("...digest.SHA256$Mappings") and instantiates each SPI from a string. Keep those names
# and constructors; everything else in BC is shrunk normally. Proven on ART by the minifiedCheck self-test (docs/R8_AUDIT.md).
-keep class org.bouncycastle.jcajce.provider.** { <init>(...); }
-keep class org.bouncycastle.jce.provider.BouncyCastleProvider { <init>(...); }
-keep class org.bouncycastle.pqc.jcajce.provider.** { <init>(...); }
