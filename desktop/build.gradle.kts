plugins {
  alias(libs.plugins.kotlin.jvm)
  application
}

java {
  sourceCompatibility = JavaVersion.VERSION_11
  targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
  compilerOptions {
    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
  }
}

application {
  mainClass.set("com.samplfie.desktop.MainKt")
  applicationName = "samplfie2-desktop"
}

dependencies {
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.jump3r)

  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
}
