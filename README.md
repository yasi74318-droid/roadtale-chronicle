# 로드테일 크로니클 (RoadTale Chronicle)

> 차량의 주행 데이터와 실제 도로 위의 위치를 읽어, 매일의 출퇴근길을 실제 길 위에서 이어지는 오디오 드라마의 무대로 바꾸고, 주말의 가족 여행을 아이가 목소리로 함께 만들어 가는 인터랙티브 이야기로 바꾸며, 길 안내까지 이야기의 등장인물이 맡아 운전자가 주행 중 화면을 볼 필요가 없는, 차량 안에서만 성립하는 음성 스토리 플랫폼입니다.

현대자동차·기아 **차량용 앱 개발 공모전** 제출 기획서(로드테일 크로니클)의 핵심 엔진을 구현한 프로토타입 저장소입니다. Android SDK 없이 순수 Kotlin/JVM으로 컴파일·실행할 수 있는 코어 엔진과, 목(Mock) SDK 위에서 두 가지 여정 시나리오를 실행하는 데모를 포함합니다.

## 두 가지 모드 — 하나의 스토리 엔진

- **데일리 크로니클 (Daily Chronicle)** — 시즌제 오디오 드라마의 에피소드가 운전자의 실제 통근 경로 위 지점(랜드마크, 다리, 터널 등)에 앵커링됩니다. 차량이 지점을 통과하면 다음 장면이 열리고, 에피소드는 목적지 도착과 동시에 클리프행어에서 끊어져 다음 날의 운전을 기다리게 합니다.
- **패밀리 저니 (Family Journey)** — 주말 장거리 여정에서는 아이가 목소리로 이야기의 갈림길을 고르고, 스토리의 길이가 여정 전체 시간에 맞게 자동 설계되어 도착 직전에 함께 완결됩니다. 정체에는 사이드 에피소드가, 충전 대기에는 충전 시간에 맞춘 완결형 스토리가 삽입됩니다.

여기에 **스토리 내비게이션**(캐릭터 목소리의 길 안내, 경로상 장소 안내와 한 단어 우회, 도착의 연출), **가족 음성 편지**, **이야기 속 휴식 코치**, **여정 컬렉션**, **크리에이터 마켓플레이스**가 같은 엔진 위에 올라갑니다.

## 내 동행자 시스템 — 사용자가 직접 고르는 동행자

아바타, 이야기의 장르, 비주얼 테마, 목소리까지 사용자가 직접 고릅니다.

- **아바타 (출시 4종)**: 올빼미 바미(기본 내레이터), 달토끼, 졸린 고양이, 판다 — 아바타마다 성격·말투·목소리가 한 세트입니다.
- **장르·스타일**: 모험, 힐링, 코미디, 미스터리 등. 여정 동기화 엔진은 그대로, 스토리 레이어만 교체됩니다.
- **비주얼 테마팩**: 서비스가 품질 기준에 맞춰 제작한 큐레이션 테마팩 중에서 선택합니다. 선택의 자유는 사용자에게, 품질의 책임은 서비스에.
- **목소리 커스터마이징**: 음성의 타입(따뜻한 할머니, 젊은 친구, 심야 라디오, 아이 동행자 등)뿐 아니라 톤과 무드까지 선택해 내레이션·길 안내·휴식 제안에 일관되게 적용합니다(TextToSpeech SDK 음성 스타일링).
- **2단계 로드맵**: 인기 애니메이션 캐릭터·셀럽 보이스는 IP 보유사와의 **공식 라이선스·파트너십**으로만 도입하는 라이선스팩(B2B) 확장입니다.

## 운전자 안전 아키텍처 — 이야기는 운전이 바쁠 때 스스로 물러난다

- **완전 잠금 화면**: 주행 중 터치 입력 차단, 표시는 최소 정보만. 조작은 정차 중 또는 동승자만.
- **집중 구간 자동 일시정지**: 복잡한 분기점·출구·유턴, 목적지 근접 구간이 다가오면 이야기가 문장의 경계에서 스스로 멈추고(북마크 저장), 구간 안에서는 캐릭터의 길 안내 음성만 남습니다. 통과하면 한 문장 리캡과 함께 자동 재개합니다.
- **선택은 동승자에게만**: 갈림길 선택·퀴즈는 동승자 전용이며, 집중 구간에서는 예약 자체를 하지 않고 **연기(defer)** 합니다. 운전자에게 허용되는 것은 완전 정지 상태에서의 한 단어 응답("응/아니")뿐입니다.
- **오디오 우선순위 큐**: 길 안내 > 안전 안내 > 이야기. 이야기가 안내를 가리는 일은 구조적으로 발생하지 않습니다.

## 저장소 구조

