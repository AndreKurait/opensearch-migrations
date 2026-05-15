/**
 * Strip and/or rename analyzer, tokenizer, char_filter, and token-filter components
 * that are incompatible with the target cluster.
 *
 * This is the *preemptive* counterpart to the Java InvalidResponse-based retry path:
 * it runs at metadata-transform time so the create-index/template request never carries
 * the offending name to begin with.
 *
 * The list of components to strip/rename is computed in Java by
 * MetadataTransformationRegistry, based on the (sourceVersion, targetVersion) pair, and
 * passed in via the bindings context. The JS transform itself is engine-agnostic — it
 * just applies the rules.
 *
 * Expected context shape:
 *   {
 *     "removed": {
 *       "filter":      ["standard", ...],
 *       "tokenizer":   [],
 *       "char_filter": [],
 *       "analyzer":    []
 *     },
 *     "renames": {
 *       "filter":      { "delimited_payload_filter": "delimited_payload" },
 *       "tokenizer":   {},
 *       "char_filter": {},
 *       "analyzer":    {}
 *     }
 *   }
 *
 * Targets in the body, both nested and dotted "index.*" forms:
 *   body.settings.analysis.{analyzer,tokenizer,filter,char_filter}.<name>
 *   body.settings.index.analysis.{analyzer,tokenizer,filter,char_filter}.<name>
 *   (also under body.template.settings.* for component/index templates)
 *
 * Analyzer references that point at a removed component are also rewritten:
 *   - analyzer's "filter" array entries
 *   - analyzer's "char_filter" array entries
 *   - analyzer's "tokenizer" string
 *
 * The transform is safe to apply when the body has no analysis section: it is a no-op.
 */

function getEntry(obj, key) {
    if (obj instanceof Map) return obj.get(key);
    if (obj && typeof obj === 'object') return obj[key];
    return undefined;
}

function deleteEntry(obj, key) {
    if (obj instanceof Map) { obj.delete(key); return; }
    if (obj && typeof obj === 'object') { delete obj[key]; }
}

function renameEntry(obj, fromKey, toKey) {
    if (obj instanceof Map) {
        if (!obj.has(fromKey)) return;
        const val = obj.get(fromKey);
        obj.delete(fromKey);
        if (!obj.has(toKey)) obj.set(toKey, val);
        return;
    }
    if (obj && typeof obj === 'object' && Object.prototype.hasOwnProperty.call(obj, fromKey)) {
        const val = obj[fromKey];
        delete obj[fromKey];
        if (!Object.prototype.hasOwnProperty.call(obj, toKey)) obj[toKey] = val;
    }
}

function entries(obj) {
    if (obj instanceof Map) return Array.from(obj.entries());
    if (obj && typeof obj === 'object') return Object.entries(obj);
    return [];
}

function setEntry(obj, key, val) {
    if (obj instanceof Map) { obj.set(key, val); return; }
    if (obj && typeof obj === 'object') { obj[key] = val; }
}

function rewriteArrayInPlace(node, key, removedSet, renameMap) {
    const arr = getEntry(node, key);
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

function rewriteStringInPlace(node, key, removedSet, renameMap) {
    const v = getEntry(node, key);
    if (typeof v !== 'string') return;
    if (removedSet.has(v)) {
        deleteEntry(node, key);
    } else if (renameMap[v]) {
        setEntry(node, key, renameMap[v]);
    }
}

function processNamedSection(section, removedNames, renameMap) {
    if (!section) return;
    for (const [name, _def] of entries(section)) {
        if (removedNames.has(name)) {
            deleteEntry(section, name);
        }
    }
    for (const [from, to] of Object.entries(renameMap || {})) {
        renameEntry(section, from, to);
    }
}

function processAnalyzers(analyzerSection, removed, renames) {
    if (!analyzerSection) return;
    // First, drop or rename analyzer keys themselves
    processNamedSection(analyzerSection, new Set(removed.analyzer || []), renames.analyzer || {});

    // Then walk each remaining analyzer definition and rewrite filter/char_filter/tokenizer refs
    const removedFilters = new Set(removed.filter || []);
    const filterRenames = renames.filter || {};
    const removedCharFilters = new Set(removed.char_filter || []);
    const charFilterRenames = renames.char_filter || {};
    const removedTokenizers = new Set(removed.tokenizer || []);
    const tokenizerRenames = renames.tokenizer || {};

    for (const [_name, def] of entries(analyzerSection)) {
        if (def == null) continue;
        rewriteArrayInPlace(def, 'filter', removedFilters, filterRenames);
        rewriteArrayInPlace(def, 'char_filter', removedCharFilters, charFilterRenames);
        rewriteStringInPlace(def, 'tokenizer', removedTokenizers, tokenizerRenames);
    }
}

function processSettingsRoot(settings, removed, renames) {
    if (!settings) return;

    // Both "settings.analysis" and "settings.index.analysis" are valid layouts.
    let analysis = getEntry(settings, 'analysis');
    if (!analysis) {
        const idx = getEntry(settings, 'index');
        if (idx) analysis = getEntry(idx, 'analysis');
    }
    if (!analysis) return;

    processAnalyzers(getEntry(analysis, 'analyzer'), removed, renames);
    processNamedSection(getEntry(analysis, 'tokenizer'),
        new Set(removed.tokenizer || []), renames.tokenizer || {});
    processNamedSection(getEntry(analysis, 'filter'),
        new Set(removed.filter || []), renames.filter || {});
    processNamedSection(getEntry(analysis, 'char_filter'),
        new Set(removed.char_filter || []), renames.char_filter || {});
}

function processBody(body, removed, renames) {
    if (!body) return;
    const settings = getEntry(body, 'settings');
    if (settings) processSettingsRoot(settings, removed, renames);
    // Templates (index/component): "template.settings"
    const template = getEntry(body, 'template');
    if (template) {
        const tmplSettings = getEntry(template, 'settings');
        if (tmplSettings) processSettingsRoot(tmplSettings, removed, renames);
    }
}

function readContext(context) {
    if (context == null) return { removed: {}, renames: {} };
    if (context instanceof Map) {
        return {
            removed: context.get('removed') || {},
            renames: context.get('renames') || {},
        };
    }
    return {
        removed: context.removed || {},
        renames: context.renames || {},
    };
}

function readBody(doc) {
    if (doc == null) return undefined;
    if (doc instanceof Map) return doc.get('body');
    return doc.body;
}

function main(context) {
    const { removed, renames } = readContext(context);

    // Empty config: cheap no-op transformer
    const noWork =
        (!removed || Object.keys(removed).length === 0) &&
        (!renames || Object.keys(renames).length === 0);
    if (noWork) return (doc) => doc;

    return (doc) => {
        if (Array.isArray(doc)) return doc.map((d) => { processBody(readBody(d), removed, renames); return d; });
        processBody(readBody(doc), removed, renames);
        return doc;
    };
}

if (typeof module !== 'undefined' && module.exports) {
    module.exports = main;
}

(() => main)();
