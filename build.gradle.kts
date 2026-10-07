/*
 * SPDX-FileCopyrightText: 2023 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 *
 * Standalone Gradle build for CI staging APKs. The ROM (Soong) build is
 * unaffected: Soong only compiles java/ and reads Android.bp/AndroidManifest.
 *
 * Three adaptations make the AOSP sources buildable with the public SDK:
 *  1. Resources: Android.bp lists 77 resource_dirs merged by Soong with
 *     --auto-add-overlay (last wins). The sanitizeDialerSources task below
 *     reproduces that merge into build/generated/dialer-res, replacing the
 *     two private @*android font refs with public equivalents.
 *  2. Manifest: a sanitized copy drops Soong-only attributes (package,
 *     coreApp, versionCode/Name come from this file instead).
 *  3. System libs: org.lineageos.lib.phone + com.android.common.io stubs in
 *     gradle/stubs (invisible to Soong).
 * The custom dialer-rootcomponentprocessor is intentionally not wired up:
 * it generates nothing referenced by the app sources (the AOSP root
 * component is handwritten; Dagger's own compiler emits the impl).
 */

import java.io.File as JFile
import java.io.StringWriter as JStringWriter
import org.gradle.api.file.SourceDirectorySet
import org.gradle.api.plugins.ExtensionAware
import org.gradle.api.tasks.compile.JavaCompile

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
    id("com.google.protobuf") version "0.10.0"
}


// Ordered exactly like Android.bp resource_dirs: later entries win,
// mirroring Soong's --auto-add-overlay.
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

