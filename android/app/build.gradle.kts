import org.gradle.api.tasks.Exec

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "chat.cypher.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "chat.cypher.droid"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        ndk {
            // Same ABI set as the bitchat Android pipeline.
            abiFilters += listOf("arm64-v8a", "x86_64", "armeabi-v7a", "x86")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlin {
        jvmToolchain(21)
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    implementation(libs.kotlinx.coroutines.android)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

// ---------------------------------------------------------------------------
// Rust core: build libcypher.so with cargo-ndk into jniLibs.
// ---------------------------------------------------------------------------

val rustDir: String = layout.projectDirectory.dir("../..").asFile.absolutePath
val rustTargets = mapOf(
    "arm64-v8a" to "aarch64-linux-android",
    "x86_64" to "x86_64-linux-android",
    "armeabi-v7a" to "armv7-linux-androideabi",
    "x86" to "i686-linux-android",
)

val buildRustDebug by tasks.registering {
    group = "rust"
    description = "Build the Rust mesh core for all Android ABIs (debug)."
    doLast {
        val abis = android.defaultConfig.ndk.abiFilters.get()
        abis.forEach { abi ->
            val triple = rustTargets[abi] ?: return@forEach
            execRust(triple, false, abi)
        }
    }
}

val buildRustRelease by tasks.registering {
    group = "rust"
    description = "Build the Rust mesh core for all Android ABIs (release)."
    doLast {
        val abis = android.defaultConfig.ndk.abiFilters.get()
        abis.forEach { abi ->
            val triple = rustTargets[abi] ?: return@forEach
            execRust(triple, true, abi)
        }
    }
}

fun execRust(triple: String, release: Boolean, abi: String) {
    val output = File(rustDir, "target/${triple}/${if (release) "release" else "debug"}/libcypher.so")
    val jniDir = File(projectDir, "src/main/jniLibs/$abi").apply { mkdirs() }
    exec {
        workingDir = File(rustDir)
        commandLine(
            "cargo", "ndk",
            "-t", triple,
            "-o", jniDir.absolutePath,
            "build",
            "-p", "cypher-android",
            if (release) "--release" else "",
        )
        // cargo-ndk needs ANDROID_NDK_HOME or NDK_HOME.
        environment["ANDROID_NDK_HOME"] = System.getenv("ANDROID_NDK_HOME")
            ?: System.getenv("NDK_HOME") ?: ""
    }.assertNormalExitValue()
    if (!output.exists() && !File(jniDir, "libcypher.so").exists()) {
        throw GradleException("cargo-ndk produced no libcypher.so for $abi")
    }
}

tasks.matching { it.name.startsWith("merge") && it.name.contains("JniLibFolders") }.configureEach {
    dependsOn(buildRustDebug)
}
tasks.matching { it.name == "externalNativeBuildDebug" }.configureEach {
    dependsOn(buildRustDebug)
}

afterEvaluate {
    tasks.matching { it.name == "mergeDebugJniLibFolders" }.configureEach { dependsOn(buildRustDebug) }
    tasks.matching { it.name == "mergeReleaseJniLibFolders" }.configureEach { dependsOn(buildRustRelease) }
}
