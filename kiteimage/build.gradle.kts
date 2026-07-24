import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.vanniktech.publish)
    alias(libs.plugins.dokka)
}

/*
 * :kiteimage is the pure-Kotlin image codec core. NO external runtime deps — only
 * kotlin-stdlib is on the classpath, exactly like :kitearchive, :kitepdf and
 * :kitetorrent. Everything here is pure computation (no sockets, no threads, no
 * disk): format sniffing, the decoders, the ARGB pixel buffer, and the
 * malformed-input / decompression-bomb guards.
 *
 * The Compose interop (ImageBitmap conversion, animated playback) will live in a
 * separate :kiteimage-compose module so this core stays portable to every target
 * and Compose never reaches consumers who don't want it.
 */
/*
 * The decompression-bomb guards deliberately sit at ~1 GiB of decoded pixels
 * (268M px), and the fuzz suite drives malformed headers right up to them. The
 * test JVM therefore needs enough heap that the *guard* is what rejects the
 * input, rather than the allocator failing first and masking which one fired.
 */
tasks.withType<Test>().configureEach {
    maxHeapSize = "3g"
}

kotlin {
    explicitApi()
    jvmToolchain(21)

    // Public API tracking. `updateLegacyAbi` refreshes api/*.api, `checkLegacyAbi`
    // fails the build when the committed dump and the code disagree: with
    // explicitApi() on, that turns an accidental signature change into a review
    // conversation instead of a broken release.
    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation {
        // Declaring the block is what switches tracking on.
    }

    android {
        namespace = "io.github.yuroyami.kiteimage"
        compileSdk = 36
        minSdk = 21
    }

    listOf(
        iosSimulatorArm64(),
        iosArm64(),
        iosX64(),
    ).forEach { target ->
        target.binaries.framework {
            baseName = "KiteImage"
            isStatic = false
        }
    }

    // Apple-Silicon macOS (Intel is deprecated in Kotlin 2.3+) — pure computation,
    // and :kiteimage-compose needs the core published for every CMP target.
    macosArm64()

    // The rest of the KMP matrix. The core is pure stdlib computation, and
    // :kitepdf-core (which targets everything) depends on it — so it ships
    // everywhere kitepdf-core does. Compose/Coil modules stay on the CMP set.
    tvosArm64(); tvosSimulatorArm64()
    watchosArm32(); watchosArm64(); watchosDeviceArm64(); watchosSimulatorArm64()
    androidNativeArm32(); androidNativeArm64(); androidNativeX64(); androidNativeX86()
    linuxX64(); linuxArm64()
    mingwX64()
    @OptIn(ExperimentalWasmDsl::class)
    wasmWasi {
        nodejs()
    }

    // Mocha's 2-second default is fine for unit tests and hopeless for the fuzz
    // suite, which drives thousands of malformed files through every decoder.
    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    js(IR) {
        browser()
        nodejs {
            testTask {
                useMocha { timeout = "600s" }
            }
        }
        binaries.library()
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
        nodejs {
            testTask {
                useMocha { timeout = "600s" }
            }
        }
    }

    jvm()

    sourceSets {
        all {
            languageSettings {
                optIn("kotlin.RequiresOptIn")
                optIn("kotlin.experimental.ExperimentalNativeApi")
            }
        }

        commonMain.dependencies {
            // Intentionally empty. KiteImage core depends on kotlin-stdlib only.
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
