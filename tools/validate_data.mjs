// data/ 형식 검증 스크립트. Node 18+, 외부 의존성 없음.
// 사용: node tools/validate_data.mjs   (오류가 있으면 종료코드 1)
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const DATA_DIR = path.join(ROOT, 'data');
const MAX_SHOW = 20;
const MIN_CM = -200;
const MAX_CM = 1500;

let errorCount = 0;
let warnCount = 0;
let fileCount = 0;
let dayCount = 0;
const shown = [];

function err(file, reason) {
  errorCount++;
  if (shown.length < MAX_SHOW) shown.push(`${file}: ${reason}`);
}
const warnShown = [];
function warn(msg) {
  warnCount++;
  if (msg && warnShown.length < MAX_SHOW) warnShown.push(msg);
}
// 'obs' 인자: 실측 데이터(data/obs)만 검사. 인자 없으면 전체.
// 'current' 인자: 조류 데이터(data/current)만 검사.
const ONLY_CURRENT = process.argv[2] === 'current';
const ONLY_OBS = process.argv[2] === 'obs' || ONLY_CURRENT;
const SKIP_OBS = ONLY_CURRENT;

const isStr = (v) => typeof v === 'string' && v.trim() !== '';
const isNum = (v) => typeof v === 'number' && Number.isFinite(v);
const isObj = (v) => v !== null && typeof v === 'object' && !Array.isArray(v);

function validDate(s) {
  const m = typeof s === 'string' && s.match(/^(\d{4})-(\d{2})-(\d{2})$/);
  if (!m) return false;
  const d = new Date(Date.UTC(+m[1], +m[2] - 1, +m[3]));
  return d.getUTCFullYear() === +m[1] && d.getUTCMonth() === +m[2] - 1 && d.getUTCDate() === +m[3];
}

function readJson(p, rel) {
  try {
    return JSON.parse(fs.readFileSync(p, 'utf8'));
  } catch (e) {
    err(rel, `JSON 파싱 실패 (${e.message})`);
    return undefined;
  }
}

function finish() {
  console.log('--- 데이터 검증 요약 ---');
  console.log(`검사한 파일: ${fileCount}, 날짜: ${dayCount}, 오류: ${errorCount}, 경고: ${warnCount}`);
  for (const s of warnShown) console.log(`경고: ${s}`);
  if (warnCount > warnShown.length && warnShown.length > 0) console.log(`... 경고 외 ${warnCount - warnShown.length}건`);
  if (errorCount > 0) {
    for (const s of shown) console.error(s);
    if (errorCount > shown.length) console.error(`... 외 ${errorCount - shown.length}건`);
    process.exit(1);
  }
  process.exit(0);
}

// ---------- data/ 존재 확인 ----------
if (!fs.existsSync(DATA_DIR) || fs.readdirSync(DATA_DIR).length === 0) {
  console.log('데이터 없음 (data/ 가 없거나 비어 있음). 검증을 건너뜁니다.');
  process.exit(0);
}

// ---------- stations.csv ----------
const csvCodes = new Set();
try {
  for (const l of fs.readFileSync(path.join(__dirname_(), 'stations.csv'), 'utf8').split(/\r?\n/)) {
    const t = l.trim();
    if (!t) continue;
    csvCodes.add(t.slice(0, Math.max(t.indexOf(','), 0) || t.length).trim());
  }
} catch {
  err('tools/stations.csv', '읽을 수 없음');
}
function __dirname_() {
  return path.dirname(fileURLToPath(import.meta.url));
}

