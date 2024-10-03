import {Construct} from "constructs";
import {Stack, StackProps} from "aws-cdk-lib";
import {readFileSync} from 'fs';
import {EngineVersion, TLSSecurityPolicy} from "aws-cdk-lib/aws-opensearchservice";
import * as defaultValuesJson from "../default-values.json"
import {NetworkStack} from "./network-stack";
import {MigrationAssistanceStack} from "./migration-assistance-stack";
import {MigrationConsoleStack} from "./service-stacks/migration-console-stack";
import {determineStreamingSourceType, StreamingSourceType} from "./streaming-source-type";
import {
    ClusterAuth,
    ClusterNoAuth,
    parseClusterDefinition,
    validateFargateCpuArch
} from "./common-utilities";
import {ReindexFromSnapshotStack} from "./service-stacks/reindex-from-snapshot-stack";
import {ClientOptions, ClusterYaml, ServicesYaml} from "./migration-services-yaml";

export interface StackPropsExt extends StackProps {
    readonly stage: string,
    readonly defaultDeployId: string,
    readonly addOnMigrationDeployId?: string
}

export interface StackComposerProps extends StackProps {
    readonly migrationsSolutionVersion: string,
    readonly migrationsAppRegistryARN?: string,
    readonly migrationsUserAgent?: string
}

export class StackComposer {
    public stacks: Stack[] = [];

    private getContextForType(optionName: string, expectedType: string, defaultValues: { [x: string]: (any); }, contextJSON: { [x: string]: (any); }): any {
        const option = contextJSON[optionName]

        // If no context is provided (undefined or empty string) and a default value exists, use it
        if ((option === undefined || option === "") && defaultValues[optionName]) {
            return defaultValues[optionName]
        }

        // Filter out invalid or missing options by setting undefined (empty strings, null, undefined, NaN)
        if (option !== false && option !== 0 && !option) {
            return undefined
        }
        // Values provided by the CLI will always be represented as a string and need to be parsed
        if (typeof option === 'string') {
            if (expectedType === 'number') {
                return parseInt(option)
            }
            if (expectedType === 'boolean' || expectedType === 'object') {
                try {
                    return JSON.parse(option)
                } catch (e) {
                    if (e instanceof SyntaxError) {
                        console.error(`Unable to parse option: ${optionName} with expected type: ${expectedType}`)
                    }
                    throw e
                }
            }
        }
        // Values provided by the cdk.context.json should be of the desired type
        if (typeof option !== expectedType) {
            throw new Error(`Type provided by cdk.context.json for ${optionName} was ${typeof option} but expected ${expectedType}`)
        }
        return option
    }

    private getEngineVersion(engineVersionString: string) : EngineVersion {
        let version: EngineVersion
        if (engineVersionString?.startsWith("OS_")) {
            // Will accept a period delimited version string (i.e. 1.3) and return a proper EngineVersion
            version = EngineVersion.openSearch(engineVersionString.substring(3))
        } else if (engineVersionString?.startsWith("ES_")) {
            version = EngineVersion.elasticsearch(engineVersionString.substring(3))
        } else {
            throw new Error(`Engine version (${engineVersionString}) is not present or does not match the expected format, i.e. OS_1.3 or ES_7.9`)
        }
        return version
    }

    private addDependentStacks(primaryStack: Stack, dependantStacks: any[]) {
        for (let stack of dependantStacks) {
            if (stack) {
                primaryStack.addDependency(stack)
            }
        }
    }

    private parseContextBlock(scope: Construct, contextId: string) {
        const contextFile = scope.node.tryGetContext("contextFile")
        if (contextFile) {
            const fileString = readFileSync(contextFile, 'utf-8');
            let fileJSON
            try {
                fileJSON = JSON.parse(fileString)
            } catch (error) {
                throw new Error(`Unable to parse context file ${contextFile} into JSON with following error: ${error}`);
            }
            const contextBlock = fileJSON[contextId]
            if (!contextBlock) {
                throw new Error(`No CDK context block found for contextId '${contextId}' in file ${contextFile}`)
            }
            return contextBlock
        }

        let contextJSON = scope.node.tryGetContext(contextId)
        if (!contextJSON) {
            throw new Error(`No CDK context block found for contextId '${contextId}'`)
        }
        // For a context block to be provided as a string (as in the case of providing via command line) it will need to be properly escaped
        // to be captured. This requires JSON to parse twice, 1. Returns a normal JSON string with no escaping 2. Returns a JSON object for use
        if (typeof contextJSON === 'string') {
            contextJSON = JSON.parse(JSON.parse(contextJSON))
        }
        return contextJSON
    }

