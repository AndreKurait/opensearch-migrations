import {StackPropsExt} from "../stack-composer";
import {ISecurityGroup, IVpc} from "aws-cdk-lib/aws-ec2";
import {CpuArchitecture, PortMapping, Protocol} from "aws-cdk-lib/aws-ecs";
import {Construct} from "constructs";
import {join} from "path";
import {Effect, PolicyStatement} from "aws-cdk-lib/aws-iam";
import {
    createOpenSearchIAMAccessPolicy,
    createOpenSearchServerlessIAMAccessPolicy,
    getSecretAccessPolicy,
    hashStringSHA256,
    createSnapshotOnAOSRole
} from "../common-utilities";
import {StreamingSourceType} from "../streaming-source-type";
import {Fn} from "aws-cdk-lib";
import {ClusterYaml, MetadataMigrationYaml, ServicesYaml} from "../migration-services-yaml";
import {ELBTargetGroup, MigrationServiceCore} from "./migration-service-core";
import { OtelCollectorSidecar } from "./migration-otel-collector-sidecar";
import { SharedLogFileSystem } from "../components/shared-log-file-system";
import {IBucket} from "aws-cdk-lib/aws-s3";
import {IFileSystem} from "aws-cdk-lib/aws-efs";

export interface MigrationConsoleProps extends StackPropsExt {
    readonly migrationsSolutionVersion: string,
    readonly vpc: IVpc,
    readonly streamingSourceType: StreamingSourceType,
    readonly fargateCpuArch: CpuArchitecture,
    readonly migrationConsoleEnableOSI: boolean,
    readonly migrationAPIEnabled?: boolean,
    readonly migrationAPIAllowedHosts?: string,
    readonly targetGroups?: ELBTargetGroup[],
    readonly servicesYaml: ServicesYaml,
    readonly otelCollectorEnabled?: boolean,
    readonly sourceCluster?: ClusterYaml,
    readonly managedServiceSourceSnapshotEnabled?: boolean
    readonly securityGroups: ISecurityGroup[],
    readonly osClusterEndpoint: string,
    readonly artifactS3: IBucket,
    readonly logEfs: IFileSystem,
}

export class MigrationConsoleStack extends MigrationServiceCore {

    getHostname(url: string): string {
        // https://alb.migration.dev.local:8000 -> alb.migration.dev.local
        return Fn.select(0, Fn.split(':', Fn.select(2, Fn.split('/', url))));
    }

    createOpenSearchIngestionManagementPolicy(pipelineRoleArn: string): PolicyStatement[] {
        const allMigrationPipelineArn = `arn:${this.partition}:osis:${this.region}:${this.account}:pipeline/*`
        const osiManagementPolicy = new PolicyStatement({
            effect: Effect.ALLOW,
            resources: [allMigrationPipelineArn],
            actions: [
                "osis:*"
            ]
        })
        const passPipelineRolePolicy = new PolicyStatement({
            effect: Effect.ALLOW,
            resources: [pipelineRoleArn],
            actions: [
                "iam:PassRole"
            ]
        })
        const configureLogGroupPolicy = new PolicyStatement({
            effect: Effect.ALLOW,
            resources: ["*"],
            actions: [
                "logs:CreateLogDelivery",
                "logs:PutResourcePolicy",
                "logs:DescribeResourcePolicies",
                "logs:DescribeLogGroups"
            ]
        })
        return [osiManagementPolicy, passPipelineRolePolicy, configureLogGroupPolicy]
    }

