// Note: This integ test uses an external S3 snapshot instead of deploying a source cluster

def call(Map config = [:]) {
    def migrationContextId = 'migration-rfs-external-snapshot'
    def stageId = config.stageId ?: 'rfs-external-snapshot-integ'
    // Get the lock resource name from config or default to the stageId
    def lockResourceName = config.lockResourceName ?: stageId
    def sourceContextId = 'source-empty'
    
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
            "dataNodeCount": 1,
            
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
            integTestCommand: '/root/lib/integ_test/integ_test/large_backfill_tests.py'
    )

}
