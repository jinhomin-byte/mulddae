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
function warn() {
  warnCount++;
}

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
if (!fs.existsSync(indexPath)) {
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

finish();
