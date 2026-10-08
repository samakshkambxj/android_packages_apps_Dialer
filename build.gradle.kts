/*
 * SPDX-FileCopyrightText: 2023 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 *
 * Standalone Gradle build for CI staging APKs. The platform/Soong build
 * (Android.bp) remains authoritative; this file mirrors its sources and
 * dependencies so GitHub Actions can assemble a debug APK per push, the
 * same flow as MaxxOS ExactCalculator.
 */

import org.gradle.api.DefaultTask
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import javax.inject.Inject

// AGP 9 bundles KGP 2.2.10, but miuix-blur needs Kotlin 2.4 metadata:
// upgrade the built-in Kotlin per the AGP 9.0 release notes
// ("Runtime dependency on Kotlin Gradle plugin").
buildscript {
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20")
    }
}

plugins {
    id("com.android.application") version "9.4.0"
    // AGP 9 provides built-in Kotlin: no kotlin.android plugin.
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20"
}

// Protos are compiled with protoc directly (Soong uses type: "lite").
// The protobuf Gradle plugin does not support AGP 9, so instead of the
// plugin we run the Maven protoc executable over java/**/*.proto.
val protocConfiguration = configurations.create("protoc")

dependencies {
    add("protoc", "com.google.protobuf:protoc:3.25.5:linux-x86_64@exe")
}

val generatedProtoDir = layout.buildDirectory.dir("generated/proto/java")
val protoFiles = fileTree("java") { include("**/*.proto") }.files

abstract class ProtocTask : DefaultTask() {
    @get:Inject
    abstract val execOperations: ExecOperations

    @TaskAction
    fun runProtoc() {
        val protocExe = project.configurations.named("protoc").get().singleFile
            .apply { setExecutable(true) }
        val outDir = project.layout.buildDirectory.dir("generated/proto/java").get().asFile
        outDir.mkdirs()
        val inputs = project.fileTree("java") { include("**/*.proto") }.files
        inputs.forEach { proto ->
            execOperations.exec {
                executable(protocExe.absolutePath)
                args(
                    "-Ijava",
                    "--java_out=lite:${outDir.absolutePath}",
                    proto.absolutePath,
                )
            }
        }
    }
}

tasks.register<ProtocTask>("generateDialerProtos") {
    outputs.dir(generatedProtoDir)
}

tasks.named("preBuild") {
    dependsOn("generateDialerProtos")
}

android {
    compileSdk = 35
    namespace = "com.android.dialer"

    defaultConfig {
        applicationId = "com.android.dialer"
        // Platform app: no platform minSdk in Soong; 26 keeps java.time /
        // streams native so no desugaring is needed for the CI APK.
        minSdk = 26
        targetSdk = 35
        // Matches AndroidManifest.xml.
        versionCode = 2900000
        versionName = "23.0"
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
        getByName("debug") {
            // Append .dev to package name so we won't conflict with AOSP build.
            applicationIdSuffix = ".dev"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        // Platform code trips NewApi/typo lint; staging APK only.
        abortOnError = false
        checkReleaseBuilds = false
    }

    sourceSets {
        getByName("main") {
            manifest.srcFile("AndroidManifest.xml")
            java.directories.add("java")
            java.directories.add("gradle/stubs/java")
            java.directories.add(generatedProtoDir.get().asFile.absolutePath)
            aidl.directories.add("java")
            // Mirrors Android.bp resource_dirs.
            res.directories.add("assets/quantum/res")
            res.directories.add("java/com/android/contacts/common/res")
            res.directories.add("java/com/android/dialer/app/res")
            res.directories.add("java/com/android/dialer/assisteddialing/res")
            res.directories.add("java/com/android/dialer/assisteddialing/ui/res")
            res.directories.add("java/com/android/dialer/blocking/res")
            res.directories.add("java/com/android/dialer/blockreportspam/res")
            res.directories.add("java/com/android/dialer/calldetails/res")
            res.directories.add("java/com/android/dialer/calllog/ui/menu/res")
            res.directories.add("java/com/android/dialer/calllogutils/res")
            res.directories.add("java/com/android/dialer/callrecord/res")
            res.directories.add("java/com/android/dialer/callstats/res")
            res.directories.add("java/com/android/dialer/clipboard/res")
            res.directories.add("java/com/android/dialer/common/res")
            res.directories.add("java/com/android/dialer/contactphoto/res")
            res.directories.add("java/com/android/dialer/contacts/displaypreference/res")
            res.directories.add("java/com/android/dialer/contacts/resources/res")
            res.directories.add("java/com/android/dialer/contactsfragment/res")
            res.directories.add("java/com/android/dialer/dialpadview/res")
            res.directories.add("java/com/android/dialer/dialpadview/theme/res")
            res.directories.add("java/com/android/dialer/glidephotomanager/impl/res")
            res.directories.add("java/com/android/dialer/helplines/res")
            res.directories.add("java/com/android/dialer/historyitemactions/res")
            res.directories.add("java/com/android/dialer/interactions/res")
            res.directories.add("java/com/android/dialer/lettertile/res")
            res.directories.add("java/com/android/dialer/lookup/res")
            res.directories.add("java/com/android/dialer/main/impl/bottomnav/res")
            res.directories.add("java/com/android/dialer/main/impl/res")
            res.directories.add("java/com/android/dialer/main/impl/toolbar/res")
            res.directories.add("java/com/android/dialer/notification/res")
            res.directories.add("java/com/android/dialer/oem/res")
            res.directories.add("java/com/android/dialer/phonenumberutil/res")
            res.directories.add("java/com/android/dialer/postcall/res")
            res.directories.add("java/com/android/dialer/precall/impl/res")
            res.directories.add("java/com/android/dialer/preferredsim/impl/res")
            res.directories.add("java/com/android/dialer/preferredsim/suggestion/res")
            res.directories.add("java/com/android/dialer/promotion/impl/res")
            res.directories.add("java/com/android/dialer/rtt/res")
            res.directories.add("java/com/android/dialer/searchfragment/common/res")
            res.directories.add("java/com/android/dialer/searchfragment/cp2/res")
            res.directories.add("java/com/android/dialer/searchfragment/directories/res")
            res.directories.add("java/com/android/dialer/searchfragment/list/res")
            res.directories.add("java/com/android/dialer/searchfragment/nearbyplaces/res")
            res.directories.add("java/com/android/dialer/searchfragment/remote/res")
            res.directories.add("java/com/android/dialer/shortcuts/res")
            res.directories.add("java/com/android/dialer/spannable/res")
            res.directories.add("java/com/android/dialer/speeddial/res")
            res.directories.add("java/com/android/dialer/theme/base/res")
            res.directories.add("java/com/android/dialer/theme/common/res")
            res.directories.add("java/com/android/dialer/theme/hidden/res")
            res.directories.add("java/com/android/dialer/theme/res")
            res.directories.add("java/com/android/dialer/util/res")
            res.directories.add("java/com/android/dialer/voicemail/listui/error/res")
            res.directories.add("java/com/android/dialer/voicemail/listui/res")
            res.directories.add("java/com/android/dialer/voicemail/settings/res")
            res.directories.add("java/com/android/dialer/widget/res")
            res.directories.add("java/com/android/incallui/answer/impl/affordance/res")
            res.directories.add("java/com/android/incallui/answer/impl/answermethod/res")
            res.directories.add("java/com/android/incallui/answer/impl/res")
            res.directories.add("java/com/android/incallui/audioroute/res")
            res.directories.add("java/com/android/incallui/autoresizetext/res")
            res.directories.add("java/com/android/incallui/callpending/res")
            res.directories.add("java/com/android/incallui/commontheme/res")
            res.directories.add("java/com/android/incallui/contactgrid/res")
            res.directories.add("java/com/android/incallui/disconnectdialog/res")
            res.directories.add("java/com/android/incallui/hold/res")
            res.directories.add("java/com/android/incallui/incall/impl/res")
            res.directories.add("java/com/android/incallui/res")
            res.directories.add("java/com/android/incallui/rtt/impl/res")
            res.directories.add("java/com/android/incallui/sessiondata/res")
            res.directories.add("java/com/android/incallui/spam/res")
            res.directories.add("java/com/android/incallui/telecomeventui/res")
            res.directories.add("java/com/android/incallui/theme/res")
            res.directories.add("java/com/android/incallui/video/impl/res")
            res.directories.add("java/com/android/incallui/video/protocol/res")
            res.directories.add("java/com/android/voicemail/impl/configui/res")
            res.directories.add("java/com/android/voicemail/impl/res")
        }
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/AL2.0",
                "/META-INF/LGPL2.1",
            )
        }
    }
}



