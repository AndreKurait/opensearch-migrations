def gitBranch = params.GIT_BRANCH ?: 'JenkinsLargeSnapshotMigration'
def gitUrl = params.GIT_REPO_URL ?: 'https://github.com/AndreKurait/opensearch-migrations.git'

library identifier: "migrations-lib@${gitBranch}", retriever: modernSCM(
        [$class: 'GitSCMSource',
         remote: "${gitUrl}"])

// Shared library function (location from root: vars/rfsDefaultE2ETest.groovy)
rfsDefaultE2ETest([stageId: params.STAGE])
