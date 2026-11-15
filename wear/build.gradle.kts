plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
}

// Release signing lives OUTSIDE the repo, as for the phone app: ~/phone-aos/keys/signing.properties, or -PsigningProps=<file>.
// The watch app MUST be signed with the same published key as the phone app, or the Data Layer will not pair them.
val releaseSigning: Map<String, String> = file((project.findProperty("signingProps") as String?) ?: "${System.getProperty("user.home")}/phone-aos/keys/signing.properties")
  .takeIf { it.exists() }?.readLines()?.filter { "=" in it && !it.startsWith("#") }?.associate { it.substringBefore("=").trim() to it.substringAfter("=").trim() } ?: emptyMap()

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
    versionCode = 900165
    versionName = "0.15.62"
    // Same rule as the phone: a personal build can bake the owner's agent name in (-PagentName=Hina).
    resValue("string", "app_name", (project.findProperty("agentName") as String?) ?: "Agent")
    (project.findProperty("appIdSuffix") as String?)?.let { applicationIdSuffix = it }
  }
  signingConfigs {
    if (releaseSigning.isNotEmpty()) create("release") { storeFile = file(releaseSigning.getValue("storeFile")); storePassword = releaseSigning["storePassword"]; keyAlias = releaseSigning["keyAlias"]; keyPassword = releaseSigning["keyPassword"] }
  }
  buildTypes {
    release {
      isMinifyEnabled = false
      // Never signed with whatever key is on the machine: the watch app must carry the published key (tools/check-apk-signature.sh).
      val wanted = gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) }
      val release = signingConfigs.findByName("release")
      if (release != null) signingConfig = release
      else if (wanted) throw GradleException("No release key. Pass -PsigningProps=<file> (storeFile, storePassword, keyAlias, keyPassword); the watch app must be signed with the published key.")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  buildFeatures { compose = true; buildConfig = true; resValues = true }
  // InvalidFragmentVersionForActivityResult: the watch app has no fragments; the check fires on registerForActivityResult in the activity and stops a release build.
  lint { disable += listOf("ExpiredTargetSdkVersion", "InvalidFragmentVersionForActivityResult") }
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
  implementation("com.google.zxing:core:3.5.3")
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.graphics.shapes)
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
  // Draws the pairing QR (zxing core only, pure Java, the version the phone reads it with).
  implementation("com.google.zxing:core:3.5.3")
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

kotlin {
  compilerOptions {
    optIn.addAll("androidx.compose.material3.ExperimentalMaterial3Api", "androidx.compose.material3.ExperimentalMaterial3ExpressiveApi")
  }
}