val sanitizeDialerSources = tasks.register("sanitizeDialerSources") {
    val resOut = layout.buildDirectory.dir("generated/dialer-res")
    val manifestOut = layout.buildDirectory.file("generated/dialer-manifest/AndroidManifest.xml")
    val protoOut = layout.buildDirectory.dir("generated/dialer-proto")
    val aidlOut = layout.buildDirectory.dir("generated/dialer-aidl")
    // Captured here (script scope has the android/project accessors).
    val sdkDir = android.sdkDirectory
    val projectJavaDir = file("java")
    outputs.dir(resOut)
    outputs.file(manifestOut)
    outputs.dir(protoOut)
    outputs.dir(aidlOut)

    doLast {
        val outDir = resOut.get().asFile
        outDir.deleteRecursively()
        outDir.mkdirs()

        val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance()
        val builder = factory.newDocumentBuilder()
        val transformer =
            javax.xml.transform.TransformerFactory.newInstance().newTransformer().apply {
                setOutputProperty(javax.xml.transform.OutputKeys.INDENT, "yes")
                setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2")
            }

        fun sanitizeText(text: String, origin: String): String {
            var out = text
                .replace("@*android:string/config_bodyFontFamily", "sans-serif")
                .replace("@*android:string/config_headlineFontFamilyMedium", "sans-serif-medium")
            check(!out.contains("@*android")) { "Private framework ref left in " + origin + ": " + out }
            return out
        }

        fun sanitizeNode(node: org.w3c.dom.Node, origin: String) {
            if (node.nodeType == org.w3c.dom.Node.ELEMENT_NODE) {
                val attrs = node.attributes
                for (i in 0 until attrs.length) {
                    val attr = attrs.item(i)
                    attr.nodeValue = sanitizeText(attr.nodeValue, origin)
                }
            } else if (node.nodeType == org.w3c.dom.Node.TEXT_NODE) {
                node.nodeValue = sanitizeText(node.nodeValue, origin)
            }
            val children = node.childNodes
            for (i in 0 until children.length) {
                sanitizeNode(children.item(i), origin)
            }
        }

        fun effectiveKey(element: org.w3c.dom.Element): String? {
            if (element.tagName == "declare-styleable") {
                return "declare-styleable|" + element.getAttribute("name")
            }
            if (!element.hasAttribute("name")) {
                return null
            }
            val type =
                if (element.hasAttribute("type") && element.getAttribute("type").isNotEmpty()) {
                    element.getAttribute("type")
                } else {
                    element.tagName
                }
            return type + "|" + element.getAttribute("name")
        }

        fun findChild(root: org.w3c.dom.Element, key: String): org.w3c.dom.Element? {
            val children = root.childNodes
            for (i in 0 until children.length) {
                val child = children.item(i)
                if (child.nodeType == org.w3c.dom.Node.ELEMENT_NODE
                    && effectiveKey(child as org.w3c.dom.Element) == key
                ) {
                    return child
                }
            }
            return null
        }

        // AAPT rejects the same <attr> being *defined* (format or enum/flag
        // children) more than once, even inside different styleables, while
        // Soong's --auto-add-overlay lets the last definition win. Track the
        // winning definition node per attr name (in Soong dir order) and strip
        // earlier losers down to bare references.
        // AAPT also merges every values file per config, so same-name
        // resources in DIFFERENT files (e.g. cm_strings.xml vs strings.xml)
        // collide too. resWinners tracks the winner per (config, type, name)
        // across all files; earlier winners are removed/stripped. This is
        // the --auto-add-overlay last-wins rule.
        val resWinners = mutableMapOf<String, org.w3c.dom.Element>()

        fun isAttrDefinition(element: org.w3c.dom.Element): Boolean {
            if (element.tagName != "attr") {
                return false
            }
            val name = element.getAttribute("name")
            if (name.isEmpty() || name.contains(":")) {
                return false
            }
            if (element.hasAttribute("format")) {
                return true
            }
            val kids = element.childNodes
            for (i in 0 until kids.length) {
                val kid = kids.item(i)
                if (kid.nodeType == org.w3c.dom.Node.ELEMENT_NODE
                    && (kid.nodeName == "enum" || kid.nodeName == "flag")
                ) {
                    return true
                }
            }
            return false
        }

        fun stripDefinitionNode(node: org.w3c.dom.Element) {
            val parent = node.parentNode ?: return
            if (parent.nodeType != org.w3c.dom.Node.ELEMENT_NODE) {
                return
            }
            if ((parent as org.w3c.dom.Element).tagName == "declare-styleable") {
                node.removeAttribute("format")
                val kids = node.childNodes
                val doomed = mutableListOf<org.w3c.dom.Node>()
                for (i in 0 until kids.length) {
                    val kid = kids.item(i)
                    if (kid.nodeType == org.w3c.dom.Node.ELEMENT_NODE
                        && (kid.nodeName == "enum" || kid.nodeName == "flag")
                    ) {
                        doomed.add(kid)
                    }
                }
                doomed.forEach { node.removeChild(it) }
            } else {
                parent.removeChild(node)
            }
        }

        fun registerDefinitions(placed: org.w3c.dom.Node, config: String) {
            fun visit(n: org.w3c.dom.Node) {
                if (n.nodeType != org.w3c.dom.Node.ELEMENT_NODE) {
                    return
                }
                val element = n as org.w3c.dom.Element
                if (isAttrDefinition(element)) {
                    val gkey = config + "|attr|" + element.getAttribute("name")
                    val prev = resWinners[gkey]
                    if (prev != null && prev !== element) {
                        stripDefinitionNode(prev)
                    }
                    resWinners[gkey] = element
                }
                val kids = n.childNodes
                for (i in 0 until kids.length) {
                    visit(kids.item(i))
                }
            }
            visit(placed)
        }

        fun mergeStyleable(targetDoc: org.w3c.dom.Document, target: org.w3c.dom.Element, src: org.w3c.dom.Element) {
            val children = src.childNodes
            for (i in 0 until children.length) {
                val child = children.item(i)
                if (child.nodeType != org.w3c.dom.Node.ELEMENT_NODE) {
                    continue
                }
                val childKey = effectiveKey(child as org.w3c.dom.Element)
                val existing =
                    if (childKey != null) {
                        findChild(target, childKey)
                    } else {
                        null
                    }
                val imported = targetDoc.importNode(child, true)
                if (existing != null) {
                    target.replaceChild(imported, existing)
                } else {
                    target.appendChild(imported)
                }
            }
        }

        // Merged values documents keyed by output-relative path.
        val valuesDocs = mutableMapOf<String, org.w3c.dom.Document>()
        fun valuesDocFor(rel: String): Pair<org.w3c.dom.Document, org.w3c.dom.Element> {
            return if (valuesDocs.containsKey(rel)) {
                val doc = valuesDocs[rel]!!
                Pair(doc, doc.documentElement)
            } else {
                val doc = builder.newDocument()
                val root = doc.createElement("resources")
                root.setAttribute("xmlns:android", "http://schemas.android.com/apk/res/android")
                root.setAttribute("xmlns:tools", "http://schemas.android.com/tools")
                root.setAttribute("xmlns:xliff", "urn:oasis:names:tc:xliff:document:1.2")
                doc.appendChild(root)
                valuesDocs[rel] = doc
                Pair(doc, root)
            }
        }

        fun copyRaw(src: JFile, rel: String) {
            val dest = outDir.resolve(rel)
            dest.parentFile.mkdirs()
            src.copyTo(dest, overwrite = true)
        }

        for (dir in dialerResDirs) {
            val srcDir = file(dir)
            if (!srcDir.isDirectory) {
                continue
            }
            srcDir.walkTopDown().forEach { f ->
                if (!f.isFile) {
                    return@forEach
                }
                val rel = srcDir.toPath().relativize(f.toPath()).toString()
                val origin = dir + "/" + rel
                if ((rel.startsWith("values") || rel.startsWith("values-")) && f.extension == "xml") {
                    val parsed =
                        try {
                            builder.parse(f)
                        } catch (_: Exception) {
                            null
                        }
                    if (parsed == null || parsed.documentElement?.tagName != "resources") {
                        copyRaw(f, rel)
                        return@forEach
                    }
                    sanitizeNode(parsed.documentElement, origin)
                    val (targetDoc, targetRoot) = valuesDocFor(rel)
                    val children = parsed.documentElement.childNodes
                    // Snapshot: importing mutates nothing in src, iterate directly.
                    for (i in 0 until children.length) {
                        val child = children.item(i)
                        if (child.nodeType == org.w3c.dom.Node.TEXT_NODE) {
                            continue
                        }
                        if (child.nodeType == org.w3c.dom.Node.COMMENT_NODE) {
                            targetRoot.appendChild(targetDoc.importNode(child, true))
                            continue
                        }
                        if (child.nodeType != org.w3c.dom.Node.ELEMENT_NODE) {
                            continue
                        }
                        val element = child as org.w3c.dom.Element
                        val key = effectiveKey(element)
                        // Bare <attr> references carry no definition and are
                        // legal to repeat (each styleable lists the attrs it
                        // uses); only real resources participate in last-wins.
                        val tracked = key != null
                            && !(element.tagName == "attr" && !isAttrDefinition(element))
                        if (element.tagName == "declare-styleable" && key != null) {
                            val existing = findChild(targetRoot, key)
                            if (existing != null) {
                                mergeStyleable(targetDoc, existing, element)
                                registerDefinitions(existing, rel.substringBefore("/"))
                            } else {
                                registerDefinitions(
                                    targetRoot.appendChild(targetDoc.importNode(element, true)),
                                    rel.substringBefore("/"),
                                )
                            }
                        } else if (key != null && tracked) {
                            val gkey = rel.substringBefore("/") + "|" + key
                            val prevGlobal = resWinners[gkey]
                            if (prevGlobal != null) {
                                stripDefinitionNode(prevGlobal)
                            }
                            val existing = findChild(targetRoot, key)
                            val imported = targetDoc.importNode(element, true)
                            if (existing != null) {
                                targetRoot.replaceChild(imported, existing)
                            } else {
                                targetRoot.appendChild(imported)
                            }
                            resWinners[gkey] = imported as org.w3c.dom.Element
                        } else if (key != null) {
                            // Bare <attr> references (no format): pass through,
                            // never tracked as definitions.
                            val existing = findChild(targetRoot, key)
                            val imported = targetDoc.importNode(element, true)
                            if (existing != null) {
                                targetRoot.replaceChild(imported, existing)
                            } else {
                                targetRoot.appendChild(imported)
                            }
                        } else {
                            // <skip/>, <eat-comment/> and friends: keep, last file wins.
                            targetRoot.appendChild(targetDoc.importNode(element, true))
                        }
                    }
                } else {
                    copyRaw(f, rel)
                }
            }
        }

        for ((rel, doc) in valuesDocs) {
            val dest = outDir.resolve(rel)
            dest.parentFile.mkdirs()
            val writer = JStringWriter()
            transformer.transform(
                javax.xml.transform.dom.DOMSource(doc),
                javax.xml.transform.stream.StreamResult(writer),
            )
            dest.writeText("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" + writer.toString()
                .replace(Regex("<\\?xml[^>]*>\\s*"), ""))
        }

        var manifest = file("AndroidManifest.xml").readText()
        manifest = manifest.replace(Regex("\\s+package=\"[^\"]*\""), "")
        manifest = manifest.replace(Regex("\\s+coreApp=\"[^\"]*\""), "")
        manifest = manifest.replace(Regex("\\s+android:versionCode=\"[^\"]*\""), "")
        manifest = manifest.replace(Regex("\\s+android:versionName=\"[^\"]*\""), "")
        manifestOut.get().asFile.apply {
            parentFile.mkdirs()
            writeText(manifest)
        }

        // AIDL: the in-tree .aidl interfaces are compiled here with the SDK
        // aidl tool (AGP never creates an aidl task for them). Pure
        // `parcelable X;` declarations need no codegen and are skipped; the
        // generated Java mirrors the source package tree.
        val aidlDir = aidlOut.get().asFile
        aidlDir.deleteRecursively()
        val sdkTools = sdkDir.get().asFile.resolve("build-tools")
        val aidlBin = sdkTools.listFiles()
            ?.filter { it.isDirectory }
            ?.sortedByDescending { it.name }
            ?.map { it.resolve("aidl") }
            ?.firstOrNull { it.isFile }
            ?: throw GradleException("aidl tool not found under " + sdkTools)
        val javaBase = projectJavaDir
        javaBase.walkTopDown().forEach { f ->
            if (!f.isFile || f.extension != "aidl") {
                return@forEach
            }
            val text = f.readText()
            if (!text.contains("interface ")) {
                return@forEach
            }
            val rel = javaBase.toPath().relativize(f.toPath()).toString()
            val dest = aidlDir.resolve(rel.removeSuffix(".aidl") + ".java")
            dest.parentFile.mkdirs()
            val proc = ProcessBuilder(
                aidlBin.absolutePath,
                "--lang=java",
                "-I" + javaBase.absolutePath,
                f.absolutePath,
                dest.absolutePath,
            ).redirectErrorStream(true).start()
            val procOut = proc.inputStream.bufferedReader().readText()
            check(proc.waitFor() == 0) { "aidl failed for " + rel + ": " + procOut }
            check(dest.isFile) { "aidl produced no output for " + rel + ": " + procOut }
        }

        // Stage protos with repo-root-relative paths (imports like
        // "java/com/.../x.proto" resolve against this tree).
        val protoDir = protoOut.get().asFile
        protoDir.deleteRecursively()
        val protoBase = projectJavaDir.toPath()
        projectJavaDir.walkTopDown().forEach { f ->
            if (f.isFile && f.extension == "proto") {
                val rel = "java/" + protoBase.relativize(f.toPath()).toString()
                val dest = protoDir.resolve(rel)
                dest.parentFile.mkdirs()
                f.copyTo(dest, overwrite = true)
            }
        }
    }
}

