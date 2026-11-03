package net.plumt.ads

import android.os.SystemClock

/** 시각 공급자. 빈도 제한·만료 판정이 이것으로만 시각을 읽어 테스트에서 바꿀 수 있다. */
public fun interface AdClock {
    /** 단조 증가하는 밀리초. 절대 시각일 필요는 없다. */
    public fun nowMillis(): Long

    public companion object {
        /** 부팅 이후 시간([SystemClock.elapsedRealtime]). 기기 시계를 바꿔도 영향받지 않는다. 프로세스 재시작을 넘어 저장하는 값에는 쓰지 않는다. */
        public val Default: AdClock = AdClock { SystemClock.elapsedRealtime() }
    }
}
