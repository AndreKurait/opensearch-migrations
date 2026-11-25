// Run the pinned sample's StackComposer with the sandbox asset store and IAM boundary.
const path = require('node:path');
const { createRequire } = require('node:module');
const sample = createRequire(path.join(process.cwd(), 'package.json'));
const { App, CliCredentialsStackSynthesizer, PermissionsBoundary } = sample('aws-cdk-lib');
const { StackComposer } = sample('./dist/lib/stack-composer');

const app = new App({
  defaultStackSynthesizer: new CliCredentialsStackSynthesizer({ qualifier: 'mactest01' }),
  permissionsBoundary: PermissionsBoundary.fromArn(process.env.CI_ROLE_BOUNDARY_ARN),
});
new StackComposer(app, {
  env: { account: process.env.CDK_DEFAULT_ACCOUNT, region: process.env.CDK_DEFAULT_REGION },
});
app.synth();
