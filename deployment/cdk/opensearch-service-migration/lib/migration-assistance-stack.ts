import {RemovalPolicy, Stack} from "aws-cdk-lib";
import {ISecurityGroup, IVpc, Port, SecurityGroup} from "aws-cdk-lib/aws-ec2";
import {FileSystem, IFileSystem, LifecyclePolicy, ThroughputMode} from 'aws-cdk-lib/aws-efs';
import {Construct} from "constructs";
import {Cluster} from "aws-cdk-lib/aws-ecs";
import {StackPropsExt} from "./stack-composer";
import {StreamingSourceType} from "./streaming-source-type";
import {Bucket, BucketEncryption, IBucket} from "aws-cdk-lib/aws-s3";
import {parseRemovalPolicy} from "./common-utilities";
import {KafkaYaml} from "./migration-services-yaml";

export interface MigrationStackProps extends StackPropsExt {
    readonly vpc: IVpc,
    readonly streamingSourceType: StreamingSourceType,
    // Future support needed to allow importing an existing MSK cluster
    readonly mskImportARN?: string,
    readonly mskBrokersPerAZCount?: number,
    readonly mskSubnetIds?: string[],
    readonly mskAZCount?: number,
    readonly replayerOutputEFSRemovalPolicy?: string
    readonly artifactBucketRemovalPolicy?: string
}


export class MigrationAssistanceStack extends Stack {
    public readonly kafkaYaml: KafkaYaml;
    public readonly sharedLogsSG: ISecurityGroup;
    public readonly sharedLogsEFS: IFileSystem;
    public readonly serviceSecurityGroup: ISecurityGroup;
    public readonly artifactBucket: IBucket;


    constructor(scope: Construct, id: string, props: MigrationStackProps) {
        super(scope, id, props);

        const bucketRemovalPolicy = parseRemovalPolicy('artifactBucketRemovalPolicy', props.artifactBucketRemovalPolicy)
        const replayerEFSRemovalPolicy = parseRemovalPolicy('replayerOutputEFSRemovalPolicy', props.replayerOutputEFSRemovalPolicy)

        const sharedLogsSG = new SecurityGroup(this, 'sharedLogsSG', {
            vpc: props.vpc,
            allowAllOutbound: false,
        });
        sharedLogsSG.addIngressRule(sharedLogsSG, Port.allTraffic());
        this.sharedLogsSG = sharedLogsSG;

        // Create an EFS file system for Traffic Replayer output
        this.sharedLogsEFS = new FileSystem(this, 'sharedLogsEFS', {
            vpc: props.vpc,
            securityGroup: sharedLogsSG,
            removalPolicy: replayerEFSRemovalPolicy,
            lifecyclePolicy: LifecyclePolicy.AFTER_1_DAY, // Cost break even is at 26 downloads / month
            throughputMode: ThroughputMode.BURSTING, // Best cost characteristics for write heavy, short-lived data
        })

        const serviceSecurityGroup = new SecurityGroup(this, 'serviceSecurityGroup', {
            vpc: props.vpc,
            // Required for retrieving ECR image at service startup
            allowAllOutbound: true,
        })
        serviceSecurityGroup.addIngressRule(serviceSecurityGroup, Port.allTraffic());
        this.serviceSecurityGroup = serviceSecurityGroup;

        this.artifactBucket = new Bucket(this, 'migrationArtifactsS3', {
            bucketName: `migration-artifacts-${this.account}-${props.stage}-${this.region}`,
            encryption: BucketEncryption.S3_MANAGED,
            enforceSSL: true,
            removalPolicy: bucketRemovalPolicy,
            autoDeleteObjects: !!(props.artifactBucketRemovalPolicy && bucketRemovalPolicy === RemovalPolicy.DESTROY)
        });

        new Cluster(this, 'migrationECSCluster', {
            vpc: props.vpc,
            clusterName: `migration-${props.stage}-ecs-cluster`
        })

    }
}
