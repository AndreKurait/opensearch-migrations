// Add this utility function to download files from the ECS task
def downloadFileFromEcsTask(String remotePath, String localPath, String stage, String clusterName = null) {
    echo "Downloading file from ${remotePath} to ${localPath} using ECS exec"
    
    // Create directory for the local file if it doesn't exist
    sh "mkdir -p \$(dirname ${localPath})"
    
    // If cluster name is not provided, construct it from the stage
    if (!clusterName) {
        clusterName = "migration-${stage}-ecs-cluster"
    }
    
    echo "Using cluster name: ${clusterName}"
    
    try {
        sh """
            # Inputs
            clusterName="migration-${stage}-ecs-cluster"
            serviceName="migration-${stage}-migration-console"
            remotePath="${remotePath}"
            localPath="${localPath}"

            echo "🔍 Finding ECS task in cluster: \$clusterName"
            TASK_ARN=\$(aws ecs list-tasks \
                --cluster "\$clusterName" \
                --service-name "\$serviceName" \
                --query 'taskArns[0]' \
                --output text)

            if [[ -z "\$TASK_ARN" || "\$TASK_ARN" == "None" ]]; then
                echo "❌ ERROR: No running task found in \$serviceName"
                exit 1
            fi

            echo "✅ Found task: \$TASK_ARN"
            echo "📂 Checking remote path: \$(dirname "\$remotePath")"
            aws ecs execute-command \
                --cluster "\$clusterName" \
                --task "\$TASK_ARN" \
                --container migration-console \
                --interactive \
                --command "ls -la \$(dirname "\$remotePath")"

            echo "📥 Downloading file: \$remotePath"
            mkdir -p "\$(dirname "\$localPath")"
            aws ecs execute-command \
                --cluster "\$clusterName" \
                --task "\$TASK_ARN" \
                --container migration-console \
                --interactive \
                --command "cat \$remotePath" \
                | tee /dev/tty | awk 'BEGIN { skip=1 }
                    /[Ss]tarting session with.*/ { skip=0; next }
                    /[Ee]xiting session with.*/ { exit }
                    !skip { print }' \
                | grep -v "Session Manager plugin" \
                | grep -v "session with SessionId" \
                | grep -v "Cannot perform" \
                | grep -v "^[[:space:]]*\$" > "\$localPath"

            if [[ ! -s "\$localPath" ]]; then
                echo "⚠️  Downloaded file is empty, removing: \$localPath"
                rm -f "\$localPath"
                exit 1
            fi

            echo "✅ File downloaded: \$localPath"
            echo "📦 Size: \$(du -h "\$localPath" | cut -f1)"
            echo "🔍 Preview:"
            head -n 5 "\$localPath"
        """

        def exists = fileExists(localPath)
        echo "File exists check: ${exists}"
        return exists
    } catch (Exception e) {
        echo "ERROR: Exception occurred during file download: ${e.message}"
        return false
    }
}

