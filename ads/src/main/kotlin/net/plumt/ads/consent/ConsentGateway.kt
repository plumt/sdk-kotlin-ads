package net.plumt.ads.consent

import android.app.Activity
import android.content.Context
import com.google.android.ump.ConsentDebugSettings
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.FormError
import com.google.android.ump.UserMessagingPlatform
import net.plumt.ads.AdFlag
import net.plumt.ads.AdsConfig
import net.plumt.ads.AdsErrorKind
import net.plumt.ads.AdsException
import net.plumt.ads.ConsentDebugGeography

/** UMP SDK 를 얇게 가린 경계. [DefaultConsentManager] 의 분기를 SDK 없이 시험하는 이음새다. */
internal interface ConsentGateway {
    fun current(): ConsentSnapshot

    /**
     * 동의 정보를 갱신하고 필요하면 폼을 띄운다. 끝나면 [onDone] (실패 원인 또는 null).
     *
     * 갱신을 **요청한 직후**(응답 전) [onUpdateRequested] 를 부른다. UMP 는 이 프로세스에서 갱신을 요청하기 전에는
     * `canRequestAds()` 가 늘 false 이고, 요청한 뒤부터 지난 실행에 저장된 동의를 돌려준다.
     */
    fun gather(activity: Activity, onUpdateRequested: () -> Unit, onDone: (AdsException?) -> Unit)

    fun showPrivacyOptions(activity: Activity, onDone: (AdsException?) -> Unit)
}

internal class UmpConsentGateway(
    context: Context,
    private val config: AdsConfig,
) : ConsentGateway {

    private val info: ConsentInformation = UserMessagingPlatform.getConsentInformation(context)

    override fun current(): ConsentSnapshot = ConsentSnapshot(
        status = consentStatusFromUmp(info.consentStatus),
        canRequestAds = info.canRequestAds(),
        privacyOptionsRequired =
            info.privacyOptionsRequirementStatus == ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED,
    )

    override fun gather(activity: Activity, onUpdateRequested: () -> Unit, onDone: (AdsException?) -> Unit) {
        val params = ConsentRequestParameters.Builder().apply {
            when (config.audience.underAgeOfConsent) {
                AdFlag.Unspecified -> Unit
                AdFlag.Yes -> setTagForUnderAgeOfConsent(true)
                AdFlag.No -> setTagForUnderAgeOfConsent(false)
            }
            debugSettings(activity)?.let { setConsentDebugSettings(it) }
        }.build()
        info.requestConsentInfoUpdate(
            activity,
            params,
            {
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { error -> onDone(error?.toException()) }
            },
            { error -> onDone(error.toException()) },
        )
        // 공식 권장: 갱신 응답을 기다리지 않고 지난 실행의 동의(canRequestAds)로 광고 SDK 초기화를 병행한다
        onUpdateRequested()
    }

    override fun showPrivacyOptions(activity: Activity, onDone: (AdsException?) -> Unit) {
        UserMessagingPlatform.showPrivacyOptionsForm(activity) { error -> onDone(error?.toException()) }
    }

    private fun debugSettings(activity: Activity): ConsentDebugSettings? {
        val geography = config.consentDebugGeography.toUmpGeography() ?: return null
        return ConsentDebugSettings.Builder(activity).apply {
            setDebugGeography(geography)
            config.testDeviceIds.forEach { addTestDeviceHashedId(it) }
        }.build()
    }
}

/**
 * UMP 의 `ConsentDebugSettings.DebugGeography` 정수. [ConsentDebugGeography.Disabled] 는 디버그 설정 자체를 넣지 않아 null.
 * EEA 밖은 UMP 4.x 에서 사라질 `DEBUG_GEOGRAPHY_NOT_EEA` 대신 `DEBUG_GEOGRAPHY_OTHER` 를 쓴다.
 */
internal fun ConsentDebugGeography.toUmpGeography(): Int? = when (this) {
    ConsentDebugGeography.Eea -> ConsentDebugSettings.DebugGeography.DEBUG_GEOGRAPHY_EEA
    ConsentDebugGeography.NotEea -> ConsentDebugSettings.DebugGeography.DEBUG_GEOGRAPHY_OTHER
    ConsentDebugGeography.RegulatedUsState -> ConsentDebugSettings.DebugGeography.DEBUG_GEOGRAPHY_REGULATED_US_STATE
    // Disabled 와, 새로 더한 값을 아직 옮기지 않은 경우 모두 디버그 설정을 넣지 않는다(실제 지역)
    else -> null
}

private fun FormError.toException(): AdsException =
    AdsException(AdsErrorKind.Consent, errorCode, message)
