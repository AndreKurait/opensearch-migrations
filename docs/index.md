## Introduction

This guide is focused on Migration Assistant for Amazon OpenSearch. Before determining if this is the right tool for you, it's essential to understand your specific needs and the current tools available to perform an upgrade or migration.

Migration Assistant fills gaps that exist in other current migration solutions, but there are cases where other solutions might be recommended.

If you are interested in moving more than one major version, for example, from Elasticsearch 6.8 to OpenSearch 2.3, Migration Assistant offers the ability to do this in a single hop. Alternative tools, such as rolling upgrades or snapshot restore, would require multiple upgrades since they cannot jump more than one major version without reindexing your data. If you are interested in capturing live traffic and having a means to perform a zero-downtime migration, this solution is also right for you.

## Supported Migration Paths

### Tested Source and Target Versions
* Elasticsearch 6.8.23 to OpenSearch 2.14.0
* Elasticsearch 7.10.2 to OpenSearch 2.14.0
* Elasticsearch 7.17.22 to OpenSearch 2.14.0
* OpenSearch 1.3.16 to OpenSearch 2.14.0

> [!NOTE]  
> We expect minor versions within the specified major versions above (i.e., Elasticsearch 6 and 7 and OpenSearch 1 and 2) to be supported, but the versions above are tested.

### Supported Source and Target Platforms
* Self-managed (hosted by cloud provider or on-premises)
* AWS OpenSearch