// ---------- index.json ----------
const range = new Map(); // code -> {firstDate, lastDate}
const indexPath = path.join(DATA_DIR, 'index.json');
if (ONLY_OBS) {
  // 조석 index 검사 생략
} else if (!fs.existsSync(indexPath)) {
  err('data/index.json', '파일 없음');
} else {
  fileCount++;
  const idx = readJson(indexPath, 'data/index.json');
  if (idx !== undefined) {
    const f = 'data/index.json';
    if (!isObj(idx)) {
      err(f, '최상위가 객체가 아님');
    } else {
      if (!isStr(idx.updated) || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?(Z|[+-]\d{2}:\d{2})$/.test(idx.updated) || Number.isNaN(Date.parse(idx.updated))) {
        err(f, 'updated 가 ISO8601 형식이 아님');
      }
      if (!isStr(idx.source)) err(f, 'source 문구 없음');
      if (!Array.isArray(idx.stations)) {
        err(f, 'stations 배열 없음');
      } else {
        let prev = null;
        const seen = new Set();
        for (const st of idx.stations) {
          if (!isObj(st) || !isStr(st.code)) {
            err(f, 'stations 항목에 code 없음');
            continue;
          }
          const c = st.code;
          if (seen.has(c)) err(f, `코드 중복: ${c}`);
          seen.add(c);
          if (prev !== null && c < prev) err(f, `코드 순 정렬 위반: ${prev} 다음 ${c}`);
          prev = c;
          if (csvCodes.size > 0 && !csvCodes.has(c)) err(f, `stations.csv 에 없는 코드: ${c}`);
          if (!isStr(st.name)) err(f, `${c}: name 형식 오류`);
          const hasData = st.firstDate !== null && st.firstDate !== undefined;
          if (hasData) {
            if (!isNum(st.lat)) err(f, `${c}: lat 형식 오류`);
            if (!isNum(st.lot)) err(f, `${c}: lot 형식 오류`);
            if (!validDate(st.firstDate)) err(f, `${c}: firstDate 형식 오류`);
            if (!validDate(st.lastDate)) err(f, `${c}: lastDate 형식 오류`);
            else if (validDate(st.firstDate) && st.firstDate > st.lastDate) err(f, `${c}: firstDate > lastDate`);
            if (validDate(st.firstDate) && validDate(st.lastDate)) range.set(c, { first: st.firstDate, last: st.lastDate });
          } else {
            for (const k of ['lat', 'lot', 'lastDate']) {
              if (st[k] !== null && st[k] !== undefined) err(f, `${c}: firstDate 가 null 인데 ${k} 가 있음`);
            }
          }
        }
      }
    }
  }
}

// ---------- 관측소별 연도 파일 ----------
const timeRe = /^([01]\d|2[0-3]):[0-5]\d$/;
for (const code of fs.readdirSync(DATA_DIR).sort()) {
  if (code === 'obs' || code === 'current' || ONLY_OBS) continue; // 실측·조류 데이터는 아래에서 따로 검사
  const dir = path.join(DATA_DIR, code);
  if (!fs.statSync(dir).isDirectory()) continue;
  for (const fname of fs.readdirSync(dir).sort()) {
    const rel = `data/${code}/${fname}`;
    const fm = fname.match(/^(\d{4})\.json$/);
    if (!fm) {
      err(rel, '파일 이름이 <연도>.json 형식이 아님');
      continue;
    }
    const year = fm[1];
    fileCount++;
    const obj = readJson(path.join(dir, fname), rel);
    if (obj === undefined) continue;
    if (!isObj(obj)) {
      err(rel, '최상위가 객체가 아님');
      continue;
    }
    if (obj.obsCode !== code) err(rel, `obsCode(${obj.obsCode})가 폴더 이름과 다름`);
    if (!isStr(obj.name)) err(rel, 'name 없음');
    if (!isNum(obj.lat)) err(rel, 'lat 없음 또는 숫자가 아님');
    if (!isNum(obj.lot)) err(rel, 'lot 없음 또는 숫자가 아님');
    if (obj.unit !== 'cm') err(rel, `unit 이 "cm" 가 아님(${obj.unit})`);
    if (!isStr(obj.source)) err(rel, 'source 문구 없음');
    if (!isObj(obj.days)) {
      err(rel, 'days 객체 없음');
      continue;
    }
    const r = range.get(code);
    if (!r && fs.existsSync(indexPath)) err(rel, 'index.json 에 이 관측소의 수집 범위가 없음');
    for (const [date, rows] of Object.entries(obj.days)) {
      dayCount++;
      if (!validDate(date)) {
        err(rel, `날짜 키 형식 오류: ${date}`);
        continue;
      }
      if (date.slice(0, 4) !== year) err(rel, `${date} 가 파일 연도(${year})와 다름`);
      if (r && (date < r.first || date > r.last)) err(rel, `${date} 가 index.json 범위(${r.first}~${r.last}) 밖`);
      if (!Array.isArray(rows) || rows.length < 1 || rows.length > 6) {
        err(rel, `${date}: 항목 수가 1~6 이 아님`);
        continue;
      }
      let prevT = null;
      for (const row of rows) {
        if (!Array.isArray(row) || row.length !== 3 || typeof row[0] !== 'string' || !timeRe.test(row[0]) || !isNum(row[1]) || (row[2] !== 'H' && row[2] !== 'L')) {
          err(rel, `${date}: 항목 형식 오류 ${JSON.stringify(row)}`);
          continue;
        }
        if (prevT !== null && row[0] < prevT) err(rel, `${date}: 시각순 정렬 위반 (${prevT} 다음 ${row[0]})`);
        prevT = row[0];
        if (row[1] < MIN_CM || row[1] > MAX_CM) warn();
      }
    }
  }
}

