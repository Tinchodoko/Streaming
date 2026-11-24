plugins {
  alias(libs.plugins.android.application)
}

android {
  // Keep the original source namespace so existing Kotlin files resolve com.pedro.streamer.R.
  // The installed application identity remains Tinchodoko's independent package.
  namespace = "com.pedro.streamer"
  compileSdk = 37

  defaultConfig {
    applicationId = "com.tinchodoko.streaming"
    minSdk = 16
    targetSdk = 37
    versionCode = providers.gradleProperty("streamingVersionCode").orElse("1").get().toInt()
    versionName = providers.gradleProperty("streamingVersionName").orElse("1.0.0").get()
    multiDexEnabled = true
  }
  signingConfigs {
    create("release") {
      val keystorePath = System.getenv("STREAMING_SIGNING_KEYSTORE_PATH")
      if (!keystorePath.isNullOrBlank()) {
        storeFile = file(keystorePath)
      }
      storePassword = System.getenv("STREAMING_SIGNING_STORE_PASSWORD")
      keyAlias = System.getenv("STREAMING_SIGNING_KEY_ALIAS")
      keyPassword = System.getenv("STREAMING_SIGNING_KEY_PASSWORD")
    }
  }
  buildTypes {
    release {
      signingConfig = signingConfigs.getByName("release")
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
  }
  buildFeatures {
    buildConfig = true
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
}

kotlin {
  jvmToolchain(17)
}

dependencies {
  implementation(project(":library"))
  implementation(project(":extra-sources"))
  implementation(libs.androidx.constraintlayout)
  implementation(libs.androidx.appcompat)
  implementation(libs.androidx.multidex)
}
