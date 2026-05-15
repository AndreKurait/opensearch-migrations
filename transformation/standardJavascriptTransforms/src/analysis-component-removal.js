/**
 * Strip and/or rename analyzer, tokenizer, char_filter, and token-filter components
 * that are incompatible with the target cluster.
 *
 * This is the preemptive counterpart to the Java InvalidResponse-based retry path:
 * it runs at metadata-transform time so the create-index/template request never carries
 * the offending name to begin with.
 *
 * The list of components to strip/rename is computed in Java by
 * MetadataTransformationRegistry, based on the (sourceVersion, targetVersion) pair, and
 * passed in via the bindings context.
 *
 * Expected context shape:
 *   {
 *     "removed": {
 *       "filter": ["standard", ...],
 *       "tokenizer": [],
 *       "char_filter": [],
 *       "analyzer": []
 *     },
 *     "renames": {
 *       "filter": { "delimited_payload_filter": "delimited_payload" },
 *       "tokenizer": {},
 *       "char_filter": {},
 *       "analyzer": {}
 *     }
 *   }
 *
 * Targets:
 *   body.settings.analysis.{analyzer,tokenizer,filter,char_filter}.<name>
 *   body.settings.index.analysis.{analyzer,tokenizer,filter,char_filter}.<name>
 *   body.template.settings.* (component/index templates)
 * Plus the flat-dotted form (snapshot upstream of CanonicalTransformer.normalize):
 *   body.settings["index.analysis.analyzer.<name>.filter"]
 *   body.settings["index.analysis.<kind>.<name>.<...>"]
 *
 * Implementation note: containers may be JS native Maps, polyglot Java Map proxies, or
 * plain JS objects. The map(...) helpers below duck-type Map-like containers so the
 * rule logic mutates them in-place regardless of which shape the host gave us.
 */

const ANALYSIS_KIND_KEYS = ['analyzer', 'tokenizer', 'filter', 'char_filter'];

/** True for native JS Map AND polyglot Java Map (which exposes .get/.set/.has/.keys). */
function isMapLike(obj) {
    if (obj instanceof Map) return true;
    return obj != null && typeof obj === 'object'
        && typeof obj.get === 'function'
        && typeof obj.set === 'function'
        && typeof obj.has === 'function'
        && typeof obj.delete === 'function';
}

function mapHas(obj, key) {
    if (isMapLike(obj)) return obj.has(key);
    return obj != null && typeof obj === 'object'
        && Object.prototype.hasOwnProperty.call(obj, key);
}

function mapGet(obj, key) {
    if (obj == null) return undefined;
    if (isMapLike(obj)) return obj.get(key);
    if (typeof obj === 'object') return obj[key];
    return undefined;
}

function mapSet(obj, key, val) {
    if (isMapLike(obj)) { obj.set(key, val); return; }
    if (obj != null && typeof obj === 'object') { obj[key] = val; }
}

function mapDelete(obj, key) {
    if (isMapLike(obj)) { obj.delete(key); return; }
    if (obj != null && typeof obj === 'object') { delete obj[key]; }
}

/** Yield the keys of a Map-like or object. For polyglot Java Maps .keys() is a Java Set,
 *  which is iterable. We materialise into an array so callers can mutate during iteration. */
function mapKeys(obj) {
    if (obj == null) return [];
    if (obj instanceof Map) return Array.from(obj.keys());
    if (isMapLike(obj)) {
        // Polyglot Java Map: .keys() returns an iterable view.
        const ks = [];
        for (const k of obj.keys()) ks.push(k);
        return ks;
    }
    if (typeof obj === 'object') return Object.keys(obj);
    return [];
}

function mapEntries(obj) {
    return mapKeys(obj).map((k) => [k, mapGet(obj, k)]);
}

/** Treat the value as a "container we can index" (Map-like or plain object). */
function isContainer(obj) {
    if (obj == null) return false;
    if (Array.isArray(obj)) return false;
    if (isMapLike(obj)) return true;
    return typeof obj === 'object';
}

// ──────────────────────────────────────────────────────────────────────
// Rule application — mutates input containers in-place.
// ──────────────────────────────────────────────────────────────────────

