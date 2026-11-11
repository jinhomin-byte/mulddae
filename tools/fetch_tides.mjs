// 조석예보(고,저조) 수집 스크립트. Node 18+, 외부 의존성 없음.
// 사용: node tools/fetch_tides.mjs   (env: TIDE_API_KEY, MAX_CALLS, STATIONS, DAYS)
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const DATA_DIR = path.join(ROOT, 'data');
const API_URL = 'https://apis.data.go.kr/1192136/tideFcstHghLw/GetTideFcstHghLwApiService';
const SOURCE = '출처: 해양수산부 국립해양조사원, 조석예보(고, 저조) (공공데이터포털, data.go.kr)';
const CONCURRENCY = 5;
const MAX_RETRY = 3;
const MIN_REMAINING = 300;
const KEEP_PAST_DAYS = 7;

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
const MAX_CALLS = Number(process.env.MAX_CALLS) > 0 ? Number(process.env.MAX_CALLS) : 8000;
const DAYS = process.env.DAYS !== undefined && process.env.DAYS !== '' ? Number(process.env.DAYS) : 365;

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
if (!Number.isInteger(DAYS) || DAYS < 0) fail('DAYS 값이 올바르지 않습니다.');

// ---------- 날짜 유틸 (KST) ----------
const pad = (n) => String(n).padStart(2, '0');
const fmt = (d) => `${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}-${pad(d.getUTCDate())}`;
const kstNow = new Date(Date.now() + 9 * 3600 * 1000);
const todayUtc = new Date(Date.UTC(kstNow.getUTCFullYear(), kstNow.getUTCMonth(), kstNow.getUTCDate()));
const addDays = (d, n) => new Date(d.getTime() + n * 86400000);
const TODAY = fmt(todayUtc);
const PRUNE_BEFORE = fmt(addDays(todayUtc, -KEEP_PAST_DAYS));