// ---------- data/obs (실측) ----------
const OBS_DIR = path.join(DATA_DIR, 'obs');
const OBS_FIELDS = ['wtem', 'artmp', 'wspd', 'maxMmntWspd', 'wndrct', 'slntQty'];
const OBS_RANGE = [[-5, 40], [-40, 50], [0, 80], [0, 100], [0, 360], [0, 50]];
const OBS_KEEP_DAYS = 365;
const OBS_GRACE_DAYS = 1; // 수집(정리)과 검증 시각 차이 여유
const kstToday = new Date(Date.now() + 9 * 3600 * 1000);
const dayStr = (offset) => {
  const d = new Date(Date.UTC(kstToday.getUTCFullYear(), kstToday.getUTCMonth(), kstToday.getUTCDate() + offset));
  return `${d.getUTCFullYear()}-${String(d.getUTCMonth() + 1).padStart(2, '0')}-${String(d.getUTCDate()).padStart(2, '0')}`;
};
const obsOldest = dayStr(-(OBS_KEEP_DAYS + OBS_GRACE_DAYS));
const obsNewest = dayStr(1);
const obsTimeRe = /^([01]\d|2[0-3]):00$/;

if (fs.existsSync(OBS_DIR) && !SKIP_OBS) {
  const f = 'data/obs/index.json';
  const obsIdxPath = path.join(OBS_DIR, 'index.json');
  if (!fs.existsSync(obsIdxPath)) {
    err(f, '파일 없음');
  } else {
    fileCount++;
    const idx = readJson(obsIdxPath, f);
    if (idx !== undefined) {
      if (!isObj(idx)) {
        err(f, '최상위가 객체가 아님');
      } else {
        if (!isStr(idx.updated) || Number.isNaN(Date.parse(idx.updated))) err(f, 'updated 가 ISO8601 형식이 아님');
        if (!isStr(idx.source)) err(f, 'source 문구 없음');
        if (JSON.stringify(idx.fields) !== JSON.stringify(OBS_FIELDS)) err(f, `fields 가 ${OBS_FIELDS.join(',')} 가 아님`);
        if (!Array.isArray(idx.stations)) {
          err(f, 'stations 배열 없음');
        } else {
          const seen = new Set();
          for (const st of idx.stations) {
            if (!isObj(st) || !isStr(st.code)) {
              err(f, 'stations 항목에 code 없음');
              continue;
            }
            if (seen.has(st.code)) err(f, `코드 중복: ${st.code}`);
            seen.add(st.code);
            if (!isStr(st.name)) err(f, `${st.code}: name 형식 오류`);
            if (!isNum(st.lat) || !isNum(st.lot)) err(f, `${st.code}: lat/lot 형식 오류`);
            if (st.lastTime !== null && !(typeof st.lastTime === 'string' && /^\d{4}-\d{2}-\d{2} \d{2}:00$/.test(st.lastTime) && validDate(st.lastTime.slice(0, 10)))) {
              err(f, `${st.code}: lastTime 형식 오류`);
            }
            if (st.wtemLast !== null && !(typeof st.wtemLast === 'string' && /^\d{4}-\d{2}-\d{2} \d{2}:00$/.test(st.wtemLast) && validDate(st.wtemLast.slice(0, 10)))) {
              err(f, `${st.code}: wtemLast 형식 오류`);
            }
          }
        }
      }
    }
  }

  for (const code of fs.readdirSync(OBS_DIR).sort()) {
    const dir = path.join(OBS_DIR, code);
    if (!fs.statSync(dir).isDirectory()) continue;
    for (const fname of fs.readdirSync(dir).sort()) {
      const rel = `data/obs/${code}/${fname}`;
      const fm = fname.match(/^(\d{4}-\d{2})\.json$/);
      if (!fm) {
        err(rel, '파일 이름이 <YYYY-MM>.json 형식이 아님');
        continue;
      }
      fileCount++;
      const obj = readJson(path.join(dir, fname), rel);
      if (obj === undefined) continue;
      if (!isObj(obj) || !isObj(obj.obs)) {
        err(rel, 'obs 객체 없음');
        continue;
      }
      for (const [date, times] of Object.entries(obj.obs)) {
        dayCount++;
        if (!validDate(date)) {
          err(rel, `날짜 키 형식 오류: ${date}`);
          continue;
        }
        if (date.slice(0, 7) !== fm[1]) err(rel, `${date} 가 파일 월(${fm[1]})과 다름`);
        if (date < obsOldest) err(rel, `${date} 가 보관 기간(${OBS_KEEP_DAYS}일)을 넘음`);
        if (date > obsNewest) err(rel, `${date} 가 미래 날짜임`);
        if (!isObj(times) || Object.keys(times).length === 0) {
          err(rel, `${date}: 시각 항목이 비어 있음`);
          continue;
        }
        for (const [t, row] of Object.entries(times)) {
          if (!obsTimeRe.test(t)) {
            err(rel, `${date}: 시각이 정시(HH:00)가 아님 ${t}`);
            continue;
          }
          if (!Array.isArray(row) || row.length !== OBS_FIELDS.length || !row.every((v) => v === null || isNum(v))) {
            err(rel, `${date} ${t}: 항목 형식 오류 ${JSON.stringify(row)}`);
            continue;
          }
          row.forEach((v, i) => {
            if (v !== null && (v < OBS_RANGE[i][0] || v > OBS_RANGE[i][1])) warn(`${code} ${date} ${t} ${OBS_FIELDS[i]}=${v} (범위 ${OBS_RANGE[i][0]}~${OBS_RANGE[i][1]})`);
          });
        }
      }
    }
  }
}