def call(Map config = [:]) {
    def sourceContext = config.sourceContext
    def migrationContext = config.migrationContext
    def defaultStageId = config.defaultStageId
    def jobName = config.jobName
    // Add new parameter for file retrieval
    def retrieveFiles = config.retrieveFiles ?: []
    if(sourceContext == null || sourceContext.isEmpty()){
        throw new RuntimeException("The sourceContext argument must be provided");
    }
    if(migrationContext == null || migrationContext.isEmpty()){
        throw new RuntimeException("The migrationContext argument must be provided");
    }
    if(defaultStageId == null || defaultStageId.isEmpty()){
        throw new RuntimeException("The defaultStageId argument must be provided");
    }
    if(jobName == null || jobName.isEmpty()){
        throw new RuntimeException("The jobName argument must be provided");
    }
    def source_context_id = config.sourceContextId ?: 'source-single-node-ec2'
    def migration_context_id = config.migrationContextId ?: 'migration-default'
    def source_context_file_name = 'sourceJenkinsContext.json'
    def migration_context_file_name = 'migrationJenkinsContext.json'
    def skipCaptureProxyOnNodeSetup = config.skipCaptureProxyOnNodeSetup ?: false
    def skipSourceDeploy = config.skipSourceDeploy ?: false
    def time = new Date().getTime()
    def testUniqueId = config.testUniqueId ?: "integ_full_${time}_${currentBuild.number}"
    def testDir = "/root/lib/integ_test/integ_test"
    def integTestCommand = config.integTestCommand ?: "${testDir}/replayer_tests.py"
    // Use a custom lock resource name if provided, otherwise use the stage parameter
    def lockResourceName = config.lockResourceName ?: params.STAGE
    
    pipeline {
        agent { label config.workerAgent ?: 'Jenkins-Default-Agent-X64-C5xlarge-Single-Host' }

        parameters {
            string(name: 'GIT_REPO_URL', defaultValue: 'https://github.com/AndreKurait/opensearch-migrations.git', description: 'Git repository url')
            string(name: 'GIT_BRANCH', defaultValue: 'JenkinsLargeSnapshotMigration', description: 'Git branch to use for repository')
            string(name: 'STAGE', defaultValue: "${defaultStageId}", description: 'Stage name for deployment environment')
        }

        options {
            // Acquire lock on a given deployment stage, use lockResourceName if provided
            // The variable 'lockVar' is only used internally for the lock mechanism
            lock(label: lockResourceName, quantity: 1, variable: 'lockVar')
            timeout(time: 30, unit: 'HOURS')
            buildDiscarder(logRotator(daysToKeepStr: '30'))
        }

        triggers {
            GenericTrigger(
                    genericVariables: [
                            [key: 'GIT_REPO_URL', value: '$.GIT_REPO_URL'],
                            [key: 'GIT_BRANCH', value: '$.GIT_BRANCH'],
                            [key: 'job_name', value: '$.job_name']
                    ],
                    tokenCredentialId: 'jenkins-migrations-generic-webhook-token',
                    causeString: 'Triggered by PR on opensearch-migrations repository',
                    regexpFilterExpression: "^$jobName\$",
                    regexpFilterText: "\$job_name",
            )
        }

        stages {
            stage('Checkout') {
                steps {
                    script {
                        // Allow overwriting this step
                        if (config.checkoutStep) {
                            config.checkoutStep()
                        } else {
                            git branch: "${params.GIT_BRANCH}", url: "${params.GIT_REPO_URL}"
                        }
                    }
                }
            }

            stage('Test Caller Identity') {
                steps {
                    script {
                        // Allow overwriting this step
                        if (config.awsIdentityCheckStep) {
                            config.awsIdentityCheckStep()
                        } else {
                            sh 'aws sts get-caller-identity'
                        }
                    }
                }
            }

            stage('Setup E2E CDK Context') {
                steps {
                    script {
                        // Allow overwriting this step
                        if (config.cdkContextStep) {
                            config.cdkContextStep()
                        } else {
                            writeFile (file: "test/$source_context_file_name", text: sourceContext)
                            sh "echo 'Using source context file options: ' && cat test/$source_context_file_name"
                            writeFile (file: "test/$migration_context_file_name", text: migrationContext)
                            sh "echo 'Using migration context file options: ' && cat test/$migration_context_file_name"
                        }
                    }
                }
            }

            stage('Build') {
                steps {
                    timeout(time: 1, unit: 'HOURS') {
                        script {
                            // Allow overwriting this step
                            if (config.buildStep) {
                                config.buildStep()
                            } else {
                                sh 'sudo --preserve-env ./gradlew clean build -x test --no-daemon'
                            }
                        }
                    }
                }
            }

            stage('Deploy') {
                steps {
                    timeout(time: 90, unit: 'MINUTES') {
                        dir('test') {
                            script {
                                // Allow overwriting this step
                                if (config.deployStep) {
                                    config.deployStep()
                                } else {
                                    // Use the actual stage parameter for deployment, not the lock variable
                                    def deployStage = params.STAGE
                                    echo "Acquired lock resource: ${lockVar}"
                                    echo "Deploying with stage: ${deployStage}"
                                    sh 'sudo usermod -aG docker $USER'
                                    sh 'sudo newgrp docker'
                                    def baseCommand = "sudo --preserve-env ./awsE2ESolutionSetup.sh --source-context-file './$source_context_file_name' " +
                                            "--migration-context-file './$migration_context_file_name' " +
                                            "--source-context-id $source_context_id " +
                                            "--migration-context-id $migration_context_id " +
                                            "--stage ${deployStage} " +
                                            "--migrations-git-url ${params.GIT_REPO_URL} " +
                                            "--migrations-git-branch ${params.GIT_BRANCH}"
                                    if (skipCaptureProxyOnNodeSetup) {
                                        baseCommand += " --skip-capture-proxy"
                                    }
                                    if (skipSourceDeploy) {
                                        baseCommand += " --skip-source-deploy"
                                    }
                                    withCredentials([string(credentialsId: 'migrations-test-account-id', variable: 'MIGRATIONS_TEST_ACCOUNT_ID')]) {
                                        withAWS(role: 'JenkinsDeploymentRole', roleAccount: "${MIGRATIONS_TEST_ACCOUNT_ID}", duration: 5400, roleSessionName: 'jenkins-session') {
                                            sh baseCommand
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            stage('Integ Tests') {
                steps {
                    timeout(time: 20, unit: 'HOURS') {
                        dir('test') {
                            script {
                                // Allow overwriting this step
                                if (config.integTestStep) {
                                    config.integTestStep()
                                } else {
                                    def deployStage = params.STAGE
                                    def test_result_file = "${testDir}/reports/${testUniqueId}/report.xml"
                                    def populatedIntegTestCommand = integTestCommand.replaceAll("<STAGE>", deployStage)
                                    def command = "pipenv run pytest --log-file=${testDir}/reports/${testUniqueId}/pytest.log " +
                                            "--junitxml=${test_result_file} ${populatedIntegTestCommand} " +
                                            "--unique_id ${testUniqueId} " +
                                            "--stage ${deployStage} " +
                                            "-s"
                                    withCredentials([string(credentialsId: 'migrations-test-account-id', variable: 'MIGRATIONS_TEST_ACCOUNT_ID')]) {
                                        withAWS(role: 'JenkinsDeploymentRole', roleAccount: "${MIGRATIONS_TEST_ACCOUNT_ID}", duration: 43,200, roleSessionName: 'jenkins-session') {
                                            sh "sudo --preserve-env ./awsRunIntegTests.sh --command '${command}' " +
                                                    "--test-result-file ${test_result_file} " +
                                                    "--stage ${deployStage}"
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            
            // Add optional stage for file retrieval
            stage('Retrieve Files') {
                when {
                    expression { return !retrieveFiles.isEmpty() }
                }
                steps {
                    timeout(time: 10, unit: 'MINUTES') {
                        dir('test') {
                            script {
                                // Allow overwriting this step
                                if (config.fileRetrievalStep) {
                                    config.fileRetrievalStep()
                                } else {
                                    def deployStage = params.STAGE
                                    withCredentials([string(credentialsId: 'migrations-test-account-id', variable: 'MIGRATIONS_TEST_ACCOUNT_ID')]) {
                                        withAWS(role: 'JenkinsDeploymentRole', roleAccount: "${MIGRATIONS_TEST_ACCOUNT_ID}", duration: 3600, roleSessionName: 'jenkins-file-retrieval-session') {
                                            // Download each file specified in the retrieveFiles list
                                            retrieveFiles.each { fileMap ->
                                                echo "Retrieving file: ${fileMap.remotePath} to ${fileMap.localPath}"
                                                downloadFileFromEcsTask(fileMap.remotePath, fileMap.localPath, deployStage, fileMap.clusterName)
                                            }
                                            
                                            // Archive all retrieved files
                                            if (config.archiveRetrievedFiles != false) {
                                                def archivePattern = retrieveFiles.collect { fileMap -> fileMap.localPath }.join(',')
                                                archiveArtifacts artifacts: archivePattern, allowEmptyArchive: true
                                            }
                                            
                                            // Call the post-retrieval callback if provided
                                            if (config.fileRetrievalCallback) {
                                                config.fileRetrievalCallback()
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        post {
            always {
                timeout(time: 10, unit: 'MINUTES') {
                    dir('test') {
                        script {
                            // Allow overwriting this step
                            if (config.finishStep) {
                                config.finishStep()
                            } else {
                                sh "echo 'Default post step performs no actions'"
                            }
                        }
                    }
                }
            }
        }
    }
}
