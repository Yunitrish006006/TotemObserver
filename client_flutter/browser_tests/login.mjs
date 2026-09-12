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
  page.setDefaultTimeout(30_000);
  const errors = [];
  page.on('pageerror', () => errors.push('browser runtime error'));
  const socketFrames = [];
  const compact = (value) => String(value ?? '').replace(/\s+/g, '');
  const socketTypeSeen = (type) => socketFrames.some((frame) => frame.direction === 'received' && frame.type === type);
  const socketClosed = () => socketFrames.some((frame) => frame.type === 'closed');
  const hasDomCandidates = async (candidates) => {
    for (const candidate of candidates) {
      try {
        if (await page.getByText(candidate, { exact: false }).isVisible()) return true;
      } catch {}
    }

    const text = await page.evaluate((tokens) => {
      const compactLocal = (value) => String(value ?? '').replace(/\s+/g, '');
      const bodyText = compactLocal(document.body?.textContent ?? '');
      const semanticsText = compactLocal(Array.from(document.querySelectorAll('[aria-label], flt-semantics, flt-semantics-placeholder'))
        .map((node) => (node.getAttribute?.('aria-label') ?? '') + (node.textContent ?? ''))
        .join('\n'));
      return `${bodyText}\n${semanticsText}`;
    }, candidates);
    return candidates.some((candidate) => text.includes(compact(candidate)));
  };
  const protocolMatch = (text) => {
    if (text.includes('尚未連線')) return socketTypeSeen('logged_out') || socketClosed();
    if (text.includes('連線已結束')) return socketClosed();
    if (text.includes('帳號或密碼不正確')) return socketTypeSeen('auth_failed') || socketClosed();
    if (text.includes('已收到')) return socketTypeSeen('pong');
    if (text.includes('已連線')) return socketTypeSeen('authenticated');
    return false;
  };

  await page.goto(origin, { waitUntil: 'networkidle' });
  page.on('websocket', (socket) => {
    if (!socket.url().includes('/observer/bridge')) return;
    socket.on('framesent', (frame) => {
      if (typeof frame.payload === 'string') {
        const match = /"type"\s*:\s*"([^"]+)"/.exec(frame.payload);
        socketFrames.push({ direction: 'sent', type: match?.[1] ?? frame.payload, raw: frame.payload });
      }
    });
    socket.on('framereceived', (frame) => {
      if (typeof frame.payload === 'string') {
        const match = /"type"\s*:\s*"([^"]+)"/.exec(frame.payload);
        socketFrames.push({ direction: 'received', type: match?.[1] ?? frame.payload, raw: frame.payload });
      }
    });
    socket.on('close', () => socketFrames.push({ direction: 'closed', type: 'closed', raw: '' }));
  });
  await page.waitForFunction(() => document.querySelector('flt-semantics-placeholder') || document.querySelector('flt-semantics'));
  await page.evaluate(() => document.querySelector('flt-semantics-placeholder')?.click());

  async function activateEditable(field, name) {
    let lastError;
    for (let attempt = 1; attempt <= 4; attempt++) {
      // Flutter can leave the previous hidden editor focused while rebuilding semantics.
      // Blurring first lets us prove that this click activated a fresh editable target.
      await page.evaluate(() => {
        const active = document.activeElement;
        if (active instanceof HTMLElement) active.blur();
      });
      await field.click();
      try {
        await page.waitForFunction(() => {
          const active = document.activeElement;
          return (active instanceof HTMLInputElement || active instanceof HTMLTextAreaElement)
            && !active.disabled && !active.readOnly;
        }, null, { timeout: 2_500 });
        return;
      } catch (error) {
        lastError = error;
        await page.waitForTimeout(100 * attempt);
      }
    }
    throw new Error(`Flutter editor did not activate for "${name}" after 4 focus attempts`, { cause: lastError });
  }

  async function fill(name, value) {
    const field = page.getByRole('textbox', { name: new RegExp(name) });
    await field.waitFor({ state: 'visible' });
    await activateEditable(field, name);
    await page.keyboard.press('ControlOrMeta+A');
    await page.keyboard.insertText(value);
    await page.waitForFunction(expected => document.activeElement?.value === expected, value);
    await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
  }
  async function label(text) {
    const normalized = text.replace(/\s+/g, '');
    const fallback = [
      text,
      normalized,
      normalized.replace(/[^\w\u4e00-\u9fff]+/g, ''),
      normalized.replace(/\W+/g, ''),
      text.includes('已收到') ? '已收到' : null,
    ].filter(Boolean);
    const candidates = [...new Set(fallback)];
    const deadline = Date.now() + 45_000;
    while (Date.now() < deadline) {
      if (await hasDomCandidates(candidates)) return;
      if (protocolMatch(text)) return;
      await page.waitForTimeout(150);
    }
    const haystack = await page.evaluate(() => {
      const compactLocal = (value) => String(value ?? '').replace(/\s+/g, '');
      const bodyText = compactLocal(document.body?.textContent ?? '');
      const semanticsText = compactLocal(Array.from(document.querySelectorAll('[aria-label], flt-semantics, flt-semantics-placeholder'))
        .map((node) => (node.getAttribute?.('aria-label') ?? '') + (node.textContent ?? ''))
        .join('\n'));
      return `${bodyText}\n${semanticsText}`;
    });
    throw new Error(`Label "${text}" not observed in 45s`);
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
