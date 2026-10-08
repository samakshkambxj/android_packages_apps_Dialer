plugins {
    `java-library`
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// Soong java_plugin equivalent: compiles the Dagger root-component
// generator from the in-repo sources. The app wires it via
// annotationProcessor(project(":dialer-processor")).
sourceSets {
    main {
        java {
            srcDirs("../java")
            include(
                "com/android/dialer/rootcomponentgenerator/**",
                "com/android/dialer/inject/**",
            )
        }
    }
}

dependencies {
    implementation("com.google.auto:auto-common:1.2.2")
    implementation("com.google.auto.service:auto-service-annotations:1.1.1")
    implementation("com.google.dagger:dagger:2.51.1")
    implementation("com.google.guava:guava:33.4.0-android")
    implementation("com.squareup:javapoet:1.13.0")
    annotationProcessor("com.google.auto.service:auto-service:1.1.1")
}
