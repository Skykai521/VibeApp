plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.shadow)
    application
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.gradle.tooling.api)
    implementation(libs.slf4j.simple)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

application {
    mainClass.set("com.vibe.gradle.host.MainKt")
}

tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    archiveBaseName.set("vibeapp-gradle-host")
    archiveVersion.set("1.0.0")
    archiveClassifier.set("all")
    // Slim the fat JAR: merge service files, drop module-info, don't touch
    // org.gradle.* (Tooling API protocol classes must stay at their
    // original package).
    mergeServiceFiles()
    exclude("module-info.class")
}
