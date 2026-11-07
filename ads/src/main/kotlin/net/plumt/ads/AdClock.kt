package net.plumt.ads

import android.os.SystemClock

/** 시각 공급자. 빈도 제한·만료 판정이 이것으로만 시각을 읽어 테스트에서 바꿀 수 있다. */
public fun interface AdClock {
    /** 밀리초. [Default] 는 단조 증가, [Wall] 은 기기 시계다. */
    public fun nowMillis(): Long

    public companion object {
        /** 부팅 이후 시간([SystemClock.elapsedRealtime]). 기기 시계를 바꿔도 영향받지 않는다. 프로세스 재시작을 넘어 저장하는 값에는 쓰지 않는다. */
        public val Default: AdClock = AdClock { SystemClock.elapsedRealtime() }

        /**
         * 기기 시계(유닉스 에포크 밀리초, [System.currentTimeMillis]). 프로세스 재시작을 넘어 저장하는 표시 시각에 쓴다.
         * 사용자가 시계를 뒤로 돌릴 수 있으므로, 이 시계를 쓰는 정책은 시각이 뒤로 가면 그 시각부터 다시 센다.
         */
        public val Wall: AdClock = AdClock { System.currentTimeMillis() }
    }
}