function rewriteArrayInPlace(arr, removedSet, renameMap) {
    if (!Array.isArray(arr)) return;
    for (let i = arr.length - 1; i >= 0; i--) {
        const v = arr[i];
        if (typeof v !== 'string') continue;
        if (removedSet.has(v)) {
            arr.splice(i, 1);
        } else if (renameMap[v]) {
            arr[i] = renameMap[v];
        }
    }
}

function rewriteStringRef(container, key, removedSet, renameMap) {
    if (!mapHas(container, key)) return;
    const v = mapGet(container, key);
    if (typeof v !== 'string') return;
    if (removedSet.has(v)) {
        mapDelete(container, key);
    } else if (renameMap[v]) {
        mapSet(container, key, renameMap[v]);
    }
}

function dropOrRenameSection(section, removedNames, renameMap) {
    if (!isContainer(section)) return;
    for (const name of mapKeys(section)) {
        if (removedNames.has(name)) mapDelete(section, name);
    }
    for (const [from, to] of Object.entries(renameMap || {})) {
        if (!mapHas(section, from)) continue;
        const v = mapGet(section, from);
        mapDelete(section, from);
        if (!mapHas(section, to)) mapSet(section, to, v);
    }
}

function processAnalyzersSection(analyzerSection, removed, renames) {
    if (!isContainer(analyzerSection)) return;
    dropOrRenameSection(
        analyzerSection,
        new Set(removed.analyzer || []),
        renames.analyzer || {}
    );

    const removedFilters = new Set(removed.filter || []);
    const filterRenames = renames.filter || {};
    const removedCharFilters = new Set(removed.char_filter || []);
    const charFilterRenames = renames.char_filter || {};
    const removedTokenizers = new Set(removed.tokenizer || []);
    const tokenizerRenames = renames.tokenizer || {};

    for (const [_name, def] of mapEntries(analyzerSection)) {
        if (!isContainer(def)) continue;
        const filterArr = mapGet(def, 'filter');
        if (Array.isArray(filterArr)) rewriteArrayInPlace(filterArr, removedFilters, filterRenames);
        const charFilterArr = mapGet(def, 'char_filter');
        if (Array.isArray(charFilterArr)) rewriteArrayInPlace(charFilterArr, removedCharFilters, charFilterRenames);
        rewriteStringRef(def, 'tokenizer', removedTokenizers, tokenizerRenames);
    }
}

function processNestedAnalysis(settings, removed, renames) {
    if (!isContainer(settings)) return;
    let analysis = mapGet(settings, 'analysis');
    if (!isContainer(analysis)) {
        const idx = mapGet(settings, 'index');
        if (isContainer(idx)) analysis = mapGet(idx, 'analysis');
    }
    if (!isContainer(analysis)) return;

    processAnalyzersSection(mapGet(analysis, 'analyzer'), removed, renames);
    dropOrRenameSection(mapGet(analysis, 'tokenizer'),
        new Set(removed.tokenizer || []), renames.tokenizer || {});
    dropOrRenameSection(mapGet(analysis, 'filter'),
        new Set(removed.filter || []), renames.filter || {});
    dropOrRenameSection(mapGet(analysis, 'char_filter'),
        new Set(removed.char_filter || []), renames.char_filter || {});
}

function parseAnalysisKey(key) {
    if (typeof key !== 'string' || key.indexOf('.') < 0) return null;
    const parts = key.split('.');
    let i = 0;
    if (parts[i] === 'index') i++;
    if (parts[i] !== 'analysis') return null;
    i++;
    const kind = parts[i];
    if (ANALYSIS_KIND_KEYS.indexOf(kind) < 0) return null;
    i++;
    if (i >= parts.length) return null;
    const name = parts[i];
    i++;
    const suffix = parts.slice(i).join('.');
    const prefixLen = key.length - (suffix.length === 0
        ? name.length
        : (name.length + 1 + suffix.length));
    return { kind, name, suffix, prefix: key.substring(0, prefixLen) };
}

