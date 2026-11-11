# 프로젝트 작업 규칙

## 역할 분담
- 메인 대화는 감독 역할만 한다. 계획, 진행 상황 요약, 내 결정이 필요한 사항만 보고한다.
- 코드 작성·수정은 coder, 빌드·실행·테스트는 tester, 오류 원인 분석은 debugger 서브에이전트에게 맡긴다.
- 기본 흐름: coder 구현 → tester 검증 → 실패 시 debugger 분석 → coder 수정 → tester 재검증.
- 메인 대화에 코드 전체나 긴 명령 출력을 붙이지 않는다. 필요한 경우 파일 경로만 알려준다.

## 기록
- 한 단계가 끝나 tester 검증을 통과하면 git 커밋한다. 커밋 메시지는 한국어로 간단히.
- 진행 중인 계획은 TODO.md 에 체크리스트로 관리한다.

## 프로젝트 정보 (시작할 때 채우기)
- 프로젝트: 나의 물때표 (갤럭시 노트10용 안드로이드 앱 + 홈 화면 위젯)
- 언어/프레임워크: Kotlin, Jetpack Compose, Glance(위젯), WorkManager. 패키지 com.mulddae.app
- 데이터: 공공데이터포털 국립해양조사원 조석예보(고저조) API. 인증키는 local.properties의 TIDE_API_KEY (커밋 금지)
- JDK: JDK 25만 설치돼 있어 Gradle이 안 돌아간다. 반드시 JAVA_HOME=C:\Users\cinqf\.jdks\jdk-17.0.21+9 로 실행한다.
- 빌드 명령: JAVA_HOME 지정 후 `./gradlew assembleDebug` (Git Bash). 한글 경로라 gradle.properties에 android.overridePathCheck=true 필요.
- 실행 명령: 에뮬레이터 M-Note_10 (`%LOCALAPPDATA%\Android\Sdk\emulator\emulator -avd M-Note_10`) 부팅 후
  `adb install -r app/build/outputs/apk/debug/app-debug.apk` → `adb shell am start -n com.mulddae.app/.MainActivity`
  (adb/emulator 호출은 멈출 수 있으니 timeout을 붙인다)
- 테스트 명령: 별도 단위 테스트 없음. `./gradlew assembleDebug` 성공 + 에뮬레이터 실행/스크린샷으로 확인.
- API 검증 결과(2026-11-11 실호출): URL https://apis.data.go.kr/1192136/tideFcstHghLw/GetTideFcstHghLwApiService, 파라미터 serviceKey/obsCode/reqDate(YYYYMMDD, 하루치)/type=json.
  필드 obsvtrNm, lot, lat, predcDt("yyyy-MM-dd HH:mm"), predcTdlvVl(cm, 숫자), extrSe(문자열 1·3=고조, 2·4=저조). 응답이 시간순이 아니라 정렬 필요.
  조회 가능 범위는 약 1년 뒤까지(그 이후 NODATA_ERROR, HTTP 200). 하루 호출 한도 10,000건. 관측소 166곳(코드표는 가이드 hwpx).
- 미확인 사항: extrSe 1/3, 2/4가 오전·오후 구분인지(첫째·둘째 고저조인지), 조위 단위 cm 확정, 일부 SO_ 코드(예: SO_0001)는 기점 관측소라 앱에서 노출할지 결정 필요.

## 환경
- Windows 환경이다. 경로와 명령은 Windows에서 동작하도록 작성한다.
