# 나의 물때표

갤럭시 노트10용 물때표 앱과 홈 화면 위젯. 이 저장소는 앱이 받아 쓰는 조석(고저조) 데이터를 GitHub Actions로 매일 수집해 정적 JSON으로 제공한다.

## 구조
- `app/` 안드로이드 앱 (Kotlin, Jetpack Compose, Glance)
- `tools/fetch_tides.mjs` 수집 스크립트 (Node 18+, 외부 의존성 없음)
- `tools/stations.csv` 관측소 코드,이름 (166곳)
- `data/index.json` 관측소 목록과 수집 범위
- `data/<관측소코드>/<연도>.json` 관측소별·연도별 고저조
- `.github/workflows/update-tides.yml` 매일 KST 03:30 수집·커밋 (수동 실행 가능)

수집 범위는 오늘 ~ +365일이며, 오늘 −7일보다 이전 데이터는 삭제한다. 하루 호출 한도(10,000건) 때문에 처음 채우는 데는 며칠이 걸리고, 이후에는 매일 새 하루치만 추가된다.

## 데이터 형식
`data/DT_0001/2026.json`

```json
{"obsCode":"DT_0001","name":"인천","lat":37.45194,"lot":126.59222,"unit":"cm","source":"...","days":{"2026-11-11":[["05:35",775,"H"],["11:40",165,"L"]]}}
```

항목은 `[시각(HH:mm, KST), 조위(cm), "H"(고조) 또는 "L"(저조)]`이며 하루 안에서 시각순이다. `index.json`은 `{"updated","source","stations":[{"code","name","lat","lot","firstDate","lastDate"}]}` 형태이고, 아직 데이터가 없는 관측소는 lat/lot/firstDate/lastDate가 null이다.

## 정적 URL
- `https://raw.githubusercontent.com/<USER>/<REPO>/main/data/index.json`
- `https://raw.githubusercontent.com/<USER>/<REPO>/main/data/<관측소코드>/<연도>.json`

## 시험 실행 (Windows Git Bash)
```bash
TIDE_API_KEY=발급키 STATIONS=DT_0001 DAYS=3 node tools/fetch_tides.mjs
```
`TIDE_API_KEY`를 생략하면 프로젝트 루트 `local.properties`의 `TIDE_API_KEY` 줄을 읽는다. 그 외 환경변수: `MAX_CALLS`(기본 8000), `STATIONS`(쉼표 구분 코드), `DAYS`(기본 365).

## 데이터 검증
`node tools/validate_data.mjs` (오류가 있으면 종료코드 1, 워크플로에서도 커밋 전에 실행)

## 출처 표시 (공공저작물)
출처: 해양수산부 국립해양조사원, 조석예보(고, 저조) (공공데이터포털, data.go.kr)

본 저장소의 `data/` 아래 데이터는 위 데이터를 가공(형식 변환·정렬)한 것이다.

## 인증키 관리
- 공공데이터포털 인증키는 발급 후 2년간 유효하다. 현재 키는 2028-11 무렵 갱신이 필요하다.
- 갱신: 포털에서 재발급 → GitHub 저장소 Settings > Secrets and variables > Actions의 `TIDE_API_KEY` 값 교체.
- 키가 만료되면 워크플로가 실패하고 GitHub 실패 알림 메일이 온다.
- 키는 Secret과 로컬 `local.properties`(git 무시)에만 둔다. 저장소에 커밋하지 않는다.
