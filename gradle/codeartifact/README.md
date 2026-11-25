# Optional Maven dependency cache

Hosted PR builds use the public repositories and the GitHub Actions Gradle cache. AWS workers with a build role can use CodeArtifact to share Maven Central and Gradle Plugin Portal dependencies. The stack grants that role access to assume a read-only role in the same AWS account.

```bash
aws cloudformation deploy \
  --region us-east-1 --stack-name opensearch-migrations-dependency-cache \
  --template-file gradle/codeartifact/dependency-cache.yaml \
  --parameter-overrides AgentRoleName=YOUR_BUILD_ROLE \
  --capabilities CAPABILITY_NAMED_IAM
```

Set the following variables in the same shell that invokes Gradle. The helper prints the endpoint and a private filename. The file contains a 12-hour token.

```bash
set -eu
set +x
export MIGRATIONS_CACHE_DOMAIN=opensearch-migrations MIGRATIONS_CACHE_REGION=us-east-1
MIGRATIONS_CACHE_TMP_DIR=$(mktemp -d)
export MIGRATIONS_CACHE_TMP_DIR
trap 'rm -rf -- "$MIGRATIONS_CACHE_TMP_DIR"' EXIT
configuration=$(bash gradle/codeartifact/configureMavenCache.sh)
MAVEN_REPOSITORY_URL=$(head -n 1 <<< "$configuration")
MAVEN_REPOSITORY_PASSWORD_FILE=$(tail -n 1 <<< "$configuration")
export MAVEN_REPOSITORY_URL MAVEN_REPOSITORY_PASSWORD_FILE MAVEN_REPOSITORY_USERNAME=aws
./gradlew :searchClusterTestFixtures:spotlessJava :dashboardsSanitizer:compileJava
```

A custom `MAVEN_REPOSITORY_URL` must proxy both public upstreams. Credential sources are `MAVEN_REPOSITORY_PASSWORD_FILE` or `MAVEN_REPOSITORY_PASSWORD`, with `MAVEN_REPOSITORY_USERNAME`. Use HTTPS and keep credentials out of the URL, logs, caches, and artifacts. Unset `MAVEN_REPOSITORY_*` to restore public defaults. The wrapper and JDK downloads are independent of Maven dependency resolution.

Run `python3 -m unittest discover -s gradle/tests -v` under JDK 21 to test repository routing and credential-file handling.