    constructor(scope: Construct, id: string, props: MigrationConsoleProps) {
        super(scope, id, props)

        let servicePortMappings: PortMapping[]|undefined
        let imageCommand: string[]|undefined

        const brokerEndpoints = "";

        const sharedLogFileSystem = new SharedLogFileSystem(this, props.stage, props.defaultDeployId, props.logEfs);


        const ecsClusterArn = `arn:${this.partition}:ecs:${this.region}:${this.account}:service/migration-${props.stage}-ecs-cluster`
        const allReplayerServiceArn = `${ecsClusterArn}/migration-${props.stage}-traffic-replayer*`
        const reindexFromSnapshotServiceArn = `${ecsClusterArn}/migration-${props.stage}-reindex-from-snapshot`
        const ecsUpdateServicePolicy = new PolicyStatement({
            effect: Effect.ALLOW,
            resources: [allReplayerServiceArn, reindexFromSnapshotServiceArn],
            actions: [
                "ecs:UpdateService",
                "ecs:DescribeServices"
            ]
        })

        const allClusterTasksArn = `arn:${this.partition}:ecs:${this.region}:${this.account}:task/migration-${props.stage}-ecs-cluster/*`
        const clusterTasksPolicy = new PolicyStatement({
            effect: Effect.ALLOW,
            resources: [allClusterTasksArn],
            actions: [
                "ecs:StopTask",
                "ecs:DescribeTasks"
            ]
        })

        const listTasksPolicy = new PolicyStatement({
            effect: Effect.ALLOW,
            resources: ["*"],
            actions: [
                "ecs:ListTasks",
            ]
        })

        const artifactS3Arn = props.artifactS3.bucketArn

        const artifactS3AnyObjectPath = `${artifactS3Arn}/*`;
        const artifactS3PublishPolicy = new PolicyStatement({
            effect: Effect.ALLOW,
            resources: [artifactS3Arn, artifactS3AnyObjectPath],
            actions: [
                "s3:*"
            ]
        })

        // Allow Console to determine proper subnets to use for any resource creation
        const describeVPCPolicy = new PolicyStatement( {
            effect: Effect.ALLOW,
            resources: ["*"],
            actions: [
                "ec2:DescribeSubnets",
                "ec2:DescribeRouteTables"
            ]
        })

        // Allow Console to retrieve Cloudwatch Metrics
        const getMetricsPolicy = new PolicyStatement({
            effect: Effect.ALLOW,
            resources: ["*"],
            actions: [
                "cloudwatch:ListMetrics",
                "cloudwatch:GetMetricData"
            ]
        })

        const getTargetSecretsPolicy = props.servicesYaml.target_cluster.auth.basicAuth?.password_from_secret_arn ?
            getSecretAccessPolicy(props.servicesYaml.target_cluster.auth.basicAuth?.password_from_secret_arn) : null;

        const getSourceSecretsPolicy = props.sourceCluster?.auth.basicAuth?.password_from_secret_arn ?
            getSecretAccessPolicy(props.sourceCluster?.auth.basicAuth?.password_from_secret_arn) : null;

        // Upload the services.yaml file to Parameter Store
        let servicesYaml = props.servicesYaml
        servicesYaml.source_cluster = props.sourceCluster
        servicesYaml.metadata_migration = new MetadataMigrationYaml();
        servicesYaml.metadata_migration.source_cluster_version = props.sourceCluster?.version
        if (props.otelCollectorEnabled) {
            const otelSidecarEndpoint = OtelCollectorSidecar.getOtelLocalhostEndpoint();
            if (servicesYaml.metadata_migration) {
                servicesYaml.metadata_migration.otel_endpoint = otelSidecarEndpoint;
            }
            if (servicesYaml.snapshot) {
                servicesYaml.snapshot.otel_endpoint = otelSidecarEndpoint;
            }
        }

        const environment: { [key: string]: string; } = {
            "MIGRATION_DOMAIN_ENDPOINT": props.osClusterEndpoint,
            "MIGRATION_KAFKA_BROKER_ENDPOINTS": brokerEndpoints,
            "MIGRATION_STAGE": props.stage,
            "MIGRATION_SOLUTION_VERSION": props.migrationsSolutionVersion,
            "MIGRATION_SERVICES_YAML": servicesYaml.stringify(),
            "MIGRATION_SERVICES_YAML_HASH": hashStringSHA256(servicesYaml.stringify()),
            "SHARED_LOGS_DIR_PATH": `${sharedLogFileSystem.mountPointPath}/migration-console-${props.defaultDeployId}`,
        }

        const openSearchPolicy = createOpenSearchIAMAccessPolicy(this.partition, this.region, this.account)
        const openSearchServerlessPolicy = createOpenSearchServerlessIAMAccessPolicy(this.partition, this.region, this.account)
        let servicePolicies = [sharedLogFileSystem.asPolicyStatement(), openSearchPolicy, openSearchServerlessPolicy, ecsUpdateServicePolicy, clusterTasksPolicy,
            listTasksPolicy, artifactS3PublishPolicy, describeVPCPolicy, getMetricsPolicy,
            // only add secrets policies if they're non-null
            ...(getTargetSecretsPolicy ? [getTargetSecretsPolicy] : []),
            ...(getSourceSecretsPolicy ? [getSourceSecretsPolicy] : [])
        ]

        if (props.migrationAPIEnabled) {
            servicePortMappings = [{
                name: "migration-console-connect",
                hostPort: 8000,
                containerPort: 8000,
                protocol: Protocol.TCP
            }]
            imageCommand = ['/bin/sh', '-c',
                '/root/loadServicesFromParameterStoreOrEnv.sh && pipenv run python /root/console_api/manage.py runserver_plus 0.0.0.0:8000 --cert-file cert.crt'
            ]

            const defaultAllowedHosts = 'localhost'
            environment["API_ALLOWED_HOSTS"] = props.migrationAPIAllowedHosts ? `${defaultAllowedHosts},${props.migrationAPIAllowedHosts}` : defaultAllowedHosts
        }


        this.createService({
            serviceName: "migration-console",
            dockerDirectoryPath: join(__dirname, "../../../../../", "TrafficCapture/dockerSolution/src/main/docker/migrationConsole"),
            portMappings: servicePortMappings,
            dockerImageCommand: imageCommand,
            volumes: [sharedLogFileSystem.asVolume()],
            mountPoints: [sharedLogFileSystem.asMountPoint()],
            environment: environment,
            taskRolePolicies: servicePolicies,
            cpuArchitecture: props.fargateCpuArch,
            taskCpuUnits: 1024,
            taskMemoryLimitMiB: 2048,
            ...props
        });

        if (props.managedServiceSourceSnapshotEnabled) {
            const snapshotRole = createSnapshotOnAOSRole(this, artifactS3Arn, this.serviceTaskRole.roleArn, this.region, props.stage, props.defaultDeployId);
        }
    }

}
