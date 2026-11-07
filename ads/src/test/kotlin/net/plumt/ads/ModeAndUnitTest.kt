package net.plumt.ads

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 30, 35])
class ModeAndUnitTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    // ---- 모드 ----

    @Test fun autoFollowsDebuggableFlagAndExplicitModesStay() {
        assertEquals(AdsMode.Development, AdsMode.Auto.resolve(debuggable = true))
        assertEquals(AdsMode.Production, AdsMode.Auto.resolve(debuggable = false))
        for (d in listOf(true, false)) {
            assertEquals(AdsMode.Development, AdsMode.Development.resolve(d))
            assertEquals(AdsMode.Production, AdsMode.Production.resolve(d))
        }
        assertEquals(AdsMode.Production, AdsMode.of(isProduction = true))
        assertEquals(AdsMode.Development, AdsMode.of(isProduction = false))
    }

    @Test fun appDebuggableReadsApplicationFlagNotLibraryBuildConfig() {
        val info = context.applicationInfo
        val original = info.flags
        try {
            info.flags = original or ApplicationInfo.FLAG_DEBUGGABLE
            assertTrue(context.isAppDebuggable())
            info.flags = original and ApplicationInfo.FLAG_DEBUGGABLE.inv()
            assertFalse(context.isAppDebuggable())
        } finally {
            info.flags = original
        }
    }

    @Test fun productionStripsDevOnlySettingsWithWarnings() {
        val cfg = AdsConfig(testDeviceIds = listOf("dev-1"), consentDebugGeography = ConsentDebugGeography.Eea)
        val (prod, warnings) = cfg.effectiveFor(AdsMode.Production)
        assertEquals(AdsMode.Production, prod.mode)
        assertTrue(prod.testDeviceIds.isEmpty())
        assertEquals(ConsentDebugGeography.Disabled, prod.consentDebugGeography)
        assertEquals(setOf(AdsWarning.TestDeviceIdsIgnored, AdsWarning.ConsentDebugGeographyIgnored), warnings)

        val (dev, devWarnings) = cfg.effectiveFor(AdsMode.Development)
        assertEquals(cfg.copy(mode = AdsMode.Development), dev)
        assertTrue(devWarnings.isEmpty())

        assertEquals(setOf(AdsWarning.TestDeviceIdsIgnored), AdsConfig(testDeviceIds = listOf("a")).effectiveFor(AdsMode.Production).second)
        assertTrue(AdsConfig().effectiveFor(AdsMode.Production).second.isEmpty())
        assertThrows(IllegalArgumentException::class.java) { cfg.effectiveFor(AdsMode.Auto) }
    }

    // ---- 시험 ID ----

    @Test fun testIdsAreGooglePublisherAndMapPerFormat() {
        listOf(
            TestAdIds.APP_ID, TestAdIds.BANNER, TestAdIds.INTERSTITIAL, TestAdIds.REWARDED,
            TestAdIds.APP_OPEN, TestAdIds.NATIVE, TestAdIds.NATIVE_VIDEO,
        ).forEach { assertTrue(it, TestAdIds.isTestId(it)) }
        assertTrue(TestAdIds.isTestId("  ${TestAdIds.BANNER}  "))
        assertFalse(TestAdIds.isTestId("ca-app-pub-sample/unit"))
        assertFalse(TestAdIds.isTestId(""))
        assertEquals(TestAdIds.BANNER, TestAdIds.forFormat(AdFormat.Banner))
        assertEquals(TestAdIds.NATIVE, TestAdIds.forFormat(AdFormat.Native))
        assertEquals(TestAdIds.INTERSTITIAL, TestAdIds.forFormat(AdFormat.Interstitial))
        assertEquals(TestAdIds.REWARDED, TestAdIds.forFormat(AdFormat.Rewarded))
        assertEquals(TestAdIds.APP_OPEN, TestAdIds.forFormat(AdFormat.AppOpen))
    }

    // ---- 단위 ----

    private val prodId = "real-unit-1"

    @Test fun factoriesSetFormatAndDefaultDevelopmentId() {
        assertEquals(AdUnit("a", AdFormat.Banner, prodId, TestAdIds.BANNER), AdUnit.banner("a", prodId))
        assertEquals(AdFormat.Native, AdUnit.native("a", prodId).format)
        assertEquals(TestAdIds.INTERSTITIAL, AdUnit.interstitial("a", prodId).development)
        assertEquals(TestAdIds.REWARDED, AdUnit.rewarded("a", prodId).development)
        assertEquals(TestAdIds.APP_OPEN, AdUnit.appOpen("a", prodId).development)
        assertEquals("custom", AdUnit.banner("a", prodId, development = "custom").development)
        assertEquals(TestAdIds.NATIVE, AdUnit("n", AdFormat.Native, prodId).development)
    }

    @Test fun constructorRejectsBlankNameOrDevelopmentButAllowsBlankProduction() {
        assertEquals(
            "name 이 비었습니다: format=Banner",
            assertThrows(IllegalArgumentException::class.java) { AdUnit.banner(" ", prodId) }.message,
        )
        assertEquals(
            "development 가 비었습니다: name=a",
            assertThrows(IllegalArgumentException::class.java) { AdUnit.banner("a", prodId, development = "") }.message,
        )
        AdUnit.banner("a", production = "") // 개발만 하는 동안은 허용
    }

    @Test fun developmentAlwaysUsesDevelopmentId() {
        assertEquals(TestAdIds.BANNER, AdUnit.banner("a", "").idFor(AdsMode.Development))
        assertEquals("dev", AdUnit.banner("a", prodId, " dev ").idFor(AdsMode.Development))
        assertNull(AdUnit.banner("a", "").problemFor(AdsMode.Development))
    }

    @Test fun productionUsesTrimmedProductionId() {
        assertEquals(prodId, AdUnit.banner("a", "  $prodId ").idFor(AdsMode.Production))
        assertNull(AdUnit.banner("a", prodId).problemFor(AdsMode.Production))
    }

    @Test fun productionRejectsBlankId() {
        for (blank in listOf("", "   ", "\t")) {
            val e = assertThrows(AdsException::class.java) { AdUnit.rewarded("reward_1", blank).idFor(AdsMode.Production) }
            assertEquals(AdsErrorKind.Configuration, e.kind)
            assertEquals(AdsException.CODE_PRODUCTION_ID_MISSING, e.code)
            assertEquals(AdErrorReason.Misconfigured, e.reason)
            assertTrue(e.message!!, "name=reward_1" in e.message!!)
        }
    }

    @Test fun productionRejectsTestIdOrSameAsDevelopment() {
        val e = AdUnit.banner("a", TestAdIds.BANNER).problemFor(AdsMode.Production)!!
        assertEquals(AdsException.CODE_TEST_ID_IN_PRODUCTION, e.code)
        assertEquals(AdErrorReason.Misconfigured, e.reason)
        // 다른 형식의 시험 ID 도 시험 ID 다
        assertEquals(AdsException.CODE_TEST_ID_IN_PRODUCTION, AdUnit.banner("a", TestAdIds.NATIVE).problemFor(AdsMode.Production)!!.code)
        // 개발 ID 를 운영에 그대로 둔 경우
        assertEquals(
            AdsException.CODE_TEST_ID_IN_PRODUCTION,
            AdUnit.banner("a", "own-dev", development = "own-dev").problemFor(AdsMode.Production)!!.code,
        )
    }

    @Test fun autoModeIsRejectedForResolution() {
        assertEquals(
            "정하지 않은 모드입니다: Auto (AdsClient.mode 를 넘기세요)",
            assertThrows(IllegalArgumentException::class.java) { AdUnit.banner("a", prodId).idFor(AdsMode.Auto) }.message,
        )
    }

    @Test fun requireFormatPassesOrFailsImmediately() {
        val unit = AdUnit.banner("b", prodId)
        assertSame(unit, unit.requireFormat(AdFormat.Banner))
        assertSame(unit, unit.requireFormat(AdFormat.Native, AdFormat.Banner))
        assertEquals(
            "형식이 맞지 않는 광고 단위입니다: name=b, format=Banner (허용: Interstitial)",
            assertThrows(IllegalArgumentException::class.java) { unit.requireFormat(AdFormat.Interstitial) }.message,
        )
    }

    @Test fun unicodeAndLongNamesAreKept() {
        val name = "배너_" + "가".repeat(5_000)
        val e = AdUnit.banner(name, "").problemFor(AdsMode.Production)!!
        assertTrue(e.message!!.contains(name))
    }

    // ---- 등록부 ----

    @Test fun registryFindsByNameAndFormatInOrder() {
        val b1 = AdUnit.banner("banner_1", "p1")
        val b2 = AdUnit.banner("banner_2", "p2")
        val r1 = AdUnit.rewarded("reward_1", "p3")
        val reg = AdUnitRegistry(b1, b2, r1)
        assertEquals(listOf(b1, b2, r1), reg.all)
        assertSame(b2, reg["banner_2"])
        assertSame(r1, reg.find("reward_1"))
        assertNull(reg.find("none"))
        assertEquals(listOf(b1, b2), reg.ofFormat(AdFormat.Banner))
        assertTrue(reg.ofFormat(AdFormat.AppOpen).isEmpty())
        assertEquals(
            "등록되지 않은 광고 단위입니다: x (등록: banner_1, banner_2, reward_1)",
            assertThrows(IllegalArgumentException::class.java) { reg["x"] }.message,
        )
        assertEquals(reg.all, AdUnitRegistry(listOf(b1, b2, r1)).all)
        assertTrue(AdUnitRegistry.Empty.all.isEmpty())
    }

    @Test fun registryRejectsDuplicateNamesEvenAcrossFormats() {
        assertEquals(
            "광고 단위 이름이 겹칩니다: a",
            assertThrows(IllegalArgumentException::class.java) {
                AdUnitRegistry(AdUnit.banner("a", "p"), AdUnit.native("a", "q"))
            }.message,
        )
    }

    @Test fun registryProblemsForProductionOnly() {
        val reg = AdUnitRegistry(AdUnit.banner("ok", "p"), AdUnit.banner("empty", ""), AdUnit.native("test", TestAdIds.NATIVE))
        assertTrue(reg.problemsFor(AdsMode.Development).isEmpty())
        assertEquals(
            listOf(AdsException.CODE_PRODUCTION_ID_MISSING, AdsException.CODE_TEST_ID_IN_PRODUCTION),
            reg.problemsFor(AdsMode.Production).map { it.code },
        )
    }

    // ---- 스위치 ----

    @Test fun neverAndOfSwitches() = runTest {
        assertFalse(AdsEnabledSource.Never.enabled.first())
        assertSame(AdsEnabledSource.Always, AdsEnabledSource.of(true))
        assertSame(AdsEnabledSource.Never, AdsEnabledSource.of(false))
    }

    @Test fun allCombinesAndRequiresSources() = runTest(UnconfinedTestDispatcher()) {
        assertThrows(IllegalArgumentException::class.java) { AdsEnabledSource.all() }
        val single = MutableAdsEnabledSource()
        assertSame(single, AdsEnabledSource.all(single))
        val build = MutableAdsEnabledSource(true)
        val purchase = MutableStateFlow(true)
        val all = AdsEnabledSource.all(build, AdsEnabledSource.from(purchase))
        val seen = mutableListOf<Boolean>()
        val job = launch { all.enabled.toList(seen) }
        purchase.value = false // 구매 직후
        build.set(false)
        purchase.value = true
        build.set(true)
        job.cancel()
        assertEquals(listOf(true, false, true), seen)
    }

    @Test fun allPropagatesFailure() = runTest {
        val broken = AdsEnabledSource.from(flow { throw IllegalStateException("x") })
        val all = AdsEnabledSource.all(AdsEnabledSource.Always, broken)
        assertThrows(IllegalStateException::class.java) { kotlinx.coroutines.runBlocking { all.enabled.first() } }
    }
}
