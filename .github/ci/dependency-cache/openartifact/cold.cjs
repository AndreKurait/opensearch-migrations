'use strict';

const fs = require('node:fs/promises');
const { randomUUID } = require('node:crypto');
const path = require('node:path');
const { fetchPublic, validateKey } = require('../proxy.cjs');

// Ordinary PRs may need a newly introduced public dependency before main warms it.
// All data is ephemeral and each network download must share these job limits.
async function openColdStore(
  root,
  { fetchArtifact = fetchPublic, maxEntries = 2048, maxBytes = 4_000_000_000, maxObjectBytes = 1_000_000_000 } = {},
) {
  const dir = await fs.mkdtemp(path.join(root, 'uncached-public-'));
  const pending = new Map();
  let unreserved = maxBytes,
    closed = false;
  const inflight = new Set();
  return {
    async resolve(key) {
      validateKey(key);
      if (closed) throw new Error('Cold dependency store closed');
      if (pending.has(key)) return pending.get(key);
      if (pending.size >= maxEntries) throw new Error('Cold dependency budget exhausted');
      const dest = path.join(dir, randomUUID());
      const download = Promise.resolve().then(async () => {
        // A reserved transfer can complete far below its ceiling. Wait for it to release capacity before rejecting parallel small requests.
        while (inflight.size && unreserved < maxObjectBytes) await Promise.race(inflight);
        if (unreserved <= 0) throw new Error('Cold dependency budget exhausted');
        const allowance = Math.min(maxObjectBytes, unreserved);
        unreserved -= allowance;
        let release;
        const lease = new Promise((ok) => {
          release = ok;
        });
        inflight.add(lease);
        try {
          const entry = await fetchArtifact(key, dest, { maxBytes: allowance });
          unreserved += allowance - (entry?.size || 0);
          return entry ? { ...entry, path: dest } : null;
        } catch (error) {
          unreserved += allowance;
          pending.delete(key);
          throw error;
        } finally {
          inflight.delete(lease);
          release();
        }
      });
      pending.set(key, download);
      return download;
    },
    async close() {
      closed = true;
      await Promise.allSettled(pending.values());
      await fs.rm(dir, { recursive: true, force: true });
    },
  };
}

module.exports = { openColdStore };
