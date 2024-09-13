## What is this guide?

This guide demonstrates a way to quickly load some test data into an Elasticsearch or OpenSearch source cluster using AWS Glue and the AWS Open Dataset library.  In it, we'll walk through how to index bitcoin transaction data on the source cluster.  For more context, check out [the official AWS Docs on setting up Glue connections to OpenSearch](https://docs.aws.amazon.com/glue/latest/dg/aws-glue-programming-etl-connect-opensearch-home.html)

## 1. Create your source cluster

Create your source cluster however you'd like, with a couple caveats:
* We're going to use basic auth (username/pass) to provide access control for the cluster.  In this example we'll be using Elasticsearch 7.10 as our source cluster but it is expected that earlier versions of Elasticsearch as well as OpenSearch 1.X and 2.X should work as well.
* The source cluster needs to be in a VPC you control and have access to in order for AWS Glue to send data to it

## 2. Create the access secret using Secret Manager

We need to create a Secret that will provide AWS Glue access to the source cluster's basic auth credentials.  We can do this easily by navigating to the AWS Console for Secrets Manager and clicking through to great a generic secret.  You can name it whatever you want, and set up replication/rotations however you'd like as well.

The key fields are:

* `opensearch.net.http.auth.user`: The username to use when accessing your source cluster
* `opensearch.net.http.auth.pass`: The password to user when accessing your source cluster


![Screenshot 2024-07-22 at 9 36 55 AM](https://github.com/user-attachments/assets/dde7e343-4a9c-4f0b-af6d-e7048ecd1b14)

## 3. Create AWS Glue access IAM Role

We need to create an IAM Role to give our AWS Glue Job permission to do work in our account.  Go to the AWS Console and create a new IAM Role.

The trust policy should allow the AWS Glue Service the ability to assume the role:

```
{
    "Version": "2012-10-17",
    "Statement": [
        {
            "Effect": "Allow",
            "Principal": {
                "Service": "glue.amazonaws.com"
            },
            "Action": "sts:AssumeRole"
        }
    ]
}
```

For the permissions, attach the `AWSGlueServiceRole` and `AmazonS3ReadOnlyAccess` managed permission sets.  Additionally, we need to give the role access to the Secret we created above; make a new inline permission and attach it to the role as well.  It will look something like:

```
{
    "Version": "2012-10-17",
    "Statement": [
        {
            "Effect": "Allow",
            "Action": "secretsmanager:GetSecretValue",
            "Resource": "arn:aws:secretsmanager:us-east-1:XXXXXXXXXXXX:secret:migration-assistant-source-cluster-creds-YDtnmx"
        }
    ]
}
```

## 4. Create an AWS Glue Connection

We next need to create an AWS Glue Connection in order to provide a path to our source cluster to AWS Glue.  Once again, we can do this via the AWS Console.  Navigate to the Glue Console and create a new Connection of the `Amazon OpenSearch Service` type, filling in your source cluster's details for each field (including VPC/Subnet/Security group).  Use the Secret from the previous step.

![Screenshot 2024-07-22 at 10 03 33 AM](https://github.com/user-attachments/assets/b5978b2e-de58-4d46-ad47-ac960e729b89)

## 5. (Optional) Examine the source data set

The sample data we'll use is [the AWS Public Blockchain dataset](https://registry.opendata.aws/aws-public-blockchain/), which is freely available.  You can learn more about it [in this blog post](https://aws.amazon.com/blogs/database/access-bitcoin-and-ethereum-open-datasets-for-cross-chain-analytics/), and browse its [contents in S3 with this link](https://us-east-2.console.aws.amazon.com/s3/buckets/aws-public-blockchain).

The BTC transaction data we'll load into our source cluster is at the S3 URI `s3://aws-public-blockchain/v1.0/btc/transactions/`. 

## 6. Create the AWS Glue Job

Select the AWS Glue Connection we created in the previous step in the console and create a Job for it.  We'll want an S3 data source and an Amazon OpenSearch Service data target, configured like so:

### S3 Source

Set the S3 URI to `s3://aws-public-blockchain/v1.0/btc/transactions`, be sure to enable `Recursive` reading of the contents of the bucket.  The data format is Parquet.

![Screenshot 2024-07-22 at 10 50 28 AM](https://github.com/user-attachments/assets/6fc4c0da-45b9-4c09-ba73-1619f59c9dd3)

### OpenSearch Target

Even though the target type here is OpenSearch, it will work for Elasticsearch as well.  Select the AWS Glue Connection we created in the previous step, and specify an index name to store the BTC transaction data in.

<img width="666" alt="Screenshot 2024-07-22 at 11 29 22 AM" src="https://github.com/user-attachments/assets/264d0d17-f7f4-4c07-8567-6cae47c3ccd1">

### (Optional) Pre-Configure the index settings

By default, the Glue Job will create a single-shard index with no special settings.  The data set we're using is roughly 1 TB in size, and [the standard guidance](https://www.elastic.co/guide/en/elasticsearch/reference/7.10/size-your-shards.html#shard-size-recommendation) is to keep shards between 10-50 GB in size.  Therefore, we'll want to pre-create our index with more shards:

```
curl -u <your username>:<your password> -X PUT "http://<your source cluster domain>:9200/bitcoin-data" -H 'Content-Type: application/json' -d'
{
  "settings": {
    "number_of_shards": 40,
    "number_of_replicas": 1
  }
}
'
```

You can also adjust any additional settings for this index at time, too.

## 7. Run the Glue Job

Once the Glue source/target are configured, you can run the job in the AWS Console to load the data into your source cluster.  This is done by clicking the "Run" button.  You can then watch the progress of the run by clicking on the "Runs" tab in the console.


