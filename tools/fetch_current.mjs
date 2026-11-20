// 조류예보(시계열) 수집 스크립트. Node 18+, 외부 의존성 없음.
// 사용: node tools/fetch_current.mjs   (먼저 node tools/build_current_map.mjs 로 data/current/map.json 생성)
// env: TIDE_API_KEY, MAX_CALLS(기본 1500, 워크플로는 5000 지정), BACKFILL(1이면 30일 백필), ONLY_POINTS(쉼표 구분 조류지점 코드), DAYS_BACK_FILL(백필 일수, 기본 30)
// 저장: data/current/<조류지점코드>.json = {source,name,lat,lot,min:10,start,days:{날짜:{s:[144],d:[144]}},maxCrsp}
// 저장 기간: 어제부터 오늘+6일(총 8일). maxCrsp = 수집 시작 시 최근 30일과 이후 예보에서 본 최대 유속(줄어들지 않음).
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const TOOLS_DIR = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(TOOLS_DIR, '..');
const CUR_DIR = path.join(ROOT, 'data', 'current');
const API_URL = 'https://apis.data.go.kr/1192136/crntFcstTime/GetCrntFcstTimeApiService';
const SOURCE = '출처: 해양수산부 국립해양조사원, 조류예보(시계열) (공공데이터포털)';
const MIN_STEP = 10; // 저장 간격(분)
const SLOTS = 144; // 하루 10분 간격 칸 수
const AHEAD_DAYS = 6; // 오늘+6일까지(어제 포함 총 8일)
const CONCURRENCY = 5;
const MAX_RETRY = 3;
const MIN_REMAINING = 500;
// 16방위 한글 -> 인덱스(0=북, 시계방향)
const DIRS = ['북', '북북동', '북동', '동북동', '동', '동남동', '남동', '남남동', '남', '남남서', '남서', '서남서', '서', '서북서', '북서', '북북서'];

// ---------- 설정 ----------
function readKey() {
  if (process.env.TIDE_API_KEY && process.env.TIDE_API_KEY.trim()) return process.env.TIDE_API_KEY.trim();
  try {
    const lines = fs.readFileSync(path.join(ROOT, 'local.properties'), 'utf8').split(/\r?\n/);
    for (const line of lines) {
      const m = line.match(/^\s*TIDE_API_KEY\s*[=:]\s*(.*?)\s*$/);
      if (m && m[1]) return m[1];
    }
  } catch { /* 파일 없음 */ }
  return '';
}

const KEY = readKey();
// 하루 한도 10,000 중 조석·실측 수집과 나눠 쓴다. 평상시 약 8일 x 95곳 = 760회, 첫 백필은 약 4,000회(따로 크게 지정).
const MAX_CALLS = Number(process.env.MAX_CALLS) > 0 ? Number(process.env.MAX_CALLS) : 1500;
const BACKFILL_ALL = process.env.BACKFILL === '1';
const BACKFILL_DAYS = Number(process.env.DAYS_BACK_FILL) > 0 ? Math.floor(Number(process.env.DAYS_BACK_FILL)) : 30;

function scrub(s) {
  let out = String(s);
  if (KEY) out = out.split(KEY).join('***');
  return out;
}

function fail(msg) {
  console.error('오류: ' + scrub(msg));
  process.exit(1);
}

if (!KEY) fail('TIDE_API_KEY 가 없습니다. 환경변수 또는 local.properties 에 설정하세요.');

// ---------- 날짜 유틸 (KST) ----------
const pad = (n) => String(n).padStart(2, '0');
const fmt = (d) => `${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}-${pad(d.getUTCDate())}`;
const kstNow = new Date(Date.now() + 9 * 3600 * 1000);
const todayUtc = new Date(Date.UTC(kstNow.getUTCFullYear(), kstNow.getUTCMonth(), kstNow.getUTCDate()));
const addDays = (d, n) => new Date(d.getTime() + n * 86400000);
const TODAY = fmt(todayUtc);
const YESTERDAY = fmt(addDays(todayUtc, -1));
const FORECAST_DATES = [];
for (let i = -1; i <= AHEAD_DAYS; i++) FORECAST_DATES.push(fmt(addDays(todayUtc, i)));
const BACKFILL_DATES = [];
for (let i = BACKFILL_DAYS; i >= 1; i--) BACKFILL_DATES.push(fmt(addDays(todayUtc, -i)));

