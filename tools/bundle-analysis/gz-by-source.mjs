import { readFileSync } from 'node:fs';
import { gzipSync } from 'node:zlib';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
// Run after ./gradlew :web:jsBrowserProductionWebpack. Attributes the bundle to npm packages and "everything else" (Kotlin code and
// its runtime) through the source map, and gzips each part on its own; the parts add up to about the whole file.
const dir = join(dirname(fileURLToPath(import.meta.url)), '../../web/build/kotlin-webpack/js/productionExecutable/') + '/';
const code = readFileSync(dir + 'circles.js', 'utf8');
const map = JSON.parse(readFileSync(dir + 'circles.js.map', 'utf8'));
const B64 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
function decode(seg) { const out = []; let shift = 0, value = 0; for (const ch of seg) { const d = B64.indexOf(ch); value += (d & 31) << shift; if (d & 32) shift += 5; else { out.push(value & 1 ? -(value >> 1) : value >> 1); value = 0; shift = 0; } } return out; }
const lines = code.split('\n'); const parts = new Map(); let src = 0;
const group = (n) => { const m = n.match(/node_modules\/((?:@[^/]+\/)?[^/]+)/); return m ? 'npm:' + m[1] : 'kotlin/other'; };
map.mappings.split(';').forEach((lineMap, lineNo) => {
  const text = lines[lineNo] ?? ''; let col = 0; const segs = [];
  for (const s of lineMap.split(',')) { if (!s) continue; const f = decode(s); col += f[0]; if (f.length > 1) { src += f[1]; segs.push([col, src]); } else segs.push([col, -1]); }
  segs.forEach(([c, si], i) => { const end = i + 1 < segs.length ? segs[i + 1][0] : text.length; const g = si < 0 ? 'unmapped' : group(map.sources[si]); parts.set(g, (parts.get(g) ?? '') + text.slice(c, end)); });
});
let sum = 0; for (const [g, t] of [...parts].sort((a, b) => b[1].length - a[1].length)) { const gz = gzipSync(t, { level: 9 }).length; sum += gz; console.log(String(t.length).padStart(8), String(gz).padStart(7), g); }
console.log('sum of separate gz', sum, 'whole', gzipSync(code, { level: 9 }).length);
