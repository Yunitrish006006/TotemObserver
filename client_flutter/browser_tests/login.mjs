import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { readFile, mkdir, writeFile } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import { resolve, dirname, extname, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { chromium } from 'playwright';

const repository = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const root = resolve(repository, 'client_flutter/build/web');
const evidence = resolve(repository, 'build/account-browser-evidence');
await mkdir(evidence, { recursive: true });
const run = `${Date.now()}`;
const server = createServer(async (request, response) => {
  try {
    const path = decodeURIComponent(new URL(request.url, 'http://localhost').pathname);
    const file = resolve(root, `.${path === '/' ? '/index.html' : path}`);
    if (!file.startsWith(root + sep)) { response.writeHead(403).end(); return; }
    const types = { '.html': 'text/html', '.js': 'text/javascript', '.json': 'application/json', '.wasm': 'application/wasm' };
    response.setHeader('Content-Type', types[extname(file)] ?? 'application/octet-stream');
    response.end(await readFile(file));
  } catch { response.writeHead(404).end(); }
});
server.listen(0, '127.0.0.1');
await once(server, 'listening');
const origin = `http://127.0.0.1:${server.address().port}`;
const java = process.env.JAVA_HOME ? resolve(process.env.JAVA_HOME, 'bin/java') : 'java';
const fixture = spawn(java, [`@${resolve(repository, 'build/account-fixture.args')}`,
  resolve(evidence, `accounts-${run}.properties`), origin], { stdio: ['pipe', 'pipe', 'pipe'] });
let output = '';
fixture.stdout.on('data', chunk => { output += chunk.toString(); });
// Do not persist protocol messages, passwords or account records in test logs/artifacts.
fixture.stderr.on('data', () => {});
let browser;
const timeout = setTimeout(() => { fixture.kill('SIGTERM'); process.exitCode = 1; }, 120_000);
try {
  const until = Date.now() + 20_000;
  while (!output.includes('OBSERVER_ACCOUNT_FIXTURE_PORT=') && Date.now() < until) {
    if (fixture.exitCode !== null) throw new Error('Account fixture failed');
    await new Promise(resolve => setTimeout(resolve, 100));
  }
  const port = /OBSERVER_ACCOUNT_FIXTURE_PORT=(\d+)/.exec(output)?.[1];
  assert.ok(port, 'Account fixture did not start');
  const executablePath = process.env.CHROME_BIN || (existsSync('/usr/bin/chromium') ? '/usr/bin/chromium' : undefined);
  browser = await chromium.launch({ executablePath, headless: true, args: ['--no-sandbox'] });
  const page = await browser.newPage({ viewport: { width: 960, height: 900 } });
  page.setDefaultTimeout(15_000);
  const errors = [];
  page.on('pageerror', () => errors.push('browser runtime error'));
  await page.goto(origin, { waitUntil: 'networkidle' });
  await page.waitForFunction(() => document.querySelector('flt-semantics-placeholder') || document.querySelector('flt-semantics'));
  await page.evaluate(() => document.querySelector('flt-semantics-placeholder')?.click());

  async function fill(name, value) {
    const field = page.getByRole('textbox', { name: new RegExp(name) });
    await field.click();
    // Flutter installs its editable DOM input after processing the focus event.
    await page.waitForFunction(() => {
      const active = document.activeElement;
      return active instanceof HTMLInputElement || active instanceof HTMLTextAreaElement;
    });
    await page.keyboard.press('ControlOrMeta+A');
    await page.keyboard.insertText(value);
    await page.waitForFunction(length => document.activeElement?.value?.length === length, value.length);
    await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
  }
  async function label(text) {
    await page.waitForFunction(text => [...document.querySelectorAll('flt-semantics')]
      .some(node => (node.getAttribute('aria-label') ?? node.textContent ?? '').includes(text)), text);
  }
  const username = `test_${run}`;
  const password = 'browser-only-test-password';
  await page.screenshot({ path: resolve(evidence, 'login.png') });
  await fill('伺服器位址', `ws://127.0.0.1:${port}/observer/bridge`);
  await fill('帳號', username);
  await fill('密碼', password);
  await page.getByRole('button', { name: '建立帳號', exact: true }).click();
  await label('已連線');
  await label('已收到 1 次連線回應');
  await page.screenshot({ path: resolve(evidence, 'connected.png') });
  await page.getByRole('button', { name: '登出', exact: true }).click();
  await label('尚未連線');
  await fill('密碼', 'incorrect-test-password');
  await page.getByRole('button', { name: '登入', exact: true }).click();
  await label('帳號或密碼不正確');
  await fill('密碼', password);
  await page.getByRole('button', { name: '登入', exact: true }).click();
  await label('已連線');
  // Shutdown must revoke the currently authenticated browser session.
  if (!fixture.stdin.writableEnded) fixture.stdin.end('\n');
  await label('連線已結束');
  await page.setViewportSize({ width: 360, height: 800 });
  await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
  await page.waitForTimeout(250);
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth), 360,
    'Mobile page must not overflow horizontally');
  // Flutter scrolls inside its viewport; fullPage also includes offscreen editable DOM layers.
  await page.screenshot({ path: resolve(evidence, 'mobile-login.png') });
  assert.equal(errors.length, 0, 'Unexpected browser runtime errors');
  await writeFile(resolve(evidence, 'result.json'), JSON.stringify({
    passed: true, checks: ['register', 'login', 'authenticated-pong', 'logout', 'wrong-password', 'server-stop-revocation'],
    renderer: 'actual Flutter release build in Chromium', mobileViewportWidth: 360, gameplay: false,
  }, null, 2));
  console.log('Browser account flow passed (6 checks)');
} catch (error) {
  if (browser) {
    const page = browser.contexts()[0]?.pages()[0];
    if (page) await page.screenshot({ path: resolve(evidence, 'failure.png') }).catch(() => {});
  }
  await writeFile(resolve(evidence, 'result.json'), JSON.stringify({ passed: false }));
  throw error;
} finally {
  clearTimeout(timeout);
  await browser?.close();
  if (!fixture.stdin.writableEnded) fixture.stdin.end('\n');
  if (fixture.exitCode === null) {
    await Promise.race([once(fixture, 'exit'), new Promise(resolve => setTimeout(resolve, 5000))]);
    if (fixture.exitCode === null) fixture.kill('SIGTERM');
  }
  await new Promise(resolve => server.close(resolve));
}
