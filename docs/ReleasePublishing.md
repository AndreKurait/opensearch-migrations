# Publishing a prerelease

Use **Release dry-run** on the intended commit before cutting a release tag. The workflow builds the Gradle Maven repository, source, six multi-architecture images, Helm chart and deployment artifacts. It signs the source and Maven files with an ephemeral test key, verifies the resulting Central bundle, and copies both version and `latest` image indexes in a runner-local registry. Any fork can run it.

## Drafting

**Release drafter** runs on a protected version tag such as `3.4.2`. Configure a tag ruleset restricting who may create, update, and delete release tags, and require reviewers on the `release-approval` environment. The tag must point at reviewed release code. The environment approval covers the job that builds artifacts, stages images, uploads the Solutions ZIP and creates the GitHub prerelease.

| Setting | Location | Purpose |
| --- | --- | --- |
| `RELEASE_DRAFT_ENABLED` | Repository variable | Set to `true` to opt in on a fork. The upstream repository has drafting enabled. |
| `RELEASE_STAGING_REGISTRY` | Repository variable | Docker Hub staging namespace, e.g. `opensearchstaging` upstream. Forks supply their own namespace. |
| `RELEASE_SOLUTIONS_S3_URI` | Repository variable | Solutions bucket/prefix, e.g. `s3://my-release-bucket/SO0290`. Forks supply their own destination. |
| `MIGRATIONS_DOCKER_USERNAME`, `RELEASE_STAGING_DOCKER_TOKEN` | `release-approval` secrets | Docker Hub service account and write token scoped to the staging repositories. |
| `SOLUTIONS_CREDENTIALS_ROLE`, `SOLUTIONS_UPLOAD_ROLE` | `release-approval` secrets | GitHub OIDC entry role and the existing Solutions upload role it may assume. |

## Promoting

Set the repository variable `RELEASE_PUBLISH_ENABLED` to `true` only after provisioning the production release credentials and destinations. Protect the `release-publish` environment with required reviewers and deployment rules allowing only the protected release tags. For both release environments, configure the OIDC entry role to require audience `sts.amazonaws.com` and the exact subject `repo:OWNER/REPO:environment:ENVIRONMENT`. A repository with customized OIDC claims should match its actual subject. Give the promotion role access to its S3 prefix, signer role, and the six public ECR repositories. Public ECR authentication uses `us-east-1`.

Drafting calls **Publish prerelease artifacts** directly after building the prerelease. A prerelease published in the UI also triggers promotion. A maintainer can dispatch it manually against its protected tag. It verifies both release asset digests before signing. The source stays at `RELEASE_S3_URI/<tag>/opensearch-migrations-<tag>.tar.gz` and its detached signature is adjacent with the suffix `.sig`. Maven Central receives an automatically published bundle. Images keep their manifest indexes under `<tag>` and `latest` in the configured destination. The workflow posts the result and run link to a release issue. After it succeeds, a maintainer can mark the GitHub release as a full release.

| `release-publish` configuration | Purpose |
| --- | --- |
| Variable `RELEASE_S3_URI` | S3 prefix including `migrations`, e.g. `s3://my-release-bucket/migrations`. |
| Variable `RELEASE_PUBLIC_ECR_REGISTRY` | Public ECR registry namespace, e.g. `public.ecr.aws/my-alias`. Create the six `opensearch-migrations-*` repositories before promotion. |
| Variable `RELEASE_PUBLISH_ROLE_ARN` | GitHub OIDC release role. Its IAM scope is independent of PR test roles. |
| Variables `RELEASE_SIGNER_CLIENT_SHA`, `RELEASE_SIGNER_ROLE_ARN`, `RELEASE_SIGNER_UNSIGNED_BUCKET`, `RELEASE_SIGNER_SIGNED_BUCKET` | Approved 40-character commit of the private OpenSearch signer client, cross-account signing role and exchange buckets supplied by the signing service owner. |
| Secrets `RELEASE_SIGNER_CLIENT_READ_TOKEN`, `RELEASE_SIGNER_EXTERNAL_ID` | Read access to `opensearch-project/opensearch-signer-client` and the signing role external ID. |
| Secrets `RELEASE_CENTRAL_USER`, `RELEASE_CENTRAL_TOKEN` | Sonatype Central Portal token username and password for the verified `org.opensearch` namespace. |

The legacy source signing path used the OpenSearch project PGP identity and a binary detached signature. Maven used the release identity and an ASCII-armored detached signature per artifact. The former S3 upload only copied the source file. The native workflow uses the same signer-client interface and publishes the detached signature as a second object. The signing service and Maven Central namespace require authorization from their owners. Enable publication in a fork only when those owners intend that fork to publish. [OpenSearch PGP implementation](https://github.com/opensearch-project/opensearch-build/blob/d12573997c93b8ced4a96eb6389868b6a38d2374/src/sign_workflow/signer_pgp.py) · [Historical signing and upload](https://github.com/opensearch-project/opensearch-build-libraries/blob/a5383f2ca18dd844c06bcd4be5194d288c7fe6a2/vars/publishToArtifactsProdBucket.groovy) · [Central Publisher API](https://central.sonatype.org/publish/publish-portal-api/)

If the workflow fails after Central accepts the bundle, copy the deployment ID from the run summary or the Central Portal and supply it as `central_deployment_id` on the next manual dispatch of the same tag. The workflow resumes polling that deployment before copying the source and promoting images. If Central rejected validation, correct the release artifacts according to the reported validation errors. Complete one tag's promotion before promoting the next tag so `latest` follows the intended release order.
