plugins {
    alias(libs.plugins.kotlin.multiplatform).apply(false)
    alias(libs.plugins.android.kmp.library).apply(false)
    // Applied (not deferred) at the root so `dokkaGenerate` aggregates every
    // library module into one API site at build/dokka/html.
    alias(libs.plugins.dokka)
}

allprojects {
    group = "io.github.yuroyami"
    version = "0.0.1-SNAPSHOT"
}

dependencies {
    dokka(project(":kiteimage"))
    dokka(project(":kiteimage-compose"))
    dokka(project(":kiteimage-coil"))
}

dokka {
    moduleName.set("KiteImage")
}
