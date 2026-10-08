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
import org.gradle.api.GradleException
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.process.ExecOperations
import java.io.File
import java.util.zip.ZipFile
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

// App Java sources, enumerated per directory: AGP 9 dropped source-dir
// excludes, and the Dagger generator must not compile into the app
// (Soong exclude_srcs; it needs the full JDK, not android.jar).
// New source dirs must be added here to be visible to the CI build.
val dialerJavaDirs = listOf(
    "java/com/android/contacts",
    "java/com/android/incallui",
    "java/com/android/voicemail",
    "java/com/android/dialer/activecalls",
    "java/com/android/dialer/animation",
    "java/com/android/dialer/app",
    "java/com/android/dialer/assisteddialing",
    "java/com/android/dialer/binary",
    "java/com/android/dialer/blocking",
    "java/com/android/dialer/blockreportspam",
    "java/com/android/dialer/calldetails",
    "java/com/android/dialer/callintent",
    "java/com/android/dialer/calllog",
    "java/com/android/dialer/calllogutils",
    "java/com/android/dialer/callrecord",
    "java/com/android/dialer/callstats",
    "java/com/android/dialer/clipboard",
    "java/com/android/dialer/common",
    "java/com/android/dialer/compat",
    "java/com/android/dialer/constants",
    "java/com/android/dialer/contactphoto",
    "java/com/android/dialer/contacts",
    "java/com/android/dialer/contactsfragment",
    "java/com/android/dialer/database",
    "java/com/android/dialer/databasepopulator",
    "java/com/android/dialer/dialercontact",
    "java/com/android/dialer/dialpadview",
    "java/com/android/dialer/function",
    "java/com/android/dialer/glide",
    "java/com/android/dialer/glidephotomanager",
    "java/com/android/dialer/helplines",
    "java/com/android/dialer/historyitemactions",
    "java/com/android/dialer/i18n",
    "java/com/android/dialer/inject",
    "java/com/android/dialer/interactions",
    "java/com/android/dialer/lettertile",
    "java/com/android/dialer/location",
    "java/com/android/dialer/logging",
    "java/com/android/dialer/lookup",
    "java/com/android/dialer/main",
    "java/com/android/dialer/metrics",
    "java/com/android/dialer/multimedia",
    "java/com/android/dialer/notification",
    "java/com/android/dialer/oem",
    "java/com/android/dialer/phonelookup",
    "java/com/android/dialer/phonenumbercache",
    "java/com/android/dialer/phonenumbergeoutil",
    "java/com/android/dialer/phonenumberproto",
    "java/com/android/dialer/phonenumberutil",
    "java/com/android/dialer/postcall",
    "java/com/android/dialer/precall",
    "java/com/android/dialer/preferredsim",
    "java/com/android/dialer/promotion",
    "java/com/android/dialer/protos",
    "java/com/android/dialer/rtt",
    "java/com/android/dialer/searchfragment",
    "java/com/android/dialer/shortcuts",
    "java/com/android/dialer/simulator",
    "java/com/android/dialer/smartdial",
    "java/com/android/dialer/spam",
    "java/com/android/dialer/spannable",
    "java/com/android/dialer/speeddial",
    "java/com/android/dialer/storage",
    "java/com/android/dialer/telecom",
    "java/com/android/dialer/theme",
    "java/com/android/dialer/util",
    "java/com/android/dialer/voicemail",
    "java/com/android/dialer/voicemailstatus",
    "java/com/android/dialer/widget",
    "java/com/android/dialer/proguard",
)

val generatedProtoDir = layout.buildDirectory.dir("generated/proto/java")
val aidlOutDir = layout.buildDirectory.dir("generated/aidl/java")
val apManualOutDir = layout.buildDirectory.dir("generated/ap_manual")
val gradleManifestFile = layout.buildDirectory.file("generated/manifest/AndroidManifest.xml")

