plugins { alias(libs.plugins.android.application) }

// Release signing lives OUTSIDE the repo, as for the phone app: ~/phone-aos/keys/signing.properties, or -PsigningProps=<file>.
val releaseSigning: Map<String, String> = file((project.findProperty("signingProps") as String?) ?: "${System.getProperty("user.home")}/phone-aos/keys/signing.properties")
  .takeIf { it.exists() }?.readLines()?.filter { "=" in it && !it.startsWith("#") }?.associate { it.substringBefore("=").trim() to it.substringAfter("=").trim() } ?: emptyMap()

// A Watch Face Format watch face (docs/wear.md, "Watch faces"): resources only. One face per package, so there are three.
android {
  namespace = "com.past9.phoneaos.face.still"
  compileSdk = 37
  defaultConfig {
    applicationId = "com.past9.phoneaos.face.still"
    minSdk = 34
    targetSdk = 36
    versionCode = (project.findProperty("faceVersionCode") as String?)?.toInt() ?: 900001
    versionName = (project.findProperty("faceVersionName") as String?) ?: "0.1.0"
  }
  signingConfigs {
    if (releaseSigning.isNotEmpty()) create("release") { storeFile = file(releaseSigning.getValue("storeFile")); storePassword = releaseSigning["storePassword"]; keyAlias = releaseSigning["keyAlias"]; keyPassword = releaseSigning["keyPassword"] }
  }
  buildTypes {
    release {
      val wanted = gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) }
      val release = signingConfigs.findByName("release")
      if (release != null) signingConfig = release
      else if (wanted) throw GradleException("No release key. Pass -PsigningProps=<file> (storeFile, storePassword, keyAlias, keyPassword).")
    }
  }
  buildFeatures { buildConfig = false }
  lint { disable += "ExpiredTargetSdkVersion" }
}