    constructor(scope: Construct, props: StackComposerProps) {

        const defaultValues: { [x: string]: (any); } = defaultValuesJson
        const region = props.env?.region
        const defaultDeployId = 'default'

        const contextId = scope.node.tryGetContext("contextId")
        if (!contextId) {
            throw new Error("Required context field 'contextId' not provided")
        }
        const contextJSON = this.parseContextBlock(scope, contextId)
        console.log('Received following context block for deployment: ')
        console.log(contextJSON)
        console.log('End of context block.')

        const stage = this.getContextForType('stage', 'string', defaultValues, contextJSON)

        const domainName = this.getContextForType('domainName', 'string', defaultValues, contextJSON)
        const engineVersion = this.getContextForType('engineVersion', 'string', defaultValues, contextJSON)
        const fineGrainedManagerUserName = this.getContextForType('fineGrainedManagerUserName', 'string', defaultValues, contextJSON)
        const fineGrainedManagerUserSecretManagerKeyARN = this.getContextForType('fineGrainedManagerUserSecretManagerKeyARN', 'string', defaultValues, contextJSON)
        const vpcId = this.getContextForType('vpcId', 'string', defaultValues, contextJSON)
        const vpcAZCount = this.getContextForType('vpcAZCount', 'number', defaultValues, contextJSON)
        const mskARN = this.getContextForType('mskARN', 'string', defaultValues, contextJSON)
        const mskBrokersPerAZCount = this.getContextForType('mskBrokersPerAZCount', 'number', defaultValues, contextJSON)
        const mskSubnetIds = this.getContextForType('mskSubnetIds', 'object', defaultValues, contextJSON)
        const mskAZCount = this.getContextForType('mskAZCount', 'number', defaultValues, contextJSON)
        const replayerOutputEFSRemovalPolicy = this.getContextForType('replayerOutputEFSRemovalPolicy', 'string', defaultValues, contextJSON)
        const artifactBucketRemovalPolicy = this.getContextForType('artifactBucketRemovalPolicy', 'string', defaultValues, contextJSON)
        const addOnMigrationDeployId = this.getContextForType('addOnMigrationDeployId', 'string', defaultValues, contextJSON)
        const defaultFargateCpuArch = this.getContextForType('defaultFargateCpuArch', 'string', defaultValues, contextJSON)
        const captureProxyESServiceEnabled = this.getContextForType('captureProxyESServiceEnabled', 'boolean', defaultValues, contextJSON)
        const migrationConsoleServiceEnabled = this.getContextForType('migrationConsoleServiceEnabled', 'boolean', defaultValues, contextJSON)
        const migrationConsoleEnableOSI = this.getContextForType('migrationConsoleEnableOSI', 'boolean', defaultValues, contextJSON)
        const migrationAPIEnabled = this.getContextForType('migrationAPIEnabled', 'boolean', defaultValues, contextJSON)
        const migrationAPIAllowedHosts = this.getContextForType('migrationAPIAllowedHosts', 'string', defaultValues, contextJSON)
        const trafficReplayerServiceEnabled = this.getContextForType('trafficReplayerServiceEnabled', 'boolean', defaultValues, contextJSON)
        const captureProxyServiceEnabled = this.getContextForType('captureProxyServiceEnabled', 'boolean', defaultValues, contextJSON)
        const targetClusterProxyServiceEnabled = this.getContextForType('targetClusterProxyServiceEnabled', 'boolean', defaultValues, contextJSON)
        const elasticsearchServiceEnabled = this.getContextForType('elasticsearchServiceEnabled', 'boolean', defaultValues, contextJSON)
        const kafkaBrokerServiceEnabled = this.getContextForType('kafkaBrokerServiceEnabled', 'boolean', defaultValues, contextJSON)
        const osContainerServiceEnabled = this.getContextForType('osContainerServiceEnabled', 'boolean', defaultValues, contextJSON)
        const otelCollectorEnabled = this.getContextForType('otelCollectorEnabled', 'boolean', defaultValues, contextJSON)
        const reindexFromSnapshotServiceEnabled = this.getContextForType('reindexFromSnapshotServiceEnabled', 'boolean', defaultValues, contextJSON)
        const reindexFromSnapshotExtraArgs = this.getContextForType('reindexFromSnapshotExtraArgs', 'string', defaultValues, contextJSON)
        const reindexFromSnapshotMaxShardSizeGiB = this.getContextForType('reindexFromSnapshotMaxShardSizeGiB', 'number', defaultValues, contextJSON)
        const albAcmCertArn = this.getContextForType('albAcmCertArn', 'string', defaultValues, contextJSON);
        const managedServiceSourceSnapshotEnabled = this.getContextForType('managedServiceSourceSnapshotEnabled', 'boolean', defaultValues, contextJSON)
        const includeNatGateway = this.getContextForType('includeNatGateway', 'boolean', defaultValues, contextJSON)

        // We're in a transition state from an older model with limited, individually defined fields and heading towards objects
        // that fully define the source and target cluster configurations. For the time being, we're supporting both.
        const sourceClusterDisabledField = this.getContextForType('sourceClusterDisabled', 'boolean', defaultValues, contextJSON)
        const sourceClusterEndpointField = this.getContextForType('sourceClusterEndpoint', 'string', defaultValues, contextJSON)
        let sourceClusterDefinition = this.getContextForType('sourceCluster', 'object', defaultValues, contextJSON)

        if (!sourceClusterDefinition && (sourceClusterEndpointField || sourceClusterDisabledField)) {
            console.warn("`sourceClusterDisabled` and `sourceClusterEndpoint` are being deprecated in favor of a `sourceCluster` object.")
            console.warn("Please update your CDK context block to use the `sourceCluster` object.")
            sourceClusterDefinition = {
                "disabled": sourceClusterDisabledField,
                "endpoint": sourceClusterEndpointField,
                "auth": {"type": "none"}
            }
        }
        const sourceClusterDisabled = !!sourceClusterDefinition?.disabled
        const sourceCluster = (sourceClusterDefinition && !sourceClusterDisabled) ? parseClusterDefinition(sourceClusterDefinition) : undefined
        const sourceClusterEndpoint = sourceCluster?.endpoint

        if (managedServiceSourceSnapshotEnabled && !sourceCluster?.auth.sigv4) {
            throw new Error("A managed service source snapshot is only compatible with sigv4 authentication. If you would like to proceed" +
                " please disable `managedServiceSourceSnapshotEnabled` and provide your own snapshot of the source cluster.")
        }

        const targetClusterEndpointField = this.getContextForType('targetClusterEndpoint', 'string', defaultValues, contextJSON)
        let targetClusterDefinition = this.getContextForType('targetCluster', 'object', defaultValues, contextJSON)
        const usePreexistingTargetCluster = !!(targetClusterEndpointField || targetClusterDefinition)
        if (!targetClusterDefinition && usePreexistingTargetCluster) {
            console.warn("`targetClusterEndpoint` is being deprecated in favor of a `targetCluster` object.")
            console.warn("Please update your CDK context block to use the `targetCluster` object.")
            let auth: any = {"type": "none"}
            if (fineGrainedManagerUserName || fineGrainedManagerUserSecretManagerKeyARN) {
                console.warn(`Use of ${fineGrainedManagerUserName} and ${fineGrainedManagerUserSecretManagerKeyARN} with a preexisting target cluster
                    will be deprecated in favor of using a \`targetCluster\` object. Please update your CDK context block.`)
                auth = {
                    "type": "basic",
                    "username": fineGrainedManagerUserName,
                    "passwordFromSecretArn": fineGrainedManagerUserSecretManagerKeyARN
                }
            }
            targetClusterDefinition = {"endpoint": targetClusterEndpointField, "auth": auth}
        }
        const targetCluster = parseClusterDefinition(targetClusterDefinition)

        // Ensure that target cluster username and password are not defined in multiple places
        if (targetCluster && (fineGrainedManagerUserName || fineGrainedManagerUserSecretManagerKeyARN)) {
            throw new Error("The `fineGrainedManagerUserName` and `fineGrainedManagerUserSecretManagerKeyARN` can only be used when a domain is being " +
                "provisioned by this tooling, which is contraindicated by `targetCluster` being provided.")
        }

        // Ensure that target version is not defined in multiple places, but `engineVersion` is set as a default value, so this is
        // a warning instead of an error.
        if (usePreexistingTargetCluster && engineVersion) {
            console.warn("The `engineVersion` value will be ignored because it's only used when a domain is being provisioned by this tooling" +
                "and in this case, `targetCluster` was provided to define an existing target cluster."
            )
        }

        const targetClusterAuth = targetCluster?.auth
        const targetVersion = targetCluster?.version ? this.getEngineVersion(targetCluster?.version) : null
        const engineVersionValue = engineVersion ? this.getEngineVersion(engineVersion) : this.getEngineVersion('OS_2.15')

        const requiredFields: { [key: string]: any; } = {"stage":stage}
        for (let key in requiredFields) {
            if (!requiredFields[key]) {
                throw new Error(`Required CDK context field ${key} is not present`)
            }
        }
        if (addOnMigrationDeployId && vpcId) {
            console.warn("Add-on deployments will use the original deployment 'vpcId' regardless of passed 'vpcId' values")
        }
        if (stage.length > 15) {
            throw new Error(`Maximum allowed stage name length is 15 characters but received ${stage}`)
        }
        const clusterDomainName = domainName ?? `os-cluster-${stage}`
        let preexistingOrContainerTargetEndpoint
        if (targetCluster && osContainerServiceEnabled) {
            throw new Error("The following options are mutually exclusive as only one target cluster can be specified for a given deployment: [targetCluster, osContainerServiceEnabled]")
        } else if (targetCluster || osContainerServiceEnabled) {
            preexistingOrContainerTargetEndpoint = targetCluster?.endpoint ?? "https://opensearch:9200"
        }

        const fargateCpuArch = validateFargateCpuArch(defaultFargateCpuArch)

        let streamingSourceType
        if (captureProxyServiceEnabled || captureProxyESServiceEnabled || trafficReplayerServiceEnabled || kafkaBrokerServiceEnabled) {
            streamingSourceType = determineStreamingSourceType(kafkaBrokerServiceEnabled)
        } else {
            console.log("MSK is not enabled and will not be deployed.")
            streamingSourceType = StreamingSourceType.DISABLED
        }

        const tlsSecurityPolicyName = this.getContextForType('tlsSecurityPolicy', 'string', defaultValues, contextJSON)
        const tlsSecurityPolicy: TLSSecurityPolicy|undefined = tlsSecurityPolicyName ? TLSSecurityPolicy[tlsSecurityPolicyName as keyof typeof TLSSecurityPolicy] : undefined
        if (tlsSecurityPolicyName && !tlsSecurityPolicy) {
            throw new Error("Provided tlsSecurityPolicy does not match a selectable option, for reference https://docs.aws.amazon.com/cdk/api/v2/docs/aws-cdk-lib.aws_opensearchservice.TLSSecurityPolicy.html")
        }

        if (sourceClusterDisabled && (sourceCluster || captureProxyESServiceEnabled || elasticsearchServiceEnabled || captureProxyServiceEnabled)) {
            throw new Error("A source cluster must be specified by one of: [sourceCluster, captureProxyESServiceEnabled, elasticsearchServiceEnabled, captureProxyServiceEnabled]");
        }

        const deployId = addOnMigrationDeployId ?? defaultDeployId

        // If enabled re-use existing VPC and/or associated resources or create new
        let networkStack: NetworkStack|undefined
        networkStack = new NetworkStack(scope, `networkStack-${deployId}`, {
            vpcId: vpcId,
            vpcAZCount: vpcAZCount,
            targetClusterEndpoint: preexistingOrContainerTargetEndpoint,
            stackName: `OSMigrations-${stage}-${region}-${deployId}-NetworkInfra`,
            description: "This stack contains resources to create/manage networking for an OpenSearch Service domain",
            stage: stage,
            defaultDeployId: defaultDeployId,
            addOnMigrationDeployId: addOnMigrationDeployId,
            albAcmCertArn: albAcmCertArn,
            elasticsearchServiceEnabled,
            captureProxyESServiceEnabled,
            captureProxyServiceEnabled,
            targetClusterProxyServiceEnabled,
            migrationAPIEnabled,
            sourceClusterDisabled,
            sourceClusterEndpoint,
            targetClusterUsername: targetCluster ? targetClusterAuth?.basicAuth?.username : fineGrainedManagerUserName,
            targetClusterPasswordSecretArn: targetCluster ? targetClusterAuth?.basicAuth?.password_from_secret_arn : fineGrainedManagerUserSecretManagerKeyARN,
            env: props.env,
            includeNatGateway
        })
        this.stacks.push(networkStack)
        let servicesYaml = new ServicesYaml();

        if (props.migrationsUserAgent) {
            servicesYaml.client_options = new ClientOptions()
            servicesYaml.client_options.user_agent_extra = props.migrationsUserAgent
        }

        servicesYaml.target_cluster = targetCluster

        let migrationStack = new MigrationAssistanceStack(scope, "migrationInfraStack", {
            vpc: networkStack.vpc,
            streamingSourceType: streamingSourceType,
            mskImportARN: mskARN,
            mskBrokersPerAZCount: mskBrokersPerAZCount,
            mskSubnetIds: mskSubnetIds,
            mskAZCount: mskAZCount,
            replayerOutputEFSRemovalPolicy: replayerOutputEFSRemovalPolicy,
            artifactBucketRemovalPolicy: artifactBucketRemovalPolicy,
            stackName: `OSMigrations-${stage}-${region}-MigrationInfra`,
            description: "This stack contains resources to assist migrating an OpenSearch Service domain",
            stage: stage,
            defaultDeployId: defaultDeployId,
            env: props.env
        })
        this.addDependentStacks(migrationStack, [networkStack])
        this.stacks.push(migrationStack)
        servicesYaml.kafka = migrationStack.kafkaYaml;

        let reindexFromSnapshotStack
        if (reindexFromSnapshotServiceEnabled && networkStack && migrationStack) {
            reindexFromSnapshotStack = new ReindexFromSnapshotStack(scope, "reindexFromSnapshotStack", {
                vpc: networkStack.vpc,
                extraArgs: reindexFromSnapshotExtraArgs,
                clusterAuthDetails: servicesYaml.target_cluster?.auth,
                sourceClusterVersion: sourceCluster?.version,
                stackName: `OSMigrations-${stage}-${region}-ReindexFromSnapshot`,
                description: "This stack contains resources to assist migrating historical data, via Reindex from Snapshot, to a target cluster",
                stage: stage,
                otelCollectorEnabled: otelCollectorEnabled,
                defaultDeployId: defaultDeployId,
                fargateCpuArch: fargateCpuArch,
                env: props.env,
                maxShardSizeGiB: reindexFromSnapshotMaxShardSizeGiB,
                securityGroups: [networkStack.osClusterAccessSG, migrationStack.serviceSecurityGroup, migrationStack.sharedLogsSG],
                osClusterEndpoint: networkStack.osClusterEndpoint,
                s3SnapshotBucket: migrationStack.artifactBucket,
                logEfs: migrationStack.sharedLogsEFS
        })
            this.addDependentStacks(reindexFromSnapshotStack, [migrationStack])
            this.stacks.push(reindexFromSnapshotStack)
            servicesYaml.backfill = reindexFromSnapshotStack.rfsBackfillYaml;
            servicesYaml.snapshot = reindexFromSnapshotStack.rfsSnapshotYaml;

        }

        let migrationConsoleStack
        if (migrationConsoleServiceEnabled && networkStack && migrationStack) {
            migrationConsoleStack = new MigrationConsoleStack(scope, "migration-console", {
                migrationsSolutionVersion: props.migrationsSolutionVersion,
                vpc: networkStack.vpc,
                streamingSourceType: streamingSourceType,
                migrationConsoleEnableOSI: migrationConsoleEnableOSI,
                migrationAPIEnabled: migrationAPIEnabled,
                servicesYaml: servicesYaml,
                migrationAPIAllowedHosts: migrationAPIAllowedHosts,
                sourceCluster,
                stackName: `OSMigrations-${stage}-${region}-MigrationConsole`,
                description: "This stack contains resources for the Migration Console ECS service",
                stage: stage,
                defaultDeployId: defaultDeployId,
                fargateCpuArch: fargateCpuArch,
                otelCollectorEnabled,
                managedServiceSourceSnapshotEnabled,
                env: props.env,
                securityGroups: [networkStack.osClusterAccessSG, migrationStack.serviceSecurityGroup, migrationStack.sharedLogsSG],
                osClusterEndpoint: networkStack.osClusterEndpoint,
                artifactS3: migrationStack.artifactBucket,
                logEfs: migrationStack.sharedLogsEFS
            })
            // To enable the Migration Console to make requests to other service endpoints with services,
            // it must be deployed after any connected services
            this.addDependentStacks(migrationConsoleStack, [migrationStack])
            this.stacks.push(migrationConsoleStack)
        }
    }
}
