import {z} from 'zod';
import {
    CLUSTER_VERSION_STRING,
    COMPLETE_SNAPSHOT_CONFIG,
    DYNAMIC_SNAPSHOT_CONFIG,
    getZodKeys,
    METADATA_OPTIONS,
    NAMED_SOURCE_CLUSTER_CONFIG,
    NAMED_TARGET_CLUSTER_CONFIG,
    NORMALIZED_PARAMETERIZED_MIGRATION_CONFIG,
    PARAMETERIZED_MIGRATION_CONFIG,
    PER_INDICES_SNAPSHOT_MIGRATION_CONFIG,
    REPLAYER_OPTIONS,
    RFS_OPTIONS,
    SNAPSHOT_MIGRATION_CONFIG,
    SOURCE_CLUSTERS_MAP,
    TARGET_CLUSTER_CONFIG,
    TARGET_CLUSTERS_MAP
} from '@opensearch-migrations/schemas'
import {
    CommonWorkflowParameters,
    ImageParameters,
    LogicalOciImages,
    makeRequiredImageParametersForKeys
} from "./commonWorkflowTemplates";
import {ConfigManagementHelpers} from "./configManagementHelpers";
import {
    AllowLiteralOrExpression,
    BaseExpression,
    ComparisonExpression,
    configMapKey,
    defineParam,
    defineRequiredParam,
    expr,
    IMAGE_PULL_POLICY,
    InfixExpression,
    InputParamDef,
    INTERNAL,
    makeParameterLoop,
    NonSerializedPlainObject,
    selectInputsFieldsAsExpressionRecord,
    selectInputsForRegister,
    Serialized, transformZodObjectToParams,
    typeToken,
    WorkflowBuilder
} from '@opensearch-migrations/argo-workflow-builders';
import {DocumentBulkLoad} from "./documentBulkLoad";
import {MetadataMigration} from "./metadataMigration";
import {CreateOrGetSnapshot} from "./createOrGetSnapshot";

const latchCoordinationPrefixParam = {
    latchCoordinationPrefix: defineRequiredParam<string>({description: "Workflow session nonce"})
};

function lowercaseFirst(str: string): string {
    return str.charAt(0).toLowerCase() + str.slice(1);
}

function defaultImagesMap(imageConfigMapName: AllowLiteralOrExpression<string>) {
    return Object.fromEntries(LogicalOciImages.flatMap(k => [
            [`image${k}Location`, defineParam({
                type: typeToken<string>(),
                from: configMapKey(imageConfigMapName,
                    `${lowercaseFirst(k)}Image`)
            })],
            [`image${k}PullPolicy`, defineParam({
                type: typeToken<string>(),
                from: configMapKey(imageConfigMapName,
                    `${lowercaseFirst(k)}PullPolicy`)
            })]
        ])
    ) as Record<`image${typeof LogicalOciImages[number]}Location`, InputParamDef<string, false>> &
        Record<`image${typeof LogicalOciImages[number]}PullPolicy`, InputParamDef<IMAGE_PULL_POLICY, false>>;
}

