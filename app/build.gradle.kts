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
    minSdk = 29
    targetSdk = 36
    versionCode = 33
    versionName = "0.5.3"
    // Android never lets an installed app rename itself or change the icon Samsung shows on
    // notifications, so a personal build bakes the owner's agent in: -PagentName=Hina -PagentIcon=ic_app_heart_red
    resValue("string", "app_name", (project.findProperty("agentName") as String?) ?: "Agent")
    manifestPlaceholders["appIcon"] = "@mipmap/" + ((project.findProperty("agentIcon") as String?) ?: "ic_launcher")
    // The runtime pack (node, codex, ripgrep) is arm64 only, like every phone we target.
    ndk { abiFilters += listOf("arm64-v8a") }
  }
  // Native executables must be extracted to nativeLibraryDir to be runnable.
  packaging { jniLibs { useLegacyPackaging = true } }

  signingConfigs {
    if (releaseSigning.isNotEmpty()) create("release") {
      storeFile = file(releaseSigning.getValue("storeFile")); storePassword = releaseSigning["storePassword"]
      keyAlias = releaseSigning["keyAlias"]; keyPassword = releaseSigning["keyPassword"]
    }
  }
  buildTypes {
    release {
      isMinifyEnabled = false
      signingConfigs.findByName("release")?.let { signingConfig = it }
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
  implementation(libs.androidx.graphics.shapes)
  implementation(libs.coil.compose)
  implementation(libs.coil.svg)
  // On-device OCR (bundled model, works offline): scans and photos become text for any AI model.
  implementation(libs.mlkit.text)
  implementation(libs.androidx.webkit)
  implementation(libs.kotlinx.coroutines.android)
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
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation("org.json:json:20240303")
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
}
