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
    try {
      let alive = true;
      try { process.kill(pid, 'SIGTERM'); } catch (error) { if (error.code !== 'ESRCH') throw error; alive = false; }
      for (let i = 0; ; ++i) {
        const failed = await fs.readFile(path.join(dir, 'failed'), 'utf8').catch(() => null);
        if (failed) throw new Error(failed);
        if (await fs.access(path.join(dir, 'done')).then(() => true, () => false)) break;
        if (!alive) throw new Error('Dependency broker exited before it finalized its observations');
        if (i > 300) throw new Error('Timed out while finalizing dependency observations');
        await delay(200);
      }
    } catch (error) {
      // A broker crash must still fail the job. Recover the paths already logged so
      // the protected publisher can independently resolve them for the next run.
      console.error(error.message);
      process.exitCode = 1;
      const journal = await fs.readFile(path.join(dir, 'origin-requests.jsonl'), 'utf8');
      const accessed = Date.now();
      const keys = new Set();
      for (const line of journal.split('\n').slice(0, -1)) {
        if (line) keys.add(JSON.parse(line).key);
      }
      if (keys.size) {
        const text = JSON.stringify(Array.from(keys, key => ({key, accessed})));
        readReceipt(text);
        try {
          await fs.writeFile(path.join(dir, 'receipt.json'), text, {flag: 'wx', mode: 0o600});
        } catch (writeError) {
          if (writeError.code !== 'EEXIST') throw writeError;
        }
      }
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
    // Retain digest-level evidence in the job log after the temporary service directory is removed.
    for (const line of serviceLog.split('\n')) {
      if (!line.startsWith('Dependency cache transfer ')) continue;
      const record = JSON.parse(line.slice('Dependency cache transfer '.length));
      if (/^(object|pack)-[a-f0-9]{64}$/.test(record.archive) && Number.isSafeInteger(record.bytes) &&
          Array.isArray(record.digests) && record.digests.every(digest => /^[a-f0-9]{64}$/.test(digest))) {
        console.log('Dependency cache transfer ' + JSON.stringify({archive:record.archive, bytes:record.bytes, digests:record.digests}));
      }
    }
    const gradle = serviceLog.match(/^Gradle remote cache (\{[^\n]+\})$/m);
    if (gradle) console.log(`Gradle remote cache ${gradle[1]}`);
    const misses = await fs.readFile(path.join(dir, 'misses.json'), 'utf8').catch(() => null);
    if (misses) console.error(`Missing published dependencies: ${JSON.parse(misses).join(', ')}`);
    const trustedMain = process.env.GITHUB_REF === 'refs/heads/main' &&
      ['push', 'schedule', 'workflow_dispatch'].includes(process.env.GITHUB_EVENT_NAME);
    if (process.env.INPUT_MODE !== 'record' && !trustedMain) {
      if (process.env.GITHUB_EVENT_NAME === 'pull_request') {
        const file = path.join(dir, 'receipt.json');
        const text = await fs.readFile(file).catch(error => { if (error.code !== 'ENOENT') throw error; });
        if (text && readReceipt(text).length) {
          const {default: artifacts} = await import('../../ci/dependency-cache/node_modules/@actions/artifact/lib/artifact.js');
          await artifacts.uploadArtifact(`public-dependency-misses-${process.env.GITHUB_RUN_ATTEMPT}-${randomUUID()}`,
            [file], dir, {retentionDays: 3});
        }
      }
      return;
    }
    const text = await fs.readFile(path.join(dir, 'receipt.json')).catch(error => {
      if (process.env.INPUT_MODE !== 'record' && error.code === 'ENOENT') return null;
      throw error;
    });
    if (text === null) return;
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
    const origins = await fs.readFile(path.join(dir, 'origin-requests.jsonl'), 'utf8').catch(error => {
      if (error.code !== 'ENOENT') throw error;
      return null;
    });
    const rows = origins?.trim().split('\n').filter(Boolean) ?? [];
    const originSummary = origins === null
      ? 'Upstream request log unavailable. The upstream request count is unknown.'
      : `${rows.length} requests. The job log lists every request, including redirects, retries and registry authentication.`;
    if (origins === null) console.warn(originSummary);
    if (process.env.CI_DEPENDENCY_STRICT_READ === '1' && (origins === null || rows.length > 0)) {
      console.error('Strict cache replay requires an available upstream request log with zero requests.');
      process.exitCode = 1;
    }
    for (const row of rows) console.log('Dependency broker upstream HTTP request ' + row);
    if (process.env.GITHUB_STEP_SUMMARY) {
      const lines = ['', '### Dependency broker upstream HTTP requests', '',
        originSummary, ''];
      if (rows.length) {
        lines.push('| Host | Dependency | HTTP status |', '| --- | --- | --- |');
        for (const row of rows.slice(0, 100)) {
          const {host, key, status} = JSON.parse(row);
          lines.push(`| ${host} | \`${key}\` | ${status} |`);
        }
      }
      await fs.appendFile(process.env.GITHUB_STEP_SUMMARY, lines.join('\n') + '\n');
    }
    if (process.env.GITHUB_ACTIONS === 'true') {
      const originFile = path.join(dir, 'origin-requests.jsonl');
      const files = origins === null ? [] : [originFile];
      if (origins === null) {
        const statusFile = path.join(dir, 'origin-report-status.txt');
        await fs.writeFile(statusFile, originSummary + '\n');
        files.push(statusFile);
      }
      for (const name of ['transfer-metrics.json', 'openartifact-metrics.json', 'misses.json']) {
        const file = path.join(dir, name);
        try { await fs.access(file); files.push(file); }
        catch (error) { if (error.code !== 'ENOENT') throw error; }
      }
      const {default: artifacts} = await import('../../ci/dependency-cache/node_modules/@actions/artifact/lib/artifact.js');
      const stripe = /^\d+$/.test(process.env.CI_GRADLE_STRIPE) ? `-stripe-${process.env.CI_GRADLE_STRIPE}` : '';
      const name = `dependency-report-${process.env.GITHUB_JOB}${stripe}-${process.env.GITHUB_RUN_ATTEMPT}-${randomUUID()}`;
      await artifacts.uploadArtifact(name, files, dir, {retentionDays: 7});
    }
    const log = await fs.readFile(path.join(dir, 'service.log'), 'utf8').catch(() => '');
    // Report diagnostic classes only; an exception message or stack may contain signed cache URLs.
    const requests = log.split('\n').filter(line => line.startsWith('Dependency request failed '));
    for (const line of new Set(requests)) console.warn(line);
    const fatal = log.match(/(?:^Broker fatal (?:uncaughtException|unhandledRejection) [A-Z0-9_]+(?: [A-Za-z0-9_.-]+:\d+){0,8}|^FATAL ERROR: [^\n]*heap out of memory|^Error \[ERR_[A-Z_]+\]|^node:events:\d+|^RangeError: Invalid string length)/gm) || [];
    for (const reason of new Set(fatal)) console.warn(`Dependency broker process: ${reason}`);
    const failures = log.match(/^Cache (?:archive|metadata) at [a-z0-9.-]+ failed \([a-z0-9_, ]{1,120}\)\.$/gim) || [];
    for (const reason of new Set(failures.slice(-10))) console.warn(reason);
    await fs.rm(dir, {recursive: true, force: true});
  }
}
finish().catch(error => { console.error(error.message); process.exitCode = 1; });
