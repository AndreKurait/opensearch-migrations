// Note: This integ test uses an external S3 snapshot instead of deploying a source cluster

def call(Map config = [:]) {
    def migrationContextId = 'migration-rfs-external-snapshot'
    def stageId = config.stageId ?: 'rfs-external-snapshot-integ'
    // Get the lock resource name from config or default to the stageId
    def lockResourceName = config.lockResourceName ?: stageId
    def sourceContextId = 'source-empty'
    
    // Define the metrics file paths
    def testDir = "/root/lib/integ_test/integ_test"
    def testUniqueId = config.testUniqueId ?: "integ_full_${new Date().getTime()}_${currentBuild.number}"
    def remoteMetricsPath = "${testDir}/reports/${testUniqueId}/backfill_metrics.csv"
    def metricsOutputDir = "backfill-metrics"
    def localMetricsPath = "${metricsOutputDir}/backfill_metrics.csv"
    
    // Define the file retrieval callback for plotting
    def plotMetricsCallback = { ->
        echo "Starting metrics plotting callback"
        
        try {
            if (fileExists(localMetricsPath)) {
                echo "Metrics file found at ${localMetricsPath}"
                
                // Display file contents for debugging
                sh """
                    echo "File size: \$(du -h ${localMetricsPath} | cut -f1)"
                    echo "File contents:"
                    cat ${localMetricsPath}
                """
                
                // Validate CSV format
                def fileContent = readFile(localMetricsPath)
                if (!fileContent.trim()) {
                    echo "ERROR: Metrics file is empty"
                    return
                }
                
                def lines = fileContent.split('\n')
                echo "Number of lines in CSV: ${lines.size()}"
                
                if (lines.size() < 2) {
                    echo "ERROR: CSV file does not have enough data (header + at least one data row)"
                    return
                }
                
                echo "CSV header: ${lines[0]}"
                echo "First data row: ${lines.size() > 1 ? lines[1] : 'N/A'}"
                
                // Plot the metrics
                echo "Plotting metrics with Jenkins Plot plugin"
                plot csvFileName: 'backfill_metrics.csv',
                     csvSeries: [[file: localMetricsPath, exclusionValues: '', displayTableFlag: true, inclusionFlag: 'OFF', url: '']],
                     group: 'Backfill Metrics',
                     title: 'Backfill Performance',
                     style: 'line',
                     exclZero: false,
                     keepRecords: true,
                     logarithmic: false,
                     numBuilds: '10',
                     yaxis: 'Value'
                
                echo "Plot configuration complete"
            } else {
                echo "ERROR: Metrics file not found at ${localMetricsPath}, skipping plot"
                
                // Check if the directory exists
                sh """
                    if [ -d "\$(dirname ${localMetricsPath})" ]; then
                        echo "Directory exists, listing contents:"
                        ls -la \$(dirname ${localMetricsPath})
                    else
                        echo "Directory does not exist: \$(dirname ${localMetricsPath})"
                    fi
                """
            }
        } catch (Exception e) {
            echo "ERROR: Exception occurred during plotting: ${e.message}"
            e.printStackTrace()
        }
    }
    
    // Empty source context to satisfy defaultIntegPipeline requirements
    def source_cdk_context = """
        {
          "source-empty": {
            "suffix": "ec2-source-<STAGE>",
            "networkStackSuffix": "ec2-source-<STAGE>"
          }
        }
    """
    
    def migration_cdk_context = """
        {
          "migration-rfs-external-snapshot": {
            "sourceCluster": {
              "disabled": true
            },
              
            "stage": "<STAGE>",
            "vpcId": "<VPC_ID>",
            "engineVersion": "OS_2.11",
            "domainName": "os-cluster-<STAGE>",
            "dataNodeCount": 2,
            
            "openAccessPolicyEnabled": true,
            "domainRemovalPolicy": "DESTROY",
            "artifactBucketRemovalPolicy": "DESTROY",
            "tlsSecurityPolicy": "TLS_1_2",
            "enforceHTTPS": true,
            "nodeToNodeEncryptionEnabled": true,
            "encryptionAtRestEnabled": true,
            "domainAZCount": 2,


            "reindexFromSnapshotServiceEnabled": true,
            "vpcEnabled": true,
            "vpcAZCount": 2,
            "migrationAssistanceEnabled": true,
            "migrationConsoleServiceEnabled": true,
            "otelCollectorEnabled": true
          }
        }
    """
    defaultIntegPipeline(
            sourceContext: source_cdk_context,
            migrationContext: migration_cdk_context,
            sourceContextId: sourceContextId,
            migrationContextId: migrationContextId,
            defaultStageId: stageId,
            lockResourceName: lockResourceName,  // Use the lock resource name for Jenkins locks
            skipCaptureProxyOnNodeSetup: true,
            skipSourceDeploy: true,
            jobName: 'rfs-external-snapshot-e2e-test',
            integTestCommand: '/root/lib/integ_test/integ_test/large_backfill_tests.py',
            testUniqueId: testUniqueId,  // Pass the unique ID to ensure consistency
            // Add file retrieval configuration
            retrieveFiles: [
                [
                    remotePath: remoteMetricsPath,
                    localPath: localMetricsPath,
                    clusterName: null  // Use default cluster name
                ]
            ],
            fileRetrievalCallback: plotMetricsCallback,
            archiveRetrievedFiles: true
    )

}
