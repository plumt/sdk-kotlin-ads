package net.plumt.ads.sample

import android.app.Activity
import android.os.Bundle
import net.plumt.ads.AdsClient

/** release 빌드에 SDK 코드가 실제로 남도록 클라이언트를 만들고 동의·초기화를 요청하는 최소 화면. 확인용이라 실행하지 않는다. */
class MainActivity : Activity() {
    private lateinit var ads: AdsClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ads = AdsClient.create(this)
        ads.gatherConsentOnFirstResume(application) { }
    }
}
