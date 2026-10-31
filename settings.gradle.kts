rootProject.name = "roadtale-chronicle"

// The prototype core is a plain Kotlin/JVM module so it compiles without the
// Android SDK. The production app (Android, API 28+) reuses this core module
// unchanged behind Pleos SDK adapters.
include("core", "demo")
