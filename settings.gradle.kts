rootProject.name = "Donat TeleDrive"

pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://mvn.mchv.eu/repository/mchv/") { content { includeGroup("it.tdlight") } }
    }
}
