// Gothic II Mobile: площадка - снимки сцены (лист кадров) без человека за экраном.
//   node shots.js lab_out.html <сцена> out.png [t0 t1 кадров] [вид q|side|front|back|top] [ghost]
// Каждый кадр - отдельный снимок 3D-вида; лист собирает sheet.py
const { chromium } = require('playwright');
const path = require('path');
(async () => {
  const [html, scene, out, t0s, t1s, ns, cam, ghost] = process.argv.slice(2);
  const browser = await chromium.launch({ args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist'] });
  const W = Number(process.env.SHOT_W || 560), H = Number(process.env.SHOT_H || 440);
  const page = await browser.newPage({ viewport: { width: W, height: H }, deviceScaleFactor: 1 });
  await page.goto('file://' + path.resolve(html));
  await page.waitForFunction(() => window.lab && window.THREE);
  const info = await page.evaluate(n => window.lab.info(n), scene);
  const t0 = t0s !== undefined && t0s !== '-' ? Number(t0s) : info.t0, t1 = t1s !== undefined && t1s !== '-' ? Number(t1s) : info.t1;
  // SHOT_TIMES="0.8,0.9,1.0" - свои моменты вместо равных шагов
  const list = process.env.SHOT_TIMES ? process.env.SHOT_TIMES.split(',').map(Number) : null;
  const n = list ? list.length : Number(ns || 12);
  const files = [];
  for (let i = 0; i < n; i++) {
    const t = list ? list[i] : (n > 1 ? t0 + (t1 - t0) * i / (n - 1) : t0);
    await page.evaluate(([s, tt, c, g, nl]) => window.lab.set(s, tt, c, { ghost: g, bare: true, nolog: nl }), [scene, t, cam || 'q', ghost === 'ghost', !!process.env.SHOT_NOLOG]);
    await page.waitForTimeout(30);
    const f = out.replace(/\.png$/, '') + '_' + String(i).padStart(2, '0') + '.png';
    await page.screenshot({ path: f });
    files.push(f);
  }
  console.log(JSON.stringify({ files, events: info.events }));
  await browser.close();
})();
