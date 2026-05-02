# Report claim-trace checklist

Use this pass before declaring `report.md` finished. Every numeric
claim in the report must resolve to a specific artifact file under
`runs/<ts>/`. If a number in the report cannot be traced, it is a
story, not a measurement — delete it or go compute it from an
artifact.

## Five-pass self-review (in order)

### Pass 1 — structure

- [ ] Verdict banner (§0) is first, before any heading
- [ ] Banner contains the word PASSED / PASSED with open items / FAILED
- [ ] Banner has exactly one customer-impact sentence
- [ ] If the report has more than ~5 visible sections, a jump-nav
      of anchor links appears under the banner
- [ ] Rendered length is 1–3 pages (roughly 8–12 KB). Over 20 KB is
      audit-scope; split into a full `report.md` + trimmed PR-body
      subset

### Pass 2 — rigor (honest counts, no celebration)

- [ ] Every "N of N data points equal X" claim distinguishes
      **defined** from **undefined** cells. Spearman-ρ / Kendall-τ
      on single-hit or zero-variance sets are undefined — state
      them as "undefined (single-hit)" in the grid, and count them
      separately in the banner
- [ ] Every hit-total claim in the report was computed from
      `hits.total.value` on a response where `hits.total.relation`
      was verified equal to `"eq"`. `"gte"` is a lower bound, not
      a count
- [ ] No celebration language before measurement (no ✅🎉 before
      the numbers that justify them)
- [ ] Every range's edges are the actual min/max from the data.
      `2.20× to 2.43× smaller` is correct; `~2.3×` or `2.0–2.5×`
      is wrong

### Pass 3 — value (what wasn't measured)

- [ ] Every numeric callout answers "so what for a real user?" at
      least implicitly
- [ ] Run-metadata (§2) is honest about what was NOT captured. If
      the chart version, git SHA, or `/_analyze` probe wasn't
      recorded, write "not captured" — do not let the reader
      wonder whether it was omitted or forgotten
- [ ] Load-bearing evidence is distinguished from controls. If
      the banner says "9 query cases passed", the report notes
      how many were load-bearing vs trivially-passing controls

### Pass 4 — consistency (no cross-section drift)

- [ ] Numbers in the §0 banner match the numbers in the tables.
      If the banner says "29 of 29 defined", §5/§6 tables must
      show exactly 29 defined cells, not 30 or 28
- [ ] Phrases like "9 query cases" / "3 indices" / "115 docs"
      appear with the same value everywhere. Grep the report
      for the number and confirm consistency
- [ ] Any arithmetic in the prose actually adds up. "35.6 + 11.9
      + 7.5 = 55.0 KB" — verify with `bc` or the artifact
- [ ] No stale rounded numbers left behind from earlier drafts
      (e.g. "~0.45" when the later cell shows 0.4545)
- [ ] All jump-nav anchors resolve (GitHub slugger rules:
      lowercase, spaces → hyphens, special chars like `↔`
      become double-hyphens, drop punctuation)

### Pass 5 — claim-trace (every number → a file)

For each numeric claim in the report, identify the artifact file
that produced it. Open the file and confirm the number is there.

Common mappings:

| Report section     | Artifact file(s)                                              |
|--------------------|---------------------------------------------------------------|
| §2 phase timings   | `phase-timings.json`                                          |
| §3 schema hash     | `schema.sha256`                                               |
| §4 probe details   | `probe-source.json`, `probe-target-before/after.json`         |
| §5 doc counts      | `probe-*.json` (`docs.count` from `_cat/indices`)             |
| §5 mappings        | `probe-source.*.mapping.json` (byte-compared with `cmp`)      |
| §5 storage deltas  | `probe-*.json` (`store.size` from `_cat/indices`)             |
| §6 hit totals      | `queries/*.{source,target}.json` (`hits.total.value`)         |
| §6 rank metrics    | `rank-metrics.json`                                           |
| §6 score drift     | `queries/*.summary.json` (`{source,target}_top10_scores`)     |

Add a final line to the report (or in §2) that explicitly makes
this trace explicit to the reader:

> Every numeric claim in this report traces to a file in
> `runs/<ts>/`: §2 timings ← `phase-timings.json`; §4/§5 ←
> `probe-*.json`; §5 mappings ← `probe-source.*.mapping.json`
> (byte compared with `cmp`); §6 rank metrics ←
> `rank-metrics.json`; §6 hit totals ← `queries/*.{source,target}.json`.

## Common failure modes to catch

- **Fabricated precision**: "target 2.34× smaller" when the
  underlying data is `{idx_a: 2.20, idx_b: 2.43, idx_c: 2.29}` —
  report the range, not a spurious mean
- **Banner / table drift**: banner claims N cells, tables show
  N±1. Pass 4 catches this
- **Undefined → 0 collapse**: some tooling writes `0` for
  undefined rank metrics. Detect and re-label as `undefined`
- **`relation:"gte"` treated as `"eq"`**: especially on queries
  that hit OpenSearch's default 10000-doc track-total cap
- **Controls counted as wins**: single-hit term matches that
  "agree" are trivially consistent and must not inflate the
  load-bearing evidence count
- **Range rounded to hide the outlier**: if one query drifts
  0.98× and the rest drift ~0.45×, the range is `0.41–0.98`,
  NOT "~0.5×" — report the bimodality explicitly
- **Arithmetic off by rounding**: `35.6 + 11.9 + 7.5 = 55.0`,
  not `54.99`. Don't paste a partial sum from `jq` without
  verifying
