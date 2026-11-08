// Vendored OpenKeychain OpenPGP API (github.com/open-keychain/openpgp-api @ f91db6b, Apache-2.0, see LICENSE).
// Only the AIDL contract and its helper classes; the two legacy Preference widgets are left out. Not on Maven Central,
// so it is built from source here (F-Droid friendly) — exactly what K-9 Mail / Thunderbird for Android do.
plugins {
    alias(libs.plugins.android.library)
}
android {
    namespace = "org.openintents.openpgp"
    compileSdk = 37
    buildToolsVersion = "36.0.0"
    defaultConfig { minSdk = 26 }
    buildFeatures { aidl = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint { disable += setOf("InvalidPackage") }
}
