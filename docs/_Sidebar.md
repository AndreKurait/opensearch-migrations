### Migration Guide
* [[Where to Begin|Where-to-Begin]] 
* [[Migration Assistant Overview|Home]]

### Deploying Solution

* [AWS Solutions deployment](https://aws.amazon.com/solutions/implementations/migration-assistant-for-amazon-opensearch-service/) (External Link)
* [[Configuration Options|Configuration-Options]]
* [[IAM and Security Groups for existing clusters|IAM-and-Security-Groups-for-Existing-Clusters]]

### Accessing Migration Console

* [[Accessing the Migration Console|Accessing-the-Migration-Console]]
* [[Console commands reference|Migration-Console-commands-references]]


### Phases of a Migration

#### 1. Assessment
 - A. [[Understanding Breaking Changes|Understanding-breaking-changes]]
 - B. [[Required Client Changes|Required-Client-Changes]]

#### 2. Setup Verification
   - A. [[Snapshot Creation Verification|Snapshot-Creation-Verification]]
   - B. [[Metadata Migration Verification|Metadata-Migration-Verification]]
   - C. [[Client Traffic Switchover Verification|Client-Traffic-Switchover-Verification]]
   - D. [[Traffic Capture Verification|Traffic-Capture-Verification]]
   - E. [[Replay Verification|Replay-Verification]]  
   - F. [[Reindex-from-Snapshot Verification|Backfill-Reindex‐from‐Snapshot-Verification]]
   - G. [[System Reset Before Migration|System-Reset-Before-Migration]]

#### 3. Metadata
   - A. [[Metadata Migration|Metadata-Migration]]
   - B. [[Metadata Validation|Metadata-Validation]]

#### 4. Backfill
   - A. [[Capture Proxy Data Replication|Capture-Proxy-Data-Replication]] 
   - B. [[Snapshot Creation|Snapshot-Creation]]
   - C. [[Backfill Execution|Migrate-Existing-Data-from-Source-Cluster]]  
   - D. [[Backfill Result Validation|Backfill-Result-Validation]] 

#### 5. Replay Activation and Validation
   - A. [[Synchronized Cluster Validation|Synchronized-Cluster-Validation]]  
   - B. [[In-flight Validation|In‐flight-Validation]]

#### 6. Client Traffic Switchover
   - A. [[Switching Traffic from Source to Target|Switching-Traffic-from-Source-to-Target]]

#### 7. Post-Migration Cleanup
   - B. [[Migration Infrastructure Teardown|Migration-Infrastructure-Teardown]]


### Other Helpful Pages

* [[Provisioning Source Cluster for Testing|Provisioning-Source-Cluster-for-Testing]]

* [[Loading Sample Data into Source|Load-sample-data-into-your-Source-Cluster]]

* Migration Integration Tests

* Project Contribution Interests