function processFlatDottedSettings(settings, removed, renames) {
    if (!isContainer(settings)) return;

    const removedByKind = {
        filter: new Set(removed.filter || []),
        tokenizer: new Set(removed.tokenizer || []),
        char_filter: new Set(removed.char_filter || []),
        analyzer: new Set(removed.analyzer || []),
    };
    const renamesByKind = {
        filter: renames.filter || {},
        tokenizer: renames.tokenizer || {},
        char_filter: renames.char_filter || {},
        analyzer: renames.analyzer || {},
    };

    // First pass: drop or rename keys whose component name is in the removed/renames set.
    for (const key of mapKeys(settings)) {
        const parsed = parseAnalysisKey(key);
        if (!parsed) continue;
        const { kind, name, suffix, prefix } = parsed;
        if (removedByKind[kind].has(name)) {
            mapDelete(settings, key);
            continue;
        }
        const renameTo = renamesByKind[kind][name];
        if (renameTo) {
            const newKey = prefix + renameTo + (suffix ? '.' + suffix : '');
            const value = mapGet(settings, key);
            mapDelete(settings, key);
            if (!mapHas(settings, newKey)) mapSet(settings, newKey, value);
        }
    }

    // Second pass: rewrite the contents of analyzer.filter / .char_filter / .tokenizer.
    for (const key of mapKeys(settings)) {
        const parsed = parseAnalysisKey(key);
        if (!parsed || parsed.kind !== 'analyzer') continue;
        const value = mapGet(settings, key);
        if (parsed.suffix === 'filter' && Array.isArray(value)) {
            rewriteArrayInPlace(value, removedByKind.filter, renamesByKind.filter);
        } else if (parsed.suffix === 'char_filter' && Array.isArray(value)) {
            rewriteArrayInPlace(value, removedByKind.char_filter, renamesByKind.char_filter);
        } else if (parsed.suffix === 'tokenizer' && typeof value === 'string') {
            if (removedByKind.tokenizer.has(value)) {
                mapDelete(settings, key);
            } else if (renamesByKind.tokenizer[value]) {
                mapSet(settings, key, renamesByKind.tokenizer[value]);
            }
        }
    }
}

function processSettingsRoot(settings, removed, renames) {
    processNestedAnalysis(settings, removed, renames);
    processFlatDottedSettings(settings, removed, renames);
}

function processBody(body, removed, renames) {
    if (!isContainer(body)) return;
    const settings = mapGet(body, 'settings');
    if (isContainer(settings)) processSettingsRoot(settings, removed, renames);
    const template = mapGet(body, 'template');
    if (isContainer(template)) {
        const tmplSettings = mapGet(template, 'settings');
        if (isContainer(tmplSettings)) processSettingsRoot(tmplSettings, removed, renames);
    }
}

function readBody(doc) {
    if (doc == null) return undefined;
    return mapGet(doc, 'body');
}

function readContext(context) {
    if (context == null) return { removed: {}, renames: {} };
    // Use mapGet so Map-shaped contexts work the same as plain objects. The values
    // (`removed`, `renames`) themselves may be plain objects or Maps; downstream callers
    // use `Object.entries`/`set.has` on plain objects for the by-kind lookup. To keep
    // that simple we coerce the Map nests to plain by reading via mapGet.
    return {
        removed: shallowMapToObject(mapGet(context, 'removed')) || {},
        renames: shallowMapToObject(mapGet(context, 'renames')) || {},
    };
}

/** Shallow-coerce a Map (or polyglot Java Map) to a plain object so the rule code
 *  can use property-style access on it. Values pass through (which may be arrays or
 *  inner Maps; for our context shape, removed/renames second-level values are
 *  string-arrays / string-keyed maps respectively, both of which we handle). */
function shallowMapToObject(value) {
    if (value == null) return value;
    if (Array.isArray(value)) return value;
    if (isMapLike(value)) {
        const out = {};
        for (const k of mapKeys(value)) {
            const v = mapGet(value, k);
            // Recursively coerce nested map for the rename map ({oldName: newName}).
            if (isMapLike(v)) {
                const inner = {};
                for (const ik of mapKeys(v)) inner[ik] = mapGet(v, ik);
                out[k] = inner;
            } else {
                out[k] = v;
            }
        }
        return out;
    }
    return value;
}

function main(context) {
    const { removed, renames } = readContext(context);

    const noWork =
        (!removed || Object.keys(removed).length === 0) &&
        (!renames || Object.keys(renames).length === 0);
    if (noWork) return (doc) => doc;

    return (doc) => {
        if (Array.isArray(doc)) {
            return doc.map((d) => { processBody(readBody(d), removed, renames); return d; });
        }
        processBody(readBody(doc), removed, renames);
        return doc;
    };
}

if (typeof module !== 'undefined' && module.exports) {
    module.exports = main;
}

(() => main)();
