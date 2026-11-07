import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.BuiltArtifactsLoader
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// 발행하지 않는 확인용 앱. 소비 앱과 같은 조건(R8 전체 모드 + 리소스 축소, 앱 규칙은 기본 파일뿐)으로 release 를 만들어
// ads 의 consumer-rules.pro 만으로 광고 SDK 가 끌어오는 라이브러리의 리플렉션 대상이 살아남는지 본다. 기기 없이 dex 만 읽는다
android {
    namespace = "net.plumt.ads.sample"
    compileSdk = 36

    defaultConfig {
        applicationId = "net.plumt.ads.sample"
        // 광고 SDK 24 이상은 minSdk 24 를 요구한다 — 최신 버전 강제 해석(-PgmaCompatVersion)일 때만 올린다
        minSdk = if (providers.gradleProperty("gmaCompatVersion").isPresent) 24 else 23
        targetSdk = 36
        versionCode = 1
        versionName = "1"
        // Google 이 공개한 시험 앱 ID. 확인용 앱은 실행하지 않지만 광고 SDK 가 요구하는 meta-data 를 소비 앱처럼 채운다
        manifestPlaceholders["admobAppId"] = "ca-app-pub-3940256099942544~3347511713"
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            // 확인용이라 debug 키로 서명한다(CI 는 AGP 가 debug 키를 만든다)
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

dependencies {
    implementation(project(":ads"))
}

// ads 모듈과 같은 강제 해석 — CI 가 최신 광고 SDK 로도 release dex 를 확인한다
providers.gradleProperty("gmaCompatVersion").orNull?.let { version ->
    configurations.configureEach {
        resolutionStrategy.force("com.google.android.gms:play-services-ads:$version")
    }
}

/**
 * release APK 의 dex 를 dexdump 로 열어, 런타임에 이름으로 만들어지는 클래스의 생성자가 R8 뒤에도 남았는지 확인한다.
 * 광고 SDK → WorkManager → Room 은 `WorkDatabase_Impl`·`InputMerger` 하위 클래스를 리플렉션으로 만든다. 생성자가 지워지면
 * 앱이 켜지자마자(androidx.startup 의 WorkManager 초기화) 죽거나 광고 SDK 의 작업이 조용히 실패한다.
 */
abstract class VerifyR8KeptConstructors : DefaultTask() {
    @get:InputFiles
    abstract val apkDirectory: DirectoryProperty

    @get:Internal
    abstract val loader: Property<BuiltArtifactsLoader>

    @get:InputFile
    abstract val dexdump: RegularFileProperty

    /** 클래스 이름(점 표기) → 남아 있어야 하는 생성자가 있는지 */
    @get:Input
    abstract val requiredConstructors: ListProperty<String>

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun verify() {
        val artifacts = checkNotNull(loader.get().load(apkDirectory.get())) { "APK 목록을 읽지 못했다: ${apkDirectory.get()}" }
        val apk = File(artifacts.elements.single().outputFile)
        val wanted = requiredConstructors.get().associateBy { "L" + it.replace('.', '/') + ";" }
        val found = HashMap<String, Boolean>()
        val work = temporaryDir.apply { deleteRecursively(); mkdirs() }
        ZipFile(apk).use { zip ->
            zip.entries().asSequence().filter { Regex("classes\\d*\\.dex").matches(it.name) }.forEach { entry ->
                val dex = File(work, entry.name)
                zip.getInputStream(entry).use { input -> dex.outputStream().use { input.copyTo(it) } }
                val process = ProcessBuilder(dexdump.get().asFile.absolutePath, "-l", "plain", dex.absolutePath)
                    .redirectErrorStream(false).start()
                var current: String? = null
                var direct = false
                process.inputStream.bufferedReader().forEachLine { line ->
                    val t = line.trim()
                    when {
                        t.startsWith("Class descriptor") -> {
                            current = t.substringAfter("'").substringBefore("'").takeIf { it in wanted }
                            current?.let { found.putIfAbsent(it, false) }
                            direct = false
                        }
                        current == null -> Unit
                        t.startsWith("Direct methods") -> direct = true
                        t.startsWith("Virtual methods") -> direct = false
                        direct && t.startsWith("name") && t.endsWith("'<init>'") -> found[current!!] = true
                    }
                }
                check(process.waitFor() == 0) { "dexdump 실패: ${entry.name}" }
            }
        }
        val lines = wanted.map { (descriptor, name) ->
            val state = when (found[descriptor]) {
                true -> "OK"
                false -> "생성자 없음"
                null -> "클래스 없음"
            }
            "$name: $state"
        }
        report.get().asFile.writeText("apk=${apk.name}\n" + lines.joinToString("\n") + "\n")
        val problems = lines.filterNot { it.endsWith(": OK") }
        check(problems.isEmpty()) {
            "R8 이 리플렉션으로 만드는 클래스의 생성자를 지웠다(ads/consumer-rules.pro 확인):\n" + problems.joinToString("\n")
        }
    }
}

androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        val sdk = androidComponents.sdkComponents.sdkDirectory
        val verify = tasks.register<VerifyR8KeptConstructors>("verifyReleaseKeptConstructors") {
            apkDirectory.set(variant.artifacts.get(SingleArtifact.APK))
            loader.set(variant.artifacts.getBuiltArtifactsLoader())
            dexdump.set(sdk.map { it.file("build-tools/${android.buildToolsVersion}/dexdump") })
            requiredConstructors.set(
                listOf(
                    // Room.getGeneratedImplementation 이 newInstance 로 만든다 — 없으면 시작 즉시 크래시
                    "androidx.work.impl.WorkDatabase_Impl",
                    // InputMerger.fromClassName 이 newInstance 로 만든다 — 없으면 광고 SDK 의 WorkManager 작업이 실패한다
                    "androidx.work.OverwritingInputMerger",
                    "androidx.work.ArrayCreatingInputMerger",
                ),
            )
            report.set(layout.buildDirectory.file("reports/r8-kept-constructors/release.txt"))
        }
        tasks.named("check") { dependsOn(verify) }
    }
}