// ---------- 지점 ----------
const mapPath = path.join(CUR_DIR, 'map.json');
if (!fs.existsSync(mapPath)) fail('data/current/map.json 이 없습니다. 먼저 node tools/build_current_map.mjs 를 실행하세요.');
let mapObj;
try {
  mapObj = JSON.parse(fs.readFileSync(mapPath, 'utf8'));
} catch (e) {
  fail(`map.json 파싱 실패 (${e.message})`);
}
// map.json 의 points 에 없는 옛 지점 파일 정리(map.json 은 보존)
{
  const known = new Set(Object.keys(mapObj.points || {}));
  let removed = 0;
  for (const f of fs.readdirSync(CUR_DIR)) {
    if (f === 'map.json' || !f.endsWith('.json')) continue;
    if (!known.has(f.slice(0, -5))) {
      fs.unlinkSync(path.join(CUR_DIR, f));
      removed++;
    }
  }
  console.log(`옛 지점 파일 정리: ${removed}개 삭제`);
}
let targets = Object.entries(mapObj.points || {})
  .map(([code, p]) => ({ code, name: p.name, lat: p.lat, lot: p.lot }))
  .sort((a, b) => (a.code < b.code ? -1 : a.code > b.code ? 1 : 0));
if (targets.length === 0) fail('map.json 에 points 가 없습니다.');
if (process.env.ONLY_POINTS && process.env.ONLY_POINTS.trim()) {
  const want = new Set(process.env.ONLY_POINTS.split(',').map((s) => s.trim()).filter(Boolean));
  const known = new Set(targets.map((p) => p.code));
  for (const c of want) {
    if (!known.has(c)) console.error(`경고: map.json 에 없는 지점 코드 무시: ${c}`);
  }
  targets = targets.filter((p) => want.has(p.code));
  if (targets.length === 0) fail('ONLY_POINTS 에 해당하는 지점이 없습니다.');
}

// ---------- 호출 ----------
let calls = 0;
let stopReason = '';
let fatal = '';
let stopped = false;
let consecutiveFail = 0;

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

class FatalError extends Error {}

function stop(reason) {
  if (!stopped) {
    stopped = true;
    stopReason = reason;
  }
}

