'use strict';
// A public base repository's cache inventory is public metadata. Fork and same-repository
// PR job tokens sometimes receive a narrower authorization response than anonymous callers.
async function listMainCaches({
  repository,
  prefix,
  page = 1,
  sort = 'created_at',
  token,
  request = fetch,
  branch = 'refs/heads/main',
}) {
  if (!/^[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+$/.test(repository) || !branch.startsWith('refs/heads/'))
    throw new Error('Invalid GitHub base repository or ref');
  const url = new URL(`https://api.github.com/repos/${repository}/actions/caches`);
  url.search = new URLSearchParams({
    ref: branch,
    key: prefix,
    per_page: '100',
    page: String(page),
    sort,
    direction: 'desc',
  });
  async function get(auth) {
    const headers = { Accept: 'application/vnd.github+json', 'User-Agent': 'opensearch-migrations-ci' };
    if (auth) headers.Authorization = `Bearer ${auth}`;
    return request(url, { headers, redirect: 'error', signal: AbortSignal.timeout(15000) });
  }
  let result = await get(token);
  if (token && [401, 403, 404].includes(result.status)) {
    await result.body?.cancel();
    result = await get();
  }
  if (!result.ok) {
    const reset = result.headers.get('x-ratelimit-remaining') === '0' ? ' (rate limit exhausted)' : '';
    throw new Error(`Cannot read GitHub base cache metadata (HTTP ${result.status})${reset}`);
  }
  const rows = (await result.json()).actions_caches;
  if (!Array.isArray(rows)) throw new Error('GitHub cache metadata did not return cache entries');
  return rows.filter((row) => row.ref === branch);
}
module.exports = { listMainCaches };
