pluginManagement {
  repositories {
    google {
      content {
        includeGroupByRegex("com\\.android.*")
        includeGroupByRegex("com\\.google.*")
        includeGroupByRegex("androidx.*")
      }
    }
    mavenCentral()
    gradlePluginPortal()
  }
}

plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0" }

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    google()
    mavenCentral()
    // The wake word engine (sherpa-onnx, Apache-2.0) is published as an AAR on its GitHub release, not on Maven Central.
    ivy {
      url = uri("https://github.com/k2-fsa/sherpa-onnx/releases/download")
      patternLayout { artifact("v[revision]/[module]-[revision].[ext]") }
      metadataSources { artifact() }
      content { includeGroup("com.k2fsa.sherpa.onnx") }
    }
  }
}

rootProject.name = "Agent"

include(":app")
