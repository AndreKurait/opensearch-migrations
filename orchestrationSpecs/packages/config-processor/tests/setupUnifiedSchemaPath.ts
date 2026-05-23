import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import {
    buildUnifiedSchema,
    UNIFIED_SCHEMA_PATH_ENV,
} from "@opensearch-migrations/schemas";

const strimziFixturePath = path.resolve(
    __dirname,
    "..",
    "..",
    "schemas",
    "tests",
    "fixtures",
    "strimzi",
    "minimal-openapi.json"
);
// setupFiles runs once per Jest worker, but the previous version wrote to a
// fixed path in os.tmpdir() — so two workers racing the writeFileSync left a
// partially-truncated JSON file on disk. The validator's JSON.parse then blew
// up mid-document (e.g. "Expected property name or '}' at position 40954").
// Scope the file to the current worker so writes never collide.
const workerId = process.env.JEST_WORKER_ID ?? "1";
const outputPath = path.join(
    os.tmpdir(),
    `orchestrationSpecs-config-processor-test-unified-schema-w${workerId}.json`,
);
const {schema} = buildUnifiedSchema({strimziSchemaPath: strimziFixturePath});

fs.writeFileSync(outputPath, JSON.stringify(schema, null, 2) + "\n");
process.env[UNIFIED_SCHEMA_PATH_ENV] = outputPath;
