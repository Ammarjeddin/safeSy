import javax.inject.Inject
import org.gradle.process.ExecOperations

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "sy.safesy"
    compileSdk = 37

    defaultConfig {
        applicationId = "sy.safesy"
        // API 26 (Android 8.0) — the floor for this fleet. Foreground services
        // and background limits behave very differently below this.
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Arabic is the primary locale; English kept for development.
    }

    buildTypes {
        debug { isMinifyEnabled = false }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

    buildFeatures { compose = true }

    // Arabic is the primary locale; English kept for development.
    androidResources { localeFilters += setOf("ar", "en") }

    sourceSets.getByName("main") { java.directories.add("src/main/kotlin") }
    sourceSets.getByName("test") { java.directories.add("src/test/kotlin") }

    testOptions { unitTests { isIncludeAndroidResources = true } }
}

// Protobuf codegen.
//
// Deliberately invoking protoc directly rather than using protobuf-gradle-plugin:
// that plugin does not yet support AGP 9 (it casts to the removed BaseExtension).
// Calling protoc ourselves is a few lines, has no plugin dependency to wait on,
// and keeps the generated sources visible.
//
// javalite: ~10x smaller runtime than full protobuf — matters on 2-3GB devices,
// and we only need serialize/deserialize.
abstract class GenerateProtoTask : DefaultTask() {
    @get:InputDirectory abstract val protoDir: DirectoryProperty
    @get:OutputDirectory abstract val outDir: DirectoryProperty

    // Gradle 9 removed project.exec at execution time; inject ExecOperations.
    @get:Inject abstract val execOps: ExecOperations

    @TaskAction
    fun generate() {
        val out = outDir.get().asFile.also { it.mkdirs() }
        val src = protoDir.get().asFile
        execOps.exec {
            commandLine(
                "protoc",
                "--proto_path=" + src.absolutePath,
                "--java_out=lite:" + out.absolutePath,
                src.absolutePath + "/safesy/v1/telemetry.proto",
            )
        }
    }
}

val generateProto by tasks.registering(GenerateProtoTask::class) {
    protoDir.set(file("../../proto"))
    outDir.set(layout.buildDirectory.dir("generated/source/proto"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.java?.addGeneratedSourceDirectory(generateProto) { it.outDir }
    }
}

dependencies {
    implementation(libs.androidx.core)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.work.runtime)
    implementation(libs.protobuf.javalite)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
}
