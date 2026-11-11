# TODO

## 방향: GitHub Actions 사전 수집 + 정적 JSON 호스팅 (API 호출은 서버 쪽 하루 1회, 앱은 JSON만 받음)

## 0. 결정/준비 (사용자)
- [ ] 공공데이터포털 조석예보 API 활용신청, 인증키 발급
- [x] GitHub 저장소: 공개로 결정 (인증키는 Secret에만 보관)
- [x] 관측소 범위: 전체 관측소로 결정
- [ ] GitHub 저장소 생성 + git init (사용자 확인 후 진행)

## 참고: API 스펙 (가이드 문서 기준, 실호출 검증 전)
- 호출: https://apis.data.go.kr/1192136/tideFcstHghLw/GetTideFcstHghLwApiService
- 파라미터: serviceKey, obsCode(필수), reqDate(YYYYMMDD, 하루치), type=json, numOfRows(최대 300), pageNo
- 응답: obsvtrNm, lot, lat, predcDt(yyyy-MM-dd HH:mm), predcTdlvVl(cm), extrSe(1오전고 2오전저 3오후고 4오후저)
- 관측소 목록 API 없음 (가이드 코드표 사용), 호출 1회 = 관측소 1곳 × 1일
- 하루 호출 한도: 10,000건 (사용자 확인) → 백필은 며칠에 나눠 받고, 이후엔 매일 N건만
- 관측소 166곳 (DT_ 63, SO_ 100, IE_ 3). 코드·이름만 있고 위경도는 응답에서 얻음
- 보관 범위(확정): 오늘~+365일, 약 60,590호출(백필 약 8일, 하루 8,000건 이하), 이후 매일 166호출. 용량 약 15MB 예상
- 인증키 유효기간 2년 → 2028-11 무렵 갱신 필요 (Actions 실패 알림/README에 만료일 기록)

## 필수: 공공저작물 출처 표시
- [ ] 포털의 이용허락범위/출처 문구 확인 (사용자) → 정확한 문구 확정
- [ ] 앱 정보 화면 또는 하단에 출처 표시 (위젯 표시 필요 여부 확인)
- [ ] 데이터 저장소 README + 각 JSON 메타(source 필드)에 출처 표시
- 초안: "출처: 해양수산부 국립해양조사원, 조석예보(고, 저조) (공공데이터포털, data.go.kr)"

## 1. API 응답 검증 (tester) — 완료
- [x] 실제 응답으로 필드명, 만조·간조 구분 코드, 관측소 코드 확인
- [x] 결과를 CLAUDE.md "미확인 사항"에 반영
- 검증 결과: 키는 변환 없이 그대로 serviceKey에 사용, 조위 단위 cm(추정), predcTdlvVl=숫자, extrSe=문자열
- 응답 시각 순서가 정렬돼 있지 않음 → 클라이언트/수집 스크립트에서 정렬 필요
- 하루 3~4건, extrSe 1·3=고조 2·4=저조
- 조회 가능 범위: 오늘부터 약 1년 뒤까지 (+365일 OK, +730일 NODATA_ERROR)
- NODATA_ERROR는 HTTP 200 + body 없음 → resultCode로 판별
- 헤더 X-RateLimit-Limit 10000 / Remaining 로 잔여 호출수 확인 가능 (한도 도달 시 중단 판단에 사용)

## 2. 수집 스크립트 (coder)
- [ ] 관측소별 N일치 고저조를 받아 JSON으로 저장하는 스크립트 (tools/ 아래)
- [ ] 출력 형식 정의: data/<관측소코드>.json (+ index.json)
- [ ] 로컬 실행 검증 (tester)

## 3. GitHub Actions
- [ ] 워크플로: 주 1회(또는 매일) 스케줄 실행, TIDE_API_KEY는 저장소 Secret
- [ ] 변경 있을 때만 커밋/푸시
- [ ] 정적 호스팅(GitHub Pages 또는 raw URL) 연결, URL 확인

## 4. 앱 수정 (coder → tester)
- [ ] 데이터 소스를 API 직접 호출 → 정적 JSON 다운로드로 교체
- [ ] 로컬 캐시(오프라인 대비), 위젯 WorkManager 갱신 주기 조정
- [ ] 앱에서 TIDE_API_KEY 제거
- [ ] 빌드 + 에뮬레이터 확인
