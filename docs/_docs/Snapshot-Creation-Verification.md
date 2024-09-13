## Snapshot Creation Verification

To migrate metadata or backfill a snapshot is required

### Install the Elasticsearch S3 Repository Plugin

The snapshot needs to be present in a location that Migration Assistant has access.  We use AWS S3 as that location, and the Migration Assistant creates an S3 Bucket for this purpose.  As a result, it is necessary to install the Elasticsearch S3 Repository Plugin on your source nodes [as described here](https://www.elastic.co/guide/en/elasticsearch/plugins/7.10/repository-s3.html).

Additionally, you need to ensure that the plugin has been configured with AWS Credentials that grant it the ability to read/write to AWS S3.  If your Elasticsearch cluster is running on EC2 or ECS instances with an execution IAM Role, that means you'll want to include the S3 Permissions in that.  Alternatively, you can store them in the Elasticsearch Key Store [as described here](https://www.elastic.co/guide/en/elasticsearch/plugins/7.10/repository-s3-client.html).

**NOTE:** Please ensure that the IAM Role your Source Cluster has access to is capable of reading/writing to all S3 Buckets in the AWS Account you've deployed the RFS into.  One of the AWS Resources created during the deployment is an S3 Bucket which RFS will use to store your snapshot; your Source Cluster must be able to access this bucket.  It is a current limitation of the deployment process that we don't have a good way of surfacing the ARN for this S3 Bucket to enable a more targeted Role Policy.

### Verifying S3 Repository Plugin configuration

You can verify that you've configured the S3 Repository Plugin correctly by taking a test snapshot.

First, you'll want to make a new S3 Bucket for the snapshot to be written into.  This is easy with the AWS CLI:

```
aws s3api create-bucket --bucket <your-bucket-name> --region <your-aws-region>
```

You can then register a new S3 Snapshot Repository on your Source Cluster like so:

```
curl -X PUT "http://<your-source-cluster>:9200/_snapshot/test_s3_repository" -H "Content-Type: application/json" -d '{
  "type": "s3",
  "settings": {
    "bucket": "<your-bucket-name>",
    "region": "<your-aws-region>"
  }
}'
```

You should get a response lile `{"acknowledged":true}`.  If the registration command fails with an error message like `Access Denied (Service: Amazon S3; Status Code: 403; Error Code: AccessDenied)`, it means your source cluster's IAM Role does not provide the necessary permissions to access the test Bucket.

You can then take a snapshot into your S3 Bucket.  To save time and expense, you can skip all the data in your Source Cluster and just snapshot the Cluster's metadata like so:

```
curl -X PUT "http://<your-source-cluster>:9200/_snapshot/test_s3_repository/test_snapshot_1" -H "Content-Type: application/json" -d '{
  "indices": "",
  "ignore_unavailable": true,
  "include_global_state": true
}'
```

You should get a response like `{"accepted":true}`.  Go into the AWS Console and check to see if your bucket now contains the snapshot.  It will look something like:

![Screenshot 2024-08-06 at 3 25 25 PM](https://github.com/user-attachments/assets/200818a5-e259-4837-aa2a-44c0bd7b099c)

#### Cleaning up from verification

To clean up the resources created while verifying your plugin is correctly configured, start by deleting the snapshot that was created:

```shell
curl -X DELETE "http://<your-source-cluster>:9200/_snapshot/test_s3_repository/test_snapshot_1?pretty"
```

Then, delete the snapshot repository:

```shell
curl -X DELETE "http://<your-source-cluster>:9200/_snapshot/test_s3_repository?pretty"
```

Finally, delete the created S3 Bucket and its contents:
```shell
aws s3 rm s3://<your-bucket-name> --recursive
aws s3api delete-bucket --bucket <your-bucket-name> --region <your-aws-region>
```

### Troubleshooting

### Related Links

### How to Contribute

owner: @chelma