// The .dev staging build side-installs next to the system Dialer, which
// already owns com.android.dialer.permission.DIALER_ORIGIN (a signature
// permission cannot be redeclared). The Gradle build therefore uses a
// manifest copy with the permission renamed; Soong keeps using the
// original AndroidManifest.xml untouched.
tasks.register<Exec>("prepareGradleManifest") {
    val outFile = gradleManifestFile.get().asFile
    inputs.file("AndroidManifest.xml")
    inputs.file("gradle/prepare_manifest.py")
    outputs.file(outFile)
    executable("python3")
    args("gradle/prepare_manifest.py", "AndroidManifest.xml", outFile.absolutePath)
}

// AIDL is compiled with the SDK aidl binary directly: AGP 9 creates no
// AIDL task for aidl.directories, so the 3 stable Dialer AIDLs are built
// here exactly like the protos above.
abstract class AidlTask : DefaultTask() {
    @get:Inject
    abstract val execOperations: ExecOperations

    @TaskAction
    fun runAidl() {
        val sdk = System.getenv("ANDROID_HOME")
            ?: throw GradleException("ANDROID_HOME is not set")
        val aidlBin = File(sdk, "build-tools").listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { dir ->
                val exe = File(dir, "aidl")
                if (exe.canExecute()) dir.name to exe else null
            }
            ?.maxByOrNull { it.first }
            ?.second
            ?: throw GradleException("no aidl binary under $sdk/build-tools")
        val outDir = project.layout.buildDirectory.dir("generated/aidl/java").get().asFile
        outDir.mkdirs()
        val rootDir = project.layout.projectDirectory.asFile
        val inputs = project.fileTree(rootDir) { include("java/**/*.aidl") }.files.sorted()
        inputs.forEach { aidl ->
            execOperations.exec {
                executable(aidlBin.absolutePath)
                args(
                    "--lang=java",
                    "-I${File(rootDir, "java").absolutePath}",
                    "--out",
                    outDir.absolutePath,
                    aidl.absolutePath,
                )
            }
        }
    }
}

tasks.register<AidlTask>("compileDialerAidl") {
    outputs.dir(aidlOutDir)
}

// Annotation processors (Dagger, AutoValue, Glide, the in-repo
// root-component generator) produce no output under AGP 9's javac task,
// so they are run explicitly with javac -proc:only; the generated sources
// join the normal compilation as plain sources.
abstract class ApGenTask : DefaultTask() {
    // Set at registration; kept as a property (not a script capture) so
    // the class stays static and Gradle can instantiate it.
    @get:Input
    var dirNames: List<String> = emptyList()

