package net.plumt.ads

import net.plumt.ads.mobileads.toSdkRating
import net.plumt.ads.mobileads.toSdkTag
import org.junit.Assert.assertEquals
import org.junit.Test

class MobileAdsMappingTest {
    @Test fun flagTagsMatchSdkConstants() {
        assertEquals(-1, AdFlag.Unspecified.toSdkTag())
        assertEquals(0, AdFlag.No.toSdkTag())
        assertEquals(1, AdFlag.Yes.toSdkTag())
        // SDK 상수와 같은 값인지 확인 (TAG_FOR_*_UNSPECIFIED=-1, FALSE=0, TRUE=1)
        assertEquals(com.google.android.gms.ads.RequestConfiguration.TAG_FOR_CHILD_DIRECTED_TREATMENT_UNSPECIFIED, AdFlag.Unspecified.toSdkTag())
        assertEquals(com.google.android.gms.ads.RequestConfiguration.TAG_FOR_CHILD_DIRECTED_TREATMENT_FALSE, AdFlag.No.toSdkTag())
        assertEquals(com.google.android.gms.ads.RequestConfiguration.TAG_FOR_CHILD_DIRECTED_TREATMENT_TRUE, AdFlag.Yes.toSdkTag())
    }

    @Test fun ratingsMatchSdkConstants() {
        val c = com.google.android.gms.ads.RequestConfiguration::class.java
        assertEquals("", AdContentRating.Unspecified.toSdkRating())
        assertEquals(c.getField("MAX_AD_CONTENT_RATING_G").get(null), AdContentRating.G.toSdkRating())
        assertEquals(c.getField("MAX_AD_CONTENT_RATING_PG").get(null), AdContentRating.PG.toSdkRating())
        assertEquals(c.getField("MAX_AD_CONTENT_RATING_T").get(null), AdContentRating.T.toSdkRating())
        assertEquals(c.getField("MAX_AD_CONTENT_RATING_MA").get(null), AdContentRating.MA.toSdkRating())
    }
}
