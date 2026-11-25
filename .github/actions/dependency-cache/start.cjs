'use strict';
const {spawn} = require('node:child_process');
const fs = require('node:fs/promises');
const path = require('node:path');
const {setTimeout: delay} = require('node:timers/promises');
const root = path.resolve(__dirname, '../../ci/dependency-cache');

async function start() {
  const mode = process.env.INPUT_MODE;
  if (mode === 'launch') return require(path.join(root, 'cli.cjs')).launchCloud();
  if (!['record', 'read'].includes(mode)) throw new Error('Unrecognized dependency proxy mode');
  if (mode === 'record' && (process.env.GITHUB_REF !== 'refs/heads/main' ||
      !['push', 'schedule', 'workflow_dispatch'].includes(process.env.GITHUB_EVENT_NAME))) throw new Error('Only main workers record cache receipts');
  const dir = await fs.mkdtemp(path.join(process.env.RUNNER_TEMP, 'dependency-proxy-'));
  const log = await fs.open(path.join(dir, 'service.log'), 'wx', 0o600);
  const child = spawn(process.execPath, [path.join(root, 'service.cjs'), mode, dir], {detached: true, stdio: ['ignore', log.fd, log.fd]});
  child.unref();
  await log.close();
  await fs.appendFile(process.env.GITHUB_STATE, `service_dir=${dir}\nservice_pid=${child.pid}\n`);
  for (let i = 0; i < 600; ++i) {
    const ready = await fs.readFile(path.join(dir, 'ready.json'), 'utf8').catch(() => null);
    if (ready) {
      for (const [key, value] of Object.entries(JSON.parse(ready))) {
        if (!/^[A-Za-z_][A-Za-z0-9_]*$/.test(key) || /[\r\n]/.test(value)) throw new Error('Invalid client environment');
        await fs.appendFile(process.env.GITHUB_ENV, `${key}=${value}\n`);
      }
      return;
    }
    const failed = await fs.readFile(path.join(dir, 'failed'), 'utf8').catch(() => null);
    if (failed) throw new Error(failed);
    try { process.kill(child.pid, 0); } catch { throw new Error('Dependency service exited during startup'); }
    await delay(200);
  }
  throw new Error('Timed out waiting for the dependency cache index');
}
start().catch(error => { console.error(error.message); process.exitCode = 1; });
