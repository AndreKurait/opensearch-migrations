import {z} from "zod";
import {
    CLUSTER_VERSION_STRING,
    COMPLETE_SNAPSHOT_CONFIG,
    CONSOLE_SERVICES_CONFIG_FILE,
    NAMED_TARGET_CLUSTER_CONFIG,
    ResourceRequirementsType,
    RFS_OPTIONS,
    DEFAULT_RESOURCES
} from "@opensearch-migrations/schemas";
import {MigrationConsole} from "./migrationConsole";

import {
    AllowLiteralOrExpression,
    BaseExpression,
    expr,
    IMAGE_PULL_POLICY,
    INTERNAL, makeDirectTypeProxy, makeStringTypeProxy,
    selectInputsForRegister,
    Serialized,
    typeToken,
    WorkflowBuilder
} from "@opensearch-migrations/argo-workflow-builders";
import {
    ReplicaSet
} from "@opensearch-migrations/argo-workflow-builders";
import {makeRepoParamDict} from "./metadataMigration";
import {
    setupLog4jConfigForContainer,
    setupTestCredsForContainer
} from "./commonUtils/containerFragments";
import {CommonWorkflowParameters} from "./commonUtils/workflowParameters";
import {makeRequiredImageParametersForKeys} from "./commonUtils/imageDefinitions";
import {makeTargetParamDict, makeCoordinatorParamDict} from "./commonUtils/clusterSettingManipulators";
import {getHttpAuthSecretName} from "./commonUtils/clusterSettingManipulators";

// Fixed prefix for coordinator cluster to avoid naming conflicts with target clusters
const COORDINATOR_CLUSTER_PREFIX = "rfs-coordinator-";

function getCoordinatorClusterName(sessionName: BaseExpression<string>) {
    return expr.concat(expr.literal(COORDINATOR_CLUSTER_PREFIX), sessionName);
}

function getCoordinatorCredsSecretName(sessionName: BaseExpression<string>) {
    return expr.concat(getCoordinatorClusterName(sessionName), expr.literal("-creds"));
}

function makeParamsDict(
    sourceVersion: BaseExpression<z.infer<typeof CLUSTER_VERSION_STRING>>,
    targetConfig: BaseExpression<Serialized<z.infer<typeof NAMED_TARGET_CLUSTER_CONFIG>>>,
    coordinatorConfig: BaseExpression<Serialized<z.infer<typeof NAMED_TARGET_CLUSTER_CONFIG>>>,
    snapshotConfig: BaseExpression<Serialized<z.infer<typeof COMPLETE_SNAPSHOT_CONFIG>>>,
    options: BaseExpression<Serialized<z.infer<typeof RFS_OPTIONS>>>,
    sessionName: BaseExpression<string>
) {
    return expr.mergeDicts(
        expr.mergeDicts(
            expr.mergeDicts(
                makeTargetParamDict(targetConfig),
                makeCoordinatorParamDict(coordinatorConfig)
            ),
            expr.omit(expr.deserializeRecord(options), "loggingConfigurationOverrideConfigMap", "podReplicas", "resources")
        ),
        expr.mergeDicts(
            expr.makeDict({
                snapshotName: expr.get(expr.deserializeRecord(snapshotConfig), "snapshotName"),
                sourceVersion: sourceVersion,
                sessionName: sessionName,
                luceneDir: "/tmp",
                cleanLocalDirs: true
            }),
            makeRepoParamDict(
                expr.omit(expr.get(expr.deserializeRecord(snapshotConfig), "repoConfig"), "s3RoleArn"),
                true)
        )
    );
}

function getRfsReplicasetName(sessionName: BaseExpression<string>) {
    return expr.concat(sessionName, expr.literal("-reindex-from-snapshot"));
}

