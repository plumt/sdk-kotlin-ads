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

    /** 동의 정보를 갱신하고 필요하면 폼을 띄운다. 끝나면 [onDone] (실패 원인 또는 null). */
    fun gather(activity: Activity, onDone: (AdsException?) -> Unit)

    fun showPrivacyOptions(activity: Activity, onDone: (AdsException?) -> Unit)
}

internal class UmpConsentGateway(
    context: Context,
    private val config: AdsConfig,
) : ConsentGateway {

    private val info: ConsentInformation = UserMessagingPlatform.getConsentInformation(context)

    override fun current(): ConsentSnapshot = ConsentSnapshot(
        status = ConsentStatus.fromUmp(info.consentStatus),
        canRequestAds = info.canRequestAds(),
        privacyOptionsRequired =
            info.privacyOptionsRequirementStatus == ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED,
    )

    override fun gather(activity: Activity, onDone: (AdsException?) -> Unit) {
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
    }

    override fun showPrivacyOptions(activity: Activity, onDone: (AdsException?) -> Unit) {
        UserMessagingPlatform.showPrivacyOptionsForm(activity) { error -> onDone(error?.toException()) }
    }

    private fun debugSettings(activity: Activity): ConsentDebugSettings? {
        val geography = when (config.consentDebugGeography) {
            ConsentDebugGeography.Disabled -> return null
            ConsentDebugGeography.Eea -> ConsentDebugSettings.DebugGeography.DEBUG_GEOGRAPHY_EEA
            ConsentDebugGeography.NotEea -> ConsentDebugSettings.DebugGeography.DEBUG_GEOGRAPHY_NOT_EEA
        }
        return ConsentDebugSettings.Builder(activity).apply {
            setDebugGeography(geography)
            config.testDeviceIds.forEach { addTestDeviceHashedId(it) }
        }.build()
    }
}

private fun FormError.toException(): AdsException =
    AdsException(AdsErrorKind.Consent, errorCode, message)
