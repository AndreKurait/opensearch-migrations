import {CfnOutput, Stack} from "aws-cdk-lib";
import {
    Instance,
    InstanceClass,
    InstanceSize,
    InstanceType,
    IVpc,
    MachineImage, Peer, Port,
    SecurityGroup,
    SubnetType
} from "aws-cdk-lib/aws-ec2";
import {FileSystem} from 'aws-cdk-lib/aws-efs';
import {Construct} from "constructs";
import {CfnCluster, CfnConfiguration} from "aws-cdk-lib/aws-msk";
import {StackPropsExt} from "./stack-composer";

export interface migrationStackProps extends StackPropsExt {
    readonly vpc: IVpc,
    // Future support needed to allow importing an existing MSK cluster
    readonly mskARN?: string,
    readonly targetEndpoint: string
}


export class MigrationAssistanceStack extends Stack {

    public readonly mskARN: string;

    constructor(scope: Construct, id: string, props: migrationStackProps) {
        super(scope, id, props);

        // Create MSK cluster config
        const mskClusterConfig = new CfnConfiguration(this, "migrationMSKClusterConfig", {
            name: 'migration-msk-config',
            serverProperties: "auto.create.topics.enable=true"
        })

        // Create an MSK cluster
        const mskCluster = new CfnCluster(this, 'migrationMSKCluster', {
            clusterName: 'migration-msk-cluster',
            kafkaVersion: '2.8.1',
            numberOfBrokerNodes: 2,
            brokerNodeGroupInfo: {
                instanceType: 'kafka.m5.large',
                clientSubnets: props.vpc.selectSubnets({subnetType: SubnetType.PUBLIC}).subnetIds,
                connectivityInfo: {
                    // Public access cannot be enabled on cluster creation
                    publicAccess: {
                        type: "DISABLED"
                    }
                },
            },
            configurationInfo: {
                arn: mskClusterConfig.attrArn,
                // Current limitation of using L1 construct, would like to get latest revision dynamically
                revision: 1
            },
            encryptionInfo: {
                encryptionInTransit: {
                    clientBroker: 'TLS',
                    inCluster: true
                },
            },
            enhancedMonitoring: 'DEFAULT',
            clientAuthentication: {
                sasl: {
                    iam: {
                        enabled: true
                    }
                },
                unauthenticated: {
                    enabled: false
                }
            }
        });
        this.mskARN = mskCluster.attrArn

        const comparatorSQLiteSG = new SecurityGroup(this, 'comparatorSQLiteSG', {
            vpc: props.vpc,
            allowAllOutbound: true,
        });
        comparatorSQLiteSG.addIngressRule(comparatorSQLiteSG, Port.allTraffic());

        // Create an EFS file system for the traffic-comparator
        const comparatorSQLiteEFS = new FileSystem(this, 'comparatorSQLiteEFS', {
            vpc: props.vpc,
            securityGroup: comparatorSQLiteSG
        });

        // Creates a security group with open access via ssh
        const oinoSecurityGroup = new SecurityGroup(this, 'orchestratorSecurityGroup', {
            vpc: props.vpc,
            allowAllOutbound: true,
        });
        oinoSecurityGroup.addIngressRule(Peer.anyIpv4(), Port.tcp(22));

        // Create EC2 instance for analysis of cluster in VPC
        const oino = new Instance(this, "orchestratorEC2Instance", {
            vpc: props.vpc,
            vpcSubnets: { subnetType: SubnetType.PUBLIC },
            instanceType: InstanceType.of(InstanceClass.T2, InstanceSize.MICRO),
            machineImage: MachineImage.latestAmazonLinux2(),
            securityGroup: oinoSecurityGroup,
            // Manually created for now, to be automated in future
            //keyName: "es-node-key"
        });

        // This is a temporary fragile piece to help with importing values from CDK to Copilot. It assumes the provided VPC has two public subnets and
        // does not currently provide the MSK broker endpoints as a future Custom Resource is needed to accomplish this
        const exports = [
            `export MIGRATION_VPC_ID=${props.vpc.vpcId}`,
            `export MIGRATION_PUBLIC_SUBNET_1=${props.vpc.publicSubnets[0].subnetId}`,
            `export MIGRATION_PUBLIC_SUBNET_2=${props.vpc.publicSubnets[1].subnetId}`,
            `export MIGRATION_DOMAIN_ENDPOINT=${props.targetEndpoint}`,
            `export MIGRATION_COMPARATOR_EFS_ID=${comparatorSQLiteEFS.fileSystemId}`,
            `export MIGRATION_COMPARATOR_EFS_SG_ID=${comparatorSQLiteSG.securityGroupId}`]

        const cfnOutput = new CfnOutput(this, 'CopilotExports', {
            value: exports.join(";"),
            description: 'Exported resource values created by CDK that are needed by Copilot container deployments',
        });

    }
}