dependencies {
    // AndroidX equivalents of the Soong static_libs.
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.cardview:cardview:1.0.0")
    implementation("androidx.coordinatorlayout:coordinatorlayout:1.2.0")
    implementation("androidx.core:core:1.15.0")
    implementation("androidx.dynamicanimation:dynamicanimation:1.1.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("androidx.localbroadcastmanager:localbroadcastmanager:1.1.0")
    implementation("androidx.preference:preference:1.2.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.google.android.material:material:1.12.0")

    // Dagger + AutoValue (javac processors, as in Soong).
    implementation("com.google.dagger:dagger:2.51.1")
    annotationProcessor("com.google.dagger:dagger-compiler:2.51.1")
    implementation("com.google.auto.value:auto-value-annotations:1.10.7")
    annotationProcessor("com.google.auto.value:auto-value:1.10.7")
    // In-repo root-component processor (Soong java_plugin equivalent).
    annotationProcessor(project(":dialer-processor"))

    // Glide (AppGlideModule present; code uses the non-generated API).
    implementation("com.github.bumptech.glide:glide:4.16.0")
    annotationProcessor("com.github.bumptech.glide:compiler:4.16.0")

    // Protos (lite, as in Soong).
    implementation("com.google.protobuf:protobuf-javalite:3.25.5")

    // Phone numbers.
    implementation("com.googlecode.libphonenumber:libphonenumber:9.0.1")
    implementation("com.googlecode.libphonenumber:geocoder:9.0.1")

    // Misc Soong static_libs available on Maven.
    implementation("com.android.volley:volley:1.2.1")
    implementation("com.google.guava:guava:33.4.0-android")
    implementation("javax.inject:javax.inject:1")
    implementation("com.google.code.findbugs:jsr305:3.0.2")
    implementation("com.google.errorprone:error_prone_annotations:2.27.0")
    implementation("org.apache.commons:commons-compress:1.26.0")
    implementation(files("prebuilts/apache-mime4j-core-0.8.9.jar"))
    implementation(files("prebuilts/apache-mime4j-dom-0.8.9.jar"))
    implementation(files("prebuilts/commons-io-2.13.0.jar"))

    // OriginSU liquid-glass bottom bar: exact Compose port (same
    // artifacts as MaxxOS ExactCalculator).
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation-core")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.runtime:runtime")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("top.yukonga.miuix.kmp:miuix-blur-android:0.9.4-rc01")
}