    @TaskAction
    fun runProcessors() {
        val compiler = javax.tools.ToolProvider.getSystemJavaCompiler()
            ?: throw GradleException("no system Java compiler (need a JDK, not a JRE)")
        val sdk = System.getenv("ANDROID_HOME")
            ?: throw GradleException("ANDROID_HOME is not set")
        val androidJar = File(sdk, "platforms/android-37/android.jar")
        val buildDir = project.layout.buildDirectory.get().asFile
        val rJar = File(buildDir,
            "intermediates/compile_r_class_jar/debug/generateDebugRFile/R.jar")
        val kotlinClasses = File(buildDir,
            "intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes")
        val cpJars = project.configurations.named("debugCompileClasspath").get().files
        val ppJars = project.configurations.named("debugAnnotationProcessorClasspath").get().files
        val outDir = project.layout.buildDirectory.dir("generated/ap_manual").get().asFile
        outDir.mkdirs()
        // javac cannot read .aar files: explode classes.jar (+ libs) out of
        // every AAR dependency into a stable local dir (Soong/AGP do the
        // same via transforms).
        fun explodeAar(aar: File): List<File> {
            val dest = File(buildDir, "generated/aar_classes/" + aar.nameWithoutExtension)
            val marker = File(dest, ".exploded")
            if (!marker.exists()) {
                dest.mkdirs()
                ZipFile(aar).use { zip ->
                    zip.entries().asSequence()
                        .filter { e ->
                            !e.isDirectory
                            && (e.name == "classes.jar" || e.name.startsWith("libs/"))
                        }
                        .forEach { e ->
                            val target = File(dest, File(e.name).name)
                            zip.getInputStream(e).use { inp ->
                                target.outputStream().use { out -> inp.copyTo(out) }
                            }
                        }
                }
                marker.createNewFile()
            }
            return dest.listFiles { f -> f.extension == "jar" }?.toList() ?: emptyList()
        }
        val explodedCp = cpJars.flatMap { f ->
            if (f.extension == "aar") explodeAar(f) else listOf(f)
        }
        val sources = (
            dirNames.flatMap { dir ->
                project.fileTree(dir) { include("**/*.java") }.files
            } + project.fileTree("gradle/stubs/java") { include("**/*.java") }.files
            + project.fileTree(
                project.layout.buildDirectory.dir("generated/proto/java").get().asFile,
            ) { include("**/*.java") }.files
            + project.fileTree(
                project.layout.buildDirectory.dir("generated/aidl/java").get().asFile,
            ) { include("**/*.java") }.files
            ).map { it.absolutePath }.sorted()
        val cp = (listOf(androidJar.absolutePath) + explodedCp.map { it.absolutePath }
            + rJar.absolutePath
            + (if (kotlinClasses.isDirectory) listOf(kotlinClasses.absolutePath) else emptyList()))
            .joinToString(File.pathSeparator)
        println("APGEN cp entries: " + (explodedCp.size + 3))
        val args = mutableListOf(
            "-proc:only",
            "-s", outDir.absolutePath,
            "-cp", cp,
            "-processorpath", ppJars.map { it.absolutePath }
                .joinToString(File.pathSeparator),
        )
        args.addAll(sources)
        val rc = compiler.run(null, null, System.err, *args.toTypedArray())
        if (rc != 0) {
            throw GradleException("Dialer annotation processing (-proc:only) failed")
        }
    }
}

tasks.register<ApGenTask>("runDialerAnnotationProcessing") {
    dirNames = dialerJavaDirs
    dependsOn("processDebugResources", "compileDebugKotlin", "compileDialerAidl")
    inputs.files(
        dialerJavaDirs.map { layout.projectDirectory.dir(it) }
            + listOf(
                "gradle/stubs/java",
                "java/com/android/dialer/main/impl/bottomnav/glass",
            ).map { layout.projectDirectory.dir(it) },
    )
    inputs.dir(generatedProtoDir)
    inputs.dir(aidlOutDir)
    outputs.dir(apManualOutDir)
}

afterEvaluate {
    tasks.named("compileDebugJavaWithJavac") {
        dependsOn("runDialerAnnotationProcessing")
    }
    tasks.named("compileDebugKotlin") {
        dependsOn("compileDialerAidl")
    }
}
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
        // Imports are repo-root-relative ("java/com/..."), so the import
        // root is the repo root and every file is compiled in one shot.
        val rootDir = project.layout.projectDirectory.asFile
        val inputs = project.fileTree(rootDir) { include("java/**/*.proto") }.files
        execOperations.exec {
            executable(protocExe.absolutePath)
            args(
                "-I${rootDir.absolutePath}",
                "--java_out=lite:${outDir.absolutePath}",
            )
            args(inputs.map { it.absolutePath })
        }
    }
}

tasks.register<ProtocTask>("generateDialerProtos") {
    outputs.dir(generatedProtoDir)
}

