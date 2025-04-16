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
    
    def metricsToPlot = [
        [field: 'Duration (min)', title: 'Duration', yaxis: 'minutes', style: 'line', logarithmic: false],
        [field: 'Reindexing Throughput Total (MiB/s)', title: 'Reindexing Throughput Total', yaxis: 'MiB/s', style: 'line', logarithmic: false],
        [field: 'Reindexing Throughput Per Worker (MiB/s)', title: 'Reindexing Throughput Per Worker', yaxis: 'MiB/s', style: 'line', logarithmic: false],
        [field: 'Size Transferred (GB)', title: 'Primary Shard Size Transferred', yaxis: 'GiB', style: 'line', logarithmic: false],
    ]
    
    def plotMetricsCallback = { ->
        echo "Starting metrics plotting callback"
        
        try {
            if (fileExists(localMetricsPath)) {
                echo "Metrics file found at ${localMetricsPath}"
                
                sh """
                    echo "File size: \$(du -h ${localMetricsPath} | cut -f1)"
                    echo "File contents:"
                    cat ${localMetricsPath}
                """
                
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
                echo "First data row: ${lines[1]}"
                
                // Plot each metric from the static list
                metricsToPlot.each { metric ->
                    echo "Plotting ${metric.title} (Field: ${metric.field})"

                    def uniqueCsvName = "backfill_metrics_" + metric.field.replaceAll(/[^A-Za-z0-9]/, '') + ".csv"
                    
                    plot csvFileName: uniqueCsvName,
                         csvSeries: [[file: localMetricsPath, exclusionValues: metric.field, displayTableFlag: false, inclusionFlag: 'INCLUDE_BY_STRING', url: '']],
                         group: 'Backfill Metrics',
                         title: metric.title,
                         style: metric.style,
                         exclZero: false,
                         keepRecords: false,
                         logarithmic: metric.logarithmic,
                         yaxis: metric.yaxis,
                         hasLegend: false
                }
                echo "Plotting complete"
            } else {
                echo "ERROR: Metrics file not found at ${localMetricsPath}, skipping plot"
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
              "endpoint": "https://google.com",
              "auth": {"type": "none"},
              "version": "ES_5.6"
            },
            "snapshot": {
                "snapshotName": "final-snapshot-document_multiplier_1744748387525_9",
                "s3Uri": "s3://migration-jenkins-snapshot-863518433585-es56-us-east-1/es56-snapshot",
                "s3Region": "us-east-1"
            },
            "stage": "<STAGE>",
            "vpcId": "<VPC_ID>",
            "engineVersion": "OS_2.17",
            "domainName": "os-cluster-<STAGE>",
            "dataNodeCount": 30,
            "dataNodeType": "r7gd.8xlarge.search",
            "ebsEnabled": false,
            "openAccessPolicyEnabled": true,
            "domainRemovalPolicy": "DESTROY",
            "artifactBucketRemovalPolicy": "DESTROY",
            "tlsSecurityPolicy": "TLS_1_2",
            "enforceHTTPS": true,
            "nodeToNodeEncryptionEnabled": true,
            "encryptionAtRestEnabled": true,
            "domainAZCount": 2,
            "dedicatedManagerNodeCount": 3,
            "dedicatedManagerNodeType": "r7g.xlarge.search",
            


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
