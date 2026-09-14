// Real Chromium -> production Observer bridge -> dedicated Minecraft ServerPlayer.
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { readFile, mkdir, writeFile } from 'node:fs/promises';
import { existsSync, createWriteStream } from 'node:fs';
import { resolve, dirname, extname, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { once } from 'node:events';
import { spawn } from 'node:child_process';
import { chromium } from 'playwright';
const repo = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const root = resolve(repo, 'client_flutter/build/web');
const evidence = resolve(repo, 'build/browser-playable/results');
const runDir = resolve(repo, 'build/browser-playable/server');
// A previous world/account is not valid evidence for a fresh admission. Never delete it implicitly.
assert.equal(existsSync(evidence) || existsSync(runDir),false,'Archive the previous build/browser-playable directory before running again');
await mkdir(evidence,{recursive:true}); await mkdir(runDir,{recursive:true});
await writeFile(resolve(runDir,'eula.txt'),'eula=true\n');
await writeFile(resolve(runDir,'server.properties'),[
  'online-mode=false','server-ip=127.0.0.1','server-port=0','spawn-protection=16',
  'gamemode=survival','difficulty=peaceful','view-distance=2','simulation-distance=2',
  'max-players=2','level-type=minecraft:flat',
  'generator-settings={"layers":[{"block":"minecraft:bedrock","height":1}],"biome":"minecraft:plains"}',
  'generate-structures=false','enable-status=false','sync-chunk-writes=false',''
].join('\n'));
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
const args = ['--project-cache-dir','build/browser-playable/gradle',
  `-PbrowserOrigin=http://127.0.0.1:${http.address().port}`];
if (process.env.TOTEM_CORE_JAR) args.push(`-PtotemCoreJar=${process.env.TOTEM_CORE_JAR}`);
args.push('runBrowserFixtureServer','--no-daemon','--console=plain');
const log = createWriteStream(resolve(evidence,'server.log'));
const server = spawn('./gradlew',args,{cwd:repo,detached:true,stdio:['ignore','pipe','pipe']});
server.stdout.pipe(log,{end:false}); server.stderr.pipe(log,{end:false});
let exited = false; let spawnError;
server.on('exit',()=>{exited=true;}); server.on('error',error=>{spawnError=error;exited=true;});
let browser, page;
async function waitFor(check,label,timeout=15000) {
  const end=Date.now()+timeout;
  while (Date.now()<end) {
    if (spawnError) throw spawnError;
    if (exited) throw new Error(`Minecraft exited during ${label}; inspect server.log`);
    if (await check()) return;
    await new Promise(resolve=>setTimeout(resolve,50));
  }
  throw new Error(`Timed out: ${label}`);
}
async function state() {
  try {return JSON.parse(await readFile(resolve(evidence,'state.json'),'utf8'));}
  catch (error) {if(error.code==='ENOENT') return {}; throw error;}
}
try {
  await waitFor(()=>existsSync(resolve(evidence,'ready.json')),'Minecraft bridge startup',180000);
  const ready=JSON.parse(await readFile(resolve(evidence,'ready.json'),'utf8'));
  const placementProtection=JSON.parse(await readFile(resolve(evidence,'placement-protection-result.json'),'utf8'));
  assert.equal(placementProtection.passed,true);
  const profile=resolve(repo,'build/browser-playable/chromium-profile');
  const browserOptions={headless:true,args:['--no-sandbox'],viewport:{width:1100,height:1100},
    executablePath:process.env.CHROME_BIN || (existsSync('/usr/bin/chromium')?'/usr/bin/chromium':undefined)};
  browser=await chromium.launchPersistentContext(profile,browserOptions);
  page=await browser.newPage();
  const errors=[], corrections=[], bootstraps=[], requests=[], uses=[], useRequests=[], outlines=[], hotbars=[];
  const destroys=[], destroyRequests=[];
  let sections=0; const floorParts=new Map();
  page.on('pageerror',()=>errors.push('runtime error'));
  // Passive observation only: no routing, mocked response, or injected input protocol.
  page.on('websocket',socket=>{
    socket.on('framereceived',event=>{
      const message=JSON.parse(event.payload.toString());
      if(message.type==='world_movement') corrections.push(message);
      if(message.type==='world_block_destroy') {destroys.push(message);assert.ok(destroys.length<100);}
      if(message.type==='world_hotbar') {hotbars.push(message);assert.ok(hotbars.length<100,'Bounded hotbar observations');}
      if(message.type==='world_target_outline') { outlines.push(message); assert.ok(outlines.length<100,'Bounded outline observations'); }
      if(message.type==='world_bootstrap') bootstraps.push(message);
      if(message.type==='world_block_use') {
        uses.push(message);
        assert.ok(uses.length<10,'Bounded use acknowledgments');
      }
      if(message.type==='world_section') {
        sections++;
        if(message.sectionY===3) {
          const key=`${message.revision}:${message.chunkX}:${message.chunkZ}`;
          if(!floorParts.has(key)) floorParts.set(key,new Set());
          floorParts.get(key).add(message.part);
        }
      }
      assert.ok(corrections.length<2000 && bootstraps.length<30,'Bounded smoke capture');
    });
    socket.on('framesent',event=>{
      const message=JSON.parse(event.payload.toString());
      if(message.type==='world_block_destroy') {
        assert.deepEqual(Object.keys(message).sort(),
          ['type','protocol','seq','sessionEpoch','subscriptionId','revision','dimension','operation','action'].sort());
        destroyRequests.push(message); assert.ok(destroyRequests.length<100);
      }
      if(message.type==='world_block_use') {
        assert.deepEqual(Object.keys(message).sort(),
          ['type','protocol','seq','sessionEpoch','subscriptionId','revision','dimension'].sort());
        useRequests.push(message);
        assert.ok(useRequests.length<10,'Bounded use requests');
      }
      if(message.type==='world_movement') {
        assert.equal(Object.hasOwn(message,'x'),false);
        assert.equal(Object.hasOwn(message,'y'),false);
        assert.equal(Object.hasOwn(message,'z'),false);
        requests.push(message);
      }
    });
  });
  await page.goto(`http://127.0.0.1:${http.address().port}`,{waitUntil:'networkidle'});
  await page.waitForFunction(()=>document.querySelector('flt-semantics-placeholder')||document.querySelector('flt-semantics'));
  await page.evaluate(()=>document.querySelector('flt-semantics-placeholder')?.click());
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
  await fill('伺服器位址',`ws://127.0.0.1:${ready.port}/observer/bridge`);
  await fill('帳號','playable_smoke'); await fill('密碼','isolated-browser-test-password');
  await page.getByRole('button',{name:'建立帳號',exact:true}).click();
  const activate=page.getByRole('button',{name:'點擊操作世界',exact:true});
  await activate.waitFor({timeout:30000});
  await waitFor(async()=> (await state()).players===1,'real player admission');
  const initial=await state();
  assert.ok(Math.abs(initial.x-8.5)<1 && Math.abs(initial.z-8.5)<1,'Deterministic vanilla spawn');
  await waitFor(()=>floorParts.get('1:0:0')?.size===4,'complete below-player floor section',30000);
  // Give the independent registry hydrator time to resolve the floor's canonical state.
  // The screenshot is reviewed as rendered evidence; snapshot arrival alone does not prove visibility.
  await page.waitForTimeout(1500);
  await page.waitForFunction(() => [...document.querySelectorAll('[aria-label]')]
    .some(node => node.getAttribute('aria-label')?.includes('準星選取')) || document.body.textContent.includes('準星選取'));
  await page.screenshot({path:resolve(evidence,'terrain.png')});
  const activationBox=await activate.boundingBox();
  let mouseX=activationBox.x+activationBox.width/2, mouseY=activationBox.y+activationBox.height/2;
  await activate.click();
  await page.waitForFunction(()=>document.pointerLockElement!==null);
  await page.waitForFunction(()=>[...document.querySelectorAll('[aria-label]')].some(node=>node.getAttribute('aria-label')?.includes('WASD')) || document.body.textContent.includes('WASD'));
  // Aim through real pointer-lock mouse deltas; corrections alone decide when aim has settled.
  const wrap=angle=>((angle+180)%360+360)%360-180;
  async function aim(yaw,pitch) {
    for(let attempt=0;attempt<12;attempt++) {
      const before=corrections.at(-1);
      if(!before) {await waitFor(()=>corrections.length>0,'initial camera correction'); continue;}
      const dyaw=wrap(yaw-before.yaw), dpitch=pitch-before.pitch;
      if(Math.abs(dyaw)<0.5 && Math.abs(dpitch)<0.5) return;
      mouseX+=Math.max(-150,Math.min(150,dyaw/0.15));
      mouseY+=Math.max(-150,Math.min(150,dpitch/0.15));
      await page.mouse.move(mouseX,mouseY);
      await waitFor(()=>corrections.at(-1)?.seq>before.seq &&
        (Math.abs(wrap(corrections.at(-1).yaw-before.yaw))>0.05 ||
         Math.abs(corrections.at(-1).pitch-before.pitch)>0.05),'mouse aim correction');
    }
    throw new Error('Could not settle authoritative use aim');
  }
  await waitFor(()=>hotbars.some(h=>h.outcome==='snapshot'),'initial authoritative hotbar');
  const firstHotbar=hotbars.find(h=>h.outcome==='snapshot');
  assert.equal(firstHotbar.selected,0); assert.equal(firstHotbar.slots[0].item,'minecraft:stick');
  assert.equal(firstHotbar.slots[0].count,7);
  await page.keyboard.press('2');
  await waitFor(()=>hotbars.some(h=>h.outcome==='selected' && h.selected===1),'number key server confirmation');
  await waitFor(async()=>{const s=await state();return s.selectedSlot===1 && s.mainHandEmpty && s.slotZeroCount===7;},
    'real selected empty hand and conserved inventory');
  await page.waitForFunction(()=>document.body.textContent.includes('快捷列 2/9 · 空手') ||
    [...document.querySelectorAll('[aria-label]')].some(n=>n.getAttribute('aria-label')?.includes('快捷列 2/9 · 空手')));
  await page.screenshot({path:resolve(evidence,'hotbar-confirmed.png')});
  const useOrigin=await state();
  assert.ok(useOrigin.mainHandEmpty && useOrigin.offHandEmpty,'Server-confirmed empty slot');
  assert.equal(useOrigin.leverPowered,false);
  const dx=10.5-useOrigin.x, dz=10.75-useOrigin.z;
  await aim(-Math.atan2(dx,dz)*180/Math.PI,
    Math.atan2(useOrigin.y+1.62-65.5,Math.hypot(dx,dz))*180/Math.PI);
  await waitFor(()=>outlines.some(o=>o.target?.x===10 && o.target?.y===65 && o.target?.z===10),
    'server lever outline');
  await page.waitForFunction(()=>[...document.querySelectorAll('flt-semantics')]
    .some(node=>[node.getAttribute('aria-label'),node.textContent]
      .some(label=>label?.includes('3D 偵錯地形，伺服器選取輪廓'))));
  const leverOutline=outlines.findLast(o=>o.target?.x===10 && o.target?.y===65 && o.target?.z===10);
  assert.ok(leverOutline.target.boxes.length>0 && leverOutline.target.boxes.length<=16);
  assert.ok(leverOutline.target.boxes.every(b=>b.length===6 && b[3]-b[0]<1),'Actual partial lever outline');
  await page.screenshot({path:resolve(evidence,'server-lever-outline.png')});
  const beforeUseRevision=bootstraps.at(-1).revision;
  await page.mouse.down({button:'right'});
  await page.mouse.up({button:'right'});
  await waitFor(()=>uses.length===1,'browser use acknowledgment');
  assert.equal(uses[0].outcome,'applied');
  assert.equal(uses[0].refreshRequired,true);
  assert.equal(uses[0].seq,useRequests[0].seq);
  assert.equal(uses[0].revision,beforeUseRevision);
  await waitFor(async()=> (await state()).leverPowered===true,'real lever powered by browser');
  await waitFor(()=>bootstraps.at(-1).revision>beforeUseRevision,'post-use authoritative refresh');
  await page.screenshot({path:resolve(evidence,'after-block-use.png')});
  const digOrigin=await state();
  assert.equal(digOrigin.miningTargetRemoved,false);
  await aim(-Math.atan2(8.5-digOrigin.x,11.5-digOrigin.z)*180/Math.PI,
    Math.atan2(digOrigin.y+1.62-64.5,Math.hypot(8.5-digOrigin.x,11.5-digOrigin.z))*180/Math.PI);
  await waitFor(()=>outlines.some(o=>o.target?.x===8 && o.target?.y===64 && o.target?.z===11),'server dirt outline');
  const beforeDigRevision=bootstraps.at(-1).revision;
  await page.mouse.down({button:'left'});
  await waitFor(()=>destroys.some(d=>d.outcome==='changed'),'real browser survival mining');
  await page.mouse.up({button:'left'});
  assert.ok(destroys.some(d=>d.outcome==='active' && d.progress>0 && d.progress<1),'Server mining progress');
  assert.ok(destroyRequests.some(d=>d.action==='hold'),'Physical hold renews operation');
  await waitFor(async()=> (await state()).miningTargetRemoved===true,'real server dirt removed');
  await waitFor(()=>bootstraps.at(-1).revision>beforeDigRevision,'post-mining world refresh');
  const digBootstrap=bootstraps.at(-1);
  await waitFor(()=>floorParts.get(`${digBootstrap.revision}:0:0`)?.size===4,
    'post-mining floor section',30000);
  await page.waitForTimeout(500);
  await page.screenshot({path:resolve(evidence,'after-block-destroy.png')});
  await aim(0,25);
  await page.keyboard.down('w');
  await waitFor(async()=> (await state()).z>13.5,'walk to wall');
  await page.waitForTimeout(700);
  const wall=await state(); assert.ok(wall.z<13.71,'Vanilla collision must stop at the wall');
  await page.keyboard.down('Space');
  await waitFor(async()=> (await state()).y>64.5,'real jump');
  const jumped=await state(); await page.keyboard.up('Space'); await page.keyboard.up('w');
  await waitFor(async()=> (await state()).onGround,'landing');
  // Vanilla yaw zero faces south; A moves east, around the wall end at x=16.
  await page.keyboard.down('a');
  await waitFor(async()=> (await state()).x>17,'strafe around wall');
  await page.keyboard.up('a'); await page.keyboard.down('w');
  await waitFor(async()=> (await state()).z>18,'cross chunk boundary');
  await page.keyboard.up('w');
  await waitFor(()=>bootstraps.some(b=>b.centerChunkZ>=1),'moving authoritative window');
  const beforeLookSequence=corrections.at(-1).seq;
  await page.mouse.move(500,400); await page.mouse.move(530,620);
  await waitFor(()=>corrections.some(c=>c.seq>beforeLookSequence && Math.abs(c.yaw)>1),'new mouse look acknowledged');
  await page.keyboard.press('Escape');
  await page.waitForFunction(()=>document.pointerLockElement===null);
  await waitFor(()=>requests.at(-1)?.forward===0 && requests.at(-1)?.strafe===0,'release input');
  await page.waitForTimeout(500);
  const final=await state();
  await waitFor(()=>corrections.some(c=>Math.abs(c.x-final.x)<0.01 && Math.abs(c.z-final.z)<0.01),'correction matches real server position');
  const finalBootstrap=bootstraps.at(-1);
  const finalFloor=`${finalBootstrap.revision}:${Math.floor(final.x/16)}:${Math.floor(final.z/16)}`;
  await waitFor(()=>floorParts.get(finalFloor)?.size===4,'streamed floor in the new world window',30000);
  await page.waitForTimeout(1500);
  await page.screenshot({path:resolve(evidence,'after-movement.png')});
  await page.getByRole('button',{name:'登出',exact:true}).click();
  await waitFor(async()=> (await state()).players===0,'logout releases real ServerPlayer');
  assert.equal(errors.length,0);
  const cached=await page.evaluate(()=>new Promise((resolve,reject)=>{
    const open=indexedDB.open('totem-observer-registry-v1',1);
    open.onerror=()=>reject(new Error('Persistent registry missing'));
    open.onsuccess=()=>{const db=open.result;
      const request=db.transaction('cache').objectStore('cache').get('latest');
      request.onerror=()=>{db.close();reject(new Error('Persistent registry read failed'));};
      request.onsuccess=()=>{db.close();resolve(request.result);};
    };
  }));
  assert.equal(cached.version,1);
  assert.ok(cached.pages.length>1 && cached.pages.length<=256,'Bounded persisted decoded pages');
  const savedOffsets=new Set(cached.pages.map(p=>p.offset));
  assert.ok(cached.pages.some(p=>p.offset>0 && p.states.includes('minecraft:cobblestone')),
    'The floor descriptor must be persisted on a nonzero page');
  await browser.close();
  browser=await chromium.launchPersistentContext(profile,browserOptions);
  page=await browser.newPage();
  const reopenedRequests=[]; let reopenedSections=0, validatedRegistry=false;
  const secondCorrectionStart=corrections.length;
  page.on('pageerror',()=>errors.push('reopened runtime error'));
  page.on('websocket',socket=>{
    socket.on('framesent',event=>{
      const m=JSON.parse(event.payload.toString());
      if(m.type==='world_registry') {reopenedRequests.push(m.offset);assert.ok(reopenedRequests.length<256);}
    });
    socket.on('framereceived',event=>{
      const m=JSON.parse(event.payload.toString());
      if(m.type==='world_registry' && m.offset===0) {
        assert.equal(m.fingerprint,cached.fingerprint); validatedRegistry=true;
      }
      if(m.type==='world_section') reopenedSections++;
      if(m.type==='world_movement') corrections.push(m);
    });
  });
  await page.goto(`http://127.0.0.1:${http.address().port}`,{waitUntil:'networkidle'});
  await page.waitForFunction(()=>document.querySelector('flt-semantics-placeholder')||document.querySelector('flt-semantics'));
  await page.evaluate(()=>document.querySelector('flt-semantics-placeholder')?.click());
  await fill('伺服器位址',`ws://127.0.0.1:${ready.port}/observer/bridge`);
  await fill('帳號','playable_smoke'); await fill('密碼','isolated-browser-test-password');
  await page.getByRole('button',{name:'登入',exact:true}).click();
  await page.getByRole('button',{name:'點擊操作世界',exact:true}).waitFor({timeout:30000});
  await waitFor(()=>validatedRegistry && reopenedSections>=4,'reopened authenticated world',30000);
  const reopenedActivate=page.getByRole('button',{name:'點擊操作世界',exact:true});
  const reopenedBox=await reopenedActivate.boundingBox();
  mouseX=reopenedBox.x+reopenedBox.width/2; mouseY=reopenedBox.y+reopenedBox.height/2;
  await reopenedActivate.click();
  await page.waitForFunction(()=>document.pointerLockElement!==null);
  await waitFor(()=>corrections.length>secondCorrectionStart,'reopened camera correction');
  await aim(0,65);
  await page.waitForFunction(()=>[...document.querySelectorAll('flt-semantics')]
    .some(n=>[n.getAttribute('aria-label'),n.textContent].some(v=>v?.includes('準星選取 minecraft:cobblestone'))));
  await page.keyboard.press('Escape');
  await page.waitForFunction(()=>document.pointerLockElement===null);
  assert.ok(reopenedRequests.includes(0),'Fresh server fingerprint must be checked');
  assert.ok(reopenedRequests.every(offset=>offset===0 || !savedOffsets.has(offset)),
    'Browser restart must reuse previously decoded registry pages');
  assert.equal(errors.length,0);
  await writeFile(resolve(evidence,'persistent-registry-result.json'),JSON.stringify({passed:true,
    storedPages:cached.pages.length,reopenedRequests,reopenedSections},null,2));
  await page.getByRole('button',{name:'登出',exact:true}).click();
  await waitFor(async()=> (await state()).players===0,'reopened logout releases player');
  await writeFile(resolve(evidence,'result.json'),JSON.stringify({passed:true,fixture:ready.fixture,
    checks:['real-admission','terrain-snapshots','visible-target-selection','pointer-lock','WASD','wall-collision','jump-land',
      'mouse-look','authoritative-correction','cross-chunk-window','logout-release',
      'browser-restart-persistent-registry','server-partial-lever-outline','number-key-hotbar','conserved-inventory','browser-right-click-use','real-lever-powered','post-use-revision-refresh',
      'browser-left-hold-mining','server-mining-progress','real-dirt-removed','post-mining-revision-refresh',
      'dedicated-placement-spawn-protection'],
    initial,wall,jumped,final,uses,destroys,destroyRequests,leverOutline,hotbars,sectionParts:sections,revisions:bootstraps.map(b=>b.revision)},null,2));
  console.log('Real browser / Minecraft movement, block-use and mining smoke passed');
} catch(error) {
  const selectionLabels = await page?.evaluate(()=>[...document.querySelectorAll('flt-semantics')]
    .flatMap(n=>[n.getAttribute('aria-label'),n.textContent]).filter(v=>v?.includes('3D 偵錯地形')).slice(0,8).map(v=>v.slice(0,256))).catch(()=>[]);
  console.error('Bounded debug selection labels:', JSON.stringify(selectionLabels));
  await page?.screenshot({path:resolve(evidence,'failure.png')}).catch(()=>{});
  throw error;
} finally {
  await browser?.close();
  await writeFile(resolve(evidence,'stop'),'stop\n');
  const end=Date.now()+15000;
  while(!exited && Date.now()<end) await new Promise(resolve=>setTimeout(resolve,100));
  if(!exited) {try {process.kill(-server.pid,'SIGTERM');} catch(error) {if(error.code!=='ESRCH') throw error;}}
  log.end(); await new Promise(resolve=>http.close(resolve));
}
