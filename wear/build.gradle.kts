plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
}

// The Wear OS app (docs/wear.md). Same applicationId and the same signing key as the phone app, so the Data Layer pairs
// them. It holds no account, key or model: the phone relays to the agent.
android {
  namespace = "com.past9.phoneaos.wear"
  compileSdk = 37

  defaultConfig {
    applicationId = "com.past9.phoneaos"
    minSdk = 30
    // The watch in use runs Wear OS 6 (Android 16, API 36); Wear OS 4 and 5 watches (API 33, 34) run it too.
    targetSdk = 36
    versionCode = 900001
    versionName = "0.1.0"
    // Same rule as the phone: a personal build can bake the owner's agent name in (-PagentName=Hina).
    resValue("string", "app_name", (project.findProperty("agentName") as String?) ?: "Agent")
    (project.findProperty("appIdSuffix") as String?)?.let { applicationIdSuffix = it }
  }
  buildTypes { release { isMinifyEnabled = false } }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  buildFeatures { compose = true; buildConfig = true; resValues = true }
  lint { disable += "ExpiredTargetSdkVersion" }
  testOptions {
    unitTests {
      isIncludeAndroidResources = true
      all {
        it.systemProperty("robolectric.graphicsMode", "NATIVE")
        it.systemProperty("robolectric.pixelCopyRenderMode", "hardware")
        it.systemProperty("roborazzi.test.record", "true")
        it.maxHeapSize = "2g"
        // Robolectric 4.16 on JDK 21: the native graphics shadow reads java.nio.Buffer.address by reflection.
        it.jvmArgs("--add-opens=java.base/java.nio=ALL-UNNAMED", "--add-opens=java.base/java.lang=ALL-UNNAMED", "--add-opens=java.base/java.util=ALL-UNNAMED")
      }
    }
  }
}

dependencies {
  implementation(project(":wearproto"))
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.wear.compose.material3)
  implementation(libs.wear.compose.foundation)
  implementation(libs.wear.compose.navigation)
  implementation(libs.wear.input)
  implementation(libs.wear.tiles)
  implementation(libs.wear.protolayout)
  implementation(libs.wear.complications.ktx)
  implementation(libs.concurrent.futures)
  implementation(libs.play.services.wearable)
  implementation(libs.kotlinx.coroutines.play.services)

  testImplementation(libs.junit)
  testImplementation(libs.androidx.test.core)
  testImplementation(libs.robolectric)
  testImplementation(libs.roborazzi)
  testImplementation(libs.roborazzi.compose)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation("org.json:json:20240303")
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
}
