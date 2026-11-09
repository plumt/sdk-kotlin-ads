package net.plumt.ads.mobileads

import android.os.Handler
import android.os.Looper

/**
 * 광고 SDK 콜백을 메인 스레드로 옮긴다. GMA Next-Gen SDK 는 불러오기·사건 콜백을 백그라운드 스레드에서 부르지만,
 * [net.plumt.ads.network] 의 콜백 계약은 메인 스레드다. 이미 메인 스레드면 곧바로 실행한다(순서를 바꾸지 않는다).
 */
internal fun onMainThread(block: () -> Unit) {
    val main = Looper.getMainLooper()
    if (Looper.myLooper() == main) block() else Handler(main).post(block)
}
