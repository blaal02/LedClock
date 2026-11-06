plugins {
    id("com.android.application") version "9.5.0" apply false
}

// Keep build output outside OneDrive; its file syncing locks/corrupts files in build/
// and breaks Gradle's incremental build ("not a regular file", "unable to delete directory").
allprojects {
    layout.buildDirectory.set(
        File(System.getProperty("user.home"), ".gradle-builds/LedClock/${project.name}")
    )
}
