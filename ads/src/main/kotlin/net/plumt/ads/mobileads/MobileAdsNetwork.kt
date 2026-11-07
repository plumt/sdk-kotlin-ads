package net.plumt.ads.mobileads

import android.content.Context
import android.content.pm.PackageManager
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.RequestConfiguration
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import net.plumt.ads.AdContentRating
import net.plumt.ads.AdFlag
import net.plumt.ads.AdsConfig
import net.plumt.ads.AdsErrorKind
import net.plumt.ads.AdsException
import net.plumt.ads.AdsLog
import net.plumt.ads.AdsMode
import net.plumt.ads.TestAdIds
import net.plumt.ads.network.AdNetwork
import net.plumt.ads.network.BannerProvider
import net.plumt.ads.network.FullScreenAdProvider
import net.plumt.ads.network.NativeAdProvider

/**
 * Google Mobile Ads SDK 기본 구현. 비공식 연동이며 Google 과 무관하다.
 * 앱 ID 는 앱 매니페스트의 `com.google.android.gms.ads.APPLICATION_ID` meta-data 로 앱이 제공한다 —
 * 없으면 SDK 가 프로세스 시작 때(초기화 전) 앱을 `IllegalStateException` 으로 멈춘다.
 * 초기화 전에 앱 ID 와 모드를 맞춰 보고, 운영 모드에 시험 앱 ID 면 초기화하지 않고 [AdsErrorKind.Configuration] 으로 실패한다.
 *
 * 요청 설정은 호출 스레드에서 적용하고, `MobileAds.initialize` 는 백그라운드(IO) 스레드에서 부른다(콜드 스타트 ANR 방지).
 */
public class MobileAdsNetwork internal constructor(
    private val sdk: MobileAdsSdk,
    private val ioDispatcher: CoroutineDispatcher,
) : AdNetwork {
    public constructor() : this(RealMobileAdsSdk, Dispatchers.IO)

    @Volatile
    private var initialized = false

    override val interstitial: FullScreenAdProvider = InterstitialProvider
    override val rewarded: FullScreenAdProvider = RewardedProvider
    override val appOpen: FullScreenAdProvider = AppOpenProvider
    override val banner: BannerProvider = MobileAdsBannerProvider
    override val native: NativeAdProvider = MobileAdsNativeProvider

    override suspend fun initialize(context: Context, config: AdsConfig) {
        if (initialized) return
        // 앱 ID 가 모드와 맞지 않으면 SDK 를 부르지 않는다 — 운영 빌드가 시험 앱 ID 로 광고를 요청하지 않게 닫힌 쪽으로 실패한다
        checkAppId(sdk.applicationId(context), config.mode)?.let { throw it }
        try {
            if (config.needsRequestConfiguration()) sdk.applyRequestConfiguration(config)
            val app = context.applicationContext
            withContext(ioDispatcher) {
                suspendCancellableCoroutine { cont ->
                    sdk.initialize(app) { if (cont.isActive) cont.resume(Unit) }
                }
            }
            initialized = true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            throw AdsException(
                AdsErrorKind.Initialization,
                AdsException.CODE_UNKNOWN,
                "광고 SDK 초기화 실패: ${e.javaClass.simpleName}: ${e.message}",
                e,
            )
        }
    }
}

/** 기본값(모두 지정 안 함·시험 기기 없음)이면 SDK 요청 설정을 건드리지 않는다. */
internal fun AdsConfig.needsRequestConfiguration(): Boolean =
    audience.childDirected != AdFlag.Unspecified ||
        audience.underAgeOfConsent != AdFlag.Unspecified ||
        audience.maxContentRating != AdContentRating.Unspecified ||
        testDeviceIds.isNotEmpty()

/** `MobileAds` 정적 호출을 가린 이음새. 초기화 스레드·순서를 SDK 없이 시험한다. */
internal interface MobileAdsSdk {
    /** 매니페스트의 앱 ID. 없으면 null. */
    fun applicationId(context: Context): String? = readManifestAppId(context)
    fun applyRequestConfiguration(config: AdsConfig)
    fun initialize(context: Context, onComplete: () -> Unit)
}

internal object RealMobileAdsSdk : MobileAdsSdk {
    override fun applyRequestConfiguration(config: AdsConfig) {
        val audience = config.audience
        MobileAds.setRequestConfiguration(
            RequestConfiguration.Builder()
                .setTagForChildDirectedTreatment(audience.childDirected.toSdkTag())
                .setTagForUnderAgeOfConsent(audience.underAgeOfConsent.toSdkTag())
                .setMaxAdContentRating(audience.maxContentRating.toSdkRating())
                .setTestDeviceIds(config.testDeviceIds)
                .build()
        )
    }

    override fun initialize(context: Context, onComplete: () -> Unit) {
        MobileAds.initialize(context) { onComplete() }
    }
}

/** 광고 SDK 가 앱 ID 를 읽는 매니페스트 meta-data 이름. */
internal const val APP_ID_META_DATA: String = "com.google.android.gms.ads.APPLICATION_ID"

/** 앱 매니페스트 `<application>` 의 앱 ID meta-data. 없거나 읽지 못하면 null. */
internal fun readManifestAppId(context: Context): String? = try {
    @Suppress("DEPRECATION")
    context.packageManager
        .getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
        .metaData?.getString(APP_ID_META_DATA)
} catch (_: PackageManager.NameNotFoundException) {
    null
}

/**
 * 앱 ID 가 [mode] 와 맞는지. 맞으면 null.
 *
 * - 비었다: 두 모드 모두 실패([AdsException.CODE_APP_ID_MISSING])
 * - 운영 모드에 시험 앱 ID: 실패([AdsException.CODE_TEST_APP_ID_IN_PRODUCTION])
 * - 개발 모드에 운영 앱 ID: 정상(시험 단위 ID 는 어느 앱 ID 로도 시험 광고만 온다). 로그만 남긴다
 */
internal fun checkAppId(appId: String?, mode: AdsMode): AdsException? {
    val id = appId?.trim().orEmpty()
    if (id.isEmpty()) {
        return AdsException(
            AdsErrorKind.Configuration, AdsException.CODE_APP_ID_MISSING,
            "매니페스트에 앱 ID($APP_ID_META_DATA)가 없거나 비었습니다",
        )
    }
    val isTest = TestAdIds.isTestId(id)
    if (mode == AdsMode.Production && isTest) {
        return AdsException(
            AdsErrorKind.Configuration, AdsException.CODE_TEST_APP_ID_IN_PRODUCTION,
            "운영 모드인데 매니페스트 앱 ID 가 시험 앱 ID 입니다: $id — 운영 빌드 변형의 manifestPlaceholders 를 확인하세요",
        )
    }
    if (mode == AdsMode.Development && !isTest) {
        AdsLog.info("개발 모드에서 운영 앱 ID 를 씁니다. 광고 단위는 시험 ID 라 시험 광고만 옵니다")
    }
    return null
}

/** SDK 의 `RequestConfiguration.TAG_FOR_*` 상수: 지정 안 함 -1, 아니오 0, 예 1. */
internal fun AdFlag.toSdkTag(): Int = when (this) {
    AdFlag.Unspecified -> -1
    AdFlag.No -> 0
    AdFlag.Yes -> 1
}

/** SDK 의 `MAX_AD_CONTENT_RATING_*` 문자열. 지정 안 함은 빈 문자열이다. */
internal fun AdContentRating.toSdkRating(): String = when (this) {
    AdContentRating.Unspecified -> ""
    else -> name
}
