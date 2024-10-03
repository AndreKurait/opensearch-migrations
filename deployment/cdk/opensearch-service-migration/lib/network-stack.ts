import {
    GatewayVpcEndpoint,
    GatewayVpcEndpointAwsService,
    InterfaceVpcEndpoint,
    InterfaceVpcEndpointAwsService,
    IpAddresses,
    ISecurityGroup,
    IVpc,
    Port,
    SecurityGroup,
    SubnetType,
    Vpc
} from "aws-cdk-lib/aws-ec2";
import {Construct} from "constructs";
import {StackPropsExt} from "./stack-composer";
import {
    IApplicationTargetGroup,
} from "aws-cdk-lib/aws-elasticloadbalancingv2";
import {Stack} from "aws-cdk-lib";
import {isStackInGovCloud} from "./common-utilities";

export interface NetworkStackProps extends StackPropsExt {
    readonly vpcId?: string;
    readonly vpcAZCount?: number;
    readonly elasticsearchServiceEnabled?: boolean;
    readonly captureProxyServiceEnabled?: boolean;
    readonly targetClusterProxyServiceEnabled?: boolean;
    readonly captureProxyESServiceEnabled?: boolean;
    readonly migrationAPIEnabled?: boolean;
    readonly sourceClusterDisabled?: boolean;
    readonly sourceClusterEndpoint?: string;
    readonly targetClusterEndpoint?: string;
    readonly targetClusterUsername?: string;
    readonly targetClusterPasswordSecretArn?: string;
    readonly albAcmCertArn?: string;
    readonly env?: { [key: string]: any };
    readonly includeNatGateway?: boolean
}

export class NetworkStack extends Stack {
    public readonly vpc: IVpc;
    public readonly albSourceProxyTG: IApplicationTargetGroup;
    public readonly albTargetProxyTG: IApplicationTargetGroup;
    public readonly albSourceClusterTG: IApplicationTargetGroup;
    public readonly osClusterAccessSG: ISecurityGroup;
    public readonly osClusterEndpoint: string;
    public readonly osUserAndSecretArn: string | undefined;

    // Validate a proper url string is provided and return an url string which contains a protocol, host name, and port.
    // If a port is not provided, the default protocol port (e.g. 443, 80) will be explicitly added
    static validateAndReturnFormattedHttpURL(urlString: string) {
        // URL will throw error if the urlString is invalid
        let url = new URL(urlString);
        if (url.protocol !== "http:" && url.protocol !== "https:") {
            throw new Error(`Invalid url protocol for target endpoint: ${urlString} was expecting 'http' or 'https'`)
        }
        if (url.pathname !== "/") {
            throw new Error(`Provided target endpoint: ${urlString} must not contain a path: ${url.pathname}`)
        }
        // URLs that contain the default protocol port (e.g. 443, 80) will not show in the URL toString()
        let formattedUrlString = url.toString()
        if (formattedUrlString.endsWith("/")) {
            formattedUrlString = formattedUrlString.slice(0, -1)
        }
        if (!url.port) {
            if (url.protocol === "http:") {
                formattedUrlString = formattedUrlString.concat(":80")
            }
            else {
                formattedUrlString = formattedUrlString.concat(":443")
            }
        }
        return formattedUrlString
    }

    private validateVPC(vpc: IVpc) {
        let uniqueAzPrivateSubnets: string[] = []
        if (vpc.privateSubnets.length > 0) {
            uniqueAzPrivateSubnets = vpc.selectSubnets({
                subnetType: SubnetType.PRIVATE_WITH_EGRESS,
                onePerAz: true
            }).subnetIds
        }
        console.info(`Detected VPC with ${vpc.privateSubnets.length} private subnets, ${vpc.publicSubnets.length} public subnets, and ${vpc.isolatedSubnets.length} isolated subnets`)
        if (uniqueAzPrivateSubnets.length < 2) {
            throw new Error(`Not enough AZs (${uniqueAzPrivateSubnets.length} unique AZs detected) used for private subnets to meet 2 or 3 AZ requirement`)
        }
    }

