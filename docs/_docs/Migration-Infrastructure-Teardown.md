After a migration is complete all resources should be removed except for the target cluster, and optionally your Cloudwatch Logs, and Replayer logs.

To remove all the CDK stack(s) which get created during a deployment you can execute a command similar to below within the CDK directory

```
cdk destroy "*" --c contextId=<CONTEXT_ID>  --c contextFile=<CONTEXT_FILE_PATH>
```
Or to remove an individual stack from the deployment we can execute

```
cdk destroy migration-console --c contextId=<CONTEXT_ID> --c contextFile=<CONTEXT_FILE_PATH>
```

Where CONTEXT_FILE_PATH is the absolute file path containing the CDK context to use and CONTEXT_ID is the id of the context block to use, see below:

<img width="463" alt="Screenshot 2024-08-12 at 10 00 33 AM" src="https://github.com/user-attachments/assets/d0991189-21b6-47a8-ba94-97699ef75333">

**Note**: If the given CONTEXT_ID has created a target cluster Domain and has the retention policy for the OpenSearch Domain set to `DESTROY`, it will remove this resource and all its data when the stack is deleted. In order to retain the Domain on stack deletion the `domainRemovalPolicy` would need to be set to `RETAIN`.


### Troubleshooting
There is a known issue with the MSKUtility stack custom resource intermittently fails to delete. The current workaround, while this teardown is being improved, is to retry delete of this stack on failure.