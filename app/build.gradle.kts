import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}
android {
    namespace = "app.dot.mail"
    compileSdk = 37
    buildToolsVersion = "36.0.0"
    defaultConfig {
        applicationId = "app.dot.mail"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.1.0-alpha.2"
        testInstrumentationRunner = "app.dot.mail.DemoTestRunner"
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Release payloads are unsigned. Maintainers/F-Droid sign them externally after verification.
            // Developer installs continue to use assembleDebug; no signing secret enters the build graph.
        }
        // Release R8 configuration plus one self-test entry point (proguard-minified-check.pro), never published:
        // proves on ART that the shrunk Bouncy Castle provider still works. See docs/R8_AUDIT.md.
        create("minifiedCheck") {
            initWith(getByName("release"))
            proguardFile("proguard-minified-check.pro")
            matchingFallbacks += "release"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; buildConfig = true }
    testOptions { unitTests.isIncludeAndroidResources = true }
    packaging { resources.excludes += setOf("META-INF/LICENSE.md", "META-INF/NOTICE.md", "META-INF/*.SF") }
    dependenciesInfo { includeInApk = false; includeInBundle = false } // F-Droid reproducibility
}
dependencies {
    implementation(project(":designsystem"))
    implementation(project(":data"))
    implementation(project(":openpgp"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.paging.compose)
    implementation(libs.paging.runtime) // AsyncPagingDataDiffer: the widget materialises one page of PagingData
    implementation(libs.biometric)
    // biometric 1.1.0 drags in fragment 1.2.5, whose FragmentActivity predates the activity-result registry (requestCode crash on any launcher).
    implementation(libs.androidx.fragment)
    implementation(libs.glance.appwidget)
    implementation(libs.androidsvg) // Apache-2.0; renders BIMI brand logos (SVG Tiny PS) to a bitmap
    implementation(libs.richeditor.compose) // Apache-2.0; rich-text email composer
    implementation(libs.work.runtime.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.webkit)
    "minifiedCheckImplementation"(libs.bouncycastle.bcprov) // same versions as :mail; only the self-test calls BC directly
    "minifiedCheckImplementation"(libs.bouncycastle.bcpkix)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.truth)
    androidTestImplementation(libs.espresso.core)
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

// Store captures use only the in-memory demo graph and run explicitly, never against an installed account.
tasks.withType<Test>().configureEach {
    systemProperty("dotmail.storeShots", providers.gradleProperty("dotmail.storeShots").orElse("false").get())
}

// Persisted notices must cover the exact resolved release graph, including platform constraints.
val verifyReleaseNotices by tasks.registering {
    val inventory = layout.projectDirectory.file("src/main/assets/third-party-components.json")
    val bundle = layout.projectDirectory.file("src/main/assets/third-party-notices.txt")
    inputs.files(inventory, bundle)
    // Resolve each invocation so a new transitive dependency cannot reuse an old up-to-date result.
    outputs.upToDateWhen { false }
    doLast {
        val manifest = groovy.json.JsonSlurper().parse(inventory.asFile) as Map<*, *>
        val documented = (manifest["components"] as Map<*, *>).keys.map { it.toString() }.toSet()
        val resolved = configurations.getByName("releaseRuntimeClasspath").incoming.resolutionResult.allComponents
            .mapNotNull { it.id as? org.gradle.api.artifacts.component.ModuleComponentIdentifier }
            .map { "${it.group}:${it.module}:${it.version}" }.toSet()
        check(resolved == documented) {
            "Release notices out of date. Missing: ${resolved - documented}; stale: ${documented - resolved}. See docs/THIRD_PARTY_NOTICES.md"
        }
        val hash = MessageDigest.getInstance("SHA-256").digest(bundle.asFile.readBytes())
            .joinToString("") { "%02x".format(it) }
        check(hash == manifest["bundle_sha256"]) { "Notices bundle hash differs from reviewed inventory" }
    }
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn(verifyReleaseNotices) }

apply(from = rootProject.file("scripts/license-inventory.gradle"))

// The reviewed asset can exclude a module only while this build proves its absence.
val verifyShippedNotices by tasks.registering(Exec::class) {
    dependsOn("packageRelease", "exportReleaseLicenseInventory")
    workingDir(rootProject.projectDir)
    commandLine(
        "python3", "scripts/notice_footprint.py",
        "--inventory", "app/build/reports/notices/resolved.json",
        "--apk", "app/build/outputs/apk/release/app-release-unsigned.apk",
        "--mapping", "app/build/outputs/mapping/release/mapping.txt",
        "--usage", "app/build/outputs/mapping/release/usage.txt",
        "--reviewed", "app/src/main/assets/third-party-components.json",
        "--report", "app/build/reports/notices/footprint.json",
    )
}
tasks.matching { it.name == "assembleRelease" }.configureEach { dependsOn(verifyShippedNotices) }