// ---------- data/current (조류) ----------
const CUR_DIR = path.join(DATA_DIR, 'current');
const CUR_SLOTS = 144;
const CUR_MAX_CRSP = 500; // cm/s
if (fs.existsSync(CUR_DIR) && (ONLY_CURRENT || !process.argv[2] || process.argv[2] === 'all')) {
  const mapRel = 'data/current/map.json';
  const mapPath = path.join(CUR_DIR, 'map.json');
  const pointCodes = [];
  if (!fs.existsSync(mapPath)) {
    err(mapRel, '파일 없음');
  } else {
    fileCount++;
    const mp = readJson(mapPath, mapRel);
    if (mp !== undefined) {
      if (!isObj(mp)) {
        err(mapRel, '최상위가 객체가 아님');
      } else {
        if (!isStr(mp.updated) || Number.isNaN(Date.parse(mp.updated))) err(mapRel, 'updated 가 ISO8601 형식이 아님');
        if (!isStr(mp.rule)) err(mapRel, 'rule 없음');
        if (!isObj(mp.stations)) err(mapRel, 'stations 객체 없음');
        if (!isObj(mp.points)) {
          err(mapRel, 'points 객체 없음');
        } else {
          for (const [pc, p] of Object.entries(mp.points)) {
            pointCodes.push(pc);
            if (!isObj(p) || !isStr(p.name) || !isNum(p.lat) || !isNum(p.lot)) err(mapRel, `points.${pc}: name/lat/lot 형식 오류`);
          }
        }
        if (isObj(mp.stations) && isObj(mp.points)) {
          for (const [sc, s] of Object.entries(mp.stations)) {
            if (!isObj(s) || !isStr(s.point) || !isNum(s.km)) err(mapRel, `stations.${sc}: point/km 형식 오류`);
            else if (!(s.point in mp.points)) err(mapRel, `stations.${sc}: points 에 없는 지점 ${s.point}`);
          }
        }
      }
    }
  }

  for (const fname of fs.readdirSync(CUR_DIR).sort()) {
    if (fname === 'map.json') continue;
    const rel = `data/current/${fname}`;
    if (!/\.json$/.test(fname)) {
      err(rel, '.json 파일이 아님');
      continue;
    }
    const code = fname.slice(0, -5);
    fileCount++;
    const obj = readJson(path.join(CUR_DIR, fname), rel);
    if (obj === undefined) continue;
    if (!isObj(obj)) {
      err(rel, '최상위가 객체가 아님');
      continue;
    }
    if (!isStr(obj.source)) err(rel, 'source 문구 없음');
    if (!isStr(obj.name)) err(rel, 'name 없음');
    if (!isNum(obj.lat) || !isNum(obj.lot)) err(rel, 'lat/lot 형식 오류');
    if (obj.min !== 10) err(rel, `min 이 10 이 아님(${obj.min})`);
    if (!isNum(obj.maxCrsp) || obj.maxCrsp < 0 || obj.maxCrsp > CUR_MAX_CRSP) err(rel, `maxCrsp 형식/범위 오류(${obj.maxCrsp})`);
    if (!isObj(obj.days)) {
      err(rel, 'days 객체 없음');
      continue;
    }
    const dates = Object.keys(obj.days).sort();
    if (dates.length === 0) err(rel, 'days 가 비어 있음');
    else if (obj.start !== dates[0]) err(rel, `start(${obj.start})가 가장 이른 날짜(${dates[0]})와 다름`);
    for (const date of dates) {
      dayCount++;
      if (!validDate(date)) {
        err(rel, `날짜 키 형식 오류: ${date}`);
        continue;
      }
      const day = obj.days[date];
      if (!isObj(day) || !Array.isArray(day.s) || !Array.isArray(day.d)) {
        err(rel, `${date}: s/d 배열 없음`);
        continue;
      }
      if (day.s.length !== CUR_SLOTS || day.d.length !== CUR_SLOTS) {
        err(rel, `${date}: s/d 길이가 ${CUR_SLOTS}가 아님(${day.s.length}/${day.d.length})`);
        continue;
      }
      if (!day.s.every((v) => isNum(v) && v >= 0 && v <= CUR_MAX_CRSP)) err(rel, `${date}: 유속 s 값이 숫자 0~${CUR_MAX_CRSP} 범위가 아님`);
      if (!day.d.every((v) => Number.isInteger(v) && v >= -1 && v <= 15)) err(rel, `${date}: 유향 d 값이 정수 -1~15 범위가 아님`);
    }
    if (pointCodes.length > 0 && !pointCodes.includes(code)) warn(`map.json 의 points 에 없는 지점 파일(수집 시 정리됨): ${fname}`);
  }

  // map.json 의 points 가 모두 파일로 존재해야 함
  // 일부 지점만 실패·시험 수집한 경우를 허용하려고 일부 누락은 경고, 하나도 없으면 오류
  const missing = pointCodes.filter((pc) => !fs.existsSync(path.join(CUR_DIR, `${pc}.json`)));
  if (pointCodes.length > 0 && missing.length === pointCodes.length) err(mapRel, 'points 지점 파일이 하나도 없음');
  else for (const pc of missing) warn(`조류 지점 파일 없음: ${pc}.json`);
}

finish();