    private createVpcEndpoints(vpc: IVpc) {
        // Gateway endpoints
        new GatewayVpcEndpoint(this, 'S3VpcEndpoint', {
            service: GatewayVpcEndpointAwsService.S3,
            vpc: vpc,
        });

        // Interface endpoints
        const createInterfaceVpcEndpoint = (service: InterfaceVpcEndpointAwsService) => {
            new InterfaceVpcEndpoint(this, `${service.shortName}VpcEndpoint`, {
                service: service,
                vpc: vpc,
            });
        };

        // General interface endpoints
        const interfaceEndpoints = [
            InterfaceVpcEndpointAwsService.CLOUDWATCH_LOGS, // Push Logs from tasks
            InterfaceVpcEndpointAwsService.CLOUDWATCH_MONITORING, // Pull Metrics from Migration Console
            InterfaceVpcEndpointAwsService.ECR_DOCKER, // Pull Images on Startup
            InterfaceVpcEndpointAwsService.ECR, // List Images on Startup
            InterfaceVpcEndpointAwsService.ECS_AGENT, // Task Container Metrics
            InterfaceVpcEndpointAwsService.ECS_TELEMETRY, // Task Container Metrics
            InterfaceVpcEndpointAwsService.ECS, // ECS Task Control
            InterfaceVpcEndpointAwsService.ELASTIC_LOAD_BALANCING, // Control ALB
            InterfaceVpcEndpointAwsService.SECRETS_MANAGER, // Cluster Password Secret
            InterfaceVpcEndpointAwsService.SSM_MESSAGES, // Session Manager
            InterfaceVpcEndpointAwsService.SSM, // Parameter Store
            InterfaceVpcEndpointAwsService.XRAY, // X-Ray Traces
            isStackInGovCloud(this) ?
                InterfaceVpcEndpointAwsService.ELASTIC_FILESYSTEM_FIPS : // EFS Control Plane GovCloud
                InterfaceVpcEndpointAwsService.ELASTIC_FILESYSTEM, // EFS Control Plane

        ];
        interfaceEndpoints.forEach(service => createInterfaceVpcEndpoint(service));
    }

    constructor(scope: Construct, id: string, props: NetworkStackProps) {
        super(scope, id, props);

        // Retrieve existing VPC
        if (props.vpcId) {
            this.vpc = Vpc.fromLookup(this, 'domainVPC', {
                vpcId: props.vpcId,
            });
        }
        // Create new VPC
        else {
            const zoneCount = props.vpcAZCount
            // Either 2 or 3 AZ count must be used
            if (zoneCount && zoneCount !== 2 && zoneCount !== 3) {
                throw new Error(`Required vpcAZCount is 2 or 3 but received: ${zoneCount}`)
            }
            this.vpc = new Vpc(this, 'domainVPC', {
                // IP space should be customized for use cases that have specific IP range needs
                ipAddresses: IpAddresses.cidr('10.0.0.0/16'),
                maxAzs: zoneCount ?? 2,
                subnetConfiguration: [
                    // Outbound internet access for private subnets require a NAT Gateway which must live in
                    // a public subnet
                    {
                        name: 'public-subnet',
                        subnetType: SubnetType.PUBLIC,
                        cidrMask: 24,
                    },
                    // Nodes will live in these subnets
                    {
                        name: 'private-subnet',
                        subnetType: SubnetType.PRIVATE_WITH_EGRESS,
                        cidrMask: 24,
                    },
                ],
                natGateways: (!!props.includeNatGateway) ? (zoneCount ?? 2) : 0,
            });
            // Only create interface endpoints if VPC not imported
            this.createVpcEndpoints(this.vpc);
        }
        this.validateVPC(this.vpc)

        if (!props.addOnMigrationDeployId) {
            // Create a default SG which only allows members of this SG to access the Domain endpoints
            const sg = new SecurityGroup(this, 'osClusterAccessSG', {
                vpc: this.vpc,
                allowAllOutbound: false,
            });
            sg.addIngressRule(sg, Port.allTraffic());
            this.osClusterAccessSG = sg

            if (props.targetClusterEndpoint) {
                this.osClusterEndpoint = NetworkStack.validateAndReturnFormattedHttpURL(props.targetClusterEndpoint);

                // This is a somewhat surprsing place for this non-network related set of parameters, but it pairs well with
                // the OS_CLUSTER_ENDPOINT parameter and is helpful to ensure it happens. This probably isn't a long-term place
                // for it, but is helpful for the time being.
                if (props.targetClusterUsername && props.targetClusterPasswordSecretArn) {
                    this.osUserAndSecretArn = `${props.targetClusterUsername} ${props.targetClusterPasswordSecretArn}`
                }
            }
        }
    }
}
