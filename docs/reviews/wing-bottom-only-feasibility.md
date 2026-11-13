# 상단 패널을 제거한 LG Wing: 하단 단독 휴대전화 사용 검토

검토일: 2026-11-13. 소스 정적 검토이며 기기 동작 검증 결과가 아니다.

## 요구 조건과 결론

대상은 상단 화면이 물리적으로 제거되고 하단 화면만 장착된 LG Wing이다. 목표는 앱 실행뿐 아니라 재부팅 후 첫 잠금 해제, 홈·최근 앱, 원래의 알림창·빠른 설정, 전화 수신, 키보드, 시스템 권한 창까지 하단에서 사용하는 것이다.

**우선 검증할 방향은 하단 물리 패널을 Android의 기본 논리 디스플레이(display 0)에 연결하는 것이다.** Android에는 물리 패널과 논리 디스플레이의 대응을 바꾸는 구조가 있다. 따라서 구조적으로 검토할 근거는 있다. 다만 해당 LG 펌웨어의 구현, 디스플레이 등록 상태와 터치 연결을 확인하기 전에는 이 개조 기기에서의 성공을 확정할 수 없다.

미러링이나 원래 알림창의 하단 이식도 후보지만, 현재 브랜치만으로 하단 단독 휴대전화 사용이 완성됐다고 판단할 수 없다. 실제 기기 Android 버전·ROM은 아직 미확인이다. 아래 AOSP 비교 기준은 실험 코드가 명시한 Android 13이다.

## 검토 기준

