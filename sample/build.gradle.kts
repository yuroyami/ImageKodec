plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.plugin)
    alias(libs.plugins.compose.compiler)
}

/*
 * Desktop-first sample gallery: every format ImageKodec decodes rendered through
 * the KiteImage() composable, including a real animated GIF and both encoders
 * dogfooded at runtime. `./gradlew :sample:run` launches it.
 */
kotlin {
    jvmToolchain(21)
    jvm()

    sourceSets {
        val jvmMain by getting {
            dependencies {
                implementation(projects.imagekodec)
                implementation(projects.imagekodecCompose)
                implementation(compose.desktop.currentOs)
                implementation(compose.material3)
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "io.github.yuroyami.imagekodec.sample.MainKt"
    }
}
