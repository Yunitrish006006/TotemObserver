// Browser input/DOM adapter fixture. This does not simulate Minecraft physics.
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { readFile, mkdir, writeFile } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import { resolve, dirname, extname, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { once } from 'node:events';
import { chromium } from 'playwright';
const repo = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const root = resolve(repo, 'client_flutter/build/web');
const evidence = resolve(repo, 'build/account-browser-evidence');
await mkdir(evidence, {recursive: true});
const http = createServer(async (req, res) => {
  try {
    const path = new URL(req.url, 'http://localhost').pathname;
    const file = resolve(root, '.' + (path === '/' ? '/index.html' : decodeURIComponent(path)));
    if (!file.startsWith(root + sep)) { res.writeHead(403).end(); return; }
    res.setHeader('Content-Type', ({'.html':'text/html','.js':'text/javascript','.wasm':'application/wasm','.json':'application/json'})[extname(file)] ?? 'application/octet-stream');
    res.end(await readFile(file));
  } catch { res.writeHead(404).end(); }
});
http.listen(0, '127.0.0.1'); await once(http, 'listening');
const browser = await chromium.launch({headless: true, args:['--no-sandbox'],
  executablePath: process.env.CHROME_BIN || (existsSync('/usr/bin/chromium') ? '/usr/bin/chromium' : undefined)});
let page;
const operationTypes = [];
try {
  page = await browser.newPage({viewport:{width:1100,height:1100}});
  const errors = []; page.on('pageerror', () => errors.push('runtime error'));
  const movements = []; let tick = 0, sectionRequests = 0, firstActiveSectionCount = null;
  let terrainSent = false;
  const hash = '0'.repeat(64), dimension = 'minecraft:overworld';
  await page.routeWebSocket('**/observer/bridge', socket => {
    const send = value => socket.send(JSON.stringify(value));
    send({type:'hello',protocol:1,authentication:true,registration:true,playerIdentityProtocol:1,
      playerAdmission:true,worldStateProtocol:1,worldBootstrapProtocol:1,worldRegistryProtocol:1,
      worldSectionProtocol:1,worldMovementProtocol:1,play:false});
    socket.onMessage(raw => {
      const message = JSON.parse(raw);
      operationTypes.push(message.type); if (operationTypes.length > 20) operationTypes.shift();
      const base = {protocol:1,seq:message.seq,sessionEpoch:42};
      if (['register','login'].includes(message.type)) {
        send({type:'authenticated',username:message.username,expiresInSeconds:900,
          playerUuid:'12345678-1234-1234-1234-123456789abc',playerName:'obs_123456781234',sessionEpoch:42,playerAttached:true,play:false});
      } else if (message.type === 'ping') send({type:'pong',seq:message.seq});
      else if (message.type === 'world_state') send({...base,type:'world_state',dimension,x:8,y:64,z:8,yaw:0,pitch:25});
      else if (message.type === 'world_bootstrap') send({...base,type:'world_bootstrap',subscriptionId:1,revision:1,
        dimension,minY:-64,height:384,centerChunkX:0,centerChunkZ:0,radius:2});
      else if (message.type === 'world_registry') send({...base,type:'world_registry',fingerprint:hash,offset:0,total:8,
        states:['minecraft:air','minecraft:stone','minecraft:stone','minecraft:stone','minecraft:stone','minecraft:stone','minecraft:stone','minecraft:stone']});
      else if (message.type === 'world_section') {
        sectionRequests++;
        if (message.sectionY === 3) terrainSent = true;
        for (let part=0;part<4;part++) {
          const ids = Buffer.alloc(1024);
          if (message.sectionY === 3 && part === 3) ids.fill(7,768);
          send({...base,type:'world_section',subscriptionId:1,revision:1,registryFingerprint:hash,dimension,
            chunkX:message.chunkX,chunkZ:message.chunkZ,sectionY:message.sectionY,part,parts:4,stateCount:1024,data:ids.toString('base64')});
        }
      } else if (message.type === 'world_movement') {
        assert.equal(Object.hasOwn(message,'x'),false); assert.equal(Object.hasOwn(message,'z'),false);
        if (message.forward && firstActiveSectionCount === null) firstActiveSectionCount = sectionRequests;
        movements.push({strafe:message.strafe,forward:message.forward,jump:message.jump,yaw:message.yaw,pitch:message.pitch});
        if (movements.length > 300) throw new Error('Unbounded movement fixture');
        send({...base,type:'world_movement',subscriptionId:1,revision:1,serverTick:++tick,
          applied:true,onGround:true,dimension,x:8,y:64,z:8,yaw:message.yaw/100,pitch:message.pitch/100});
      } else if (message.type === 'logout') socket.close();
      else throw new Error('Unexpected fixture operation');
    });
  });
  await page.goto(`http://127.0.0.1:${http.address().port}`, {waitUntil:'networkidle'});
  await page.waitForFunction(() => document.querySelector('flt-semantics-placeholder') || document.querySelector('flt-semantics'));
  await page.evaluate(() => document.querySelector('flt-semantics-placeholder')?.click());
  async function fill(name,value) {
    const field = page.getByRole('textbox',{name:new RegExp(name)});
    await field.waitFor();
    for (let attempt=0;attempt<4;attempt++) {
      await page.evaluate(() => document.activeElement?.blur());
      await field.click();
      try {
        await page.waitForFunction(() => document.activeElement instanceof HTMLInputElement || document.activeElement instanceof HTMLTextAreaElement,null,{timeout:2500});
        await page.keyboard.press('ControlOrMeta+A'); await page.keyboard.insertText(value);
        await page.waitForFunction(expected => document.activeElement?.value === expected,value,{timeout:2500});
        await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
        return;
      } catch { if (attempt===3) throw new Error('Flutter field activation failed'); }
    }
  }
  await fill('伺服器位址','ws://127.0.0.1:25580/observer/bridge');
  await fill('帳號','browser_input'); await fill('密碼','browser-only-test-password');
  await page.getByRole('button',{name:'建立帳號',exact:true}).click();
  const activate = page.getByRole('button',{name:'點擊操作世界',exact:true});
  await activate.waitFor();
  assert.equal(movements.length,0);
  await activate.click();
  await page.waitForFunction(() => document.pointerLockElement !== null);
  await page.waitForFunction(() => [...document.querySelectorAll('[aria-label]')].some(node => node.getAttribute('aria-label')?.includes('WASD')) || document.body.textContent.includes('WASD'));
  await page.keyboard.down('w'); await page.keyboard.down('Space');
  await page.mouse.move(500,400); await page.mouse.move(580,440);
  const until = Date.now()+5000;
  while (!movements.some(m => m.forward===1 && m.jump===1 && m.yaw!==0) && Date.now()<until) await page.waitForTimeout(50);
  if (!movements.some(m => m.forward===1 && m.jump===1 && m.yaw!==0)) {
    console.log(JSON.stringify({lastMovement:movements.slice(-6),pointerLocked:await page.evaluate(() => document.pointerLockElement !== null)}));
    await page.screenshot({path:resolve(evidence,'first-person-input-failure.png')});
  }
  assert.ok(movements.some(m => m.forward===1 && m.jump===1 && m.yaw!==0),'Captured DOM input must reach movement intent');
  await page.keyboard.up('w'); await page.keyboard.up('Space');
  await page.keyboard.press('Escape');
  await page.waitForFunction(() => document.pointerLockElement === null);
  const releasedAt = Date.now();
  while ((movements.at(-1)?.forward !== 0 || movements.at(-1)?.jump !== 0) && Date.now()-releasedAt < 5000) await page.waitForTimeout(50);
  assert.equal(movements.at(-1).forward,0); assert.equal(movements.at(-1).jump,0);
  assert.ok(firstActiveSectionCount < 43,'Movement must work while sections are still loading');
  const terrainDeadline = Date.now()+15000;
  while (!terrainSent && Date.now()<terrainDeadline) await page.waitForTimeout(50);
  assert.ok(terrainSent,'Terrain fixture must be delivered');
  await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
  await page.screenshot({path:resolve(evidence,'first-person-input-fixture.png')});
  await page.getByRole('button',{name:'登出',exact:true}).click();
  const count = movements.length; await page.waitForTimeout(300);
  assert.equal(movements.length,count); assert.equal(errors.length,0);
  await writeFile(resolve(evidence,'movement-input-result.json'),JSON.stringify({passed:true,
    fixture:'bounded protocol fixture; no Minecraft physics',checks:['actual-pointer-lock','WASD-jump','mouse-delta','movement-during-streaming','escape','disconnect'],playable:false},null,2));
  console.log('Browser pointer-lock input fixture passed (not a Minecraft playable smoke)');
} catch(error) {
  console.log(JSON.stringify({recentOperations:operationTypes}));
  await page?.screenshot({path:resolve(evidence,'first-person-input-failure.png')}).catch(()=>{});
  throw error;
} finally { await browser.close(); await new Promise(resolve => http.close(resolve)); }
