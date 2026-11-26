'use strict';
const fs = require('node:fs/promises');
const {randomUUID} = require('node:crypto');
const path = require('node:path');
const {setTimeout: delay} = require('node:timers/promises');
const {receiptPath} = require('../../ci/dependency-cache/cli.cjs');
const {readReceipt} = require('../../ci/dependency-cache/receipts.cjs');

async function finish() {
  const dir = process.env.STATE_service_dir;
  if (!dir) return;
  try {
    const pid = Number(process.env.STATE_service_pid);
    if (!Number.isSafeInteger(pid) || pid < 2) throw new Error('Invalid dependency proxy process');
    try { process.kill(pid, 'SIGTERM'); } catch (error) { if (error.code !== 'ESRCH') throw error; }
    for (let i = 0; ; ++i) {
      const failed = await fs.readFile(path.join(dir, 'failed'), 'utf8').catch(() => null);
      if (failed) throw new Error(failed);
      if (await fs.access(path.join(dir, 'done')).then(() => true, () => false)) break;
      if (i > 300) throw new Error('Timed out while finalizing dependency observations');
      await delay(200);
    }
    const metrics = await fs.readFile(path.join(dir,'openartifact-metrics.json'),'utf8').catch(() => null);
    if (metrics) console.log(`OpenArtifact storage ${metrics}`);
    const transfer = await fs.readFile(path.join(dir, 'transfer-metrics.json'), 'utf8').catch(() => null);
    if (transfer) {
      console.log(`Dependency archive and cold-origin transfers ${transfer}`);
      if (process.env.GITHUB_STEP_SUMMARY) {
        const { archiveTransfers, archivePayloadBytes, originLookups, originPayloadBytes, catalogObjects, restoredObjects } = JSON.parse(transfer);
        await fs.appendFile(process.env.GITHUB_STEP_SUMMARY, [
          '### Dependency proxy reads', '',
          '| Source | Transfers | Payload bytes |', '| --- | ---: | ---: |',
          `| GitHub cache archives | ${archiveTransfers} | ${archivePayloadBytes} |`,
          `| Public dependency origins | ${originLookups} | ${originPayloadBytes} |`, '',
          ...(catalogObjects === undefined ? [] : [`${restoredObjects} of ${catalogObjects} distinct catalog objects transferred to this job.`, '']),
        ].join('\n'));
      }
    }
    const serviceLog = await fs.readFile(path.join(dir, 'service.log'), 'utf8').catch(() => '');
    const gradle = serviceLog.match(/^Gradle remote cache (\{[^\n]+\})$/m);
    if (gradle) console.log(`Gradle remote cache ${gradle[1]}`);
    const misses = await fs.readFile(path.join(dir, 'misses.json'), 'utf8').catch(() => null);
    if (misses) console.error(`Missing published dependencies: ${JSON.parse(misses).join(', ')}`);
    if (process.env.INPUT_MODE !== 'record') return;
    const text = await fs.readFile(path.join(dir, 'receipt.json'));
    const observations = readReceipt(text);
    if (!observations.length) return;
    const cache = await require('../../ci/dependency-cache/github-store.cjs').loadToolkit();
    const key = `migrations-main-requests-v1-${process.env.GITHUB_RUN_ID}-${process.env.GITHUB_RUN_ATTEMPT}-${randomUUID()}`;
    await fs.mkdir(path.dirname(receiptPath), {recursive: true});
    await fs.writeFile(receiptPath, text, {mode: 0o600});
    try {
      if (await cache.saveCache([receiptPath], key) < 0) throw new Error('Could not save main worker observations');
      console.log(`Saved ${observations.length} main worker observations.`);
    } finally { await fs.rm(receiptPath, {force: true}); }
  } finally {
    const log = await fs.readFile(path.join(dir, 'service.log'), 'utf8').catch(() => '');
    const failures = log.match(/^Cache (?:archive|metadata) at [a-z0-9.-]+ failed \([a-z0-9_, ]{1,120}\)\.$/gim) || [];
    for (const reason of new Set(failures.slice(-10))) console.warn(reason);
    await fs.rm(dir, {recursive: true, force: true});
  }
}
finish().catch(error => { console.error(error.message); process.exitCode = 1; });