// 반환: {status:'ok', items} | {status:'nodata'} | {status:'aborted'} | 예외
async function callApi(code, date, min) {
  const url =
    `${API_URL}?serviceKey=${KEY}&obsCode=${encodeURIComponent(code)}` +
    `&reqDate=${date.replace(/-/g, '')}&min=${min}&type=json&numOfRows=300&pageNo=1`;
  let lastErr = 'unknown';
  for (let attempt = 0; attempt <= MAX_RETRY; attempt++) {
    if (stopped) return { status: 'aborted' };
    if (calls >= MAX_CALLS) {
      stop(`호출 예산(MAX_CALLS=${MAX_CALLS}) 도달`);
      return { status: 'aborted' };
    }
    if (attempt > 0) await sleep(1000 * 2 ** (attempt - 1));
    calls++;
    let res;
    let text;
    try {
      res = await fetch(url, { signal: AbortSignal.timeout(30000) });
      text = await res.text();
    } catch (e) {
      lastErr = `네트워크 오류(${e?.cause?.code || e?.name || 'error'})`;
      continue;
    }
    const remainingHeader = res.headers.get('x-ratelimit-remaining');
    const remaining = remainingHeader !== null && remainingHeader !== '' ? Number(remainingHeader) : null;
    if (remaining !== null && !Number.isNaN(remaining) && remaining < MIN_REMAINING) {
      stop(`잔여 호출 수 부족(X-RateLimit-Remaining=${remaining})`);
    }

    if (res.status === 401 || res.status === 403) {
      throw new FatalError(`인증 오류(HTTP ${res.status}). 인증키를 확인하세요.`);
    }
    if (res.status === 429) {
      lastErr = 'HTTP 429';
      if (remaining !== null && remaining <= 0) throw new FatalError('일일 호출 한도 초과(HTTP 429).');
      continue;
    }
    if (res.status >= 500) {
      lastErr = `HTTP ${res.status}`;
      continue;
    }

    let json = null;
    try {
      json = JSON.parse(text);
    } catch { /* XML 오류 응답일 수 있음 */ }

    if (!json) {
      const code2 = text.match(/<returnReasonCode>\s*(\d+)\s*</)?.[1];
      const msg = text.match(/<returnAuthMsg>\s*([^<]*)</)?.[1] || text.match(/<errMsg>\s*([^<]*)</)?.[1] || '';
      if (code2 === '22') throw new FatalError(`일일 호출 한도 초과(${msg}).`);
      if (code2 && ['20', '30', '31', '32', '33'].includes(code2)) {
        throw new FatalError(`인증 오류(코드 ${code2}, ${msg}). 인증키를 확인하세요.`);
      }
      if (/SERVICE_KEY|LIMITED_NUMBER/.test(text)) {
        throw new FatalError(`인증/한도 오류(${msg || 'SERVICE_KEY 또는 LIMITED_NUMBER'}).`);
      }
      lastErr = `응답 파싱 실패(HTTP ${res.status})`;
      continue;
    }

    const header = json.response?.header ?? json.header ?? {};
    const body = json.response?.body ?? json.body;
    const rc = String(header.resultCode ?? '');
    const rm = String(header.resultMsg ?? '');
    if (rc === '00' || rc === '0') {
      const raw = body?.items?.item;
      const items = Array.isArray(raw) ? raw : raw ? [raw] : [];
      if (items.length === 0) return { status: 'nodata' };
      return { status: 'ok', items };
    }
    if (rc === '03' || /NODATA/.test(rm)) return { status: 'nodata' };
    if (rc === '22') throw new FatalError(`일일 호출 한도 초과(${rc} ${rm}).`);
    if (['20', '30', '31', '32', '33'].includes(rc) || /SERVICE_KEY|SERVICE_ACCESS/.test(rm)) {
      throw new FatalError(`인증 오류(${rc} ${rm}). 인증키를 확인하세요.`);
    }
    lastErr = `API 오류(${rc} ${rm})`;
  }
  throw new Error(lastErr);
}

// ---------- 변환 ----------
const unknownDirs = new Map();

// 하루치 items -> {s,d,max} (144칸이 모두 차야 함). 모자라면 null
function toDay(items, date) {
  const s = new Array(SLOTS).fill(null);
  const d = new Array(SLOTS).fill(null);
  let max = 0;
  for (const it of items) {
    const m = String(it.predcDt || '').match(/^(\d{4}-\d{2}-\d{2})[ T](\d{2}):(\d{2})/);
    if (!m || m[1] !== date) continue;
    const minutes = Number(m[2]) * 60 + Number(m[3]);
    if (minutes % MIN_STEP !== 0) continue;
    const slot = minutes / MIN_STEP;
    if (slot < 0 || slot >= SLOTS) continue;
    const sp = it.crsp === null || it.crsp === undefined || it.crsp === '' ? NaN : Number(it.crsp);
    if (!Number.isFinite(sp)) continue;
    const label = String(it.crdir ?? '').trim();
    let di = DIRS.indexOf(label);
    if (di < 0) {
      di = -1;
      unknownDirs.set(label, (unknownDirs.get(label) || 0) + 1);
    }
    s[slot] = sp;
    d[slot] = di;
    if (sp > max) max = sp;
  }
  if (s.some((v) => v === null)) return null;
  return { s, d, max };
}

// 백필용: 최댓값만
function maxOf(items) {
  let max = 0;
  for (const it of items) {
    const sp = it.crsp === null || it.crsp === undefined || it.crsp === '' ? NaN : Number(it.crsp);
    if (Number.isFinite(sp) && sp > max) max = sp;
  }
  return max;
}

// ---------- 지점별 처리 ----------
const filePath = (code) => path.join(CUR_DIR, `${code}.json`);
let okPoints = 0;
let failedPoints = 0;
let heldPoints = 0; // 백필이 덜 끝나 저장을 보류한 지점
let skippedPoints = 0; // 예산/중단으로 시작도 못 한 지점
let noInfoPoints = 0; // 모든 날짜가 데이터 없음(정상, 기존 파일 유지)
let backfillPoints = 0;
let failedDates = 0;
let nodataDates = 0;
let savedDays = 0;

