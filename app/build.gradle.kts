plugins {
    id("com.android.application")
}

val appVersion = "1.3.0"

val envKeystorePath = System.getenv("ANDROID_KEYSTORE_PATH")
val envKeystoreAlias = System.getenv("ANDROID_KEYSTORE_ALIAS")
val envKeystorePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
val envKeyPassword = System.getenv("ANDROID_KEY_PASSWORD")

android {
    namespace = "my.github.MrxSiN.threadshideads"
    compileSdk = 36

    val releaseSigningConfig = if (
        !envKeystorePath.isNullOrBlank() &&
        !envKeystoreAlias.isNullOrBlank() &&
        !envKeystorePassword.isNullOrBlank() &&
        !envKeyPassword.isNullOrBlank() &&
        file(envKeystorePath).isFile
    ) {
        signingConfigs.create("release") {
            storeFile = file(envKeystorePath)
            storePassword = envKeystorePassword
            keyAlias = envKeystoreAlias
            keyPassword = envKeyPassword
        }
    } else {
        null
    }

    defaultConfig {
        applicationId = "io.github.mrxsin.threadshideads"
        minSdk = 23
        targetSdk = 36
        versionCode = 7
        versionName = appVersion
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Hot-path timing logs (hook/HotPathStats): debug builds, or -PhotPathStats.
        buildConfigField("boolean", "HOT_PATH_STATS", project.hasProperty("hotPathStats").toString())
    }

    sourceSets {
        // Frozen v1.2.1 policy, model fixtures and graph generators, shared by
        // the host parity tests and the on-device parity/benchmark tests.
        getByName("test").java.srcDir("src/sharedTest/java")
        getByName("androidTest").java.srcDir("src/sharedTest/java")
    }

    buildFeatures {
        buildConfig = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // -PbenchmarkRelease runs the instrumented tests against the release
    // build (no CheckJNI, optimized native code), signed with the debug key
    // when no release key is configured. Local benchmarking only.
    val benchmarkRelease = project.hasProperty("benchmarkRelease")
    if (benchmarkRelease) {
        testBuildType = "release"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            releaseSigningConfig?.let { signingConfig = it }
            if (releaseSigningConfig == null && (benchmarkRelease || project.hasProperty("hotPathStats"))) {
                signingConfig = signingConfigs.getByName("debug")
            }
        }
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*"
            )
        }
    }
}

androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            output.outputFileName.set("ThreadsHideAds-v$appVersion.apk")
        }
    }
}

dependencies {
    implementation("org.luckypray:dexkit:2.2.0")
    compileOnly("de.robv.android.xposed:api:82")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}

// ---- Brainfuck core: generation, checks and the host build used by JVM tests.

val python = if (System.getProperty("os.name").startsWith("Windows")) "python" else "python3"
val hostCoreLibrary = rootProject.layout.buildDirectory.file(
    "bfhost/" + System.mapLibraryName("threadsbf")
).get().asFile

val generateBrainfuck by tasks.registering(Exec::class) {
    group = "brainfuck"
    description = "Regenerates brainfuck/generated and the AOT C source from brainfuck/src."
    workingDir = rootDir
    commandLine(python, "tools/bftool/gen.py")
}

val checkBrainfuck by tasks.registering(Exec::class) {
    group = "brainfuck"
    description = "Fails when generated Brainfuck, C or ABI files are stale."
    workingDir = rootDir
    commandLine(python, "tools/bftool/gen.py", "--check")
}

val buildHostCore by tasks.registering(Exec::class) {
    group = "brainfuck"
    description = "Builds libthreadsbf for the host JVM (JVM parity tests)."
    workingDir = rootDir
    inputs.dir("src/main/cpp")
    outputs.file(hostCoreLibrary)
    commandLine(python, "tools/bftool/hostlib.py", System.getProperty("java.home"), hostCoreLibrary.absolutePath)
}

val testBrainfuck by tasks.registering(Exec::class) {
    group = "brainfuck"
    description = "Toolchain, reference-vs-AOT and text parity tests (Python)."
    workingDir = rootDir
    dependsOn("testDebugUnitTest")
    commandLine(python, "-m", "unittest", "discover", "-s", "tests/compiler", "-v")
}

tasks.withType<Test>().configureEach {
    dependsOn(buildHostCore)
    // A regenerated core must re-run the parity tests.
    inputs.file(hostCoreLibrary)
    systemProperty("threadsbf.hostlib", hostCoreLibrary.absolutePath)
    systemProperty("threadsbf.debugErrors", project.hasProperty("debugErrors").toString())
}
