package net.plumt.ads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 값을 하나 더할 때 고치는 곳은 정의 한 곳이다. 그 정의에서 파생되는 문서 표(ERRORS.md·README·CHANGELOG)가
 * 어긋나면 여기서 실패한다.
 */
class DocsConsistencyTest {

    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }
    private val errors = File(root, "ERRORS.md").readText()
    private val readme = File(root, "README.md").readText()
    private val changelog = File(root, "CHANGELOG.md").readText()

    private fun version(module: String): String = File(root, "$module/gradle.properties").readLines()
        .first { it.startsWith("VERSION_NAME=") }.substringAfter('=')

    @Test fun `ERRORS 는 모든 원인 분류와 단계를 적는다`() {
        val missing = AdErrorReason.values.map { "`${it.name}`" }.filterNot { errors.contains(it) } +
            AdsErrorKind.values.map { "`${it.name}`" }.filterNot { errors.contains(it) }
        assertTrue("ERRORS.md 에 없는 값: $missing", missing.isEmpty())
    }

    @Test fun `ERRORS 는 모든 표시 억제 이유와 동의 결과를 적는다`() {
        val gatherResults = ConsentGatherResult::class.java.declaredClasses
            .filter { ConsentGatherResult::class.java.isAssignableFrom(it) && !it.isInterface }
            .map { it.simpleName }
        assertEquals(5, gatherResults.size)
        val missing = SuppressReason.values.map { "Suppressed(${it.name})" }.filterNot { errors.contains(it) } +
            gatherResults.map { "ConsentGatherResult.$it" }.filterNot { errors.contains(it) } +
            AdsWarning.values.map { it.name }.filterNot { errors.contains(it) }
        assertTrue("ERRORS.md 에 없는 신호: $missing", missing.isEmpty())
    }

    @Test fun `README 설치 줄과 빌드 버전이 같다`() {
        for (module in listOf("ads", "ads-compose")) {
            val line = "net.plumt.ads:$module:${version(module)}"
            assertTrue("README 에 설치 줄이 없다: $line", readme.contains(line))
        }
    }

    @Test fun `README 는 API 레벨 표와 알려진 한계를 둔다`() {
        assertTrue(readme.contains("## API 레벨별 동작"))
        assertTrue(readme.contains("## 알려진 한계"))
        assertTrue(readme.contains("| 24 |"))
    }

    @Test fun `CHANGELOG 는 두 모듈의 현재 버전을 적는다`() {
        for (module in listOf("ads", "ads-compose")) {
            val section = changelog.substringAfter("## $module\n").substringBefore("\n## ")
            assertTrue("CHANGELOG 의 $module 에 ${version(module)} 이 없다", section.contains("### [${version(module)}]"))
        }
    }
}