> [!NOTE] 
> The tooling is designed to work with other cloud provider platforms, but it is not officially tested with these other platforms. If you would like to add support, please contact one of the maintainers on [GitHub](https://github.com/opensearch-project/opensearch-migrations/blob/main/MAINTAINERS.md).

## Supported Components

Before starting a migration, consider the scope of the components involved. The table below outlines the components that should be considered for migration, indicates their support by the Migration Assistant, and provides comments and recommendations.

| Component                        | Supported           | Comments and Recommendations                                                                                                                                                                         |
|----------------------------------|---------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Documents**                    | Yes                 | Migrate existing data with Reindex-from-Snapshot and live traffic with Capture-and-Replay                                                                                                           |
| **Index Settings**               | Yes                 | Migrate with Metadata Migration Tool                                                                                                                                                                |
| **Index Mappings**               | Yes                 | Migrate with Metadata Migration Tool                                                                                                                                                                |
| **Index Templates**              | Yes                 | Migrate with Metadata Migration Tool                                                                                                                                                                |
| **Component Templates**          | Yes                 | Migrate with Metadata Migration Tool                                                                                                                                                                |
| **Aliases**                      | Yes                 | Migrate with Metadata Migration Tool                                                                                                                                                                |
| **ISM Policies**                 | Expected in 2025    | Manually migrate using API                                                                                                                                                                          |
| **Elasticsearch Kibana Dashboards** | Expected in 2025 | Only needed if tooling is being used to migrate Elasticsearch Kibana Dashboards to OpenSearch Dashboards. Export JSON files from Kibana and import into OpenSearch Dashboards; before importing, use the [dashboardsSanitizer](../../tree/main/dashboardsSanitizer) tool on X-Pack visualizations like Canvas and Lens in Kibana Dashboards, as they may require recreation for compatibility with OpenSearch. |
| **Security Constructs**          | No                  | Configure roles and permissions based on cloud provider recommendations. For example, if using AWS, leverage IAM for enhanced security management.                                                  |
| **Plugins**                      | No                  | Check plugin compatibility; some Elasticsearch plugins may not have direct equivalents in OpenSearch.                                                                                              |


## Questions Asked Before Beginning and Why they Matter
### 1. What are the source and target platforms and their versions?
*Why this matters:* A user needs to confirm that their migration tooling work with the intended source and target migration path. 

### 2. By when do you expect the migration to be completed?
Why this matters: If using Migration Assistant or other tooling their may be additional features/enhancements that improve your experience. Alternatively, their may be a limitation is later supported. If you find that the tooling has a limitation preventing you from completing a migration or if you would have an improved user experience by a change, please open a feature request [here](https://github.com/opensearch-project/opensearch-migrations/issues/new/choose).

### 3. How much data is stored in the cluster?
*Why this matters:* Migration Assistant should be used on Clusters 100TB or less. Assessment testing should be done to estimate the total cost of a migration adequately scaling your tooling to meet the needs. In general, the faster you can execute a migration, the lower the cost and the less risks. Even if scaling to a large number of resources. 

### 4. Could you specify the mean and peak throughput of the cluster?
*Why this matters:* If you are using Capture and Replay, you should make sure that your peak loads are operating within limits of this tooling. The Proxy should be scaled to handle peak throughput. Less critical, the Replayer may fall behind in peaks, depending on scaling but their should be enough Replayer resources available to synchronize the source and target clusters. In other words, the Replayer should be able to handle traffic replay faster than was original sent to the source cluster to catch and guarantee the clusters can remain in sync. This is also a function of your backfill time. The longer the backfill, the longer it takes for the Replayer to catch up.

### 5. Provide a breakdown of nodes in the cluster. (e.g., Total nodes, number of coordinating nodes, and number of data nodes.)
*Why this matters:* This is a considering for how the Traffic Capture Proxy is deployed. Specifically, if the proxy is installed on nodes, rather than in front of a cluster, then a user has to make sure that each coordinating node has a proxy deployed so that traffic is guarenteed to make to to target server. 

### 6. What version of the Lucene index are you currently using?
*Why this matters:* You need to understand if you are doing more than one major hop. If so, one of the indexing solutions must be performed to move your cluster to the latest version. 

### 7. What's the main application of your cluster? (e.g., search, logging, or other)
*Why this matters:* For logging use cases, there are typically less updates to existing documents. Depending on the retention period a user may want to tee traffic with the Capture and Proxy solution without the need for backfilling data. For example, if the retention period of data is 2 weeks, data can be teed for two weeks and then the source cluster can be decommissioned. For search use cases, you have to consider how often your records are updated. If records are updated within a very short period (typically less than 1s but dependent on the scale of your cluster).

8. How much downtime can you afford during the migration?
*Why this matters:* Zero-down time migrations are an option with Migration Assistant but with increased infrastructure costs for Capture and Replay and requires careful planning for client switchover. 

9. What's your level of acceptance for discrepancies during the upgrade? For example, if relevancy is re-ordered because the two clusters aren't exactly in sync, is this tolerable?
*Why this matters:* When clusters are synchronized, there may be slight delay (typically less than 1s) between source and target

10. Are there any SLAs in place for downstream users or services?

11. Provide the details of the most recent upgrade, including versions involved. If available, a history of all upgrade paths would be beneficial.

12. How many client applications connect to the cluster? Are you aware of any potential upgrade compatibility concerns?
13. Are you utilizing Kibana or any other visualization tools?
14. List any plugins in use that will require migration.
15. Is inter-node encryption active within the cluster?
16. Do you have data encryption at rest enabled?
17. Which authentication and authorization mechanisms are in place?
18. Are there schematic diagrams showcasing how Elasticsearch/OpenSearch integrates with your entire system?
19. Is Logstash or DataPrepper part of your current setup?
20. Do you possess a dedicated environment for trial upgrades before the production shift? Please outline your typical upgrade process.
21. Share any specifics about the requests directed at your cluster that might pose migration challenges (e.g., request format, payload size).
22. Would you be open to a trusted proxy preceding your source cluster, even if it adds a 10-30ms latency?
23. To clients hit source domains directly or is there a layer of indirection (e.g., LB)
24. Are retention policies in place? If so, for how long, and is it on all indices?
25. Do you have the option to rebuild your cluster from source? In other words, is your data stored in some other persistent storage that you can load into a target cluster?
26. What are the transactions per second? How about indices per second, queries per second?


