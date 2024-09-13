This page outlines scenarios for using the Migration tools with existing clusters, including any necessary configuration changes to ensure proper communication between them.


### Importing an OpenSearch Service or OpenSearch Serverless Target Cluster
#### OpenSearch Service
For a Domain, there are typically two items that need to be configured to allow proper functioning of this solution
1. The Domain should have a security group that allows communication from the applicable Migration services (Traffic Replayer, Migration Console, Reindex-from-Snapshot). This CDK will automatically create an `osClusterAccessSG` security group, which has already been applied to the Migration services, that a user should then add to their existing Domain to allow this access.
2. The access policy on the Domain should be an open access policy that allows all access or an access policy that at least allows the IAM task roles for the applicable Migration services (Traffic Replayer, Migration Console, Reindex-from-Snapshot)

#### OpenSearch Serverless
A Collection, will need to configure a Network and Data Access policy to allow proper functioning of this solution
1. The Collection should have a network policy that has a `VPC` access type by creating a VPC endpoint on the VPC used for this solution. This VPC endpoint should be configured for the private subnets of the VPC and attach the `osClusterAccessSG` security group.
2. The data access policy needed should grant permission to perform all [index operations](https://docs.aws.amazon.com/opensearch-service/latest/developerguide/serverless-data-access.html#serverless-data-supported-permissions) (`aoss:*`) for all indexes in the given collection, and use the task roles of the applicable Migration services (Traffic Replayer, Migration Console, Reindex-from-Snapshot) as the principals for this data access policy.


### Capture Proxy directly on Coordinator nodes of Source Cluster
Although this CDK does not set up the Capture Proxy on source cluster nodes (except in the case of the demo solution), the Capture Proxy instances do need to communicate with resources deployed by this CDK (e.g. Kafka) which this section covers

Before [setting up Capture Proxy instances](https://github.com/opensearch-project/opensearch-migrations/tree/main/TrafficCapture/trafficCaptureProxyServer#installing-capture-proxy-on-coordinator-nodes) on the source cluster, the IAM policies and Security Groups for the nodes should allow access to the Migration tooling:
1. The coordinator nodes should add the `trafficStreamSourceSG` security group to allow sending captured traffic to Kafka
2. The IAM role used by the coordinator nodes should have permissions to publish captured traffic to Kafka. A template policy to use, can be seen below
   * This can be added through the AWS Console (IAM Role -> Add permissions -> Create inline policy -> JSON view)
```json
{
    "Version": "2012-10-17",
    "Statement": [
        {
            "Action": "kafka-cluster:Connect",
            "Resource": "arn:aws:kafka:<REGION>:<ACCOUNT-ID>:cluster/migration-msk-cluster-<STAGE>/*",
            "Effect": "Allow"
        },
        {
            "Action": [
                "kafka-cluster:CreateTopic",
                "kafka-cluster:DescribeTopic",
                "kafka-cluster:WriteData"
            ],
            "Resource": "arn:aws:kafka:<REGION>:<ACCOUNT-ID>:topic/migration-msk-cluster-<STAGE>/*",
            "Effect": "Allow"
        }
    ]
}
```
