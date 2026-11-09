package net.plumt.ads.mobileads

import android.content.Context
import android.content.pm.PackageManager
import com.google.android.libraries.ads.mobile.sdk.MobileAds
import com.google.android.libraries.ads.mobile.sdk.common.RequestConfiguration
import com.google.android.libraries.ads.mobile.sdk.initialization.InitializationConfig
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
 * GMA Next-Gen SDK 기본 구현. 비공식 연동이며 Google 과 무관하다.
 * 앱 ID 는 앱 매니페스트의 `com.google.android.gms.ads.APPLICATION_ID` meta-data 로 앱이 제공한다 — 이 구현이 그 값을 읽어
 * 초기화 설정으로 SDK 에 넘기고, 동의(UMP) SDK 도 같은 meta-data 를 읽는다.
 * 초기화 전에 앱 ID 와 모드를 맞춰 보고, 비었거나 운영 모드에 시험 앱 ID 면 초기화하지 않고 [AdsErrorKind.Configuration] 으로 실패한다.
 *
 * 요청 설정(아동·동의 연령·등급·시험 기기)은 초기화 설정에 담아 함께 넘긴다. `MobileAds.initialize` 는 백그라운드(IO)
 * 스레드에서 부른다(SDK 요구 — 메인 스레드에서 부르면 ANR 이 날 수 있다). 광고는 초기화가 끝난 뒤에만 불러온다.
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

    /**
     * 매니페스트 앱 ID 가 [mode] 와 맞지 않으면 그 원인, 맞으면 null. 동의(UMP)보다 먼저 클라이언트가 부른다 —
     * 앱 ID 가 없으면 동의 수집이 먼저 실패해 [initialize] 까지 가지 않으므로, 새 설치에서도 설정 오류가 드러나게 한다.
     */
    internal fun appIdProblem(context: Context, mode: AdsMode): AdsException? = checkAppId(sdk.applicationId(context), mode)

    override suspend fun initialize(context: Context, config: AdsConfig) {
        if (initialized) return
        // 앱 ID 가 모드와 맞지 않으면 SDK 를 부르지 않는다 — 운영 빌드가 시험 앱 ID 로 광고를 요청하지 않게 닫힌 쪽으로 실패한다
        val rawAppId = sdk.applicationId(context)
        checkAppId(rawAppId, config.mode)?.let { throw it }
        val appId = rawAppId!!.trim()
        try {
            val requestConfig = config.takeIf { it.needsRequestConfiguration() }
            val app = context.applicationContext
            withContext(ioDispatcher) {
                suspendCancellableCoroutine { cont ->
                    sdk.initialize(app, appId, requestConfig) { if (cont.isActive) cont.resume(Unit) }
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

    /**
     * [appId] 로 SDK 를 초기화한다. [requestConfig] 가 null 이 아니면 그 요청 설정(아동·동의 연령·등급·시험 기기)을 함께 넘긴다.
     * 어댑터 초기화까지 끝나면 [onComplete] (아무 스레드).
     */
    fun initialize(context: Context, appId: String, requestConfig: AdsConfig?, onComplete: () -> Unit)
}

internal object RealMobileAdsSdk : MobileAdsSdk {
    override fun initialize(context: Context, appId: String, requestConfig: AdsConfig?, onComplete: () -> Unit) {
        val init = InitializationConfig.Builder(appId)
        requestConfig?.let { init.setRequestConfiguration(it.toRequestConfiguration()) }
        MobileAds.initialize(context, init.build()) { onComplete() }
    }
}

private fun AdsConfig.toRequestConfiguration(): RequestConfiguration = RequestConfiguration.Builder()
    .setTagForChildDirectedTreatment(audience.childDirected.toChildDirectedTag())
    .setTagForUnderAgeOfConsent(audience.underAgeOfConsent.toUnderAgeTag())
    .setMaxAdContentRating(audience.maxContentRating.toSdkRating())
    .setTestDeviceIds(testDeviceIds)
    .build()

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

/** SDK 의 `RequestConfiguration.TagForChildDirectedTreatment`. */
internal fun AdFlag.toChildDirectedTag(): RequestConfiguration.TagForChildDirectedTreatment = when (this) {
    AdFlag.Unspecified -> RequestConfiguration.TagForChildDirectedTreatment.TAG_FOR_CHILD_DIRECTED_TREATMENT_UNSPECIFIED
    AdFlag.No -> RequestConfiguration.TagForChildDirectedTreatment.TAG_FOR_CHILD_DIRECTED_TREATMENT_FALSE
    AdFlag.Yes -> RequestConfiguration.TagForChildDirectedTreatment.TAG_FOR_CHILD_DIRECTED_TREATMENT_TRUE
}

/** SDK 의 `RequestConfiguration.TagForUnderAgeOfConsent`. */
internal fun AdFlag.toUnderAgeTag(): RequestConfiguration.TagForUnderAgeOfConsent = when (this) {
    AdFlag.Unspecified -> RequestConfiguration.TagForUnderAgeOfConsent.TAG_FOR_UNDER_AGE_OF_CONSENT_UNSPECIFIED
    AdFlag.No -> RequestConfiguration.TagForUnderAgeOfConsent.TAG_FOR_UNDER_AGE_OF_CONSENT_FALSE
    AdFlag.Yes -> RequestConfiguration.TagForUnderAgeOfConsent.TAG_FOR_UNDER_AGE_OF_CONSENT_TRUE
}

/** SDK 의 `RequestConfiguration.MaxAdContentRating`. */
internal fun AdContentRating.toSdkRating(): RequestConfiguration.MaxAdContentRating = when (this) {
    AdContentRating.Unspecified -> RequestConfiguration.MaxAdContentRating.MAX_AD_CONTENT_RATING_UNSPECIFIED
    AdContentRating.G -> RequestConfiguration.MaxAdContentRating.MAX_AD_CONTENT_RATING_G
    AdContentRating.PG -> RequestConfiguration.MaxAdContentRating.MAX_AD_CONTENT_RATING_PG
    AdContentRating.T -> RequestConfiguration.MaxAdContentRating.MAX_AD_CONTENT_RATING_T
    AdContentRating.MA -> RequestConfiguration.MaxAdContentRating.MAX_AD_CONTENT_RATING_MA
}
