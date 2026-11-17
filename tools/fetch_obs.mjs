// 조위관측소 실측(수온 등) 수집 스크립트. Node 18+, 외부 의존성 없음.
// 사용: node tools/fetch_obs.mjs   (env: TIDE_API_KEY, MAX_CALLS, STATIONS, FETCH_YESTERDAY)
// 저장: data/obs/<관측소코드>/<YYYY-MM>.json = {"obs":{"YYYY-MM-DD":{"HH:00":[FIELDS 순서 값...]}}}
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const TOOLS_DIR = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(TOOLS_DIR, '..');
const OBS_DIR = path.join(ROOT, 'data', 'obs');
const API_URL = 'https://apis.data.go.kr/1192136/dtRecent/GetDTRecentApiService';
const SOURCE = '출처: 해양수산부 국립해양조사원, 조위관측소 최신 관측데이터 (공공데이터포털)';
const FIELDS = ['wtem', 'artmp', 'wspd', 'maxMmntWspd', 'wndrct', 'slntQty'];
const CONCURRENCY = 5;
const MAX_RETRY = 3;
const MIN_REMAINING = 300;
const KEEP_DAYS = 365;

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
// 하루 한도 10,000 중 조석 수집과 나눠 쓰므로 실행당 예산을 낮게 둔다(실행 1회 약 66~132회).
const MAX_CALLS = Number(process.env.MAX_CALLS) > 0 ? Number(process.env.MAX_CALLS) : 400;

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
const PRUNE_BEFORE = fmt(addDays(todayUtc, -KEEP_DAYS));
const KST_HOUR = kstNow.getUTCHours();
// KST 00~03시 실행에서는 어제 23:00 값을 확정하기 위해 어제도 함께 받는다(하루 최대 약 4회 x 66 추가).
const FETCH_DATES = KST_HOUR <= 3 || process.env.FETCH_YESTERDAY === '1' ? [YESTERDAY, TODAY] : [TODAY];

// ---------- 관측소 ----------
const allStations = fs
  .readFileSync(path.join(TOOLS_DIR, 'obs_stations.csv'), 'utf8')
  .split(/\r?\n/)
  .map((l) => l.trim())
  .filter(Boolean)
  .map((l) => {
    const [code, name, lat, lot] = l.split(',').map((s) => s.trim());
    return { code, name, lat: Number(lat), lot: Number(lot) };
  })
  .sort((a, b) => (a.code < b.code ? -1 : a.code > b.code ? 1 : 0));

let targetStations = allStations;
if (process.env.STATIONS && process.env.STATIONS.trim()) {
  const want = new Set(process.env.STATIONS.split(',').map((s) => s.trim()).filter(Boolean));
  const known = new Set(allStations.map((s) => s.code));
  for (const c of want) {
    if (!known.has(c)) console.error(`경고: 목록에 없는 관측소 코드 무시: ${c}`);
  }
  targetStations = allStations.filter((s) => want.has(s.code));
  if (targetStations.length === 0) fail('STATIONS 에 해당하는 관측소가 없습니다.');
}

// ---------- 저장소 로드 ----------
// store[code][month] = { obs: { date: { 'HH:00': [..] } } }
const store = new Map();
const dirty = new Set(); // "code/month"
const bad = new Set(); // 읽기/파싱 실패한 "code/month" (덮어쓰지 않고 건너뜀)
const fileText = new Map();

const filePath = (code, month) => path.join(OBS_DIR, code, `${month}.json`);

if (fs.existsSync(OBS_DIR)) {
  for (const code of fs.readdirSync(OBS_DIR)) {
    const dir = path.join(OBS_DIR, code);
    if (!fs.statSync(dir).isDirectory()) continue;
    for (const f of fs.readdirSync(dir)) {
      const m = f.match(/^(\d{4}-\d{2})\.json$/);
      if (!m) continue;
      try {
        const text = fs.readFileSync(path.join(dir, f), 'utf8');
        const obj = JSON.parse(text);
        if (!obj || typeof obj.obs !== 'object' || obj.obs === null) throw new Error('obs 없음');
        if (!store.has(code)) store.set(code, new Map());
        store.get(code).set(m[1], obj);
        fileText.set(`${code}/${m[1]}`, text);
      } catch {
        bad.add(`${code}/${m[1]}`);
        console.error(`경고: obs/${code}/${f} 읽기/파싱 실패, 덮어쓰지 않고 이 관측소·월은 건너뜁니다.`);
      }
    }
  }
}

