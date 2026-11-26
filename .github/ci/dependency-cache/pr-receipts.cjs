'use strict';
const {execFile} = require('node:child_process');
const {promisify} = require('node:util');
const {randomUUID} = require('node:crypto');
const fs = require('node:fs/promises');
const path = require('node:path');
const {readReceipt, MAX_RECEIPT_BYTES, MAX_RECEIPT_ENTRIES} = require('./receipts.cjs');
const {receiptPath} = require('./cli.cjs');
const exec = promisify(execFile);

async function main() {
  const {GITHUB_REPOSITORY: repo, SOURCE_ID: run, SOURCE_ATTEMPT: attempt} = process.env;
  if (!/^\d+$/.test(run) || !/^\d+$/.test(attempt)) throw new Error('Invalid source run');
  const api = `repos/${repo}/actions`;
  const {stdout} = await exec('gh', ['api', '--paginate', '--slurp', `${api}/runs/${run}/artifacts?per_page=100`]);
  const names = new RegExp(`^public-dependency-misses-${attempt}-[0-9a-f-]{36}$`);
  const rows = JSON.parse(stdout).flatMap(page => page.artifacts).filter(a => names.test(a.name) && !a.expired);
  if (rows.length > 256) throw new Error('Too many PR dependency receipts');
  const requests = new Map();
  for (const artifact of rows) {
    if (!Number.isSafeInteger(artifact.id) || artifact.size_in_bytes > MAX_RECEIPT_BYTES + 65536) throw new Error('Oversized dependency receipt');
    const zip = await exec('gh', ['api', `${api}/artifacts/${artifact.id}/zip`], {encoding: 'buffer', maxBuffer: MAX_RECEIPT_BYTES + 65536});
    // Never extract candidate-selected filenames. Only the bounded receipt can leave the archive.
    const {spawnSync} = require('node:child_process');
    const script = `import io,sys,zipfile\nwith zipfile.ZipFile(io.BytesIO(sys.stdin.buffer.read())) as z:\n a=z.infolist()\n if len(a)!=1 or a[0].filename!='receipt.json' or a[0].file_size>${MAX_RECEIPT_BYTES}: raise ValueError('Invalid PR receipt')\n sys.stdout.buffer.write(z.read(a[0]))`;
    const decoded = spawnSync('python3', ['-I', '-c', script], {input: zip.stdout, maxBuffer: MAX_RECEIPT_BYTES + 1024});
    if (decoded.status !== 0) throw new Error('Invalid PR dependency archive');
    for (const row of readReceipt(decoded.stdout)) {
      // The protected recorder independently resolves each allowlisted public origin. The PR provides no bytes, timestamps or digests.
      requests.set(row.key, {key: row.key, accessed: Date.now()});
      if (requests.size > MAX_RECEIPT_ENTRIES) throw new Error('Too many distinct PR dependency requests');
    }
  }
  if (!requests.size) return;
  const text = JSON.stringify([...requests.values()]);
  readReceipt(text);
  const key = `migrations-main-requests-v1-${run}-${attempt}-${randomUUID()}`;
  await fs.mkdir(path.dirname(receiptPath), {recursive: true});
  await fs.writeFile(receiptPath, text, {mode: 0o600});
  try {
    const {saveCache} = await import('@actions/cache');
    if (await saveCache([receiptPath], key) < 0) throw new Error('Could not stage protected PR request receipt');
    await exec('gh', ['workflow', 'run', 'dependency-cache-sync.yml', '--repo', repo, '--ref', 'main', '-f', `receipt=${key}`]);
    console.log(`Queued independent public-origin verification of ${requests.size} PR dependency requests.`);
  } finally { await fs.rm(receiptPath, {force:true}); }
}
main().catch(error => { console.error(error.message); process.exitCode = 1; });
