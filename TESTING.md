## Gradle Usage

[Gradle](https://gradle.org/) is used to build this repo, including its java artifacts as well as docker images.  Gradle handles dependencies between projects, compiling java code, running [JUnit](https://junit.org/junit5/) tests, and building docker images.  It can also deploy a demo/test environment via docker-compose for a rapid develop experience (see [dockerSolution](./TrafficCapture/dockerSolution/README.md)).

The Gradle application is packaged within the repository, so one can simply run [gradlew](./gradlew) from the root of the repository.  `./gradlew tasks` will show the tasks available at the top-level.  `.../gradlew tasks` run in any subproject directory will show specific tasks that can be run for that project.  Gradle can publish a scan to `scans.gradle.com` of its logs, performance, etc. at the end of its run, which can be used to diagnose a number of issues from test failures to build performance.

This `OpensearchMigrations` Gradle project is composed of many subprojects, defined by [settings.gradle](settings.gradle).  Those projects are configured similarly in the [build.gradle](./build.gradle) file.  Additional settings are defined in the [gradle.properties](./gradle.properties) file.

## Tests and Parallelization  

Gradle is configured to run most tasks from the projects in parallel, with a special exemption for tests marked to run them in total isolation of anything else that the gradle parent process is doing.  Tasks that can be run in parallel include building targets and running tests.  Notice that typical dependency rules apply.  The number of tasks running concurrently will be limited by the maxWorkerCount that gradle is passed or configures (which is typically the # of CPUs).

Each project within the project has the same base test configuration.  Targets include `test`, `slowTest`, `isolatedTest`, and `fullTest`, which are defined within the root `build.gradle` file.  Those targets are defined via the @Tag("NAME") attribute on each test (class or method).  A project's `test` tasks will be run as part of its `build` task.

A summary of each project's target composition is as follows.  Notice that `allTests` exists at the top-level project to depend upon ALL of the test tasks across all of the projects.

| Target | Composition | Purpose |
|---|---|---|
|`slowTest`| `@Tag("longTest")`| Tests that are too slow to provide value for every build run |
|`isolatedTest`| `@Tag("isolatedTest")`| Tests that may skew more toward integration tests and may take seconds to minutes to run.  While these tests may require more time, they shouldn't require exhaustive use of resources or be sensitive to other tasks running in parallel |
|`test`| all other tests NOT marked with the tags above ("longTest" or "isolatedTest")| Tests that require significant or exclusive use of resources or have sensitive performance bounds |
|`fullTest`| a task dependent upon the tasks above | Convenience Task |

The `isolatedTest` task (for each project) will run each of the tagged tests in serial and will run the isolatedTest task itself in serial from all of the other tasks within the project.  While the `isolatedTest` task isn't marked as dependent upon the other tests, it is marked to run _after_ other tests if gradle is set to run them.  That eliminates the requirement that test or slowTest run BEFORE the isolatedTest target when a developer is trying to only run the isolatedTest target.  Likewise, `slowTest` isn't dependent upon `test`, but those two targets may run in parallel since there aren't isolation requirements.  Parallelization for the test runners IS configured for `test` and `slowTest` targets so that those tests may complete quicker on hardware with more capacity.

## Traffic Capture Memory Leak Detections

TrafficCapture overrides the test and slowTest targets to enable netty leak detection only for slowTest.  The regular test targets for the TrafficCapture subprojects sets and environment variable to disable leak detection and the `slowTest` target for those subprojects leaves the `disableMemoryLeakTests` unset but alters the tag definition to include all tests but those tagged with isolatedTest.

## Integration tests on GitHub Actions

**Local Integ Tests** runs ten source/target suites in separate kind clusters and the Docker Compose replay suite on PRs, the merge queue, and pushes to `main`. These GitHub-hosted jobs use a read-only repository token. The check uses the tests and fixtures from the checked-out revision. Job logs stream during runs. The job summary lists case outcomes, and the run's artifacts contain test reports and collected application logs for 30 days.

For a compatibility run, dispatch **Local Integ Tests** with `suite=matrix`. Select one source, one target, or leave both as `all` to run all 30 configured combinations. Optionally pass `test-ids` as comma-separated four-digit IDs (or `all`). For example:

```bash
gh workflow run local-integ-tests.yml --ref YOUR_BRANCH \
  -f suite=matrix -f source=ES_6.8 -f target=OS_2.19 -f test-ids=0036
```

The default IDs and scheduled groups are in [.github/scripts/local-integ-matrix.py](.github/scripts/local-integ-matrix.py). The hourly run covers Elasticsearch and OpenSearch sources. The six-hour run covers Solr 8 and 9. The compatibility matrix includes Solr 6 and 7, which require filesystem backup. Every six hours the compatibility matrix and additional standard kind suites exercise the latest published upstream Migration Assistant release. Set the repository Actions variable `LOCAL_INTEG_CANARY_REF` to a tag or commit SHA in this repository to test a different release candidate.

## AWS integration tests

PRs run local integration tests automatically. To run a PR against AWS, review the entire change that will execute, including test code, setup scripts, Gradle tasks and dependency changes. On the PR, a collaborator with current write, maintain or admin access can comment `/aws-test FULL_HEAD_SHA eks`. Copy the 40-character SHA from the PR’s **Commits** tab. A push to the PR requires a new approval. `/aws-test FULL_HEAD_SHA` uses the PR’s `run-eks-tests`, `run-cfn-tests`, or `run-eks-isolated-tests` labels if present. With no matching labels, it runs all seven enabled suites. Labels alone never start an AWS build.

| Suite | Default test coverage | Worker timeout |
| --- | --- | --- |
| `eks` | Managed Elasticsearch 7.10 → OpenSearch 1.3, cases `0001,0002` | 3 h |
| `aoss` (paused) | OpenSearch 1.3 snapshots → classic and NextGen Serverless collections, `0021,0024` | 3 h |
| `byos` (paused) | S3 snapshot → OpenSearch 2.19, `0010` | 18 h |
| `cdc` | Capture and replay, `0031,0032,0033,0040,0042` | 5 h |
| `cdc-aoss` | Capture and replay to Serverless, `0034,0041` | 4 h |
| `cdc-k6` | k6 traffic replay, `0080` | 5 h |
| `cfn-create`, `cfn-import` | Install and verify the solution with a new or existing VPC | 3 h each |
| `isolated` | Build in one cluster, deploy in an isolated VPC and run `0040` | 4 h |

To run a specific changed test before merge, dispatch **AWS integration tests** from `main` with the PR number and reviewed head SHA. A dispatch without a PR runs `main`. Inputs are passed as data. Each suite supports its own subset of parameters, validated in [.github/ci/controller/parameters.py](.github/ci/controller/parameters.py).

```bash
gh workflow run aws-integration.yml --ref main \
  -f suite=cdc -f pr=123 -f sha=FULL_REVIEWED_40_CHARACTER_SHA \
  -f parameters='{"TEST_IDS":"0040,0042","TARGET_VERSION":"OS_2.19","SPEEDUP_FACTOR":20}'
```

- Change a test implementation or add fixtures in the PR, select its ID, and run that revision. To make it a default, update the suite in [.github/ci/aws/suites.py](.github/ci/aws/suites.py). The protected controller learns new suite names when that change reaches `main`.
- The two suites that read pre-existing S3 snapshots, `byos` and `aoss` (including its two aliases), are paused until the fixture bucket moves or grants the test account read access. The worker and controller both enforce this. `cdc-aoss` creates its own data and remains enabled.
- `cdc-tests` selects the three CDC suites. Set `release` to a published numeric version such as `3.4.2` to exercise the release source and assets. The k6 canary builds its images from the release source. All seven enabled suites run on pushes to `main`. Scheduled groups and the six-hour release canary are in [.github/ci/controller/plan.py](.github/ci/controller/plan.py).
- The PR commit receives one `aws-integration/<suite>` status per suite. Open **Details** for the GitHub Actions run. The watch job streams CodeBuild logs to the Actions log and lists case outcomes in its job summary. Download `aws-<suite>-<attempt>` for the JUnit report and machine-readable summary. A long job hands its CloudWatch cursor to the next GitHub runner.

### AWS identity and runner setup

GitHub’s orchestration code comes from `main`. It verifies current collaborator permission, PR state, target branch and head SHA before requesting a build. The launcher exchanges a GitHub OIDC token for a short-lived role session. Its trust checks the audience, repository and owner IDs, main ref, subject and reusable workflow. Keep `main` and workflow changes under review. Use GitHub-hosted runners for credential-free fork PRs. Keep secrets for release and publishing in separately protected environments.

The disposable CodeBuild worker checks out the approved SHA and runs the PR’s code with `MigrationsGitHubWorker-us-west-2`. Approval gives that revision and its dependencies temporary access to the CI sandbox. Their output streams to public GitHub logs, and the test reports are downloadable. Review code that can print credentials or fixture data, and give the worker access only to fixtures suitable for public disclosure. The builds share a test account and an IAM role across runs. Separate projects and roles are required if suites must be mutually isolated. Worker cleanup runs after tests. An EventBridge rule starts a fresh cleanup worker on terminal failure or cancellation. Check its result in CodeBuild if the original worker was interrupted.

Provision the Oregon (`us-west-2`) stack using an operator profile and the expected sandbox account ID supplied locally. It needs PyYAML. Refresh the account configuration after provisioning or changing quotas. The discovery command without `--publish` prints available capacity and suite queues. With `--publish`, it writes a single `AWS_CI_CONFIG` secret to the `aws-ci` GitHub environment restricted to `main`.

```bash
python3 .github/ci/infra/deploy.py --profile github-actions-test --region us-west-2 \
  --expected-account "$AWS_CI_ACCOUNT_ID"
python3 .github/ci/controller/capacity.py --profile github-actions-test --publish
```

The environment secret contains the account ID, region, and numeric capacity for Linux/Large CodeBuild, VPCs, ENIs per availability zone, Elastic IPs, internet and egress-only gateways, NAT gateways per availability zone and Standard On-Demand EC2 vCPUs. GitHub obtains temporary credentials through OIDC. The launcher cannot deploy the IAM stack.

The controller subtracts background usage and assigns suites to shared GitHub queues across PRs, pushes and schedules. It checks live capacity before launching each worker and reserves resources through teardown. With five Elastic IPs and 32 Standard On-Demand vCPUs in Oregon, one AWS suite runs at a time. Each source build runs native amd64 and arm64 BuildKit pods that request four CPUs apiece. The CloudFormation and EKS suites reserve 24 vCPUs for these builds and the cluster. CDC and isolated suites reserve 32 vCPUs. The isolated suite temporarily holds two EKS clusters, two VPCs and four EIPs. Local GitHub-hosted tests run independently.

The Linux/Large project caps running workers at 12 and the controller leaves one slot for recovery. AWS enforces the regional Linux/Large limit across projects. EC2 and per-zone ENI reservations estimate peak usage. The CDC shared workload pool retains its tested 96-vCPU limit, or 80 vCPUs for `cdc-aoss`. Its dedicated workload pools have independent limits, so the regional quota can constrain scaling within a single run. Refresh the environment secret after a quota change and update the suite reservations when its footprint changes. [.github/ci/controller/capacity.py](.github/ci/controller/capacity.py)

While developing the AWS integration on this fork, set the repository variable `AWS_CI_DEVELOPMENT_PAUSE` to `true` to pause automatic native checks and AWS push and schedule runs. Native workflows remain available through manual dispatch. Maintainers can still request AWS suites on a reviewed PR commit. Delete the variable to restore automatic runs.
