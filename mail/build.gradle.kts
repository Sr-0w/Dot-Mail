// Pure-JVM mail engine: IMAP/SMTP/MIME on Jakarta Mail (Eclipse Angus). No Android deps,
// so it is unit-tested on the JVM against a real GreenMail server.
plugins {
    alias(libs.plugins.kotlin.jvm)
}
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.jakarta.mail.api)
    implementation(libs.angus.mail)
    implementation("org.eclipse.angus:angus-activation:2.0.3")
    api("jakarta.activation:jakarta.activation-api:2.1.4")
    implementation(libs.jsoup)
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.greenmail)
}

// Live opt-in is explicit, credential-free, and never satisfied from cached test results.
tasks.withType<Test>().configureEach {
    val live = providers.environmentVariable("DOTMAIL_LIVE").orElse("0")
    environment("DOTMAIL_LIVE", live.get())
    inputs.property("dotmail.live", live)
    if (live.get() == "1") {
        outputs.upToDateWhen { false }
        outputs.cacheIf { false }
    }
}