// 365일보다 오래된 날짜 정리
for (const [code, months] of store) {
  for (const [month, obj] of months) {
    for (const d of Object.keys(obj.obs)) {
      if (d < PRUNE_BEFORE) {
        delete obj.obs[d];
        dirty.add(`${code}/${month}`);
      }
    }
  }
}

// ---------- 호출 ----------
let calls = 0;
let stopReason = '';
let fatal = '';
let stopped = false;
let consecutiveFail = 0;
let unavailable = 0;
let failed = 0;
let newRows = 0;
const okStations = new Set();

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

class FatalError extends Error {}

function stop(reason) {
  if (!stopped) {
    stopped = true;
    stopReason = reason;
  }
}

// 반환: {status:'ok', items} | {status:'nodata'} | {status:'unavailable'} | {status:'aborted'} | 예외
async function callApi(code, date) {
  const url =
    `${API_URL}?serviceKey=${KEY}&obsCode=${encodeURIComponent(code)}` +
    `&reqDate=${date.replace(/-/g, '')}&min=60&type=json&numOfRows=200&pageNo=1`;
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
    if (/OBSERVATION_STATION_TEMPORARILY_UNAVAILABLE/.test(rm)) return { status: 'unavailable' };
    if (rc === '03' || /NODATA/.test(rm)) return { status: 'nodata' };
    if (rc === '22') throw new FatalError(`일일 호출 한도 초과(${rc} ${rm}).`);
    if (['20', '30', '31', '32', '33'].includes(rc) || /SERVICE_KEY|SERVICE_ACCESS/.test(rm)) {
      throw new FatalError(`인증 오류(${rc} ${rm}). 인증키를 확인하세요.`);
    }
    lastErr = `API 오류(${rc} ${rm})`;
  }
  throw new Error(lastErr);
}

const toVal = (v) => {
  if (v === null || v === undefined || v === '') return null;
  const n = Number(v);
  return Number.isFinite(n) ? n : null;
};

// 정시(HH:00) 값만 저장. 같은 시각은 덮어쓰고 새 시각은 추가. 반환: 저장한 행 수
function applyItems(task, items) {
  const month = task.date.slice(0, 7);
  if (bad.has(`${task.code}/${month}`)) return 0;
  let count = 0;
  for (const it of items) {
    const m = String(it.obsrvnDt || '').match(/^(\d{4}-\d{2}-\d{2})[ T](\d{2}):(\d{2})/);
    if (!m || m[1] !== task.date || m[3] !== '00') continue;
    if (m[1] < PRUNE_BEFORE) continue;
    const row = FIELDS.map((k) => toVal(it[k]));
    if (row.every((v) => v === null)) continue;
    if (!store.has(task.code)) store.set(task.code, new Map());
    const months = store.get(task.code);
    let obj = months.get(month);
    if (!obj) {
      obj = { obs: {} };
      months.set(month, obj);
    }
    if (!obj.obs[task.date]) obj.obs[task.date] = {};
    const time = `${m[2]}:00`;
    if (JSON.stringify(obj.obs[task.date][time]) !== JSON.stringify(row)) {
      obj.obs[task.date][time] = row;
      dirty.add(`${task.code}/${month}`);
      newRows++;
    }
    count++;
  }
  return count;
}

const tasks = [];
for (const st of targetStations) {
  for (const date of FETCH_DATES) tasks.push({ code: st.code, date });
}

let cursor = 0;
async function worker() {
  while (!stopped && !fatal) {
    const idx = cursor++;
    if (idx >= tasks.length) return;
    const task = tasks[idx];
    try {
      const r = await callApi(task.code, task.date);
      if (r.status === 'aborted') return;
      consecutiveFail = 0;
      if (r.status === 'unavailable') {
        unavailable++;
        console.error(`경고: ${task.code} 점검 중(일시 사용 불가), 건너뜀`);
      } else if (r.status === 'ok') {
        if (applyItems(task, r.items) > 0) okStations.add(task.code);
      }
    } catch (e) {
      if (e instanceof FatalError) {
        fatal = e.message;
        return;
      }
      failed++;
      consecutiveFail++;
      console.error(`경고: ${task.code} ${task.date} 실패 (${scrub(e.message)})`);
      if (consecutiveFail >= 20) {
        fatal = '연속 호출 실패가 20회를 넘어 중단합니다(네트워크/서비스 장애 의심).';
        return;
      }
    }
  }
}

