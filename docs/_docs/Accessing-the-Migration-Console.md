## Managing the Migration Process via the Migration Console

The Migrations Assistant deployment includes an ECS task that hosts tools to run different phases of the migration and to check on progress/results of the migration.

## AWS Solutions bootstrap

Following the AWS solutions deployment the bootstrap box contains a script that makes it easier to access the migration console through that instance.

TODO: It is not clear how a user can get to accessContainer.sh. Please specify.

```
export STAGE=dev
export AWS_REGION=us-west-2
./deployment/cdk/opensearch-service-migration/accessContainer.sh migration-console ${STAGE} ${AWS_REGION}
```
Typically, STAGE is `dev`, but may differ depending on what the user specified at deployment.

## Manually via AWS CLI

On a machine with the [AWS CLI](https://docs.aws.amazon.com/cli/latest/userguide/getting-started-install.html) and the [AWS Session Manager Plugin](https://docs.aws.amazon.com/systems-manager/latest/userguide/session-manager-working-with-install-plugin.html) you can directly connect to the migration console.  Note: you'll need to run `aws configure` with credentials that have access to the environment.

```
export STAGE=dev
export SERVICE_NAME=migration-console
export TASK_ARN=$(aws ecs list-tasks --cluster migration-${STAGE}-ecs-cluster --family "migration-${STAGE}-${SERVICE_NAME}" | jq --raw-output '.taskArns[0]')
aws ecs execute-command --cluster "migration-${STAGE}-ecs-cluster" --task "${TASK_ARN}" --container "${SERVICE_NAME}" --interactive --command "/bin/bash"
```

TODO: Add steps to validate a user is on the migration console.

## Troubleshooting

TODO: Add documentation on creating a github issue

owner: @mikaylathompson