// Soong merges Android.bp resource_dirs with later dirs overriding earlier
// ones per resource key; AGP/AAPT2 fails on duplicates instead. The CI
// build pre-merges with the same last-wins semantics (gradle/merge_res.py).
// Order mirrors Android.bp resource_dirs exactly.
val dialerResDirs = listOf(
    "assets/quantum/res",
    "java/com/android/contacts/common/res",
    "java/com/android/dialer/app/res",
    "java/com/android/dialer/assisteddialing/res",
    "java/com/android/dialer/assisteddialing/ui/res",
    "java/com/android/dialer/blocking/res",
    "java/com/android/dialer/blockreportspam/res",
    "java/com/android/dialer/calldetails/res",
    "java/com/android/dialer/calllog/ui/menu/res",
    "java/com/android/dialer/calllogutils/res",
    "java/com/android/dialer/callrecord/res",
    "java/com/android/dialer/callstats/res",
    "java/com/android/dialer/clipboard/res",
    "java/com/android/dialer/common/res",
    "java/com/android/dialer/contactphoto/res",
    "java/com/android/dialer/contacts/displaypreference/res",
    "java/com/android/dialer/contacts/resources/res",
    "java/com/android/dialer/contactsfragment/res",
    "java/com/android/dialer/dialpadview/res",
    "java/com/android/dialer/dialpadview/theme/res",
    "java/com/android/dialer/glidephotomanager/impl/res",
    "java/com/android/dialer/helplines/res",
    "java/com/android/dialer/historyitemactions/res",
    "java/com/android/dialer/interactions/res",
    "java/com/android/dialer/lettertile/res",
    "java/com/android/dialer/lookup/res",
    "java/com/android/dialer/main/impl/bottomnav/res",
    "java/com/android/dialer/main/impl/res",
    "java/com/android/dialer/main/impl/toolbar/res",
    "java/com/android/dialer/notification/res",
    "java/com/android/dialer/oem/res",
    "java/com/android/dialer/phonenumberutil/res",
    "java/com/android/dialer/postcall/res",
    "java/com/android/dialer/precall/impl/res",
    "java/com/android/dialer/preferredsim/impl/res",
    "java/com/android/dialer/preferredsim/suggestion/res",
    "java/com/android/dialer/promotion/impl/res",
    "java/com/android/dialer/rtt/res",
    "java/com/android/dialer/searchfragment/common/res",
    "java/com/android/dialer/searchfragment/cp2/res",
    "java/com/android/dialer/searchfragment/directories/res",
    "java/com/android/dialer/searchfragment/list/res",
    "java/com/android/dialer/searchfragment/nearbyplaces/res",
    "java/com/android/dialer/searchfragment/remote/res",
    "java/com/android/dialer/shortcuts/res",
    "java/com/android/dialer/spannable/res",
    "java/com/android/dialer/speeddial/res",
    "java/com/android/dialer/theme/base/res",
    "java/com/android/dialer/theme/common/res",
    "java/com/android/dialer/theme/hidden/res",
    "java/com/android/dialer/theme/res",
    "java/com/android/dialer/util/res",
    "java/com/android/dialer/voicemail/listui/error/res",
    "java/com/android/dialer/voicemail/listui/res",
    "java/com/android/dialer/voicemail/settings/res",
    "java/com/android/dialer/widget/res",
    "java/com/android/incallui/answer/impl/affordance/res",
    "java/com/android/incallui/answer/impl/answermethod/res",
    "java/com/android/incallui/answer/impl/res",
    "java/com/android/incallui/audioroute/res",
    "java/com/android/incallui/autoresizetext/res",
    "java/com/android/incallui/callpending/res",
    "java/com/android/incallui/commontheme/res",
    "java/com/android/incallui/contactgrid/res",
    "java/com/android/incallui/disconnectdialog/res",
    "java/com/android/incallui/hold/res",
    "java/com/android/incallui/incall/impl/res",
    "java/com/android/incallui/res",
    "java/com/android/incallui/rtt/impl/res",
    "java/com/android/incallui/sessiondata/res",
    "java/com/android/incallui/spam/res",
    "java/com/android/incallui/telecomeventui/res",
    "java/com/android/incallui/theme/res",
    "java/com/android/incallui/video/impl/res",
    "java/com/android/incallui/video/protocol/res",
    "java/com/android/voicemail/impl/configui/res",
    "java/com/android/voicemail/impl/res",
)

