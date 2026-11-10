plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
}

// Release signing lives OUTSIDE the repo (never commit a key): ~/phone-aos/keys/signing.properties, or -PsigningProps=<file>.
val releaseSigning: Map<String, String> = file((project.findProperty("signingProps") as String?) ?: "${System.getProperty("user.home")}/phone-aos/keys/signing.properties")
  .takeIf { it.exists() }?.readLines()?.filter { "=" in it && !it.startsWith("#") }?.associate { it.substringBefore("=").trim() to it.substringAfter("=").trim() } ?: emptyMap()

android {
  namespace = "com.past9.phoneaos"
  compileSdk = 37

  defaultConfig {
    applicationId = "com.past9.phoneaos"
    minSdk = 28
    targetSdk = 28
    versionCode = 141
    versionName = "0.15.38"
    // Android never lets an installed app rename itself or change the icon Samsung shows on
    // notifications, so a personal build bakes the owner's agent in: -PagentName=Hina -PagentIcon=ic_app_heart_red
    resValue("string", "app_name", (project.findProperty("agentName") as String?) ?: "Agent")
    manifestPlaceholders["appIcon"] = "@mipmap/" + ((project.findProperty("agentIcon") as String?) ?: "ic_launcher")
    // The runtime pack (node, codex, ripgrep) is arm64 only, like every phone we target.
    ndk { abiFilters += listOf("arm64-v8a") }
    // A test install beside the real app (an emulator that already has it): -PappIdSuffix=.linktest
    (project.findProperty("appIdSuffix") as String?)?.let { applicationIdSuffix = it }
    // Tests that need a real WebView (app/src/androidTest) run on a phone or an emulator: ./gradlew :app:connectedDebugAndroidTest
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }
  // The runtimes (node, Claude Code, Codex, Antigravity) are not in the APK: they download on first use (runtime/RuntimePacks.kt).
  packaging {
    resources { excludes += setOf("META-INF/versions/9/OSGI-INF/MANIFEST.MF", "META-INF/DEPENDENCIES", "META-INF/INDEX.LIST") }
    // Native libraries are compressed inside the APK (and unpacked by Android at install). Stored uncompressed, the wake
    // word engine alone would add 24 MB to the download; compressed it adds 9, and the OCR library shrinks too.
    jniLibs { useLegacyPackaging = true }
  }

  signingConfigs {
    if (releaseSigning.isNotEmpty()) create("release") {
      storeFile = file(releaseSigning.getValue("storeFile")); storePassword = releaseSigning["storePassword"]
      keyAlias = releaseSigning["keyAlias"]; keyPassword = releaseSigning["keyPassword"]
    }
  }
  lint {
    // Sideloaded from GitHub, not Play: targetSdk 28 is deliberate (Termux route for downloaded runtimes).
    disable += "ExpiredTargetSdkVersion"
  }

  buildTypes {
    release {
      // Unused library code and resources are dropped; the app's own classes stay whole (proguard-rules.pro).
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      // A release build is never signed with whatever happens to be on the machine. If no key was given, it stops here
      // rather than shipping a file that cannot install over the app people already have (3 Nov 2026: an APK signed
      // with a different key broke every update, INSTALL_FAILED_UPDATE_INCOMPATIBLE).
      val wanted = gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) }
      val release = signingConfigs.findByName("release")
      if (release != null) {
        signingConfig = release
      } else if (wanted) {
        throw GradleException(
          "No release key. Pass -PsigningProps=<file> with storeFile, storePassword, keyAlias and keyPassword.\n" +
            "The published app is signed with certificate sha256 38d9d3377b0ae2229a0803a1ff84eb7a7d35c2db80334772c4b8072f27b0959a;\n" +
            "an APK signed with any other key will not install over an existing copy. Check it with tools/check-apk-signature.sh."
        )
      }
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  buildFeatures {
    resValues = true
    compose = true
    buildConfig = true
  }
  testOptions {
    unitTests {
      isIncludeAndroidResources = true
      all {
        it.systemProperty("robolectric.graphicsMode", "NATIVE")
        it.systemProperty("robolectric.pixelCopyRenderMode", "hardware")
        // Without this Roborazzi writes nothing and the run still passes green.
        it.systemProperty("roborazzi.test.record", "true")
        it.maxHeapSize = "3g"
        // The wake word accuracy run (docs/wake-word.md) loads the engine's Linux library; unset, those tests skip themselves.
        System.getenv("WAKE_NATIVE_DIR")?.let { dir -> it.systemProperty("java.library.path", dir); it.environment("WAKE_NATIVE_DIR", dir) }
        listOf("WAKE_EVAL_DIR", "WAKE_EVAL_OUT", "WAKE_EVAL_ARGS").forEach { k -> System.getenv(k)?.let { v -> it.environment(k, v) } }
        // Live provider tests run only when a key is passed in the environment (never stored in the repo).
        it.environment("DEEPSEEK_KEY", System.getenv("DEEPSEEK_KEY") ?: "")
        it.environment("OPENCODE_KEY", System.getenv("OPENCODE_KEY") ?: "")
      }
    }
  }
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

kotlin {
  compilerOptions {
    optIn.addAll(
      "androidx.compose.material3.ExperimentalMaterial3Api",
      "androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
    )
  }
}

val sftpJarFiles: Configuration by configurations.creating { isTransitive = false }
val strippedSftp = tasks.register<Jar>("strippedSftp") {
  archiveFileName.set("sshd-sftp-noservices.jar")
  destinationDirectory.set(layout.buildDirectory.dir("testlibs"))
  from(zipTree(sftpJarFiles.singleFile)) { exclude("META-INF/services/**") }
}

dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.lifecycle.service)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.room.runtime)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.work.runtime.ktx)
  implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.security.crypto)
  implementation(libs.okhttp)
  // Machines: SSH into any server or computer the user has credentials for.
  implementation("com.hierynomus:sshj:0.39.0")
  implementation("org.bouncycastle:bcprov-jdk18on:1.80")
  implementation("org.bouncycastle:bcpkix-jdk18on:1.80")
  implementation(libs.androidx.graphics.shapes)
  implementation(libs.coil.compose)
  implementation(libs.coil.svg)
  // On-device OCR (bundled model, works offline): scans and photos become text for any AI model.
  implementation(libs.mlkit.text)
  implementation(libs.androidx.webkit)
  // Built-in camera (CameraX only, no ML Kit).
  implementation(libs.androidx.camera.core)
  implementation(libs.androidx.camera.camera2)
  implementation(libs.androidx.camera.lifecycle)
  implementation(libs.androidx.camera.view)
  // Reading the link QR code from CameraX frames: zxing core only (pure Java, about 0.5 MB, no Play Services, works offline,
  // and its encoder lets unit tests make real QR codes). ML Kit barcode scanning would add a model and Play Services.
  implementation("com.google.zxing:core:3.5.3")
  // Background screen: Shizuku, when the person has it, lends the shell user that may open a hidden display (screen/ScreenHelper.kt).
  implementation("dev.rikka.shizuku:api:13.1.5")
  implementation("dev.rikka.shizuku:provider:13.1.5")
  implementation(libs.kotlinx.coroutines.android)
  // Wake word: on-device keyword spotting (sherpa-onnx with onnxruntime linked in, one .so, arm64 only). No network, no key.
  implementation("com.k2fsa.sherpa.onnx:sherpa-onnx-static-link-onnxruntime:2.0.1@aar")
  implementation(libs.androidx.credentials)
  implementation(libs.androidx.credentials.play.services)
  implementation(libs.googleid)
  "ksp"(libs.androidx.room.compiler)

  testImplementation(libs.junit)
  testImplementation(libs.androidx.test.core)
  testImplementation(libs.robolectric)
  testImplementation(libs.roborazzi)
  testImplementation(libs.roborazzi.compose)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.okhttp.mockwebserver)
  testImplementation("org.apache.sshd:sshd-core:2.14.0")
  // sshd-sftp registers a java.nio FileSystemProvider that breaks Robolectric; use a copy without its service files.
  testImplementation(files(strippedSftp))
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation("org.json:json:20240303")
  androidTestImplementation(libs.junit)
  androidTestImplementation("androidx.test:runner:1.6.2")
  androidTestImplementation("androidx.test.ext:junit:1.2.1")
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
}

// The SFTP server side for MachinesTest, minus META-INF/services (see testImplementation above).
dependencies { sftpJarFiles("org.apache.sshd:sshd-sftp:2.14.0") }
