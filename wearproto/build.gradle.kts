plugins {
  alias(libs.plugins.android.library)
}

// The watch <-> phone protocol: paths, payloads, the router and the card rules. No Android UI, no network, no keys.
// Both the phone app and the watch app depend on it, so the two sides can never disagree about a message.
android {
  namespace = "com.past9.phoneaos.wearproto"
  compileSdk = 37
  defaultConfig { minSdk = 28 }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  testOptions { unitTests.isReturnDefaultValues = false }
}

dependencies {
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation("org.json:json:20240303")
}
