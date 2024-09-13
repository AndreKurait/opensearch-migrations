This guide walks through steps to provision an Elasticsearch cluster on EC2. The CDK that will provision this cluster can be found on the `migration-es` branch of the `opensearch-cluster-cdk` Github [forked repo](https://github.com/lewijacn/opensearch-cluster-cdk/tree/migration-es)

1. Download repository for source cluster CDK
```
git clone https://github.com/lewijacn/opensearch-cluster-cdk.git && cd opensearch-cluster-cdk && git checkout migration-es
```

2. Install NPM dependencies
```
npm install
```
3. Configure AWS credentials

Configure the desired [AWS credentials](https://docs.aws.amazon.com/cdk/v2/guide/getting_started.html#getting_started_prerequisites) for the environment, as these will dictate the region and account used for deployment.

4. Configure cluster options

The below options configure a single node Elasticsearch 7.10.2 cluster on EC2 as well as a VPC to place the cluster into, though alternatively a `vpcId` could be provided for an existing VPC. It also configures an internal load balancer, which should be used when interacting with the cluster. These options can be copied and pasted into a `cdk.context.json` file in the current repository root directory to use for a deployment. Be sure to swap the `<STAGE>` placeholders with the desired deployment stage e.g. `dev`

```
{
  "source-single-node-ec2": {
    "suffix": "ec2-source-<STAGE>",
    "networkStackSuffix": "ec2-source-<STAGE>",
    "distVersion": "7.10.2",
    "cidr": "12.0.0.0/16",
    "distributionUrl": "https://artifacts.elastic.co/downloads/elasticsearch/elasticsearch-oss-7.10.2-linux-x86_64.tar.gz",
    "captureProxyEnabled": false,
    "securityDisabled": true,
    "minDistribution": false,
    "cpuArch": "x64",
    "isInternal": true,
    "singleNodeCluster": true,
    "networkAvailabilityZones": 2,
    "dataNodeCount": 1,
    "managerNodeCount": 0,
    "serverAccessType": "ipv4",
    "restrictServerAccessTo": "0.0.0.0/0"
  }
}
```
Note: Other versions of Elasticsearch or OpenSearch can be specified in distributionUrl.

5. CDK Bootstrap region if first CDK deployment in region

**Note**: This action should only need to be performed once for a region
```
cdk bootstrap --c contextId=source-single-node-ec2 --c contextFile=cdk.context.json
```
6. Deploy Cloudformation Stacks with CDK
```
cdk deploy "*" --c contextId=source-single-node-ec2 --c contextFile=cdk.context.json
```

After the deployment has finished, CDK will output the internal load balancer endpoint that can be used within the VPC to interact with the source cluster as you normally would:
```
# Stack output
opensearch-infra-stack-ec2-source-dev.loadbalancerurl = opense-clust-owiejfo2345-sdfljsd.elb.us-east-1.amazonaws.com

# Curl command within VPC
curl http://opense-clust-owiejfo2345-sdfljsd.elb.us-east-1.amazonaws.com:9200
```

Finally, once you are done using the provisioned source cluster, it can be deleted by executing the following command
```
cdk destroy "*" --c contextId=source-single-node-ec2 --c contextFile=cdk.context.json
```

For a full list of options, please reference the CDK options [here](https://github.com/lewijacn/opensearch-cluster-cdk/tree/migration-es?tab=readme-ov-file#required-context-parameters)

Owner: @lewijacn