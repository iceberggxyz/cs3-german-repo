import com.android.build.gradle.BaseExtension
import com.lagradost.cloudstream3.gradle.CloudstreamExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

/*
 * IceBERGG CloudStream-3 German Repository
 * -----------------------------------------
 * Build-System analog zur offiziellen CloudStream-Plugin-Vorlage
 * (recloudstream/gradle). Jedes Unterverzeichnis mit einer eigenen
 * `build.gradle.kts` wird automatisch als eigenständiges Plugin-Modul
 * gebaut (siehe settings.gradle.kts).
 */
buildscript {
    repositories {
        google()
        mavenCentral()
        // JitPack liefert das CloudStream-Gradle-Plugin und die API-Stubs.
        maven("https://jitpack.io")
    }

    dependencies {
        classpath("com.android.tools.build:gradle:8.7.3")
        // CloudStream-Gradle-Plugin: baut aus jedem Modul ein .cs3-Plugin.
        // Hinweis: Das Plugin ist nur als JitPack-SNAPSHOT verfügbar; ältere
        // Commit-Builds stellen die `cloudstream`-Configuration nicht bereit.
        // Gepinnt sind deshalb die Laufzeit-Abhängigkeiten in `dependencies`.
        classpath("com.github.recloudstream:gradle:-SNAPSHOT")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.0")
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

fun Project.cloudstream(configuration: CloudstreamExtension.() -> Unit) =
    extensions.getByName<CloudstreamExtension>("cloudstream").configuration()

fun Project.android(configuration: BaseExtension.() -> Unit) =
    extensions.getByName<BaseExtension>("android").configuration()

subprojects {
    apply(plugin = "com.android.library")
    apply(plugin = "kotlin-android")
    apply(plugin = "com.lagradost.cloudstream3.gradle")

    cloudstream {
        // Repo-Slug, unter dem die Builds veröffentlicht werden.
        setRepo(System.getenv("GITHUB_REPOSITORY") ?: "iceberggxyz/cs3-german-repo")
    }

    android {
        namespace = "com.icebergg.cs3"

        defaultConfig {
            minSdk = 21
            compileSdkVersion(35)
            targetSdk = 35
        }

        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_1_8
            targetCompatibility = JavaVersion.VERSION_1_8
        }

        tasks.withType<KotlinJvmCompile> {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_1_8) // Required
                freeCompilerArgs.addAll(
                    "-Xno-call-assertions",
                    "-Xno-param-assertions",
                    "-Xno-receiver-assertions"
                )
            }
        }
    }

    dependencies {
        val cloudstream by configurations
        val implementation by configurations

        // Stubs für alle CloudStream-Klassen (werden NICHT in das Plugin gebündelt).
        cloudstream("com.lagradost:cloudstream3:pre-release")

        // Diese Abhängigkeiten sind optional; es dürfen beliebige der von der
        // App bereitgestellten Bibliotheken verwendet werden:
        // https://github.com/recloudstream/cloudstream/blob/master/app/build.gradle.kts
        implementation(kotlin("stdlib"))
        implementation("com.github.Blatzar:NiceHttp:0.4.13") // HTTP
        implementation("org.jsoup:jsoup:1.21.2") // HTML-Parser
        // Jackson NICHT über 2.13.1 anheben (bricht Kompatibilität auf alten Geräten).
        implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.13.1") // JSON
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
    }
}

task<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