function getRfsReplicasetManifest
(args: {
    workflowName: BaseExpression<string>,
    jsonConfig: BaseExpression<string>
    sessionName: BaseExpression<string>,
    podReplicas: BaseExpression<number>,
    targetBasicCredsSecretNameOrEmpty: AllowLiteralOrExpression<string>,
    coordinatorBasicCredsSecretNameOrEmpty: AllowLiteralOrExpression<string>,

    useLocalstackAwsCreds: BaseExpression<boolean>,
    loggingConfigMap: BaseExpression<string>,

    rfsImageName: BaseExpression<string>,
    rfsImagePullPolicy: BaseExpression<IMAGE_PULL_POLICY>,
    resources: BaseExpression<ResourceRequirementsType>
}): ReplicaSet {
    const targetBasicCredsSecretName = expr.ternary(
        expr.isEmpty(args.targetBasicCredsSecretNameOrEmpty),
        expr.literal("empty"),
        args.targetBasicCredsSecretNameOrEmpty
    );
    const coordinatorBasicCredsSecretName = expr.ternary(
        expr.isEmpty(args.coordinatorBasicCredsSecretNameOrEmpty),
        expr.literal("empty"),
        args.coordinatorBasicCredsSecretNameOrEmpty
    );
    const useCustomLogging = expr.not(expr.isEmpty(args.loggingConfigMap));
    const baseContainerDefinition = {
        name: "bulk-loader",
        image: makeStringTypeProxy(args.rfsImageName),
        imagePullPolicy: makeStringTypeProxy(args.rfsImagePullPolicy),
        command: ["/rfs-app/runJavaWithClasspathWithRepeat.sh"],
        env: [
            // see getTargetHttpAuthCreds() - it's very similar, but for a raw K8s container, we pass
            // environment variables as a list, as K8s expects them.  The getTargetHttpAuthCreds()
            // returns them in a key-value format that the ContainerBuilder uses, which is converted
            // by the argoResourceRenderer.  It would be a nice idea to unify this format with the
            // container builder's, but it's probably a much bigger lift than it seems since we're
            // type checking this object against the k8s schema below.
            //
            // I could also use getTargetHttpAuthCreds to create the partial values, then substitute
            // those into here by splicing.  Writing a generic splicer isn't that straightforward since
            // there are a few other inconsistencies between the manifest and argo-container definitions.
            // As of now, we only have this block (though a couple others will come about too) and it
            // doesn't seem like it's worth the complexity.  There's some readability value to having
            // less normalization here as it benefits readability.
            //
            {
                name: "TARGET_USERNAME",
                valueFrom: {
                    secretKeyRef: {
                        name: makeStringTypeProxy(targetBasicCredsSecretName),
                        key: "username",
                        optional: true
                    }
                }
            },
            {
                name: "TARGET_PASSWORD",
                valueFrom: {
                    secretKeyRef: {
                        name: makeStringTypeProxy(targetBasicCredsSecretName),
                        key: "password",
                        optional: true
                    }
                }
            },
            {
                name: "COORDINATOR_USERNAME",
                valueFrom: {
                    secretKeyRef: {
                        name: makeStringTypeProxy(coordinatorBasicCredsSecretName),
                        key: "username",
                        optional: true
                    }
                }
            },
            {
                name: "COORDINATOR_PASSWORD",
                valueFrom: {
                    secretKeyRef: {
                        name: makeStringTypeProxy(coordinatorBasicCredsSecretName),
                        key: "password",
                        optional: true
                    }
                }
            },
            // We don't have a mechanism to scrape these off disk so need to disable this to avoid filling up the disk
            {
                name: "FAILED_REQUESTS_LOGGER_LEVEL",
                value: "OFF"
            },
            {
                name: "CONSOLE_LOG_FORMAT",
                value: "json"
            }
        ],
        args: [
            "org.opensearch.migrations.RfsMigrateDocuments",
            "---INLINE-JSON",
            makeStringTypeProxy(args.jsonConfig)
        ],
        resources: makeDirectTypeProxy(args.resources)
    };

    const finalContainerDefinition= setupTestCredsForContainer(
        args.useLocalstackAwsCreds,
        setupLog4jConfigForContainer(
            useCustomLogging,
            args.loggingConfigMap,
            { container: baseContainerDefinition, volumes: []}
        )
    );
    return {
        apiVersion: "apps/v1",
        kind: "ReplicaSet",
        metadata: {
            name: makeStringTypeProxy(getRfsReplicasetName(args.sessionName)),
            labels: {
                "workflows.argoproj.io/workflow": makeStringTypeProxy(args.workflowName)
            },
        },
        spec: {
            replicas: makeDirectTypeProxy(args.podReplicas),
            selector: {
                matchLabels: {
                    app: "bulk-loader",
                },
            },
            template: {
                metadata: {
                    labels: {
                        app: "bulk-loader",
                        "workflows.argoproj.io/workflow": makeStringTypeProxy(args.workflowName),
                    },
                },
                spec: {
                    serviceAccountName: "argo-workflow-executor",
                    containers: [finalContainerDefinition.container],
                    volumes: [...finalContainerDefinition.volumes]
                }
            }
        }
    } as ReplicaSet;
}


