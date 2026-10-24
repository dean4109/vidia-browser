plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.android")
}

// Signing comes from environment variables set by the GitHub workflow.
val keystorePath: String? = System.getenv("KEYSTORE_PATH")

android {
  namespace = "app.vidia.browser"
  compileSdk = 35

  defaultConfig {
    // Your Play Console package name. Set the APPLICATION_ID repository variable
    // (Settings → Secrets and variables → Actions → Variables) to use your own.
    applicationId = System.getenv("APPLICATION_ID")?.takeIf { it.isNotBlank() } ?: "app.vidia.browser"
    minSdk = 29
    targetSdk = 35
    versionCode = (System.getenv("BUILD_NUMBER") ?: "1").toInt()
    versionName = "1.0.${System.getenv("BUILD_NUMBER") ?: "0"}"
  }

  signingConfigs {
    if (keystorePath != null) {
      create("release") {
        storeFile = file(keystorePath)
        storePassword = System.getenv("KEYSTORE_PASSWORD")
        keyAlias = System.getenv("KEY_ALIAS")
        keyPassword = System.getenv("KEY_PASSWORD")
      }
    }
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      if (keystorePath != null) signingConfig = signingConfigs.getByName("release")
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  kotlinOptions { jvmTarget = "17" }
}
