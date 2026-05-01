# Pitfalls

Things that have bitten people. Check this list when something's weird.

## Schema drift

The JSON Schema at `/root/.workflowUser.schema.json` changes between
releases. Your training data is stale. Always `cat` the live file in
Phase 0 and trust it over anything else — including the sample YAML,
which itself drifts behind the schema. If a field you "know" exists is
rejected, look at the live schema first.

## Version string format

The regex is `^(?:ES [125678]|OS [123]|SOLR [89])(?:\.[0-9]+)+$`. Strict
rules:

- Engine prefix is literal: `ES` / `OS` / `SOLR`. `Solr` lowercase fails.
- Major version is restricted: ES {1,2,5,6,7,8}, OS {1,2,3}, SOLR {8,9}.
  ES 3 or 4 won't parse (no such releases). SOLR 7 won't parse.
- At least one `.N` segment required. `ES 7.10` fails; `ES 7.10.2`
  passes.

## TLS self-signed certs

If probe sees a self-signed cert on either cluster (common for local
kind setups and corporate clusters), set `allowInsecure: true` on that
cluster in the config. The migration workers honor this flag; curl
needs `-k` independently.

## Secrets are namespaced

All `authConfig.basic.secretName` lookups happen in the `ma` namespace.
If the user created the secret in `default`, the migration fails at
submit with an opaque error. Always `kubectl -n ma get secret <name>`
before submit.

## AWS-managed source + createSnapshotConfig = SigV4 required

The schema enforces this in a `superRefine`. If the source endpoint
matches `*.es.amazonaws.com` or `*.aos.*.on.aws` and the config creates
a snapshot, you need `authConfig: { sigv4: { region: … } }` **and** the
referenced repo must have a non-empty `s3RoleArn`.

## workflow configure edit --stdin overwrites silently

The `default` session config is overwritten byte-for-byte on every
`--stdin` call. If the user had a saved config, it's gone. Warn before
overwriting.

## Approval gates

`skipApprovals: true` is fine for demos but dangerous for production.
Default to asking the user in the interview; if they say "fully
automated", `skipApprovals: true` it — but log a one-liner in the
report noting that no human gate fired.

## Long logs burn context

When watching progress, filter aggressively:

- RFS: `kubectl logs ... --tail=5 | grep -o 'Doc Number [0-9]*'`
- Metadata: `--tail=100 | grep -A 50 'Starting Metadata'`
- Workflow status: `grep -E 'Phase:|Running|Succeeded|Failed'`

Never dump the full log into the report or context window; link to the
kubectl command instead.

## Doc count mismatches are real errors

A 1-doc delta usually means the snapshot raced an indexer. A ≥1%
delta is a bug. Don't paper over it with "close enough" — investigate
via `workflow output <name>` and fix the config before re-running.

## Relevancy drift is normal and expected

BM25 parameters, analyzer versions, and tokenizer behaviors change
between ES 7/OS 1/OS 2/OS 3. Small score drift (≤5%) and occasional
rank swaps in positions 5–10 are expected. Report them, hypothesize a
cause, don't raise alarm.

## Solr → OpenSearch isn't a round-trip

SolrCloud-specific concepts (routing keys, request handlers,
updateRequestProcessorChain, velocity templates) have no OpenSearch
equivalent. The migration covers documents + mappings. Tell the user
explicitly when you see these features.

## Do not reference proprietary Elastic naming

In committed text (reports, commits, PRs, code comments), say
"Elasticsearch" only when technically necessary (referring to a specific
version or wire protocol). Never cite Elastic Inc. marketing copy or
copy from elastic.co docs. Companion is parallel to, not derived from,
upstream Elastic work.
