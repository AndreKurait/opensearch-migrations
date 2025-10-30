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
                        expr.asString(b.inputs.iterationNumber)
                    )
                }))
            .addTask("runRfsIteration", DocumentBulkLoad, "runBulkLoad",
                c => c.register({
                    ...selectInputsForRegister(b, c),
                    snapshotConfig: expr.serialize(c.tasks.createSnapshot.outputs.snapshotConfig),
                    // For non-first iterations, add experimental parameters
                    documentBackfillConfig: expr.serialize(
                        expr.ternary(
                            new ComparisonExpression(">", expr.deserializeRecord(b.inputs.iterationNumber), expr.literal(1)),
                            expr.mergeDicts(
                                expr.deserializeRecord(b.inputs.documentBackfillConfig),
                                expr.makeDict({
                                    experimentalPreviousSnapshotName: expr.concat(
                                        b.inputs.sessionName,
                                        expr.literal("-iteration-"),
                                        expr.asString(new InfixExpression<number, any, any>("-", expr.deserializeRecord(b.inputs.iterationNumber), expr.literal(1), typeToken<number>()))
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

        .addSteps(b => b
            .addStep("runRfsIterationWithSnapshot", INTERNAL, "runSingleRfsIteration",
                c => c.register({
                    ...selectInputsForRegister(b, c),
                    iterationNumber: c.item
                }),
                {
                    loopWith: makeParameterLoop(
                        // Create an infinite array by using a large range
                        // The loop will continue until manually cancelled
                        expr.toArray(
                            expr.literal(1), expr.literal(2), expr.literal(3), 
                            expr.literal(4), expr.literal(5), expr.literal(6),
                            expr.literal(7), expr.literal(8), expr.literal(9),
                            expr.literal(10), expr.literal(11), expr.literal(12),
                            expr.literal(13), expr.literal(14), expr.literal(15),
                            expr.literal(16), expr.literal(17), expr.literal(18),
                            expr.literal(19), expr.literal(20), expr.literal(21),
                            expr.literal(22), expr.literal(23), expr.literal(24),
                            expr.literal(25), expr.literal(26), expr.literal(27),
                            expr.literal(28), expr.literal(29), expr.literal(30),
                            expr.literal(31), expr.literal(32), expr.literal(33),
                            expr.literal(34), expr.literal(35), expr.literal(36),
                            expr.literal(37), expr.literal(38), expr.literal(39),
                            expr.literal(40), expr.literal(41), expr.literal(42),
                            expr.literal(43), expr.literal(44), expr.literal(45),
                            expr.literal(46), expr.literal(47), expr.literal(48),
                            expr.literal(49), expr.literal(50), expr.literal(51),
                            expr.literal(52), expr.literal(53), expr.literal(54),
                            expr.literal(55), expr.literal(56), expr.literal(57),
                            expr.literal(58), expr.literal(59), expr.literal(60),
                            expr.literal(61), expr.literal(62), expr.literal(63),
                            expr.literal(64), expr.literal(65), expr.literal(66),
                            expr.literal(67), expr.literal(68), expr.literal(69),
                            expr.literal(70), expr.literal(71), expr.literal(72),
                            expr.literal(73), expr.literal(74), expr.literal(75),
                            expr.literal(76), expr.literal(77), expr.literal(78),
                            expr.literal(79), expr.literal(80), expr.literal(81),
                            expr.literal(82), expr.literal(83), expr.literal(84),
                            expr.literal(85), expr.literal(86), expr.literal(87),
                            expr.literal(88), expr.literal(89), expr.literal(90),
                            expr.literal(91), expr.literal(92), expr.literal(93),
                            expr.literal(94), expr.literal(95), expr.literal(96),
                            expr.literal(97), expr.literal(98), expr.literal(99),
                            expr.literal(100)
                        )
                    )
                })
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
