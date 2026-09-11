import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

val rustProjectDir = file("src/main/rust/hoshiepub")
val uniffiOutDir = layout.buildDirectory.dir("generated/source/uniffi/main/kotlin").get().asFile
val rustDebugJniLibsDir = layout.buildDirectory.dir("jniLibs/debug").get().asFile
val rustReleaseJniLibsDir = layout.buildDirectory.dir("jniLibs/release").get().asFile

val isWindows = System.getProperty("os.name").lowercase().contains("win")
val userHome = if (isWindows) {
    System.getenv("USERPROFILE") ?: System.getProperty("user.home") ?: ""
} else {
    System.getProperty("user.home") ?: System.getenv("HOME") ?: ""
}
val cargoExeName = if (isWindows) "cargo.exe" else "cargo"
val cargoFallback = file("$userHome/.cargo/bin/$cargoExeName").takeIf { it.exists() }?.absolutePath ?: cargoExeName
val cargo = System.getenv("CARGO") ?: cargoFallback

val targetNdkVersion = "29.0.14206865"
val localSdkDir = rootProject.file("local.properties").takeIf { it.isFile }?.let { propertiesFile ->
    Properties().apply {
        propertiesFile.inputStream().use { input -> load(input) }
    }.getProperty("sdk.dir")
}
val defaultSdkDirs = when {
    isWindows -> listOfNotNull(
        System.getenv("LOCALAPPDATA")?.let { file(it).resolve("Android/Sdk") },
        file(userHome).resolve("AppData/Local/Android/Sdk"),
    )
    System.getProperty("os.name").lowercase().contains("mac") -> listOf(
        file(userHome).resolve("Library/Android/sdk"),
    )
    else -> listOf(
        file(userHome).resolve("Android/Sdk"),
    )
}
val sdkDirCandidates = (
    sequenceOf(
    System.getenv("ANDROID_HOME"),
    System.getenv("ANDROID_SDK_ROOT"),
        localSdkDir,
    ).filterNotNull().map { file(it) } + defaultSdkDirs.asSequence()
    )
    .filter { it.isDirectory }
    .distinctBy { it.absolutePath }
    .toList()
val defaultNdkDir = sdkDirCandidates.asSequence()
    .map { it.resolve("ndk").resolve(targetNdkVersion) }
    .firstOrNull { it.isDirectory }
    ?.absolutePath

val configuredNdkDir = sequenceOf(
    System.getenv("ANDROID_NDK_HOME"),
    providers.gradleProperty("androidNdkHome").orNull,
).filterNotNull()
    .map { file(it) }
    .firstOrNull { it.isDirectory }
    ?.absolutePath
val androidNdkHome = configuredNdkDir ?: defaultNdkDir
    ?: throw GradleException(
        "Android NDK $targetNdkVersion was not found. Set ANDROID_NDK_HOME or androidNdkHome."
    )

val releaseKeystorePath = providers.environmentVariable("ANDROID_KEYSTORE_FILE").orNull
val releaseKeystorePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").orNull
val releaseVersionName = providers.gradleProperty("releaseVersionName").orNull
val releaseVersionCode = providers.gradleProperty("releaseVersionCode").orNull?.toIntOrNull()
if (providers.gradleProperty("releaseVersionCode").isPresent && releaseVersionCode == null) {
    throw GradleException("releaseVersionCode must be an integer.")
}
val releaseSigningValues = listOf(
    releaseKeystorePath,
    releaseKeystorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
)
val isReleaseSigningRequested = releaseSigningValues.any { !it.isNullOrBlank() }
val isReleaseSigningConfigured = releaseSigningValues.all { !it.isNullOrBlank() } &&
    releaseKeystorePath?.let { file(it).isFile } == true

if (isReleaseSigningRequested && !isReleaseSigningConfigured) {
    throw GradleException(
        "Release signing requires ANDROID_KEYSTORE_FILE, ANDROID_KEYSTORE_PASSWORD, " +
            "ANDROID_KEY_ALIAS, and ANDROID_KEY_PASSWORD, and the keystore file must exist."
    )
}

val hostLibExtension = when {
    System.getProperty("os.name").lowercase().contains("mac") -> "dylib"
    System.getProperty("os.name").lowercase().contains("win") -> "dll"
    else -> "so"
}

