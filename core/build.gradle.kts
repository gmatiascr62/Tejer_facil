plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // Las clases org.opencv.* las aporta en tiempo de ejecución el AAR oficial de
    // OpenCV para Android (app) o el empaquetado JVM de openpnp (pruebas).
    compileOnly(libs.opencv.jvm)

    testImplementation(libs.opencv.jvm)
    testImplementation(libs.pdfbox)
    testImplementation(libs.junit)
}

tasks.test {
    maxHeapSize = "2g"
    // Propiedades opcionales para sondas manuales (ver RealPagesProbeTest).
    for (prop in listOf("debugOut", "realPages", "realFilter", "realMasks", "rowDebug", "cellProbe")) {
        systemProperty(prop, System.getProperty(prop) ?: "")
    }
    testLogging {
        events("failed", "passed", "skipped")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