// ---------- 관측소 ----------
const allStations = fs
  .readFileSync(path.join(import.meta.dirname ?? path.dirname(fileURLToPath(import.meta.url)), 'stations.csv'), 'utf8')
  .split(/\r?\n/)
  .map((l) => l.trim())
  .filter(Boolean)
  .map((l) => {
    const i = l.indexOf(',');
    return { code: l.slice(0, i).trim(), name: l.slice(i + 1).trim() };
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
// store[code][year] = { obsCode, name, lat, lot, unit, source, days: {date: [[t,v,k],...]} }
const store = new Map();
const dirty = new Set(); // "code/year"
const fileText = new Map(); // 기존 파일 내용(변경 비교용)

function filePath(code, year) {
  return path.join(DATA_DIR, code, `${year}.json`);
}

for (const st of allStations) {
  const dir = path.join(DATA_DIR, st.code);
  if (!fs.existsSync(dir)) continue;
  for (const f of fs.readdirSync(dir)) {
    const m = f.match(/^(\d{4})\.json$/);
    if (!m) continue;
    const text = fs.readFileSync(path.join(dir, f), 'utf8');
    try {
      const obj = JSON.parse(text);
      if (!store.has(st.code)) store.set(st.code, new Map());
      store.get(st.code).set(m[1], obj);
      fileText.set(`${st.code}/${m[1]}`, text);
    } catch {
      console.error(`경고: ${st.code}/${f} 파싱 실패, 무시합니다.`);
    }
  }
}

function hasDay(code, date) {
  return !!store.get(code)?.get(date.slice(0, 4))?.days?.[date];
}

// 오래된 날짜 정리
for (const [code, years] of store) {
  for (const [year, obj] of years) {
    for (const d of Object.keys(obj.days || {})) {
      if (d < PRUNE_BEFORE) {
        delete obj.days[d];
        dirty.add(`${code}/${year}`);
      }
    }
  }
}

// ---------- 작업 목록: 가까운 날짜부터, 날짜 안에서 코드 순 ----------
const tasks = [];
for (let i = 0; i <= DAYS; i++) {
  const date = fmt(addDays(todayUtc, i));
  for (const st of targetStations) {
    if (!hasDay(st.code, date)) tasks.push({ code: st.code, stName: st.name, date });
  }
}
const initialMissing = tasks.length;

// ---------- 호출 ----------
let calls = 0;
let fetched = 0;
let nodata = 0;
let failed = 0;
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

// 반환: {status:'ok', items, remaining} | {status:'nodata'} | 예외
async function callApi(code, date) {
  const url =
    `${API_URL}?serviceKey=${KEY}&obsCode=${encodeURIComponent(code)}` +
    `&reqDate=${date.replace(/-/g, '')}&type=json&numOfRows=300&pageNo=1`;
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
      // data.go.kr 게이트웨이 오류는 XML 로 오는 경우가 있음
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
    if (rc === '00' || rc === '0' || rc === '') {
      const raw = body?.items?.item;
      const items = Array.isArray(raw) ? raw : raw ? [raw] : [];
      if (items.length === 0) return { status: 'nodata' };
      return { status: 'ok', items, remaining };
    }
    if (rc === '03') return { status: 'nodata' };
    if (rc === '22') throw new FatalError(`일일 호출 한도 초과(${rc} ${rm}).`);
    if (['20', '30', '31', '32', '33'].includes(rc) || /SERVICE_KEY|SERVICE_ACCESS/.test(rm)) {
      throw new FatalError(`인증 오류(${rc} ${rm}). 인증키를 확인하세요.`);
    }
    lastErr = `API 오류(${rc} ${rm})`;
    // 05 등 일시적 서비스 오류는 재시도
  }
  throw new Error(lastErr);
}

function applyItems(task, items) {
  const year = task.date.slice(0, 4);
  if (!store.has(task.code)) store.set(task.code, new Map());
  const years = store.get(task.code);
  const first = items[0];
  let obj = years.get(year);
  if (!obj) {
    obj = { obsCode: task.code, name: task.stName, lat: null, lot: null, unit: 'cm', source: SOURCE, days: {} };
    years.set(year, obj);
  }
  const lat = Number(first.lat);
  const lot = Number(first.lot);
  if (first.obsvtrNm) obj.name = String(first.obsvtrNm).trim();
  if (first.lat !== undefined && first.lat !== '' && Number.isFinite(lat)) obj.lat = lat;
  if (first.lot !== undefined && first.lot !== '' && Number.isFinite(lot)) obj.lot = lot;
  const rows = [];
  for (const it of items) {
    const dt = String(it.predcDt || '');
    const m = dt.match(/^(\d{4}-\d{2}-\d{2})[ T](\d{2}:\d{2})/);
    const v = Number(it.predcTdlvVl);
    const se = String(it.extrSe);
    if (!m || m[1] !== task.date || !Number.isFinite(v)) continue;
    rows.push([m[2], v, se === '1' || se === '3' ? 'H' : 'L']);
  }
  if (rows.length === 0) return false;
  rows.sort((a, b) => (a[0] < b[0] ? -1 : a[0] > b[0] ? 1 : 0));
  obj.days[task.date] = rows;
  dirty.add(`${task.code}/${year}`);
  return true;
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
      if (r.status === 'nodata') nodata++;
      else if (applyItems(task, r.items)) fetched++;
      else nodata++;
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
function sortedDays(days) {
  const out = {};
  for (const k of Object.keys(days).sort()) out[k] = days[k];
  return out;
}

function save() {
  for (const [code, years] of store) {
    for (const [year, obj] of years) {
      const key = `${code}/${year}`;
      const p = filePath(code, year);
      if (Object.keys(obj.days).length === 0) {
        if (fs.existsSync(p)) fs.rmSync(p);
        continue;
      }
      if (!dirty.has(key)) continue;
      const out = {
        obsCode: obj.obsCode,
        name: obj.name,
        lat: obj.lat,
        lot: obj.lot,
        unit: obj.unit || 'cm',
        source: SOURCE,
        days: sortedDays(obj.days),
      };
      const text = JSON.stringify(out) + '\n';
      if (fileText.get(key) === text) continue;
      fs.mkdirSync(path.dirname(p), { recursive: true });
      fs.writeFileSync(p, text, 'utf8');
    }
    // 빈 디렉터리 정리
    const dir = path.join(DATA_DIR, code);
    if (fs.existsSync(dir) && fs.readdirSync(dir).length === 0) fs.rmdirSync(dir);
  }

  // index.json
  const stations = allStations.map((st) => {
    const years = store.get(st.code);
    let name = st.name;
    let lat = null;
    let lot = null;
    let firstDate = null;
    let lastDate = null;
    if (years) {
      for (const y of [...years.keys()].sort()) {
        const obj = years.get(y);
        const ds = Object.keys(obj.days).sort();
        if (ds.length === 0) continue;
        if (obj.name) name = obj.name;
        if (obj.lat !== null && obj.lat !== undefined) lat = obj.lat;
        if (obj.lot !== null && obj.lot !== undefined) lot = obj.lot;
        if (!firstDate || ds[0] < firstDate) firstDate = ds[0];
        if (!lastDate || ds[ds.length - 1] > lastDate) lastDate = ds[ds.length - 1];
      }
    }
    return { code: st.code, name, lat, lot, firstDate, lastDate };
  });
  const indexPath = path.join(DATA_DIR, 'index.json');
  let updated = new Date().toISOString().replace(/\.\d{3}Z$/, 'Z');
  const core = JSON.stringify({ source: SOURCE, stations });
  if (fs.existsSync(indexPath)) {
    try {
      const old = JSON.parse(fs.readFileSync(indexPath, 'utf8'));
      if (JSON.stringify({ source: old.source, stations: old.stations }) === core) updated = old.updated;
    } catch { /* 새로 작성 */ }
  }
  const text = JSON.stringify({ updated, source: SOURCE, stations }) + '\n';
  const prev = fs.existsSync(indexPath) ? fs.readFileSync(indexPath, 'utf8') : null;
  if (prev !== text) {
    fs.mkdirSync(DATA_DIR, { recursive: true });
    fs.writeFileSync(indexPath, text, 'utf8');
  }
}

// ---------- 실행 ----------
console.log(`대상: 관측소 ${targetStations.length}곳, ${TODAY} ~ +${DAYS}일, 미수집 ${initialMissing}건, 호출 예산 ${MAX_CALLS}`);
await Promise.all(Array.from({ length: CONCURRENCY }, () => worker()));
save();

const remainingMissing = initialMissing - fetched - nodata;
console.log('--- 요약 ---');
console.log(`호출 수: ${calls}`);
console.log(`새로 받은 (관측소,날짜): ${fetched}`);
console.log(`데이터 없음 ${nodata}건(다음 실행에서 재시도), 호출 실패: ${failed}`);
console.log(`남은 미수집 (관측소,날짜): ${remainingMissing}`);
console.log(`중단 사유: ${fatal ? '[오류] ' + scrub(fatal) : stopReason || '없음(정상 완료 또는 가능한 범위 모두 처리)'}`);
if (fatal) {
  console.error('오류: ' + scrub(fatal));
  process.exit(1);
}
process.exit(0);
