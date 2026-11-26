plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

fun gitOutput(vararg args: String, fallback: String): String {
    val command = "git ${args.joinToString(" ")}"
    return try {
        val execOutput = providers.exec {
            commandLine("git", *args)
            isIgnoreExitValue = true
        }
        val exitValue = execOutput.result.get().exitValue
        val output = execOutput.standardOutput.asText.get().trim()
        when {
            exitValue != 0 -> {
                logger.warn("Version derivation: `$command` exited $exitValue; using \"$fallback\".")
                fallback
            }
            output.isEmpty() -> {
                logger.warn("Version derivation: `$command` produced no output; using \"$fallback\".")
                fallback
            }
            else -> output
        }
    } catch (e: Exception) {
        logger.warn("Version derivation: `$command` failed (${e.javaClass.simpleName}: ${e.message}); using \"$fallback\".")
        fallback
    }
}

// Monotonic versionCode as long as main only moves forward; Play rejects an AAB
// whose versionCode is <= the highest already uploaded. CI checks out with
// fetch-depth: 0 and `checkReleaseVersionDerivation` refuses to build a release
// when the clone is shallow or git history cannot be read.
val versionProblems = mutableListOf<String>()

val shallowAnswer: String = gitOutput("rev-parse", "--is-shallow-repository", fallback = "unknown")
val isShallowClone: Boolean = shallowAnswer == "true"
if (shallowAnswer != "true" && shallowAnswer != "false") {
    versionProblems += "whether the clone is shallow could not be determined (git answered " +
        "\"$shallowAnswer\"), so the commit count cannot be trusted as complete"
}

val gitCommitCount: Int = gitOutput("rev-list", "--count", "HEAD", fallback = "").let { count ->
    val parsed = count.toIntOrNull()
    when {
        parsed == null -> {
            if (count.isNotEmpty()) {
                logger.warn("Version derivation: commit count \"$count\" is not a number; using 1.")
            }
            versionProblems += "the commit count could not be read from git, so versionCode fell back to 1"
            1
        }
        isShallowClone -> {
            logger.warn(
                "Version derivation: shallow clone — the commit count ($parsed) is truncated, so " +
                    "this build's versionCode is NOT monotonic and must not be published. " +
                    "Run `git fetch --unshallow` for a publishable build."
            )
            versionProblems += "the clone is shallow, so the commit count ($parsed) is truncated " +
                "and the versionCode is not monotonic (run `git fetch --unshallow`)"
            parsed
        }
        else -> parsed
    }
}
val gitShortSha: String = gitOutput("rev-parse", "--short", "HEAD", fallback = "unknown").also {
    if (it == "unknown") {
        versionProblems += "the commit hash could not be read, so the versionName names no commit"
    }
}

// CI's unit test and lint runs set -PtellyportFixedVersion (via
// ORG_GRADLE_PROJECT_tellyportFixedVersion) so per-commit manifest versions don't
// bust the Gradle build cache. Release packaging refuses to run with it set.
val fixedTestVersion: Boolean = providers.gradleProperty("tellyportFixedVersion").isPresent

val buildBranch: String = (
    providers.environmentVariable("GITHUB_HEAD_REF").orNull?.ifEmpty { null }
        ?: providers.environmentVariable("GITHUB_REF_NAME").orNull?.ifEmpty { null }
        ?: gitOutput("rev-parse", "--abbrev-ref", "HEAD", fallback = "unknown")
    ).replace(Regex("[^A-Za-z0-9._/-]"), "-").take(30)

fun releaseKeystoreEnv(name: String): String? =
    providers.environmentVariable(name).orNull?.takeIf { it.isNotBlank() }

val releaseKeystorePath = releaseKeystoreEnv("RELEASE_KEYSTORE_FILE")
val releaseKeystorePassword = releaseKeystoreEnv("RELEASE_KEYSTORE_PASSWORD")
val releaseKeyAlias = releaseKeystoreEnv("RELEASE_KEY_ALIAS")
val releaseKeyPassword = releaseKeystoreEnv("RELEASE_KEY_PASSWORD")

val anyReleaseKeystoreVarSet = releaseKeystorePath != null || releaseKeystorePassword != null ||
    releaseKeyAlias != null || releaseKeyPassword != null
val releaseSigningConfigured = releaseKeystorePath != null && releaseKeystorePassword != null &&
    releaseKeyAlias != null && releaseKeyPassword != null

val checkReleaseVersionDerivation = tasks.register("checkReleaseVersionDerivation") {
    description = "Fails a release build whose version was not derived from real git history."
    val problems = versionProblems.toList()
    doLast {
        if (problems.isNotEmpty()) {
            throw GradleException(
                "Refusing to build a release with an underived version: " +
                    problems.joinToString("; ") +
                    ". A fallback versionCode is either a rejected Play upload or a phantom " +
                    "downgrade on a tester's device, and a warning is not where anyone would find it."
            )
        }
    }
}
tasks.configureEach {
    if (name.startsWith("pre") && name.endsWith("ReleaseBuild")) {
        dependsOn(checkReleaseVersionDerivation)
    }
}

val checkReleaseNotFixedVersion = tasks.register("checkReleaseNotFixedVersion") {
    description = "Fails a release package built with -PtellyportFixedVersion."
    val fixed = fixedTestVersion
    doLast {
        check(!fixed) {
            "A release build can't be packaged with -PtellyportFixedVersion: its versionCode is a placeholder."
        }
    }
}
tasks.matching {
    it.name == "packageRelease" || it.name == "packageReleaseBundle" || it.name == "bundleRelease"
}.configureEach {
    dependsOn(checkReleaseNotFixedVersion)
}

androidComponents {
    onVariants(selector().withBuildType("debug")) { variant ->
        if (!fixedTestVersion) {
            val name = "$gitCommitCount-debug+$buildBranch@$gitShortSha"
            variant.outputs.forEach { it.versionName.set(name) }
        }
    }
}

android {
    namespace = "tv.tellyport"
    compileSdk = 34

    defaultConfig {
        applicationId = "tv.tellyport"
        minSdk = 28
        targetSdk = 34
        versionCode = if (fixedTestVersion) 1 else gitCommitCount
        versionName = if (fixedTestVersion) "test" else "$gitCommitCount+$gitShortSha"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        create("release") {
            if (anyReleaseKeystoreVarSet && !releaseSigningConfigured) {
                error(
                    "Partial release-keystore configuration. Set all of RELEASE_KEYSTORE_FILE, " +
                        "RELEASE_KEYSTORE_PASSWORD, RELEASE_KEY_ALIAS, RELEASE_KEY_PASSWORD — or none, " +
                        "to build unsigned. With none set, assembleRelease and bundleRelease succeed " +
                        "and emit an unsigned artifact, which Play will reject on upload."
                )
            }

            if (releaseSigningConfigured) {
                val keystore = file(releaseKeystorePath!!)
                check(keystore.exists()) {
                    "RELEASE_KEYSTORE_FILE is set but does not exist: ${keystore.path}"
                }
                storeFile = keystore
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
        aidl = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
            all {
                it.systemProperty(
                    "robolectric.dependency.repo.url",
                    "https://maven-central.storage-download.googleapis.com/maven2"
                )
                it.systemProperty("robolectric.dependency.repo.id", "google-maven-central")
            }
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.tv.material)

    implementation(libs.androidx.datastore)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
