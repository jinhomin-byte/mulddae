// 조위관측소 <-> 조류 예보지점 매핑 생성. Node 18+, 외부 의존성 없음. API 호출 없음.
// 사용: node tools/build_current_map.mjs
// 규칙: 수온 있는 관측소(data/obs/index.json 의 wtemLast 가 null 이 아님)는 20km, 없으면 10km 이내 가장 가까운 지점만 매핑.
// 저장: data/current/map.json = {updated, rule, stations:{조위코드:{point,km}}, points:{조류코드:{name,lat,lot}}}
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const TOOLS_DIR = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(TOOLS_DIR, '..');
const CUR_DIR = path.join(ROOT, 'data', 'current');
const RULE = '수온있음20km/없음10km';
const KM_WITH_WTEM = 20;
const KM_WITHOUT_WTEM = 10;

function fail(msg) {
  console.error('오류: ' + msg);
  process.exit(1);
}

function readJson(p) {
  try {
    return JSON.parse(fs.readFileSync(p, 'utf8'));
  } catch (e) {
    return fail(`${p} 읽기/파싱 실패 (${e.message})`);
  }
}

const rad = (d) => (d * Math.PI) / 180;
function haversineKm(lat1, lon1, lat2, lon2) {
  const dLat = rad(lat2 - lat1);
  const dLon = rad(lon2 - lon1);
  const a = Math.sin(dLat / 2) ** 2 + Math.cos(rad(lat1)) * Math.cos(rad(lat2)) * Math.sin(dLon / 2) ** 2;
  return 6371 * 2 * Math.asin(Math.sqrt(a));
}

const idx = readJson(path.join(ROOT, 'data', 'index.json'));
const obsIdx = readJson(path.join(ROOT, 'data', 'obs', 'index.json'));
const points = readJson(path.join(TOOLS_DIR, 'current_points.json'));
if (!Array.isArray(idx.stations) || !Array.isArray(obsIdx.stations) || !Array.isArray(points) || points.length === 0) {
  fail('입력 데이터 형식이 올바르지 않습니다.');
}

const hasWtem = new Set(obsIdx.stations.filter((s) => s.wtemLast !== null && s.wtemLast !== undefined).map((s) => s.code));

const stations = {};
const usedPoints = {};
let withCnt = 0;
let withoutCnt = 0;
for (const st of idx.stations) {
  if (typeof st.lat !== 'number' || typeof st.lot !== 'number') continue;
  let best = null;
  let bestKm = Infinity;
  for (const p of points) {
    const km = haversineKm(st.lat, st.lot, p.lat, p.lot);
    if (km < bestKm) {
      bestKm = km;
      best = p;
    }
  }
  if (!best) continue;
  const wtem = hasWtem.has(st.code);
  if (bestKm > (wtem ? KM_WITH_WTEM : KM_WITHOUT_WTEM)) continue;
  stations[st.code] = { point: best.code, km: Math.round(bestKm * 10) / 10 };
  usedPoints[best.code] = { name: best.name, lat: best.lat, lot: best.lot };
  if (wtem) withCnt++;
  else withoutCnt++;
}

const sortedObj = (o) => Object.fromEntries(Object.keys(o).sort().map((k) => [k, o[k]]));
const core = { rule: RULE, stations: sortedObj(stations), points: sortedObj(usedPoints) };
const mapPath = path.join(CUR_DIR, 'map.json');
let updated = new Date().toISOString().replace(/\.\d{3}Z$/, 'Z');
if (fs.existsSync(mapPath)) {
  try {
    const old = JSON.parse(fs.readFileSync(mapPath, 'utf8'));
    if (JSON.stringify({ rule: old.rule, stations: old.stations, points: old.points }) === JSON.stringify(core)) updated = old.updated;
  } catch { /* 새로 작성 */ }
}
fs.mkdirSync(CUR_DIR, { recursive: true });
fs.writeFileSync(mapPath, JSON.stringify({ updated, ...core }) + '\n', 'utf8');

console.log(`매핑된 관측소: ${Object.keys(stations).length}곳 (수온 있음 ${withCnt}, 없음 ${withoutCnt}) / 전체 ${idx.stations.length}곳`);
console.log(`필요한 조류 지점: ${Object.keys(usedPoints).length}곳 / 후보 ${points.length}곳`);