val mergedResDir = layout.buildDirectory.dir("generated/res/merged")

tasks.register<Exec>("mergeDialerRes") {
    executable("python3")
    val outDir = mergedResDir.get().asFile
    args(listOf("gradle/merge_res.py", outDir.absolutePath) + dialerResDirs)
    inputs.file("gradle/merge_res.py")
    dialerResDirs.forEach { inputs.dir(it) }
    outputs.dir(outDir)
}

tasks.named("preBuild") {
    dependsOn("generateDialerProtos", "mergeDialerRes", "prepareGradleManifest")
}

// Annotation processing is pre-run by runDialerAnnotationProcessing above
// (AGP's javac task silently skips processors in this setup); disable it
// here so processors never run twice on the same sources.
tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-proc:none")
}

android {
    // Matches MaxxOS ExactCalculator (CI runners carry android-37).
    compileSdk = 37
    namespace = "com.android.dialer"

    defaultConfig {
        applicationId = "com.android.dialer"
        // miuix-blur requires minSdk 33.
        minSdk = 33
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
            manifest.srcFile(gradleManifestFile)
            res.directories.add(mergedResDir.get().asFile.absolutePath)
            dialerJavaDirs.forEach { java.directories.add(it) }
            java.directories.add("gradle/stubs/java")
            java.directories.add(generatedProtoDir.get().asFile.absolutePath)
            java.directories.add(aidlOutDir.get().asFile.absolutePath)
            java.directories.add(apManualOutDir.get().asFile.absolutePath)
            // Kotlin lives only in the glass navbar; registered explicitly
            // (AGP 9 does not pick Kotlin up from java.directories).
            kotlin.directories.add("java/com/android/dialer/main/impl/bottomnav/glass")
        }
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/AL2.0",
                "/META-INF/DEPENDENCIES",
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
    implementation("com.google.auto.value:auto-value-annotations:1.11.1")
    annotationProcessor("com.google.auto.value:auto-value:1.11.1")
    // In-repo root-component processor (Soong java_plugin equivalent).
    annotationProcessor(project(":dialer-processor"))
    // The generator sources also compile with the app (AGP 9 has no source
    // excludes; Soong excludes them via exclude_srcs). They are inert at
    // runtime: processors only run from the annotation-processor path.
    implementation("com.google.auto:auto-common:1.2.2")
    implementation("com.google.auto.service:auto-service-annotations:1.1.1")
    implementation("com.squareup:javapoet:1.13.0")

    // Glide (AppGlideModule present; code uses the non-generated API).
    implementation("com.github.bumptech.glide:glide:4.16.0")
    annotationProcessor("com.github.bumptech.glide:compiler:4.16.0")

    // Protos (lite, as in Soong).
    implementation("com.google.protobuf:protobuf-javalite:3.25.5")

    // Phone numbers.
    implementation("com.googlecode.libphonenumber:libphonenumber:9.0.41")
    implementation("com.googlecode.libphonenumber:geocoder:3.41")

    // Misc Soong static_libs available on Maven.
    implementation("com.android.volley:volley:1.2.1")
    implementation("com.google.guava:guava:33.4.0-android")
    implementation("javax.inject:javax.inject:1")
    implementation("com.google.code.findbugs:jsr305:3.0.2")
    implementation("com.google.errorprone:error_prone_annotations:2.27.0")
    implementation("org.apache.commons:commons-compress:1.26.0")
    implementation(files("prebuilts/apache-mime4j-core-0.8.9.jar"))
    implementation(files("prebuilts/apache-mime4j-dom-0.8.9.jar"))
    // Soong pins 2.13.0 via prebuilt; Maven coordinate lets Gradle unify
    // it with the newer transitive copy instead of duplicating classes.
    implementation("commons-io:commons-io:2.13.0")

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
