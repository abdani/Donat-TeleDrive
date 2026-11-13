import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("multiplatform") version "2.4.21"
    kotlin("plugin.compose") version "2.4.21"
    kotlin("plugin.serialization") version "2.4.21"
    id("org.jetbrains.compose") version "1.12.2"
    id("com.google.devtools.ksp") version "2.3.12"
    id("androidx.room") version "2.8.5"
}

val roomVersion = "2.8.5"
val lwjglVersion = "3.4.3"

kotlin {
    // Windows-first: only the JVM desktop target for now. Shared code stays in commonMain.
    jvm()
    compilerOptions { freeCompilerArgs.add("-Xexpect-actual-classes") } // Room's generated DB constructor

    sourceSets {
        commonMain.dependencies {
            implementation("org.jetbrains.compose.runtime:runtime:1.12.2")
            implementation("org.jetbrains.compose.foundation:foundation:1.12.2")
            implementation("org.jetbrains.compose.material3:material3:1.9.0")
            implementation("org.jetbrains.compose.material:material-icons-extended:1.7.3")
            implementation("androidx.room:room-runtime:$roomVersion")
            implementation("androidx.sqlite:sqlite-bundled:2.6.2")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
        }
        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
            implementation(project.dependencies.platform("it.tdlight:tdlight-java-bom:3.5.8+td.1.8.68"))
            implementation("it.tdlight:tdlight-java")
            implementation("it.tdlight:tdlight-natives::windows_amd64")
            implementation("org.slf4j:slf4j-nop:2.0.5") // TDLight logs via SLF4J; discard them on purpose (no secrets in logs, no startup warning)
            implementation("net.java.dev.jna:jna-platform:5.19.1") // Windows DPAPI
            // Modern Windows file/folder dialogs (IFileDialog) instead of AWT's legacy one
            for (m in listOf("lwjgl", "lwjgl-nfd")) {
                implementation("org.lwjgl:$m:$lwjglVersion")
                implementation("org.lwjgl:$m:$lwjglVersion:natives-windows")
            }
            implementation("io.nayuki:qrcodegen:1.8.0") // QR login
            implementation("org.apache.pdfbox:pdfbox:3.0.8") // in-app PDF preview
            implementation("uk.co.caprica:vlcj:4.12.1") // MKV/HEVC/FLAC playback via installed VLC
            // In-app audio/video player (MP4/H.264, MP3, M4A, WAV)
            for (m in listOf("base", "graphics", "media", "swing")) implementation("org.openjfx:javafx-$m:21.0.12:win")
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

dependencies {
    add("kspJvm", "androidx.room:room-compiler:$roomVersion")
}

tasks.withType<Test> {
    systemProperty("donat.log", "off")
    // Unit tests get their own app-data folder (LAN inbox, settings, key) — never the real app's. LiveTest needs the real session.
    if (!providers.gradleProperty("liveTest").isPresent) environment("LOCALAPPDATA", layout.buildDirectory.dir("test-appdata").get().asFile.absolutePath)
    // LiveTest (real Telegram, temporary drive) runs only with -PliveTest=true
    providers.gradleProperty("liveTest").orNull?.let { systemProperty("donat.live", it) }
    // UI on demo data for screenshots (UiDemoTest): gradlew jvmTest --tests donat.teledrive.UiDemoTest -PuiDemo=true
    providers.gradleProperty("uiDemo").orNull?.let { systemProperty("donat.uidemo", it); outputs.upToDateWhen { false } }
    testLogging { showStandardStreams = providers.gradleProperty("liveTest").isPresent }
}

// Dev launcher (run.bat, restart script): the app runs from a *copy* of its jar. `gradlew run` uses build/libs directly,
// so a rebuild while the app was open swapped classes under it (NoClassDefFoundError on the next screen opened).
tasks.register("devSnapshot") {
    val jar = tasks.named<Jar>("jvmJar")
    val runtime = kotlin.jvm().compilations["main"].runtimeDependencyFiles!!
    val dir = layout.buildDirectory.dir("devrun")
    dependsOn(jar)
    doLast {
        val out = dir.get().asFile.apply { mkdirs() }
        val app = jar.get().archiveFile.get().asFile.copyTo(File(out, "app.jar"), overwrite = true)
        // Forward slashes: inside quotes in a java @argfile, a backslash is an escape character.
        val cp = (listOf(app) + runtime.files).joinToString(";") { it.absolutePath.replace('\\', '/') }
        File(out, "args.txt").writeText("-Dcompose.application.configure.swing.globals=true -Dfile.encoding=UTF-8 -cp \"$cp\" donat.teledrive.MainKt")
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

compose.desktop {
    application {
        mainClass = "donat.teledrive.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            modules("java.instrument", "jdk.httpserver", "jdk.jfr", "jdk.unsupported", "jdk.unsupported.desktop") // from suggestRuntimeModules
            packageName = "Donat TeleDrive"
            packageVersion = "0.1.0"
            windows {
                menuGroup = "Donat TeleDrive"
                perUserInstall = true
                upgradeUuid = "6f1d3c52-4b8e-4f3a-9a57-2d0c9e8b1a44"
            }
        }
    }
}