tasks.named("preBuild") {
    dependsOn(sanitizeDialerSources)
}

// The in-tree Dagger root-component *processor* is a host build tool, not
// app code: it needs the JDK compiler API (javax.lang.model), which is not
// on the android.jar bootclasspath, and nothing in the app references it
// (the AOSP root component is handwritten; Dagger's own compiler emits the
// impl). Keep it out of compilation; Soong still builds it as a separate
// java_plugin from the same sources.
tasks.withType<JavaCompile>().configureEach {
    exclude("com/android/dialer/rootcomponentgenerator/**")
}

// The protobuf codegen tasks read the staged proto tree above; declare the
// edge explicitly so task validation accepts the producer/consumer order.
tasks.configureEach {
    if (name.startsWith("generate") && name.endsWith("Proto")) {
        dependsOn(sanitizeDialerSources)
    }
}



android {
    compileSdk = 37
    namespace = "com.android.dialer"

    defaultConfig {
        applicationId = "com.android.dialer"
        // miuix-blur (OriginSU liquid glass) requires minSdk 33.
        minSdk = 33
        targetSdk = 35
        versionCode = 2900000
        versionName = "23.0"
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            setProguardFiles(
                listOf(
                    getDefaultProguardFile("proguard-android-optimize.txt"),
                    "proguard.flags"
                )
            )
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
    sourceSets {
        getByName("main") {
            manifest.srcFile("build/generated/dialer-manifest/AndroidManifest.xml")
            java.srcDirs("java", "gradle/stubs/java", "build/generated/dialer-aidl")
            res.srcDirs("build/generated/dialer-res")
            aidl.srcDirs("java")
        }
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
    packaging {
        resources {
            excludes += setOf(
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/DEPENDENCIES",
                "**/module-info.class"
            )
        }
    }
}

// The protobuf plugin adds a `proto` source-directory-set to the main
// source set. It points at the sanitized task's staged copy (declared as
// that task's output, so Gradle orders them correctly); the staged tree
// preserves repo-root-relative paths ("java/com/...") so in-tree proto
// imports resolve, mirroring Soong.
(android.sourceSets.getByName("main") as ExtensionAware)
    .extensions
    .configure<SourceDirectorySet>("proto") {
        srcDir("build/generated/dialer-proto")
    }

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:4.36.2"
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                maybeCreate("java").option("lite")
            }
        }
    }
}

