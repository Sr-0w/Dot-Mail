// Local store (Room), repositories, sync (WorkManager), credentials (Keystore).
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
}
fun oauthProperty(name: String) = providers.gradleProperty(name).orElse("").get().let {
    require(it.matches(Regex("[A-Za-z0-9._-]*"))) { "OAuth client ID contains invalid characters" }
    "\"$it\""
}
fun googleOAuth(type: com.android.build.api.dsl.LibraryBuildType, clientId: String) {
    require(clientId.matches(Regex("[A-Za-z0-9._-]*"))) { "OAuth client ID contains invalid characters" }
    val scheme = if (clientId.isBlank()) "app.dot.mail.oauth.google.unconfigured" else "com.googleusercontent.apps.${clientId.removeSuffix(".apps.googleusercontent.com")}"
    type.manifestPlaceholders["dotmailGoogleOAuthScheme"] = scheme
    type.buildConfigField("String", "OAUTH_GOOGLE_REDIRECT_SCHEME", "\"$scheme\"")
    type.buildConfigField("String", "OAUTH_GOOGLE_CLIENT_ID", "\"$clientId\"")
}
android {
    namespace = "app.dot.mail.data"
    compileSdk = 37
    buildToolsVersion = "36.0.0"
    buildFeatures { buildConfig = true }
    defaultConfig {
        minSdk = 26
        buildConfigField("String", "OAUTH_MICROSOFT_CLIENT_ID", oauthProperty("dotmail.oauth.microsoft.clientId"))
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes {
        // Google ties an Android client ID to the signing certificate, so debug builds (debug key) may use their own
        // client: dotmail.oauth.google.clientId.debug, falling back to the release one.
        getByName("release") { googleOAuth(this, providers.gradleProperty("dotmail.oauth.google.clientId").orElse("").get()) }
        getByName("debug") {
            googleOAuth(this, providers.gradleProperty("dotmail.oauth.google.clientId.debug")
                .orElse(providers.gradleProperty("dotmail.oauth.google.clientId")).orElse("").get())
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
    packaging { resources.excludes += setOf("META-INF/LICENSE.md", "META-INF/NOTICE.md", "META-INF/*.SF") }
}
ksp { arg("room.schemaLocation", "$projectDir/schemas") }
dependencies {
    api(project(":mail"))
    api(libs.kotlinx.coroutines.android)
    api(libs.paging.common)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.serialization.json) // settings backup (pure JSON model, JVM-testable)
    implementation("androidx.browser:browser:1.8.0")
    implementation(libs.jsoup)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    implementation(libs.room.paging)
    ksp(libs.room.compiler)
    implementation(libs.work.runtime.ktx)
    implementation(libs.androidx.lifecycle.process)
    testImplementation(libs.junit)
    testImplementation(libs.paging.testing)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.room.testing)
    testImplementation(libs.work.testing)
    testImplementation(libs.greenmail)
}

// Exported schemas are JVM-test resources for real upgrade validation.
android.sourceSets.getByName("test").resources.srcDir("$projectDir/schemas")

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