async function processPoint(pt) {
  const p = filePath(pt.code);
  let old = null;
  let oldText = null;
  if (fs.existsSync(p)) {
    try {
      oldText = fs.readFileSync(p, 'utf8');
      old = JSON.parse(oldText);
      if (!old || typeof old.days !== 'object' || old.days === null) throw new Error('days 없음');
    } catch {
      console.error(`경고: ${pt.code}.json 읽기/파싱 실패, 새로 만듭니다.`);
      old = null;
      oldText = null;
    }
  }
  const doBackfill = BACKFILL_ALL || old === null;
  if (doBackfill) backfillPoints++;

  const days = {};
  if (old) {
    for (const [k, v] of Object.entries(old.days)) if (k >= YESTERDAY) days[k] = v;
  }
  let maxCrsp = old && Number.isFinite(old.maxCrsp) ? old.maxCrsp : 0;
  let gotDays = 0;
  let pointFailed = 0;
  let backfillIncomplete = false;
  let aborted = false;
  let nodataCount = 0;

  for (const date of FORECAST_DATES) {
    try {
      const r = await callApi(pt.code, date, MIN_STEP);
      if (r.status === 'aborted') {
        aborted = true;
        break;
      }
      consecutiveFail = 0;
      if (r.status === 'nodata') {
        nodataDates++; // 연말 이후 등 조회 범위 밖이거나 일시적 빈 응답: 키를 새로 만들지 않고, 기존 날짜 데이터는 지우지 않음
        nodataCount++;
        continue;
      }
      const day = toDay(r.items, date);
      if (!day) {
        pointFailed++;
        failedDates++;
        console.error(`경고: ${pt.code} ${date} 자료가 ${SLOTS}칸을 채우지 못해 건너뜀(${r.items.length}건)`);
        continue;
      }
      days[date] = { s: day.s, d: day.d };
      if (day.max > maxCrsp) maxCrsp = day.max;
      gotDays++;
    } catch (e) {
      if (e instanceof FatalError) {
        fatal = e.message;
        return;
      }
      pointFailed++;
      failedDates++;
      consecutiveFail++;
      console.error(`경고: ${pt.code} ${date} 실패 (${scrub(e.message)})`);
      if (consecutiveFail >= 20) {
        fatal = '연속 호출 실패가 20회를 넘어 중단합니다(네트워크/서비스 장애 의심).';
        return;
      }
    }
  }

  if (!aborted && nodataCount === FORECAST_DATES.length) {
    // 모든 날짜가 데이터 없음(연말 이후 등): 파일은 건드리지 않고 "정보 없음"으로 정상 처리
    noInfoPoints++;
    return;
  }

  if (doBackfill && !aborted) {
    for (const date of BACKFILL_DATES) {
      try {
        const r = await callApi(pt.code, date, 30);
        if (r.status === 'aborted') {
          backfillIncomplete = true;
          break;
        }
        consecutiveFail = 0;
        if (r.status === 'ok') {
          const mx = maxOf(r.items);
          if (mx > maxCrsp) maxCrsp = mx;
        }
      } catch (e) {
        if (e instanceof FatalError) {
          fatal = e.message;
          return;
        }
        backfillIncomplete = true;
        failedDates++;
        consecutiveFail++;
        console.error(`경고: ${pt.code} ${date} 백필 실패 (${scrub(e.message)})`);
        if (consecutiveFail >= 20) {
          fatal = '연속 호출 실패가 20회를 넘어 중단합니다(네트워크/서비스 장애 의심).';
          return;
        }
        break; // 이 지점 백필은 포기(저장 보류 -> 다음 실행에서 다시 백필)
      }
    }
  } else if (doBackfill && aborted) {
    backfillIncomplete = true;
  }

  if (aborted && gotDays === 0) {
    skippedPoints++;
    return;
  }
  if (gotDays === 0 && Object.keys(days).length === 0) {
    failedPoints++;
    return;
  }
  if (gotDays === 0 && pointFailed > 0) {
    failedPoints++; // 새 자료를 하나도 못 받음(기존 파일은 그대로 둠)
    return;
  }
  if (doBackfill && backfillIncomplete) {
    // 백필이 덜 끝난 채로 저장하면 maxCrsp 가 낮게 굳으므로 저장을 보류한다(파일이 없으면 다음 실행에서 다시 백필).
    heldPoints++;
    console.error(`경고: ${pt.code} 백필이 끝나지 않아 저장을 보류합니다.`);
    return;
  }

  const keys = Object.keys(days).sort();
  if (keys.length === 0) {
    failedPoints++;
    return;
  }
  const sortedDays = {};
  for (const k of keys) sortedDays[k] = days[k];
  const text =
    JSON.stringify({
      source: SOURCE,
      name: pt.name,
      lat: pt.lat,
      lot: pt.lot,
      min: MIN_STEP,
      start: keys[0],
      days: sortedDays,
      maxCrsp,
    }) + '\n';
  okPoints++;
  savedDays += keys.length;
  if (pointFailed > 0) console.error(`경고: ${pt.code} 일부 날짜(${pointFailed}일) 실패, 받은 날짜만 저장`);
  if (text !== oldText) {
    fs.mkdirSync(CUR_DIR, { recursive: true });
    fs.writeFileSync(p, text, 'utf8');
  }
}