- 검토 시작 시 PR #1~#5는 모두 병합됐으며, 열린 PR은 없었다.
- 최신 `origin/master`: `4b4f0fb` (v1.6.1 PR #5 병합).
- 관련 미병합 브랜치 `origin/feat/top-mirror`: `0e1464e`. 최신 master 이후 7개 커밋이다.
- 위 커밋의 브랜치 소스를 직접 읽고 AOSP Android 13과 비교했다. 실기기 설정 변경은 실행하지 않았다.

## 방법 비교

| 방법 | 원래 알림창 재사용 | 하단 단독 사용 적합성 | 남은 핵심 확인 |
| --- | --- | --- | --- |
| 하단 물리 패널을 display 0에 연결 | 기본 화면에 붙은 SystemUI가 하단에 표시될 가능성 | 우선 검증 | LG 패널·터치·전원 정책, 초기 부팅과 잠금, 스위블 분기 |
| 메인 논리 화면 전체를 하단에 미러링 | 미러에 SystemUI가 포함되고 입력이 작동하면 가능 | 차선책 | 상단 제거 후에도 원본 화면이 존재하고 렌더링되는지, 잠금·입력·절전 |
| 원래 SystemUI 알림창을 하단에 직접 배치 | 가능성을 검증할 가치가 있음 | 알림창 해결만으로는 불충분 | 알림창과 잠금 화면의 결합, display context·window token·입력·밝기 |
| 기존 자체 알림 패널 유지 | 자체 구현 | 현재 기능의 대안 | 원래 알림창·빠른 설정의 모든 동작을 별도로 구현해야 함 |

상단 패널 제거와 Android 내부의 display 0 소멸은 같은 의미가 아니다. 하드웨어 서비스가 제거된 패널을 계속 등록해 둘 수도 있다. 미러링 판단에 필요한 것은 실제 등록·렌더링 상태다.

## 현재 실험 코드에서 확인된 장애물

코드 위치는 모두 `feat/top-mirror`의 검토 시점 커밋 기준이다.

### 1. DisplaySwap의 반사 호출이 AOSP Android 13 API와 다르다

`DisplaySwap.java:120–123`은 `Layout.createDisplayLocked(address, boolean, boolean, int)`를 호출한다. AOSP Android 13의 해당 메서드는 `address, isDefault, isEnabled`의 **3개 인자**를 받는다. 추가 정수 인자로 논리 ID 4를 지정하는 API가 아니다.

LG가 별도 overload를 추가했는지는 펌웨어에서 확인해야 한다. 그런 overload가 없다면 첫 레이아웃 생성에서 실패하므로, 이 코드는 display swap 성공의 증거가 될 수 없다. AOSP는 비기본 디스플레이 ID를 별도 할당하므로 단순히 인자를 하나 지우는 것만으로 기존 ID 4 보존까지 해결되는 것도 아니다.

근거: [브랜치 DisplaySwap](https://github.com/jshsakura/GoDaijishou/blob/0e1464e/app/src/main/java/kr/opencourse/godaijishou/DisplaySwap.java#L120), [AOSP Android 13 Layout](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-13.0.0_r1/services/core/java/com/android/server/display/layout/Layout.java).

### 2. 기존 패널 끄기 로직과 display swap이 충돌할 수 있다

`TopPanel.java:29–53`은 논리 display 0 뒤의 물리 패널을 찾아 전원을 끈다. `SystemSettings.java:173–192`는 설정·디스플레이 변경 때 이 로직을 실행한다.

하단을 display 0으로 바꾼 뒤 기존 모드가 패널 끄기를 요구하면 **하단 패널을 끄는 경로**가 된다. 패널 정체성과 논리 디스플레이 역할을 분리하거나, 기본 화면 전환 모드에서는 이 로직을 비활성화해야 한다.

근거: [TopPanel](https://github.com/jshsakura/GoDaijishou/blob/0e1464e/app/src/main/java/kr/opencourse/godaijishou/TopPanel.java#L29), [SystemSettings](https://github.com/jshsakura/GoDaijishou/blob/0e1464e/app/src/main/java/kr/opencourse/godaijishou/SystemSettings.java#L173).

### 3. 상단과 하단이 둘 다 등록돼 있다는 전제가 있다

`DisplaySwap.java:135–138`은 display 0과 display 4의 물리 주소를 모두 읽어야 진행한다. 상단 제거 후 기존 상단 장치가 등록되지 않는 구성이라면 이 조건에서 중단된다.

또한 모듈 전반에 `SECOND_SCREEN_DISPLAY_ID = 4` 가정이 있다. 기본 화면 전환 후에는 기존 런처 리다이렉트·볼륨·스크린샷·회전·알림 처리가 어떤 패널을 가리키는지 다시 정해야 한다.

근거: [DisplaySwap 주소 확인](https://github.com/jshsakura/GoDaijishou/blob/0e1464e/app/src/main/java/kr/opencourse/godaijishou/DisplaySwap.java#L135), [MainModule](https://github.com/jshsakura/GoDaijishou/blob/0e1464e/app/src/main/java/kr/opencourse/godaijishou/MainModule.java#L29).

### 4. DisplaySwap은 영구 모드가 아니라 짧은 실험이다

`watch()`는 부팅 때 테스트 설정을 0으로 만들고, 30초 경과 또는 화면 OFF 때 원래 대응으로 돌아가게 한다. 따라서 현재 설계로는 잠금·깨우기·재부팅을 통과하는 고정 모드가 아니다.

복원도 원래 `DeviceStateToLayoutMap`을 되돌리는 방식이 아니다. 새로 만든 반대 레이아웃을 `override`에 남겨 두므로, 테스트가 끝났다고 LG의 원래 상태별 정책이 완전히 복원됐다고 볼 수 없다. 실제 구현에서는 원본 레이아웃과 정책을 보존하고 복구 결과를 확인해야 한다.

근거: [DisplaySwap watch](https://github.com/jshsakura/GoDaijishou/blob/0e1464e/app/src/main/java/kr/opencourse/godaijishou/DisplaySwap.java#L67), [set](https://github.com/jshsakura/GoDaijishou/blob/0e1464e/app/src/main/java/kr/opencourse/godaijishou/DisplaySwap.java#L108).

### 5. 현재 미러링은 잠금 화면을 다루지 않는다

`TopMirror.java:81–86`은 잠겨 있으면 미러를 숨긴다. 하단의 LG 자체 잠금 화면으로 잠금 해제한 뒤에만 메인 화면을 보여주는 구조다. 따라서 이 방식이 작동하더라도 원래 메인 잠금 화면까지 전부 대체했다는 의미는 아니다.

`show()`는 display 0과 4가 둘 다 있어야 실행된다. 스위블이 닫힌 것으로 판단되면 `park()`는 미러 입력을 중단하고 메인 해상도를 복원한다. 상단이 없는 고정 개조 기기에는 스위블 상태에 따른 복귀 정책도 별도로 정의해야 한다.

근거: [TopMirror 잠금 처리](https://github.com/jshsakura/GoDaijishou/blob/0e1464e/app/src/main/java/kr/opencourse/godaijishou/TopMirror.java#L71), [표시 조건](https://github.com/jshsakura/GoDaijishou/blob/0e1464e/app/src/main/java/kr/opencourse/godaijishou/TopMirror.java#L137), [park](https://github.com/jshsakura/GoDaijishou/blob/0e1464e/app/src/main/java/kr/opencourse/godaijishou/TopMirror.java#L278).

## 원래 알림창만 가져오는 경우

Android 13 AOSP의 알림창은 `NotificationShadeWindowControllerImpl.attach()`에서 실제 SystemUI View를 `TYPE_NOTIFICATION_SHADE` 창으로 추가한다. 같은 컨트롤러가 keyguard·bouncer·빠른 설정·알림 답장 입력 상태도 관리한다. 따라서 알림 목록 View 하나만 이동시키는 문제로 볼 수 없다.

`expandNotificationsPanel` 계열 호출은 기존 알림창을 펼치는 요청이며, 그것만으로 창의 대상 디스플레이가 변경되는 것은 아니다. 하단에서 보이게 하려면 실제 창 배치 또는 미러와 입력 전달이 추가로 필요하다.

기술적으로 이식을 무조건 불가능하다고 결론 내릴 근거도 없다. AOSP Android 13의 DisplayPolicy는 알림창 추가 시 권한과 해당 정책의 중복 창을 검사한다. 실제 LG SystemUI의 생성 경로, display context와 관련 창을 함께 확인해야 한다. 보조 화면 system decor 옵션만 켠다고 완전한 알림창이 자동 생성된다는 근거는 없다.

근거: [알림창 컨트롤러](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-13.0.0_r1/packages/SystemUI/src/com/android/systemui/statusbar/phone/NotificationShadeWindowControllerImpl.java), [CentralSurfacesImpl](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-13.0.0_r1/packages/SystemUI/src/com/android/systemui/statusbar/phone/CentralSurfacesImpl.java), [DisplayPolicy](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-13.0.0_r1/services/core/java/com/android/server/wm/DisplayPolicy.java), [AOSP 다중 디스플레이 설명](https://source.android.com/docs/core/display/multi_display/system-decorations).

## 다음 검증 순서

1. 실제 Android 버전·ROM과 현재 등록된 물리/논리 디스플레이, 터치 viewport, 포커스 화면을 확인한다. 상단 장치가 남아 있는지부터 판별한다.
2. 해당 LG 펌웨어의 `services.jar`와 SystemUI 구현에서 Layout/LogicalDisplayMapper 및 스위블 분기를 확인한다. AOSP와의 차이를 먼저 확정한다.
3. 패널 끄기·미러링·기존 하단 리다이렉트를 함께 동작시키지 않는 독립 실험으로 하단→display 0 대응을 검증한다. 실제 물리 주소로 하단을 식별하고 터치·회전·밀도·밝기도 함께 확인한다.
4. 성공하면 초기 부팅과 화면 잠금/깨우기, SystemUI 재시작, 재부팅에서도 유지되는 모드로 설계한다. Android 프레임워크 밖의 부트로더·복구 화면 출력은 별도 과제다.
5. 전환이 펌웨어에서 막힐 때 메인 화면 렌더링의 생존 여부를 바탕으로 미러링 또는 원래 SystemUI 창의 하단 배치를 선택한다.

실기기에서 필요한 1차 정보는 아래처럼 읽기 명령으로 얻을 수 있다. 이 검토에서는 실행하지 않았다. 전체 덤프는 로컬에서 먼저 검토하고 필요한 디스플레이·입력·창 배치 부분만 공유한다.

```sh
adb shell getprop ro.build.version.release
adb shell getprop ro.build.version.sdk
adb shell getprop ro.build.fingerprint
adb shell dumpsys display
adb shell dumpsys input
adb shell dumpsys window displays
```

## 완료 판정 조건

| 상황 | 하단에서 통과해야 할 동작 |
| --- | --- |
| 전원 켜기·재부팅 | 첫 PIN/패턴 입력과 홈 진입, 상단 표시 불필요 |
| 잠금·깨우기 | 전원 버튼으로 정상 진입, 터치와 인증 정상 |
| 상단 가장자리 스와이프 | 원래 알림창·빠른 설정 열기/닫기, 앱 터치와 충돌 없음 |
| 알림 조작 | 알림 클릭, 삭제, 그룹, 버튼, 직접 답장과 키보드 |
| 기본 탐색 | 홈·뒤로·최근 앱, 회전, 전체화면 앱 복귀 |
| 전화·알람 | 잠금·절전 상태에서 표시, 수신·종료·알람 중지, 통화 오디오 |
| 시스템 창 | 권한·생체 인증·USB 디버깅·전원 메뉴가 하단에 표시 |
| 화면 제어 | 밝기 슬라이더가 실제 하단 패널 제어, 꺼짐·켜짐 안정 |
| 앱 호환 | 카메라, 게임, 보호 콘텐츠 사용 앱의 필요한 화면과 입력 |

코드 주석의 호환성 설명이나 일반 APK 빌드 성공은 위 조건 통과의 증거로 사용하지 않는다. 이번 검토에서는 실기기에 연결하지 않았고 기기 덤프도 확보하지 못했다. 따라서 결과는 소스에 근거한 방향 선정 및 기존 코드의 제약 확인까지다.
