pluginManagement {
    repositories {
        maven { url = uri(providers.gradleProperty("toolgateSdkRepository").get()) }
        gradlePluginPortal()
    }
    // The SDK version is the repository's VERSION, which core-mcp/build.py passes in: development and release builds
    // stamp it, and the package must use exactly the SDK published by that build.
    plugins { id("io.ololabs.toolgate.sdk") version providers.gradleProperty("toolgateSdkVersion").get() }
}
rootProject.name = "hello-world"