dependencies {
    implementation("androidx.annotation:annotation:1.7.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.cardview:cardview:1.0.0")
    implementation("androidx.collection:collection:1.4.0")
    implementation("androidx.coordinatorlayout:coordinatorlayout:1.2.0")
    implementation("androidx.fragment:fragment:1.7.0")
    implementation("androidx.gridlayout:gridlayout:1.0.0")
    implementation("androidx.interpolator:interpolator:1.0.0")
    implementation("androidx.loader:loader:1.0.0")
    implementation("androidx.localbroadcastmanager:localbroadcastmanager:1.1.0")
    implementation("androidx.preference:preference:1.2.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.startup:startup-runtime:1.1.1")
    implementation("androidx.viewpager:viewpager:1.0.0")
    implementation("com.google.android.material:material:1.14.0")
    implementation("com.android.volley:volley:1.2.1")
    implementation("com.googlecode.libphonenumber:libphonenumber:9.0.41")
    implementation("com.google.guava:guava:33.3.1-android")
    implementation("com.google.dagger:dagger:2.56.2")
    implementation("com.google.auto.value:auto-value-annotations:1.11.1")
    // The in-tree Dagger root-component processor sources compile along but
    // stay inert (no ServiceLoader registration, unlike the Soong
    // java_plugin wiring), so the APK needs these on the classpath.
    implementation("com.google.auto.service:auto-service-annotations:1.1.1")
    implementation("com.google.auto:auto-common:1.2.2")
    implementation("com.squareup:javapoet:1.13.0")
    implementation("com.github.bumptech.glide:glide:4.16.0")
    implementation("com.google.protobuf:protobuf-javalite:4.36.2")
    implementation("javax.inject:javax.inject:1")
    implementation("com.google.code.findbugs:jsr305:3.0.2")
    implementation("com.google.errorprone:error_prone_annotations:2.50.0")
    implementation("org.apache.commons:commons-compress:1.28.0")
    implementation("commons-io:commons-io:2.20.0")
    implementation(files("prebuilts/apache-mime4j-core-0.8.9.jar"))
    implementation(files("prebuilts/apache-mime4j-dom-0.8.9.jar"))

    annotationProcessor("com.google.dagger:dagger-compiler:2.56.2")
    annotationProcessor("com.google.auto.value:auto-value:1.11.1")
    annotationProcessor("com.github.bumptech.glide:compiler:4.16.0")

    // OriginSU liquid-glass bottom bar: exact Compose port (see java/com/android/dialer/glass).
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation-core")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.runtime:runtime")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("top.yukonga.miuix.kmp:miuix-blur-android:0.9.4-rc01")
}
