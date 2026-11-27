plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release credentials are injected by the build environment. Never commit a keystore
// or signing passwords to this repository.
val alphaStoreFile = providers.environmentVariable("NEXORA_ALPHA_STORE_FILE").orNull
val alphaStorePassword = providers.environmentVariable("NEXORA_ALPHA_STORE_PASSWORD").orNull
val alphaKeyAlias = providers.environmentVariable("NEXORA_ALPHA_KEY_ALIAS").orNull
val alphaKeyPassword = providers.environmentVariable("NEXORA_ALPHA_KEY_PASSWORD").orNull
val alphaSigningValues = listOf(alphaStoreFile, alphaStorePassword, alphaKeyAlias, alphaKeyPassword)
val alphaSigningConfigured = alphaSigningValues.all { !it.isNullOrBlank() }
val alphaSigningPartiallyConfigured = alphaSigningValues.any { !it.isNullOrBlank() } && !alphaSigningConfigured

if (alphaSigningPartiallyConfigured) {
    throw GradleException(
        "Incomplete Alpha signing configuration. Set NEXORA_ALPHA_STORE_FILE, " +
            "NEXORA_ALPHA_STORE_PASSWORD, NEXORA_ALPHA_KEY_ALIAS and NEXORA_ALPHA_KEY_PASSWORD."
    )
}

val releaseStoreFile = providers.environmentVariable("NEXORA_RELEASE_STORE_FILE").orNull
val releaseStorePassword = providers.environmentVariable("NEXORA_RELEASE_STORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("NEXORA_RELEASE_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("NEXORA_RELEASE_KEY_PASSWORD").orNull
val releaseSigningValues = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword
)
val releaseSigningConfigured = releaseSigningValues.all { !it.isNullOrBlank() }
val releaseSigningPartiallyConfigured = releaseSigningValues.any { !it.isNullOrBlank() } && !releaseSigningConfigured

if (releaseSigningPartiallyConfigured) {
    throw GradleException(
        "Incomplete release signing configuration. Set NEXORA_RELEASE_STORE_FILE, " +
            "NEXORA_RELEASE_STORE_PASSWORD, NEXORA_RELEASE_KEY_ALIAS and NEXORA_RELEASE_KEY_PASSWORD."
    )
}

android {
    namespace = "com.nexora.browser"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.nexora.browser"
        minSdk = 26
        targetSdk = 34
        versionCode = 33
        versionName = "0.4.0-alpha29"
    }
    signingConfigs {
        if (alphaSigningConfigured) {
            create("alpha") {
                storeFile = file(requireNotNull(alphaStoreFile))
                storePassword = requireNotNull(alphaStorePassword)
                keyAlias = requireNotNull(alphaKeyAlias)
                keyPassword = requireNotNull(alphaKeyPassword)
            }
        }
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = file(requireNotNull(releaseStoreFile))
                storePassword = requireNotNull(releaseStorePassword)
                keyAlias = requireNotNull(releaseKeyAlias)
                keyPassword = requireNotNull(releaseKeyPassword)
            }
        }
    }
    buildTypes {
        create("alpha") {
            initWith(getByName("debug"))
            isDebuggable = false
            isMinifyEnabled = false
            isShrinkResources = false
            matchingFallbacks += listOf("debug", "release")
            // Alpha builds must use the exact Alpha23 keystore. The verification task
            // below prevents a runner-generated debug key from becoming an update APK.
            signingConfig = if (alphaSigningConfigured) {
                signingConfigs.getByName("alpha")
            } else {
                signingConfigs.getByName("debug")
            }
        }
        release {
            // Keep shrinking disabled until the release smoke suite covers reflection,
            // WebView integrations and backup/restore on supported devices.
            isMinifyEnabled = false
            isShrinkResources = false
            isDebuggable = false
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            isMinifyEnabled = false
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { viewBinding = true }
}

val publishedTemplateAssets = tasks.register<Sync>("syncPublishedTemplateAssets") {
    from(rootProject.file("web/ua_templates.json"))
    into(layout.buildDirectory.dir("generated/publishedTemplateAssets"))
}
android.sourceSets["main"].assets.srcDir(publishedTemplateAssets)
tasks.named("preBuild") { dependsOn(publishedTemplateAssets) }

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("com.google.guava:guava:33.5.0-android")
    implementation("androidx.activity:activity-ktx:1.9.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.vaadin.external.google:android-json:0.0.20131108.vaadin1")
}


tasks.register("verifyReleaseSigningConfiguration") {
    group = "verification"
    description = "Verifies that a private release keystore and all signing values are configured."
    doLast {
        if (!releaseSigningConfigured) {
            throw GradleException(
                "Release signing is not configured. Set NEXORA_RELEASE_STORE_FILE, " +
                    "NEXORA_RELEASE_STORE_PASSWORD, NEXORA_RELEASE_KEY_ALIAS and NEXORA_RELEASE_KEY_PASSWORD."
            )
        }
        val keystore = file(requireNotNull(releaseStoreFile))
        if (!keystore.isFile) {
            throw GradleException("Release keystore does not exist: ${keystore.absolutePath}")
        }
    }
}


tasks.register("verifyAlphaSigningConfiguration") {
    group = "verification"
    description = "Verifies the original Alpha23 signing keystore is configured for upgradeable alpha builds."
    doLast {
        if (!alphaSigningConfigured) {
            throw GradleException(
                "Alpha signing is not configured. Supply the original Alpha23 keystore using " +
                    "NEXORA_ALPHA_STORE_FILE, NEXORA_ALPHA_STORE_PASSWORD, " +
                    "NEXORA_ALPHA_KEY_ALIAS and NEXORA_ALPHA_KEY_PASSWORD. " +
                    "A runner-generated debug key is not upgrade-compatible."
            )
        }
        val keystore = file(requireNotNull(alphaStoreFile))
        if (!keystore.isFile) {
            throw GradleException("Alpha keystore does not exist: ${keystore.absolutePath}")
        }
    }
}

tasks.configureEach {
    if (name == "assembleAlpha" || name == "bundleAlpha") {
        dependsOn("verifyAlphaSigningConfiguration")
    }
}