// ---------- 저장 ----------
function sortedObj(o) {
  const out = {};
  for (const k of Object.keys(o).sort()) out[k] = o[k];
  return out;
}

function save() {
  for (const [code, months] of store) {
    for (const [month, obj] of months) {
      const key = `${code}/${month}`;
      const p = filePath(code, month);
      // 빈 날짜 제거
      for (const d of Object.keys(obj.obs)) {
        if (Object.keys(obj.obs[d]).length === 0) {
          delete obj.obs[d];
          dirty.add(key);
        }
      }
      if (Object.keys(obj.obs).length === 0) {
        if (fs.existsSync(p)) fs.rmSync(p);
        continue;
      }
      if (!dirty.has(key)) continue;
      const days = {};
      for (const d of Object.keys(obj.obs).sort()) days[d] = sortedObj(obj.obs[d]);
      const text = JSON.stringify({ obs: days }) + '\n';
      if (fileText.get(key) === text) continue;
      fs.mkdirSync(path.dirname(p), { recursive: true });
      fs.writeFileSync(p, text, 'utf8');
    }
    const dir = path.join(OBS_DIR, code);
    if (fs.existsSync(dir) && fs.readdirSync(dir).length === 0) fs.rmdirSync(dir);
  }

  const stations = allStations.map((st) => {
    let last = null;
    let wtemLast = null; // 수온(wtem)이 null 이 아닌 마지막 관측 시각
    const wi = FIELDS.indexOf('wtem');
    for (const obj of store.get(st.code)?.values() ?? []) {
      for (const [d, times] of Object.entries(obj.obs)) {
        for (const [t, row] of Object.entries(times)) {
          const stamp = `${d} ${t}`;
          if (!last || stamp > last) last = stamp;
          if (row[wi] !== null && row[wi] !== undefined && (!wtemLast || stamp > wtemLast)) wtemLast = stamp;
        }
      }
    }
    return { code: st.code, name: st.name, lat: st.lat, lot: st.lot, lastTime: last, wtemLast };
  });

  const indexPath = path.join(OBS_DIR, 'index.json');
  let updated = new Date().toISOString().replace(/\.\d{3}Z$/, 'Z');
  const core = JSON.stringify({ source: SOURCE, fields: FIELDS, stations });
  const prev = fs.existsSync(indexPath) ? fs.readFileSync(indexPath, 'utf8') : null;
  if (prev !== null) {
    try {
      const old = JSON.parse(prev);
      if (JSON.stringify({ source: old.source, fields: old.fields, stations: old.stations }) === core) updated = old.updated;
    } catch { /* 새로 작성 */ }
  }
  const text = JSON.stringify({ updated, source: SOURCE, fields: FIELDS, stations }) + '\n';
  if (prev !== text) {
    fs.mkdirSync(OBS_DIR, { recursive: true });
    fs.writeFileSync(indexPath, text, 'utf8');
  }
}

// ---------- 실행 ----------
console.log(`대상: 관측소 ${targetStations.length}곳, 날짜 ${FETCH_DATES.join(', ')} (KST ${pad(KST_HOUR)}시), 호출 예산 ${MAX_CALLS}`);
await Promise.all(Array.from({ length: CONCURRENCY }, () => worker()));
save();

console.log('--- 요약 ---');
console.log(`호출 수: ${calls}`);
console.log(`데이터를 받은 관측소: ${okStations.size}곳, 새로 저장/갱신한 시각: ${newRows}건`);
console.log(`점검 중: ${unavailable}건, 호출 실패: ${failed}건`);
console.log(`중단 사유: ${fatal ? '[오류] ' + scrub(fatal) : stopReason || '없음'}`);
if (fatal) {
  console.error('오류: ' + scrub(fatal));
  process.exit(1);
}
if (okStations.size === 0) {
  // 데이터 없음/점검 중뿐이면 경고만, 호출 오류가 있었으면(전 관측소 오류) 실패
  if (failed > 0) {
    console.error('오류: 데이터를 받은 관측소가 없고 호출 오류가 있었습니다.');
    process.exit(1);
  }
  console.error('경고: 이번 실행에서 새로 저장된 데이터가 없습니다(데이터 없음/점검 중).');
}
process.exit(0);