function getCheckHistoricalBackfillCompletionScript(sessionName: BaseExpression<string>) {
    const template = `
set -e && 
python -c '
import sys
from lib.console_link.console_link.environment import Environment
from lib.console_link.console_link.models.backfill_rfs import get_detailed_status_obj
from lib.console_link.console_link.models.backfill_rfs import all_shards_finished_processing

status = get_detailed_status_obj(Environment(config_file="/config/migration_services.yaml").target_cluster,
                                 True,
                                 "{{SESSION_NAME}}")
print(status)
all_finished = all_shards_finished_processing(Environment(config_file="/config/migration_services.yaml").target_cluster,
                                              "{{SESSION_NAME}}")
sys.exit(0 if all_finished else 1)'`;
    return expr.fillTemplate(template, {"SESSION_NAME": sessionName});
}

export const DocumentBulkLoad = WorkflowBuilder.create({
    k8sResourceName: "document-bulk-load",
    serviceAccountName: "argo-workflow-executor"
})

    .addParams(CommonWorkflowParameters)


    .addTemplate("stopHistoricalBackfill", t => t
        .addRequiredInput("sessionName", typeToken<string>())
        .addResourceTask(b => b
            .setDefinition({
                action: "delete", flags: ["--ignore-not-found"],
                manifest: {
                    "apiVersion": "apps/v1",
                    "kind": "ReplicaSet",
                    "metadata": {
                        "name": getRfsReplicasetName(b.inputs.sessionName)
                    }
                }
            })
        ))


    .addTemplate("waitForCompletion", t => t
        .addRequiredInput("configContents", typeToken<z.infer<typeof CONSOLE_SERVICES_CONFIG_FILE>>())
        .addRequiredInput("sessionName", typeToken<string>())
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole"]))
        .addSteps(b => b
            .addStep("checkHistoricalBackfillCompletion", MigrationConsole, "runMigrationCommand", c =>
                c.register({
                    ...selectInputsForRegister(b, c),
                    command: getCheckHistoricalBackfillCompletionScript(b.inputs.sessionName)
                }))
        )
        .addRetryParameters({
            limit: "200",
            retryPolicy: "Always",
            backoff: {duration: "5", factor: "2", cap: "300"}
        })
    )


    .addTemplate("startHistoricalBackfill", t => t
        .addRequiredInput("sessionName", typeToken<string>())
        .addRequiredInput("rfsJsonConfig", typeToken<string>())
        .addRequiredInput("targetBasicCredsSecretNameOrEmpty", typeToken<string>())
        .addRequiredInput("coordinatorBasicCredsSecretNameOrEmpty", typeToken<string>())
        .addRequiredInput("podReplicas", typeToken<number>())
        .addRequiredInput("loggingConfigurationOverrideConfigMap", typeToken<string>())
        .addRequiredInput("useLocalStack", typeToken<boolean>(), "Only used for local testing")
        .addRequiredInput("resources", typeToken<ResourceRequirementsType>())
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["ReindexFromSnapshot"]))

        .addResourceTask(b => b
            .setDefinition({
                action: "create",
                setOwnerReference: true,
                manifest: getRfsReplicasetManifest({
                    podReplicas: expr.deserializeRecord(b.inputs.podReplicas),
                    loggingConfigMap: b.inputs.loggingConfigurationOverrideConfigMap,
                    useLocalstackAwsCreds: expr.deserializeRecord(b.inputs.useLocalStack),
                    sessionName: b.inputs.sessionName,
                    targetBasicCredsSecretNameOrEmpty: b.inputs.targetBasicCredsSecretNameOrEmpty,
                    coordinatorBasicCredsSecretNameOrEmpty: b.inputs.coordinatorBasicCredsSecretNameOrEmpty,
                    rfsImageName: b.inputs.imageReindexFromSnapshotLocation,
                    rfsImagePullPolicy: b.inputs.imageReindexFromSnapshotPullPolicy,
                    workflowName: expr.getWorkflowValue("name"),
                    jsonConfig: expr.toBase64(b.inputs.rfsJsonConfig),
                    resources: expr.deserializeRecord(b.inputs.resources),
                })
            }))
    )


    .addTemplate("startHistoricalBackfillFromConfig", t => t
        .addRequiredInput("sessionName", typeToken<string>())
        .addRequiredInput("sourceVersion", typeToken<z.infer<typeof CLUSTER_VERSION_STRING>>())

        .addRequiredInput("snapshotConfig", typeToken<z.infer<typeof COMPLETE_SNAPSHOT_CONFIG>>())
        .addRequiredInput("targetConfig", typeToken<z.infer<typeof NAMED_TARGET_CLUSTER_CONFIG>>())
        .addRequiredInput("coordinatorConfig", typeToken<z.infer<typeof NAMED_TARGET_CLUSTER_CONFIG>>())
        .addRequiredInput("documentBackfillConfig", typeToken<z.infer<typeof RFS_OPTIONS>>())
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["ReindexFromSnapshot"]))

        .addSteps(b => b
            .addStep("startHistoricalBackfill", INTERNAL, "startHistoricalBackfill", c =>
                c.register({
                    ...selectInputsForRegister(b,c),
                    podReplicas: expr.dig(expr.deserializeRecord(b.inputs.documentBackfillConfig), ["podReplicas"], 1),
                    targetBasicCredsSecretNameOrEmpty: getHttpAuthSecretName(b.inputs.targetConfig),
                    coordinatorBasicCredsSecretNameOrEmpty: getHttpAuthSecretName(b.inputs.coordinatorConfig),
                    loggingConfigurationOverrideConfigMap: expr.dig(expr.deserializeRecord(b.inputs.documentBackfillConfig), ["loggingConfigurationOverrideConfigMap"], ""),
                    useLocalStack: expr.dig(expr.deserializeRecord(b.inputs.snapshotConfig), ["repoConfig", "useLocalStack"], false),
                    rfsJsonConfig: expr.asString(expr.serialize(
                        makeParamsDict(b.inputs.sourceVersion,
                            b.inputs.targetConfig,
                            b.inputs.coordinatorConfig,
                            b.inputs.snapshotConfig,
                            b.inputs.documentBackfillConfig,
                            b.inputs.sessionName)
                    )),
                     resources: expr.serialize(expr.jsonPathStrict(b.inputs.documentBackfillConfig, "resources"))
                })
            )
        )
    )


    .addTemplate("runBulkLoad", t => t
        .addRequiredInput("sourceVersion", typeToken<z.infer<typeof CLUSTER_VERSION_STRING>>())
        .addRequiredInput("targetConfig", typeToken<z.infer<typeof NAMED_TARGET_CLUSTER_CONFIG>>())
        .addRequiredInput("coordinatorConfig", typeToken<z.infer<typeof NAMED_TARGET_CLUSTER_CONFIG>>())
        .addRequiredInput("snapshotConfig", typeToken<z.infer<typeof COMPLETE_SNAPSHOT_CONFIG>>())
        .addRequiredInput("sessionName", typeToken<string>())
        .addOptionalInput("indices", c => [] as readonly string[])
        .addRequiredInput("documentBackfillConfig", typeToken<z.infer<typeof RFS_OPTIONS>>())
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["ReindexFromSnapshot", "MigrationConsole"]))

        .addSteps(b => b
            .addStep("startHistoricalBackfillFromConfig", INTERNAL, "startHistoricalBackfillFromConfig", c =>
                c.register({
                    ...selectInputsForRegister(b, c)
                }))
            .addStep("setupWaitForCompletion", MigrationConsole, "getConsoleConfig", c =>
                c.register({
                    ...selectInputsForRegister(b, c),
                    targetConfig: b.inputs.coordinatorConfig
                }))
            .addStep("waitForCompletion", INTERNAL, "waitForCompletion", c =>
                c.register({
                    ...selectInputsForRegister(b, c),
                    configContents: c.steps.setupWaitForCompletion.outputs.configContents
                }))
            .addStep("stopHistoricalBackfill", INTERNAL, "stopHistoricalBackfill", c =>
                c.register({sessionName: b.inputs.sessionName}))
        )
    )


    .addTemplate("doNothing", t => t
        .addSteps(b => b.addStepGroup(c => c)))


    // Coordinator cluster deployment templates (OS 3.1)
    .addTemplate("createCoordinatorSecret", t => t
        .addRequiredInput("sessionName", typeToken<string>())
        .addResourceTask(b => b
            .setDefinition({
                action: "apply",
                manifest: {
                    apiVersion: "v1",
                    kind: "Secret",
                    metadata: {
                        name: makeStringTypeProxy(getCoordinatorCredsSecretName(b.inputs.sessionName)),
                        labels: {
                            "rfs-coordinator": "true",
                            "session-name": makeStringTypeProxy(b.inputs.sessionName)
                        }
                    },
                    type: "Opaque",
                    stringData: {
                        username: "admin",
                        password: "myStrongPassword123!"
                    }
                }
            })))


    .addTemplate("createCoordinatorService", t => t
        .addRequiredInput("sessionName", typeToken<string>())
        .addResourceTask(b => b
            .setDefinition({
                action: "apply",
                manifest: {
                    apiVersion: "v1",
                    kind: "Service",
                    metadata: {
                        name: makeStringTypeProxy(getCoordinatorClusterName(b.inputs.sessionName)),
                        labels: {
                            app: makeStringTypeProxy(getCoordinatorClusterName(b.inputs.sessionName)),
                            "rfs-coordinator": "true",
                            "session-name": makeStringTypeProxy(b.inputs.sessionName)
                        }
                    },
                    spec: {
                        selector: {
                            app: makeStringTypeProxy(getCoordinatorClusterName(b.inputs.sessionName))
                        },
                        ports: [{
                            name: "https",
                            port: 9200,
                            targetPort: "https"
                        }]
                    }
                }
            })))


    .addTemplate("createCoordinatorStatefulSet", t => t
        .addRequiredInput("sessionName", typeToken<string>())
        .addResourceTask(b => b
            .setDefinition({
                action: "apply",
                manifest: {
                    apiVersion: "apps/v1",
                    kind: "StatefulSet",
                    metadata: {
                        name: makeStringTypeProxy(getCoordinatorClusterName(b.inputs.sessionName)),
                        labels: {
                            app: makeStringTypeProxy(getCoordinatorClusterName(b.inputs.sessionName)),
                            "rfs-coordinator": "true",
                            "session-name": makeStringTypeProxy(b.inputs.sessionName)
                        }
                    },
                    spec: {
                        serviceName: makeStringTypeProxy(getCoordinatorClusterName(b.inputs.sessionName)),
                        replicas: 1,
                        persistentVolumeClaimRetentionPolicy: {
                            whenDeleted: "Delete",
                            whenScaled: "Retain"
                        },
                        selector: {
                            matchLabels: {
                                app: makeStringTypeProxy(getCoordinatorClusterName(b.inputs.sessionName))
                            }
                        },
                        template: {
                            metadata: {
                                labels: {
                                    app: makeStringTypeProxy(getCoordinatorClusterName(b.inputs.sessionName)),
                                    "rfs-coordinator": "true",
                                    "session-name": makeStringTypeProxy(b.inputs.sessionName)
                                }
                            },
                            spec: {
                                serviceAccountName: "argo-workflow-executor",
                                initContainers: [{
                                    name: "install-plugins",
                                    image: "opensearchproject/opensearch:3.1.0",
                                    command: ["sh", "-c", `set -euo pipefail
cp -r /usr/share/opensearch/plugins/* /plugins/ 2>/dev/null || true
bin/opensearch-plugin install --batch repository-s3
cp -r /usr/share/opensearch/plugins/repository-s3 /plugins/`],
                                    volumeMounts: [{
                                        name: "plugins",
                                        mountPath: "/plugins"
                                    }]
                                }],
                                containers: [{
                                    name: "opensearch",
                                    image: "opensearchproject/opensearch:3.1.0",
                                    ports: [{
                                        name: "https",
                                        containerPort: 9200
                                    }],
                                    env: [
                                        { name: "cluster.name", value: makeStringTypeProxy(getCoordinatorClusterName(b.inputs.sessionName)) },
                                        { name: "discovery.type", value: "single-node" },
                                        {
                                            name: "OPENSEARCH_INITIAL_ADMIN_USERNAME",
                                            valueFrom: {
                                                secretKeyRef: {
                                                    name: makeStringTypeProxy(getCoordinatorCredsSecretName(b.inputs.sessionName)),
                                                    key: "username"
                                                }
                                            }
                                        },
                                        {
                                            name: "OPENSEARCH_INITIAL_ADMIN_PASSWORD",
                                            valueFrom: {
                                                secretKeyRef: {
                                                    name: makeStringTypeProxy(getCoordinatorCredsSecretName(b.inputs.sessionName)),
                                                    key: "password"
                                                }
                                            }
                                        },
                                        { name: "OPENSEARCH_JAVA_OPTS", value: "-Xms2g -Xmx2g" }
                                    ],
                                    resources: {
                                        requests: { cpu: "2", memory: "4Gi" },
                                        limits: { cpu: "2", memory: "4Gi" }
                                    },
                                    readinessProbe: {
                                        exec: {
                                            command: ["sh", "-c", `curl -sk -u "\${OPENSEARCH_INITIAL_ADMIN_USERNAME}:\${OPENSEARCH_INITIAL_ADMIN_PASSWORD}" "https://localhost:9200/_cluster/health?wait_for_status=yellow&timeout=1s"`]
                                        },
                                        initialDelaySeconds: 5,
                                        periodSeconds: 5,
                                        timeoutSeconds: 3,
                                        failureThreshold: 24
                                    },
                                    volumeMounts: [
                                        { name: "data", mountPath: "/usr/share/opensearch/data" },
                                        { name: "plugins", mountPath: "/usr/share/opensearch/plugins" }
                                    ]
                                }],
                                volumes: [{
                                    name: "plugins",
                                    emptyDir: {}
                                }]
                            }
                        },
                        volumeClaimTemplates: [{
                            metadata: { name: "data" },
                            spec: {
                                accessModes: ["ReadWriteOnce"],
                                resources: {
                                    requests: { storage: "1Gi" }
                                }
                            }
                        }]
                    }
                }
            })))


    .addTemplate("deployCoordinatorCluster", t => t
        .addRequiredInput("sessionName", typeToken<string>())
        .addSteps(b => b
            .addStep("createSecret", INTERNAL, "createCoordinatorSecret", c =>
                c.register({ sessionName: b.inputs.sessionName }))
            .addStep("createService", INTERNAL, "createCoordinatorService", c =>
                c.register({ sessionName: b.inputs.sessionName }))
            .addStep("createStatefulSet", INTERNAL, "createCoordinatorStatefulSet", c =>
                c.register({ sessionName: b.inputs.sessionName }))
        ))


    .addTemplate("waitForCoordinatorClusterReady", t => t
        .addRequiredInput("sessionName", typeToken<string>())
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole"]))
        .addContainer(cb => cb
            .addImageInfo(cb.inputs.imageMigrationConsoleLocation, cb.inputs.imageMigrationConsolePullPolicy)
            .addCommand(["sh", "-c"])
            .addResources(DEFAULT_RESOURCES.MIGRATION_CONSOLE_CLI)
            .addArgs([makeStringTypeProxy(expr.fillTemplate(
                `kubectl wait --for=condition=ready pod/{{CLUSTER_NAME}}-0 --timeout=300s`,
                { "CLUSTER_NAME": getCoordinatorClusterName(cb.inputs.sessionName) }
            ))]))
        .addRetryParameters({
            limit: "60",
            retryPolicy: "Always",
            backoff: { duration: "5", factor: "1", cap: "5" }
        }))


    .addTemplate("deleteCoordinatorCluster", t => t
        .addRequiredInput("sessionName", typeToken<string>())
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole"]))
        .addContainer(cb => cb
            .addImageInfo(cb.inputs.imageMigrationConsoleLocation, cb.inputs.imageMigrationConsolePullPolicy)
            .addCommand(["sh", "-c"])
            .addResources(DEFAULT_RESOURCES.MIGRATION_CONSOLE_CLI)
            .addArgs([makeStringTypeProxy(expr.fillTemplate(
                `set -e
CLUSTER_NAME="{{CLUSTER_NAME}}"
echo "Deleting coordinator cluster: $CLUSTER_NAME"
kubectl delete statefulset "$CLUSTER_NAME" --ignore-not-found
kubectl delete service "$CLUSTER_NAME" --ignore-not-found
kubectl delete secret "$CLUSTER_NAME-creds" --ignore-not-found
kubectl delete pvc -l app="$CLUSTER_NAME" --ignore-not-found
echo "Coordinator cluster deleted"`,
                { "CLUSTER_NAME": getCoordinatorClusterName(cb.inputs.sessionName) }
            ))])))


    .addTemplate("setupAndRunBulkLoad", t => t
        .addRequiredInput("sourceVersion", typeToken<z.infer<typeof CLUSTER_VERSION_STRING>>())
        .addRequiredInput("targetConfig", typeToken<z.infer<typeof NAMED_TARGET_CLUSTER_CONFIG>>())
        .addRequiredInput("snapshotConfig", typeToken<z.infer<typeof COMPLETE_SNAPSHOT_CONFIG>>())
        .addRequiredInput("sessionName", typeToken<string>())
        .addOptionalInput("indices", c => [] as readonly string[])
        .addRequiredInput("documentBackfillConfig", typeToken<z.infer<typeof RFS_OPTIONS>>())
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["ReindexFromSnapshot", "MigrationConsole"]))

        .addSteps(b => {
            const useTargetForCoordination = expr.dig(
                expr.deserializeRecord(b.inputs.documentBackfillConfig),
                ["useTargetClusterForWorkCoordination"],
                true
            );
            const deployCoordinator = expr.not(useTargetForCoordination);
            const coordinatorClusterName = getCoordinatorClusterName(b.inputs.sessionName);
            const coordinatorCredsSecretName = getCoordinatorCredsSecretName(b.inputs.sessionName);

            // Build coordinator config for dedicated cluster
            const dedicatedCoordinatorConfig = expr.serialize(expr.makeDict({
                name: expr.literal("coordinator"),
                endpoint: expr.concat(expr.literal("https://"), coordinatorClusterName, expr.literal(":9200")),
                allowInsecure: expr.literal(true),
                version: expr.literal("OS 3.1"),
                authConfig: expr.makeDict({
                    basic: expr.makeDict({
                        secretName: coordinatorCredsSecretName
                    })
                })
            }));

            return b
                .addStep("deployCoordinatorCluster", INTERNAL, "deployCoordinatorCluster", c =>
                    c.register({ sessionName: b.inputs.sessionName }),
                    { when: { templateExp: deployCoordinator } })
                .addStep("waitForCoordinatorClusterReady", INTERNAL, "waitForCoordinatorClusterReady", c =>
                    c.register({ ...selectInputsForRegister(b, c), sessionName: b.inputs.sessionName }),
                    { when: { templateExp: deployCoordinator } })
                .addStep("runBulkLoad", INTERNAL, "runBulkLoad", c =>
                    c.register({
                        ...selectInputsForRegister(b, c),
                        coordinatorConfig: expr.ternary(
                            deployCoordinator,
                            dedicatedCoordinatorConfig,
                            b.inputs.targetConfig
                        )
                    }))
                .addStep("deleteCoordinatorCluster", INTERNAL, "deleteCoordinatorCluster", c =>
                    c.register({ ...selectInputsForRegister(b, c), sessionName: b.inputs.sessionName }),
                    { when: { templateExp: deployCoordinator } });
        })
    )

    .getFullScope();
