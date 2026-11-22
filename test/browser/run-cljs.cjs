const { chromium } = require('@playwright/test');
const { spawn } = require('node:child_process');
const { mkdtemp, writeFile, rm } = require('node:fs/promises');
const { tmpdir } = require('node:os');
const { join, resolve } = require('node:path');
const net = require('node:net');
const { setTimeout: delay } = require('node:timers/promises');

function listening(port) {
  return new Promise((resolvePort, reject) => {
    const socket = net.connect({ port, host: '127.0.0.1' });
    socket.once('connect', () => { socket.destroy(); resolvePort(true); });
    socket.once('error', error => error.code === 'ECONNREFUSED' ? resolvePort(false) : reject(error));
  });
}

function stop(child) {
  if (!child.pid || child.exitCode !== null || child.signalCode !== null) return;
  try {
    process.kill(-child.pid, 'SIGTERM');
  } catch (error) {
    if (error.code !== 'ESRCH') throw error;
  }
}

async function run() {
  const port = 9016;
  if (await listening(port)) throw new Error(`ClojureScript test port ${port} is already in use`);
  const scratch = await mkdtemp(join(tmpdir(), 'bluegenes-cljs-'));
  const config = join(scratch, 'tests.edn');
  await writeFile(config, `#kaocha/v1
{:tests [{:id :unit-cljs :type :kaocha.type/cljs
          :cljs/repl-env cljs.repl.browser/repl-env
          :cljs/compiler-options {:launch-browser false :port ${port}}
          :cljs/timeout 60000}]}`);
  const child = spawn('lein', ['kaocha', '--config-file', config, ...process.argv.slice(2)], {
    cwd: resolve(__dirname, '../..'), stdio: 'inherit', detached: true,
  });
  let spawnError;
  child.once('error', error => { spawnError = error; });
  const completed = new Promise(resolveExit => child.once('close', resolveExit));
  const deadline = setTimeout(() => stop(child), 600000);
  const interrupt = () => stop(child);
  process.once('SIGINT', interrupt);
  process.once('SIGTERM', interrupt);
  let browser;
  try {
    const readyBy = Date.now() + 180000;
    while (!await listening(port)) {
      if (spawnError) throw spawnError;
      if (child.exitCode !== null || child.signalCode !== null || Date.now() > readyBy) {
        throw new Error('ClojureScript test server did not become ready');
      }
      await delay(250);
    }
    browser = await chromium.launch();
    const page = await browser.newPage();
    await page.goto(`http://127.0.0.1:${port}`);
    const code = await completed;
    if (code !== 0) throw new Error(`ClojureScript suite exited with ${code ?? child.signalCode}`);
  } finally {
    clearTimeout(deadline);
    process.removeListener('SIGINT', interrupt);
    process.removeListener('SIGTERM', interrupt);
    stop(child);
    if (browser) await browser.close();
    await rm(scratch, { recursive: true });
  }
}

run().catch(error => { console.error(error); process.exitCode = 1; });
