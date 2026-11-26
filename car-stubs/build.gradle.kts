import java.util.Properties

plugins {
    id("java-library")
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val sdkDir = localProps.getProperty("sdk.dir")
    ?: System.getenv("ANDROID_HOME")
    ?: System.getenv("ANDROID_SDK_ROOT")
    ?: file("${System.getProperty("user.home")}/Android/Sdk").takeIf { it.exists() }?.absolutePath
    ?: error("Android SDK not found. Set sdk.dir in local.properties or ANDROID_HOME.")
val androidJar = listOf("android-36.1", "android-36", "android-35", "android-34", "android-33")
    .map { file("$sdkDir/platforms/$it/android.jar") }
    .firstOrNull { it.exists() }
    ?: file("$sdkDir/platforms").listFiles()
        ?.sortedByDescending { it.name }
        ?.map { file("${it.absolutePath}/android.jar") }
        ?.firstOrNull { it.exists() }
    ?: error("android.jar not found under $sdkDir/platforms")

dependencies {
    compileOnly(files(androidJar))
}
