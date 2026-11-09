package net.plumt.ads

import com.google.android.libraries.ads.mobile.sdk.common.RequestConfiguration
import net.plumt.ads.mobileads.toChildDirectedTag
import net.plumt.ads.mobileads.toSdkRating
import net.plumt.ads.mobileads.toUnderAgeTag
import org.junit.Assert.assertEquals
import org.junit.Test

class MobileAdsMappingTest {
    @Test fun flagTagsMatchSdkConstants() {
        // SDK 열거값의 정수(UNSPECIFIED=-1, FALSE=0, TRUE=1)가 레거시 상수와 같은지까지 본다
        assertEquals(-1, AdFlag.Unspecified.toChildDirectedTag().value)
        assertEquals(0, AdFlag.No.toChildDirectedTag().value)
        assertEquals(1, AdFlag.Yes.toChildDirectedTag().value)
        assertEquals(RequestConfiguration.TagForChildDirectedTreatment.TAG_FOR_CHILD_DIRECTED_TREATMENT_TRUE, AdFlag.Yes.toChildDirectedTag())
        assertEquals(-1, AdFlag.Unspecified.toUnderAgeTag().value)
        assertEquals(0, AdFlag.No.toUnderAgeTag().value)
        assertEquals(1, AdFlag.Yes.toUnderAgeTag().value)
        assertEquals(RequestConfiguration.TagForUnderAgeOfConsent.TAG_FOR_UNDER_AGE_OF_CONSENT_FALSE, AdFlag.No.toUnderAgeTag())
    }

    @Test fun ratingsMatchSdkConstants() {
        assertEquals("", AdContentRating.Unspecified.toSdkRating().value)
        assertEquals("G", AdContentRating.G.toSdkRating().value)
        assertEquals("PG", AdContentRating.PG.toSdkRating().value)
        assertEquals("T", AdContentRating.T.toSdkRating().value)
        assertEquals("MA", AdContentRating.MA.toSdkRating().value)
    }
}
