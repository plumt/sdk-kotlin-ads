# 이 라이브러리 자체는 리플렉션·직렬화를 쓰지 않는다. 아래 규칙은 광고 SDK 가 끌어오는 의존을 위한 것이다.
# 앱의 진짜 누락 경고를 가리지 않도록 -dontwarn 은 두지 않는다.

# 광고 SDK(GMA Next-Gen ads-mobile-sdk 1.6.0) → work-runtime 2.7.x → room-runtime 2.2.x.
# Next-Gen SDK 자체의 규칙(Gson·난독화된 내부 클래스)은 그 aar 의 proguard.txt 가 싣는다 — 여기서 더하지 않는다.
# Room 은 생성된 *_Impl 을 이름으로 찾아 기본 생성자로 만든다(Room.getGeneratedImplementation).
# room-runtime 2.2.x 의 규칙 "-keep class * extends androidx.room.RoomDatabase" 는 멤버를 적지 않아
# R8 전체 모드(AGP 8 이상 기본)에서 기본 생성자를 지키지 않는다 → WorkDatabase_Impl 생성자가 지워져
# androidx.startup 의 WorkManager 초기화에서 앱이 켜지자마자 죽는다. room 2.7 이상이 싣는 규칙과 같은 형태로 보강한다(2.5 까지는 2.2 와 같은 멤버 없는 규칙이다).
-keep class * extends androidx.room.RoomDatabase { void <init>(); }

# WorkManager 는 작업마다 InputMerger 를 클래스 이름으로 만든다(InputMerger.fromClassName, 기본값 OverwritingInputMerger).
# work-runtime 의 규칙 "-keep class * extends androidx.work.InputMerger" 도 멤버가 없어 같은 이유로 생성자가 지워지고,
# 그러면 광고 SDK 가 맡긴 작업이 "Could not create Input Merger" 로 실패한다(크래시 없이 조용히).
-keep class * extends androidx.work.InputMerger { public <init>(); }
