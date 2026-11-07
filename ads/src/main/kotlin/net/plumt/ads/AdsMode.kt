package net.plumt.ads

import android.content.Context
import android.content.pm.ApplicationInfo

/**
 * 개발·운영 모드. [AdsConfig.mode] 로 고르고, 클라이언트는 [AdsClient.mode] 로 실제로 정한 값([Development]·[Production])을 알린다.
 *
 * | | [Development] | [Production] |
 * |---|---|---|
 * | 광고 단위 ID | [AdUnit.development](생략하면 Google 공개 시험 ID) | [AdUnit.production] |
 * | 시험 기기 ID·동의 디버그 지역 | 적용 | 무시하고 [AdsWarning] 으로 알린다 |
 * | 운영 ID 가 비었거나 시험 ID | 상관없다 | 그 광고를 요청하지 않고 [AdsErrorKind.Configuration] 실패 |
 * | 매니페스트 앱 ID 가 시험 ID | 정상 | SDK 를 초기화하지 않고 [AdsErrorKind.Configuration] 실패 |
 */
public enum class AdsMode {
    /**
     * 앱이 디버그 가능 빌드(`ApplicationInfo.FLAG_DEBUGGABLE`)면 [Development], 아니면 [Production].
     * 라이브러리의 `BuildConfig.DEBUG` 는 앱의 debug/release 와 무관해 쓰지 않는다.
     * 디버그 가능한 운영 변형(예: 운영 서버를 붙인 debug 빌드)에 실제 광고를 내려면 [Production] 을 직접 넘긴다.
     */
    Auto,

    /** 시험 광고만 요청한다. */
    Development,

    /** 실제 광고를 요청한다. 시험 ID·시험 설정이 섞이면 실패로 알린다. */
    Production,
    ;

    public companion object {
        /**
         * 앱 빌드 플래그를 모드로 옮긴다. 예: `AdsMode.of(isProduction = !BuildConfig.DEBUG)`,
         * flavor 를 쓰면 `AdsMode.of(BuildConfig.FLAVOR == "prod")`.
         */
        public fun of(isProduction: Boolean): AdsMode = if (isProduction) Production else Development
    }
}

/** [AdsMode.Auto] 를 앱의 디버그 가능 여부로 정한다. 결과는 [AdsMode.Development]·[AdsMode.Production] 중 하나다. */
internal fun AdsMode.resolve(debuggable: Boolean): AdsMode = when (this) {
    AdsMode.Auto -> if (debuggable) AdsMode.Development else AdsMode.Production
    else -> this
}

/** 앱(라이브러리가 아니라)의 `android:debuggable`. AGP 가 debug 빌드 유형에 켜고 release 에 끈다. */
internal fun Context.isAppDebuggable(): Boolean =
    (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

/** 운영 모드에서 무시한 개발 전용 설정. 앱이 운영 빌드에서 지우면 사라진다. */
public enum class AdsWarning {
    /** [AdsConfig.testDeviceIds] 를 운영 모드라 적용하지 않았다. */
    TestDeviceIdsIgnored,

    /** [AdsConfig.consentDebugGeography] 를 운영 모드라 적용하지 않았다. */
    ConsentDebugGeographyIgnored,
}

/** 운영 모드면 개발 전용 설정을 지운 설정과 무시한 항목을 돌려준다. [AdsConfig.mode] 는 정한 모드로 바꾼다. */
internal fun AdsConfig.effectiveFor(mode: AdsMode): Pair<AdsConfig, Set<AdsWarning>> {
    require(mode != AdsMode.Auto) { "정하지 않은 모드입니다: $mode" }
    if (mode == AdsMode.Development) return copy(mode = mode) to emptySet()
    val warnings = buildSet {
        if (testDeviceIds.isNotEmpty()) add(AdsWarning.TestDeviceIdsIgnored)
        if (consentDebugGeography != ConsentDebugGeography.Disabled) add(AdsWarning.ConsentDebugGeographyIgnored)
    }
    return copy(mode = mode, testDeviceIds = emptyList(), consentDebugGeography = ConsentDebugGeography.Disabled) to warnings
}
