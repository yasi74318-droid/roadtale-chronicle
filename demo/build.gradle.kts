plugins {
    kotlin("jvm") version "2.0.21"
    application
}

dependencies {
    implementation(project(":core"))
}

application {
    mainClass.set("com.roadtale.demo.MainKt")
}

kotlin {
    jvmToolchain(17)
}
