import java.util.Properties

plugins {
    id("com.android.application") version "9.2.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.21" apply false
}

// OneDrive locks files inside build/ folders. Set `build.dir=<absolute path>` in
// local.properties to move build outputs outside the synced folder.
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
localProps.getProperty("build.dir")?.let { base ->
    allprojects {
        layout.buildDirectory.set(file("$base/${project.name}"))
    }
}