android {
    namespace = "moe.antimony.hoshi"
    ndkVersion = targetNdkVersion
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "moe.requited.hoshi"
        minSdk = 26
        targetSdk = 36
        versionCode = 104040
        versionName = "1.4.4"
        releaseVersionCode?.let { versionCode = it }
        releaseVersionName?.let { versionName = it }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        externalNativeBuild {
            cmake {
                targets += "hoshidicts_jni"
            }
        }
    }

    if (isReleaseSigningConfigured) {
        signingConfigs {
            create("release") {
                storeFile = file(releaseKeystorePath!!)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            manifestPlaceholders["appLabel"] = "Hoshi Debug"
            ndk {
                abiFilters += listOf("arm64-v8a", "x86_64")
            }

        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            manifestPlaceholders["appLabel"] = "Hoshi Reader"
            ndk {
                abiFilters += listOf("arm64-v8a")
            }
            if (isReleaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    androidResources {
        generateLocaleConfig = true
    }
    lint {
        disable += "DirectSystemCurrentTimeMillisUsage"
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }
    sourceSets["main"].java.directories.add(uniffiOutDir.absolutePath)
    sourceSets["debug"].jniLibs.directories.add(rustDebugJniLibsDir.absolutePath)
    sourceSets["release"].jniLibs.directories.add(rustReleaseJniLibsDir.absolutePath)
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.adaptive.navigation.suite)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.coil.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.media3.datasource)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.transformer)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.work)
    implementation(libs.ankidroid.api)
    implementation(libs.google.dagger.hilt.android)
    implementation(libs.kotlinx.serialization.json)
    implementation("net.java.dev.jna:jna:${libs.versions.jna.get()}@aar")
    ksp(libs.androidx.hilt.compiler)
    ksp(libs.google.dagger.hilt.android.compiler)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly("net.java.dev.jna:jna:${libs.versions.jna.get()}@jar")
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

val buildRustHost by tasks.registering(Exec::class) {
    workingDir = rustProjectDir
    inputs.files(
        rustProjectDir.resolve("Cargo.toml"),
        rustProjectDir.resolve("Cargo.lock"),
        rustProjectDir.resolve("uniffi.toml"),
    )
    inputs.dir(rustProjectDir.resolve("src"))
    outputs.file(rustProjectDir.resolve("target/debug/libhoshiepub.$hostLibExtension"))

    commandLine(cargo, "build", "--lib")
}

val generateUniffiKotlin by tasks.registering(Exec::class) {
    dependsOn(buildRustHost)
    workingDir = rustProjectDir

    val hostLibPath = rustProjectDir.resolve("target/debug/libhoshiepub.$hostLibExtension")

    inputs.file(hostLibPath)
    inputs.file(rustProjectDir.resolve("uniffi.toml"))
    inputs.file(rustProjectDir.resolve("Cargo.toml"))
    inputs.file(rustProjectDir.resolve("Cargo.lock"))
    outputs.dir(uniffiOutDir)

    commandLine(
        cargo,
        "run",
        "--features",
        "bindgen",
        "--bin",
        "uniffi-bindgen",
        "--",
        "generate",
        "--library",
        hostLibPath.absolutePath,
        "--config",
        rustProjectDir.resolve("uniffi.toml").absolutePath,
        "--language",
        "kotlin",
        "--out-dir",
        uniffiOutDir.absolutePath,
        "--no-format",
    )
}

val buildRustAndroidDebug by tasks.registering(Exec::class) {
    workingDir = rustProjectDir
    environment("ANDROID_NDK_HOME", androidNdkHome)
    inputs.files(
        rustProjectDir.resolve("Cargo.toml"),
        rustProjectDir.resolve("Cargo.lock"),
        rustProjectDir.resolve("uniffi.toml"),
    )
    inputs.dir(rustProjectDir.resolve("src"))
    inputs.property("androidNdkHome", androidNdkHome)
    outputs.files(
        rustDebugJniLibsDir.resolve("arm64-v8a/libhoshiepub.so"),
        rustDebugJniLibsDir.resolve("x86_64/libhoshiepub.so"),
    )

    commandLine(
        cargo,
        "ndk",
        "-t",
        "arm64-v8a",
        "-t",
        "x86_64",
        "-o",
        rustDebugJniLibsDir.absolutePath,
        "build",
        "--lib",
    )
}

val buildRustAndroidRelease by tasks.registering(Exec::class) {
    workingDir = rustProjectDir
    environment("ANDROID_NDK_HOME", androidNdkHome)
    inputs.files(
        rustProjectDir.resolve("Cargo.toml"),
        rustProjectDir.resolve("Cargo.lock"),
        rustProjectDir.resolve("uniffi.toml"),
    )
    inputs.dir(rustProjectDir.resolve("src"))
    inputs.property("androidNdkHome", androidNdkHome)
    outputs.file(rustReleaseJniLibsDir.resolve("arm64-v8a/libhoshiepub.so"))

    commandLine(
        cargo,
        "ndk",
        "-t",
        "arm64-v8a",
        "-o",
        rustReleaseJniLibsDir.absolutePath,
        "build",
        "--lib",
        "--release",
    )
}

tasks.named("preBuild") {
    dependsOn(generateUniffiKotlin)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    dependsOn(generateUniffiKotlin)
    source(uniffiOutDir)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    dependsOn(buildRustHost)
    systemProperty("jna.library.path", rustProjectDir.resolve("target/debug").absolutePath)
}

afterEvaluate {
    tasks.named("preDebugBuild") {
        dependsOn(buildRustAndroidDebug)
    }
    tasks.named("preReleaseBuild") {
        dependsOn(buildRustAndroidRelease)
    }
}