```
core/src/main/kotlin/com/roadtale/core/
├─ model/StoryModels.kt          # 에피소드·장면·지점, 내 동행자(아바타·장르·테마·목소리)
├─ state/JourneyStateMachine.kt  # 여정 상태 머신 (IDLE … EPISODE_PLAYING,
│                                #  STORY_PAUSED_FOCUS, WAYPOINT_SCENE_SHIFT,
│                                #  CLIFFHANGER, ARRIVED …) — 안전 규칙을 전이 규칙으로 인코딩
├─ sdk/SdkConnectionLayer.kt     # Pleos 스타일 SDK 추상화 (Vehicle / FusedLocation /
│                                #  NaviHelper / Media·TTS·STT 인터페이스)
├─ sdk/mock/MockSdk.kt           # 개발·데모용 목 SDK 구현
├─ scheduler/WaypointAnchorScheduler.kt
│                                # 지점 앵커링 스케줄러: 장면↔지점 매핑, 시간 기반 폴백
│                                #  트리거, 집중 구간 사전 감지, ETA-클리프행어 정렬
├─ variety/VarietyEngine.kt      # 컨텍스트 변주 엔진: 날씨·교통·시간대·EV 배터리 반응
└─ engine/RoadTaleEngine.kt      # 네 컴포넌트를 하나로 묶는 엔진 파사드
demo/src/main/kotlin/com/roadtale/demo/Main.kt
                                 # 시뮬레이션 데모: 비 오는 월요일 출근길 시나리오 +
                                 #  EV 가족 주말 여행 시나리오, 전체 이벤트 로그 출력
DEMO-RUN.md                      # 실제 컴파일·실행 로그(2026-10-31, kotlinc 2.0.21 / JDK 21)
```

## 실행 방법

Android SDK가 필요 없습니다. JDK와 Kotlin 컴파일러만 있으면 됩니다.

```bash
./build-demo.sh        # kotlinc로 컴파일하고 데모를 실행해 demo-output.txt에 로그 저장
# 또는 수동으로:
kotlinc core/src/main/kotlin demo/src/main/kotlin -include-runtime -d build/roadtale-demo.jar
java -jar build/roadtale-demo.jar
```

데모는 두 여정을 끝까지 실행합니다. 두 시나리오 모두 상태 머신이 `JOURNEY_COMPLETE`에 도달하는 것으로 끝나며, 전체 로그는 [DEMO-RUN.md](DEMO-RUN.md)에서 확인할 수 있습니다.

## 로드맵

| 단계 | 내용 |
|---|---|
| 1단계: 코어 엔진 | 여정 상태 머신, Vehicle/Location/NaviHelper 연동, 안전 모드 — **이 저장소가 그 프로토타입** |
| 2단계: 콘텐츠 엔진 | 여정 동기화 스토리 엔진, 지점 앵커링 스케줄러, 컨텍스트 변주 엔진 고도화 |
| 3단계: 모드 완성 | 데일리 크로니클 시즌 1, 패밀리 저니(분기 스토리, 음성 게임, 가이드 라디오), 스토리 내비게이션, 휴식 코치, 음성 편지, EV 충전 모드, 오프라인 팩 |
| 4단계: 플랫폼 | 크리에이터 마켓플레이스 MVP, 컬렉션·배지, QA |
| **2차 심사** | **실제 동작 가능한 앱(APK) 및 시연 준비** — Android(API 28+, Kotlin, MVVM + Coroutine/Flow) |

---

# RoadTale Chronicle — English Summary

**RoadTale Chronicle (로드테일 크로니클)** is an in-vehicle voice story platform for the Hyundai–Kia In-Vehicle App Contest. It reads the car's live driving data and real position on the road, and turns the daily commute into a serialized audio drama staged on the driver's actual route — and the family trip into an interactive story children co-create with their voices. Even turn-by-turn guidance is delivered by the story's characters, so the driver never needs to look at a screen.

This repository is the working prototype of the proposal's core engine, in pure Kotlin/JVM (no Android SDK required):

- **`JourneyStateMachine`** — the journey state machine (`IDLE`, `EPISODE_PLAYING`, `STORY_PAUSED_FOCUS`, `WAYPOINT_SCENE_SHIFT`, `CLIFFHANGER`, `ARRIVED`, …). The driver-safety architecture is encoded as transition rules: inside a focus zone the story is silent, choices are *deferred* (never asked), and a driver reply is honored only at a complete stop.
- **`SdkConnectionLayer`** — clean Pleos-style abstractions over the Vehicle, FusedLocation, NaviHelper and Media/TTS/STT SDKs, plus mock implementations for development.
- **`WaypointAnchorScheduler`** — maps scenes to route waypoints, keeps the story moving with a time-based fallback trigger, looks focus zones ahead for the auto-pause, and scales scene durations so the cliffhanger lands exactly at the destination.
- **`VarietyEngine`** — the context variation layer: the story reacts to weather (rainy-day variant + rain soundscape), traffic (side scenes instead of stretching the plot), night (night version) and EV battery state (a character's "energy gauge", charging interludes).

**Two modes, one engine:** *Daily Chronicle* (commute serial anchored to real waypoints, daily cliffhanger) and *Family Journey* (passenger voice choices, story length fitted to the whole trip). The **"My Companion" (내 동행자)** system lets users choose their avatar (Bami the owl, the Moon Rabbit, the Sleepy Cat, the Panda), genre, curated visual theme pack, and even the voice type/tone/mood — with licensed character/celebrity packs as an official-partnership phase-2 B2B roadmap.

**Run the demo:**

```bash
./build-demo.sh
# or manually:
kotlinc core/src/main/kotlin demo/src/main/kotlin -include-runtime -d build/roadtale-demo.jar
java -jar build/roadtale-demo.jar
```

The demo plays a rainy Monday commute and an EV family weekend trip end-to-end; both finish at `JOURNEY_COMPLETE`. The full verified run log is in [DEMO-RUN.md](DEMO-RUN.md). A production Android app (API 28+, MVVM + Coroutine/Flow) with a working APK is the deliverable for the contest's phase-2 review.