export const FullMigration = WorkflowBuilder.create({
    k8sResourceName: "full-migration",
    parallelism: 100,
    serviceAccountName: "argo-workflow-executor"
})


    .addParams(CommonWorkflowParameters)


    .addTemplate("doNothing", t => t
        .addSteps(b => b.addStepGroup(c => c)))


    .addTemplate("runReplayerForTarget", t => t
        .addRequiredInput("targetConfig", typeToken<z.infer<typeof TARGET_CLUSTER_CONFIG>>())
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole"]))
        .addContainer(cb => cb
            .addImageInfo(cb.inputs.imageMigrationConsoleLocation, cb.inputs.imageMigrationConsolePullPolicy)
            .addCommand(["sh", "-c"])
            .addArgs(["echo runReplayerForTarget"])))


    .addTemplate("runSingleRfsIteration", t => t
        .addRequiredInput("sourceVersion", typeToken<z.infer<typeof CLUSTER_VERSION_STRING>>())
        .addRequiredInput("sourceConfig", typeToken<z.infer<typeof NAMED_SOURCE_CLUSTER_CONFIG>>())
        .addRequiredInput("targetConfig", typeToken<z.infer<typeof NAMED_TARGET_CLUSTER_CONFIG>>())
        .addRequiredInput("snapshotConfig", typeToken<z.infer<typeof COMPLETE_SNAPSHOT_CONFIG>>())
        .addRequiredInput("sessionName", typeToken<string>())
        .addRequiredInput("iterationNumber", typeToken<number>())
        .addOptionalInput("indices", c => [] as readonly string[])
        .addRequiredInput("documentBackfillConfig", typeToken<z.infer<typeof RFS_OPTIONS>>())
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["ReindexFromSnapshot", "MigrationConsole"]))

        .addDag(b => b
            .addTask("createSnapshot", CreateOrGetSnapshot, "createOrGetSnapshot",
                c => c.register({
                    ...selectInputsForRegister(b, c),
                    sourceConfig: b.inputs.sourceConfig,
                    snapshotConfig: expr.serialize(expr.makeDict({
                        repoConfig: expr.get(expr.deserializeRecord(b.inputs.snapshotConfig), "repoConfig")
                    })),
                    indices: b.inputs.indices as any,
                    autocreateSnapshotName: expr.concat(
                        b.inputs.sessionName,
                        expr.literal("-iteration-"),
                        expr.asString(expr.deserializeRecord(b.inputs.iterationNumber))
                    )
                }))
            .addTask("runRfsIteration", DocumentBulkLoad, "runBulkLoad",
                c => c.register({
                    ...selectInputsForRegister(b, c),
                    snapshotConfig: expr.serialize(c.tasks.createSnapshot.outputs.snapshotConfig),
                    // For non-first iterations, add experimental parameters
                    documentBackfillConfig: expr.serialize(
                        expr.ternary(
                            expr.greaterThan(expr.deserializeRecord(b.inputs.iterationNumber), expr.literal(1)),
                            expr.mergeDicts(
                                expr.deserializeRecord(b.inputs.documentBackfillConfig),
                                expr.makeDict({
                                    experimentalPreviousSnapshotName: expr.concat(
                                        b.inputs.sessionName,
                                        expr.literal("-iteration-"),
                                        expr.asString(new InfixExpression("-", expr.deserializeRecord(b.inputs.iterationNumber), expr.literal(1)))
                                    ),
                                    experimentalDeltaMode: expr.literal("UPDATES_AND_DELETES")
                                })
                            ),
                            expr.deserializeRecord(b.inputs.documentBackfillConfig)
                        )
                    )
                }),
                {dependencies: ["createSnapshot"]})
        )
    )


    .addTemplate("runRfsContinuously", t => t
        .addRequiredInput("sourceVersion", typeToken<z.infer<typeof CLUSTER_VERSION_STRING>>())
        .addRequiredInput("sourceConfig", typeToken<z.infer<typeof NAMED_SOURCE_CLUSTER_CONFIG>>())
        .addRequiredInput("targetConfig", typeToken<z.infer<typeof NAMED_TARGET_CLUSTER_CONFIG>>())
        .addRequiredInput("snapshotConfig", typeToken<z.infer<typeof COMPLETE_SNAPSHOT_CONFIG>>())
        .addRequiredInput("sessionName", typeToken<string>())
        .addOptionalInput("indices", c => [] as readonly string[])
        .addRequiredInput("documentBackfillConfig", typeToken<z.infer<typeof RFS_OPTIONS>>())
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["ReindexFromSnapshot", "MigrationConsole"]))

        .addDag(b => b
            .addTask("iteration1", INTERNAL, "runSingleRfsIteration",
                c => c.register({
                    ...selectInputsForRegister(b, c),
                    iterationNumber: expr.literal(1)
                }))
            .addTask("iteration2", INTERNAL, "runSingleRfsIteration",
                c => c.register({
                    ...selectInputsForRegister(b, c),
                    iterationNumber: expr.literal(2)
                }),
                {dependencies: ["iteration1"]})
            .addTask("iteration3", INTERNAL, "runSingleRfsIteration",
                c => c.register({
                    ...selectInputsForRegister(b, c),
                    iterationNumber: expr.literal(3)
                }),
                {dependencies: ["iteration2"]})
            .addTask("iteration4", INTERNAL, "runSingleRfsIteration",
                c => c.register({
                    ...selectInputsForRegister(b, c),
                    iterationNumber: expr.literal(4)
                }),
                {dependencies: ["iteration3"]})
            .addTask("iteration5", INTERNAL, "runSingleRfsIteration",
                c => c.register({
                    ...selectInputsForRegister(b, c),
                    iterationNumber: expr.literal(5)
                }),
                {dependencies: ["iteration4"]})
            .addTask("iteration6", INTERNAL, "runSingleRfsIteration",
                c => c.register({
                    ...selectInputsForRegister(b, c),
                    iterationNumber: expr.literal(6)
                }),
                {dependencies: ["iteration5"]})
            .addTask("iteration7", INTERNAL, "runSingleRfsIteration",
                c => c.register({
                    ...selectInputsForRegister(b, c),
                    iterationNumber: expr.literal(7)
                }),
                {dependencies: ["iteration6"]})
            .addTask("iteration8", INTERNAL, "runSingleRfsIteration",
                c => c.register({
                    ...selectInputsForRegister(b, c),
                    iterationNumber: expr.literal(8)
                }),
                {dependencies: ["iteration7"]})
            .addTask("iteration9", INTERNAL, "runSingleRfsIteration",
                c => c.register({
                    ...selectInputsForRegister(b, c),
                    iterationNumber: expr.literal(9)
                }),
                {dependencies: ["iteration8"]})
            .addTask("iteration10", INTERNAL, "runSingleRfsIteration",
                c => c.register({
                    ...selectInputsForRegister(b, c),
                    iterationNumber: expr.literal(10)
                }),
                {dependencies: ["iteration9"]})
        )
    )


    .addTemplate("foreachSnapshotMigration", t => t
        .addRequiredInput("sourceConfig", typeToken<z.infer<typeof NAMED_SOURCE_CLUSTER_CONFIG>>())
        .addRequiredInput("targetConfig", typeToken<z.infer<typeof NAMED_TARGET_CLUSTER_CONFIG>>())
        .addRequiredInput("snapshotConfig", typeToken<z.infer<typeof COMPLETE_SNAPSHOT_CONFIG>>())
        .addOptionalInput("metadataMigrationConfig", c=>
            expr.empty<z.infer<typeof METADATA_OPTIONS>>())
        .addOptionalInput("documentBackfillConfig",  c=>
            expr.empty<z.infer<typeof RFS_OPTIONS>>())

        .addInputsFromRecord(latchCoordinationPrefixParam)
        .addInputsFromRecord(ImageParameters)

        .addSteps(b => b
            .addStep("idGenerator", INTERNAL, "doNothing")
            .addStep("metadataMigrate", MetadataMigration, "migrateMetaData", c => {
                    return c.register({
                        ...selectInputsForRegister(b, c)
                    });
                },
                { when: { templateExp: expr.not(expr.isEmpty(b.inputs.metadataMigrationConfig)) }}
            )
            .addStep("bulkLoadDocumentsLoop", INTERNAL, "runRfsContinuously", c => {
                    return c.register({
                        ...(selectInputsForRegister(b, c)),
                        sessionName: c.steps.idGenerator.id,
                        sourceVersion: expr.jsonPathStrict(b.inputs.sourceConfig, "version")
                    });
                },
                { when: { templateExp: expr.not(expr.isEmpty(b.inputs.documentBackfillConfig)) }}
            )
            // .addStep("targetBackfillCompleteCheck", ConfigManagementHelpers, "decrementLatch", c =>
            //     c.register({
            //         ...(selectInputsForRegister(b, c)),
            //         prefix: b.inputs.latchCoordinationPrefix,
            //         targetName: expr.jsonPathStrict(b.inputs.targetConfig, "name"),
            //         processorId: c.steps.idGenerator.id
            //     }))
            // // TODO - move this upward
            // .addStep("runReplayerForTarget", INTERNAL, "runReplayerForTarget", c =>
            //     c.register({
            //         ...selectInputsForRegister(b, c)
            //     }))
        )
    )


    .addTemplate("foreachSnapshotExtraction", t => t
        .addRequiredInput("sourceConfig", typeToken<z.infer<typeof NAMED_SOURCE_CLUSTER_CONFIG>>())
        .addRequiredInput("targetConfig", typeToken<z.infer<typeof NAMED_TARGET_CLUSTER_CONFIG>>())
        .addInputsFromRecord(transformZodObjectToParams(SNAPSHOT_MIGRATION_CONFIG))

        .addOptionalInput("sourcePipelineName", c=>
            expr.concatWith("_",
                expr.get(expr.deserializeRecord(c.inputParameters.sourceConfig), "name"),
                expr.join(expr.deserializeRecord(c.inputParameters.indices))
            )
        )
        .addRequiredInput("latchCoordinationPrefix", typeToken<string>())
        .addInputsFromRecord(ImageParameters)

        .addSteps(b => b
            .addStep("createOrGetSnapshot", CreateOrGetSnapshot, "createOrGetSnapshot",
                c => c.register({
                    ...selectInputsForRegister(b, c),
                    // ...selectInputsFieldsAsExpressionRecord(b.inputs.snapshotConfigAlias, c),
                    indices: b.inputs.indices,
                    autocreateSnapshotName: b.inputs.sourcePipelineName,
                }))

            .addStep("foreachSnapshotMigration", INTERNAL, "foreachSnapshotMigration", c=> {
                    const d = c.defaults;
                    const o = c.item;
                    console.log(d + " " + o);
                    return c.register({
                        ...(() => {
                            const { snapshotConfig, ...rest } = selectInputsForRegister(b, c);
                            return rest;
                        })(),
                        ...selectInputsFieldsAsExpressionRecord(c.item, c,
                            getZodKeys(PER_INDICES_SNAPSHOT_MIGRATION_CONFIG)),
                        snapshotConfig: expr.serialize(c.steps.createOrGetSnapshot.outputs.snapshotConfig)
                    });
                },
                {loopWith: makeParameterLoop(expr.deserializeRecord(b.inputs.migrations))}

            )
        )
    )


    .addTemplate("foreachMigrationPair", t=>t
        .addRequiredInput("sourceConfig", typeToken<z.infer<typeof NAMED_SOURCE_CLUSTER_CONFIG>>())
        .addRequiredInput("targetConfig", typeToken<z.infer<typeof NAMED_TARGET_CLUSTER_CONFIG>>())
        .addOptionalInput("snapshotExtractAndLoadConfigArray",
            c => expr.empty<z.infer<typeof SNAPSHOT_MIGRATION_CONFIG>[]>())
        .addOptionalInput("replayerConfig",
            c => expr.empty<z.infer<typeof REPLAYER_OPTIONS>>())

        .addRequiredInput("latchCoordinationPrefix", typeToken<string>())
        .addInputsFromRecord(ImageParameters)

        .addSteps(b=>b
            .addStep("foreachSnapshotExtraction", INTERNAL, "foreachSnapshotExtraction", c =>
                    c.register({
                        ...selectInputsForRegister(b, c),
                        ...selectInputsFieldsAsExpressionRecord(c.item, c, getZodKeys(SNAPSHOT_MIGRATION_CONFIG))
                    }),
                {
                    when: { templateExp: expr.not(expr.isEmpty(b.inputs.snapshotExtractAndLoadConfigArray)) },
                    loopWith: makeParameterLoop(
                        expr.deserializeRecord(b.inputs.snapshotExtractAndLoadConfigArray))
                }
            )
            // TODO - add a sensor here to wait for an event
        )
    )


    .addTemplate("main", t => t
        .addRequiredInput("migrationConfigs", typeToken<z.infer<typeof PARAMETERIZED_MIGRATION_CONFIG>[]>(),
            "List of server configurations to direct migrated traffic toward") // expand

        .addRequiredInput("latchCoordinationPrefix", typeToken<string>())
        .addInputsFromRecord(defaultImagesMap(t.inputs.workflowParameters.imageConfigMapName))

        .addSteps(b => b
            .addStep("foreachMigrationPair", INTERNAL, "foreachMigrationPair",
                c => {
                    return c.register({
                        ...selectInputsForRegister(b, c),
                        ...selectInputsFieldsAsExpressionRecord(c.item, c,
                            getZodKeys(PARAMETERIZED_MIGRATION_CONFIG))
                    });
                },
                {loopWith: makeParameterLoop(expr.deserializeRecord(b.inputs.migrationConfigs))})
            .addStep("cleanup", ConfigManagementHelpers, "cleanup",
                c => c.register({
                    ...selectInputsForRegister(b, c),
                    prefix: b.inputs.latchCoordinationPrefix
                }))
        )
    )


    .setEntrypoint("main")
    .getFullScope();
