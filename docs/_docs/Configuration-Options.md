This page lays out configuration options for three key migrations: 
1. Metadata Migration
2. Backfill Migration with Reindex-from-Snapshot(RFS)
3. Live Capture Migration with Capture and Replay(C&R)

Notice that each of these migrations may be dependent upon either a snapshot or capture proxy. Also note that other than the snapshot/proxy prerequisite, each of these three migrations are independent of each other. For a complete list of options, please refer to opensearch-migrations [options.md](https://github.com/opensearch-project/opensearch-migrations/blob/main/deployment/cdk/opensearch-service-migration/options.md)

Regardless of the type of migration, options for the source cluster endpoint, target cluster endpoint, and existing VPC that are shown below should be configured for the Migration tools to best perform the necessary migration. 

> [!IMPORTANT]
> The Migration tooling expects the source cluster, target cluster, and migration resources to all exist in the same VPC. If this is not the case, manual networking setup outside of this documentation is likely required.

> [!TIP]
> The CDK context blocks below are shown as a separate context block per migration for simplicity. A user should combine these options if performing multiple migration types as the actual execution of each migration is still controlled by the user from the Migration Console.

| Name                              | Example                                                              | Description                                                                                                                              |
|-----------------------------------|----------------------------------------------------------------------|:-----------------------------------------------------------------------------------------------------------------------------------------|
| sourceClusterEndpoint             | `"https://source-cluster.elb.us-east-1.endpoint.com"`                | The endpoint for the source cluster.   
| targetClusterEndpoint            | `"https://vpc-demo-opensearch-cluster-cv6hggdb66ybpk4kxssqt6zdhu.us-west-2.es.amazonaws.com:443"` |  The endpoint for the target cluster. Required if using an existing target cluster for the migration, instead of creating one. |
| vpcId            | `"vpc-123456789abcdefgh"` |  Specify an existing VPC to place the Migration resources inside of. The VPC provided must have at least 2 private subnets which span at least 2 availability zones   |

### Metadata Migration Options
Sample Metadata Migration CDK Options
```
{
  "metadata-migration": {
    "stage": "dev",
    "vpcId": <VPC_ID>,
    "sourceClusterEndpoint": <SOURCE_CLUSTER_ENDPOINT>,
    "targetClusterEndpoint": <TARGET_CLUSTER_ENDPOINT>,
    "reindexFromSnapshotServiceEnabled": true,
    "artifactBucketRemovalPolicy": "DESTROY"
  }
}
```
There are no CDK options, currently, for configuring Metadata migrations specifically, which are performed from the Migration Console. This migration requires an existing snapshot, which can be created when accessing the Migration Console. 

### Backfill Migration with Reindex-from-Snapshot Options
Sample Backfill Migration CDK Options
```
{
  "backfill-migration": {
    "stage": "dev",
    "vpcId": <VPC_ID>,
    "sourceClusterEndpoint": <SOURCE_CLUSTER_ENDPOINT>,
    "targetClusterEndpoint": <TARGET_CLUSTER_ENDPOINT>,
    "reindexFromSnapshotServiceEnabled": true,
    "reindexFromSnapshotExtraArgs": "",
    "artifactBucketRemovalPolicy": "DESTROY"
  }
}
```
Performing a Reindex-from-Snapshot backfill migration requires that a snapshot be existing. The specific CDK options for backfill migrations are below. To find all available arguments that can be provided to `reindexFromSnapshotExtraArgs` see [here](https://github.com/opensearch-project/opensearch-migrations/blob/main/DocumentsFromSnapshotMigration/README.md#arguments). At a minimum no extra arguments may be needed.

| Name                              | Example                                                              | Description                                                                                                                              |
|-----------------------------------|----------------------------------------------------------------------|:-----------------------------------------------------------------------------------------------------------------------------------------|
| reindexFromSnapshotServiceEnabled | true                                                                 | Create resources for deploying and configuring the RFS ECS service                                                                       |
| reindexFromSnapshotExtraArgs      | "--target-aws-region us-east-1 --target-aws-service-signing-name es" | Extra arguments to provide to the Document Migration command with space separation. See RFS Extra Arguments below for available options. Boolean negation is used, so --no-insecure can be passed to remove the --insecure flag if present. |                                                              |


### Live Capture Migration with Capture and Replay Options
Sample Live Capture Migration CDK Options
```
{
  "live-capture-migration": {
    "stage": "dev",
    "vpcId": <VPC_ID>,
    "sourceClusterEndpoint": <SOURCE_CLUSTER_ENDPOINT>,
    "targetClusterEndpoint": <TARGET_CLUSTER_ENDPOINT>,
    "captureProxyServiceEnabled": true,
    "captureProxyExtraArgs": "",
    "trafficReplayerServiceEnabled": true,
    "trafficReplayerExtraArgs": "",
    "artifactBucketRemovalPolicy": "DESTROY"
  }
}
```
Performing a live capture migration requires that a Capture Proxy be configured to capture incoming traffic and send that traffic to be replayed on the target cluster via the Traffic Replayer service. To find all available arguments that can be provided to `captureProxyExtraArgs` see the `@Parameter` fields [here](https://github.com/opensearch-project/opensearch-migrations/blob/main/TrafficCapture/trafficCaptureProxyServer/src/main/java/org/opensearch/migrations/trafficcapture/proxyserver/CaptureProxy.java), and for `trafficReplayerExtraArgs` see the `@Parameter` fields [here](https://github.com/opensearch-project/opensearch-migrations/blob/main/TrafficCapture/trafficReplayer/src/main/java/org/opensearch/migrations/replay/TrafficReplayer.java). At a minimum no extra arguments may be needed.
| Name                                 | Example                                                                                | Description                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                        |
|--------------------------------------|----------------------------------------------------------------------------------------|:-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| captureProxyServiceEnabled        | true                                                                                   | Enable deploying the given service, via a new CloudFormation stack                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                 |
| captureProxyExtraArgs             | `"--suppressCaptureForHeaderMatch user-agent .*elastic-java/7.17.0.*"`                   | Extra arguments to provide to the Capture Proxy command. This includes available arguments specified by the [Capture Proxy](https://github.com/opensearch-project/opensearch-migrations/blob/main/TrafficCapture/trafficCaptureProxyServer/src/main/java/org/opensearch/migrations/trafficcapture/proxyserver/CaptureProxy.java)                                                                                                                                                                                                                                                                                                                                                                      |
| trafficReplayerServiceEnabled        | true                                                                                   | Enable deploying the given service, via a new CloudFormation stack                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                 |
| trafficReplayerExtraArgs             | "--sigv4-auth-header-service-region es,us-east-1 --speedup-factor 5"                   | Extra arguments to provide to the Replayer command. This includes auth header options and other parameters supported by the [Traffic Replayer](https://github.com/opensearch-project/opensearch-migrations/blob/main/TrafficCapture/trafficReplayer/src/main/java/org/opensearch/migrations/replay/TrafficReplayer.java).                                                                                                                                                                                                                                                                                                                                                                       |


### TODOs
TODO: Remove requirement that Metadata migration requires the RFS service be enabled

TODO: Make Replayer args clearer in a README file

TODO: Present a clear path for what options need to be provided for different auth patterns (no auth, basic auth, sigv4) that are used for source/target cluster


Owner: @lewijacn