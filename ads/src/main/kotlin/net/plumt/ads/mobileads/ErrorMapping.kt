package net.plumt.ads.mobileads

import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import net.plumt.ads.AdErrorReason
import net.plumt.ads.AdsErrorKind
import net.plumt.ads.AdsException

/**
 * 광고 SDK 불러오기 오류 코드(`AdRequest.ERROR_CODE_*`)의 분류. 모르는 코드는 [AdErrorReason.Unknown].
 * SDK 는 오프라인(호스트 이름 조회 실패·연결 거부)을 네트워크 코드가 아니라 내부 오류(0)로 알리기도 한다 —
 * 그때는 [message] 로 [AdErrorReason.Network] 를 가려낸다.
 */
internal fun loadReason(code: Int, message: String? = null): AdErrorReason {
    val byCode = when (code) {
        AdRequest.ERROR_CODE_NO_FILL, AdRequest.ERROR_CODE_MEDIATION_NO_FILL -> AdErrorReason.NoFill
        AdRequest.ERROR_CODE_NETWORK_ERROR -> AdErrorReason.Network
        AdRequest.ERROR_CODE_INVALID_REQUEST, AdRequest.ERROR_CODE_INVALID_AD_STRING -> AdErrorReason.InvalidRequest
        AdRequest.ERROR_CODE_APP_ID_MISSING -> AdErrorReason.AppIdMissing
        AdRequest.ERROR_CODE_REQUEST_ID_MISMATCH -> AdErrorReason.AppIdMismatch
        AdRequest.ERROR_CODE_INTERNAL_ERROR -> AdErrorReason.Internal
        else -> AdErrorReason.Unknown
    }
    val ambiguous = byCode == AdErrorReason.Internal || byCode == AdErrorReason.Unknown
    return if (ambiguous && looksLikeNetworkFailure(message)) AdErrorReason.Network else byCode
}

/** 연결 실패를 뜻하는 SDK·플랫폼 메시지 조각(소문자). 기기 언어와 무관하게 영문으로 온다. */
private val NETWORK_MESSAGE_HINTS = listOf(
    "unable to resolve host",
    "unknownhostexception",
    "no address associated with hostname",
    "failed to connect",
    "connectexception",
    "network is unreachable",
    "sockettimeoutexception",
    "no network",
    "network error",
)

internal fun looksLikeNetworkFailure(message: String?): Boolean {
    if (message.isNullOrBlank()) return false
    val m = message.lowercase()
    return NETWORK_MESSAGE_HINTS.any { it in m }
}

/** 광고 SDK 표시 오류 코드(`FullScreenContentCallback.ERROR_CODE_*`)의 분류. 모르는 코드는 [AdErrorReason.Unknown]. */
internal fun showReason(code: Int): AdErrorReason = when (code) {
    FullScreenContentCallback.ERROR_CODE_AD_REUSED -> AdErrorReason.AlreadyUsed
    FullScreenContentCallback.ERROR_CODE_NOT_READY -> AdErrorReason.Expired
    FullScreenContentCallback.ERROR_CODE_APP_NOT_FOREGROUND -> AdErrorReason.AppInBackground
    FullScreenContentCallback.ERROR_CODE_INTERNAL_ERROR, FullScreenContentCallback.ERROR_CODE_MEDIATION_SHOW_ERROR ->
        AdErrorReason.Internal
    else -> AdErrorReason.Unknown
}

internal fun loadException(code: Int, message: String): AdsException =
    AdsException(AdsErrorKind.Load, code, message, reason = loadReason(code, message))

internal fun showException(code: Int, message: String): AdsException =
    AdsException(AdsErrorKind.Show, code, message, reason = showReason(code))