let cursor = 0;
async function worker() {
  while (!stopped && !fatal) {
    const idx = cursor++;
    if (idx >= targets.length) return;
    await processPoint(targets[idx]);
  }
}

// ---------- 실행 ----------
console.log(
  `대상: 조류 지점 ${targets.length}곳, 날짜 ${FORECAST_DATES[0]}~${FORECAST_DATES[FORECAST_DATES.length - 1]}, ` +
    `백필 ${BACKFILL_ALL ? `전체(${BACKFILL_DAYS}일)` : `파일 없는 지점만(${BACKFILL_DAYS}일)`}, 호출 예산 ${MAX_CALLS}`
);
{
  const perPoint = FORECAST_DATES.length + BACKFILL_DAYS;
  const newPoints = targets.filter((t) => !fs.existsSync(filePath(t.code))).length;
  const need = BACKFILL_ALL ? targets.length * perPoint : newPoints * perPoint;
  if (need > MAX_CALLS) {
    console.error(`경고: 예산 부족: ${Math.floor(MAX_CALLS / perPoint)}곳만 저장됨, 다음 실행에서 이어서 받음 (필요 약 ${need}회, 예산 ${MAX_CALLS}회)`);
  }
}
await Promise.all(Array.from({ length: CONCURRENCY }, () => worker()));
// 중단으로 시작도 못 한 지점
skippedPoints += Math.max(0, targets.length - Math.min(cursor, targets.length));

// 오늘 이전(어제 이전) 날짜가 남은 다른 지점 파일 정리는 각 지점 처리 때 이미 함(어제까지만 유지).
console.log('--- 요약 ---');
console.log(`호출 수: ${calls}`);
console.log(`저장한 지점: ${okPoints}곳(날짜 ${savedDays}일), 백필 대상: ${backfillPoints}곳, 백필 미완료로 보류: ${heldPoints}곳`);
console.log(`실패 지점: ${failedPoints}곳, 실패 날짜: ${failedDates}건, 데이터 없음(범위 밖) 날짜: ${nodataDates}건, 처리 못 한 지점: ${skippedPoints}곳, 정보 없음(기존 파일 유지) 지점: ${noInfoPoints}곳`);
if (unknownDirs.size > 0) {
  console.error(`경고: 표에 없는 유향 문자열 -> -1 처리: ${[...unknownDirs].map(([k, v]) => `"${k}" ${v}건`).join(', ')}`);
}
console.log(`중단 사유: ${fatal ? '[오류] ' + scrub(fatal) : stopReason || '없음'}`);
if (fatal) {
  console.error('오류: ' + scrub(fatal));
  process.exit(1);
}
if (okPoints === 0 && failedPoints > 0) {
  console.error('오류: 저장된 지점이 없고 호출 실패가 있었습니다.');
  process.exit(1);
}
process.exit(0);
