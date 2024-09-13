### Assumptions
- Traffic Capture Proxy deployed in ECS (not within cluster nodes)
- Migration Console and Traffic Replayer have been deployed

### Replication setup and validation
1. Navigate to Migration ECS Cluster in AWS Console
1. Navigate to Capture Proxy Service
1. Verify > 0 desired count and running
   * if not, update service to increase to at least 1 and wait for startup
1. Within "Load balancer health" on "Health and Metrics" tab, verify all targets are reporting healthy
    * This means the ALB is able to connect to the source cluster through the capture proxy
1. Navigate to the Migration Console Terminal
1. Execute `console kafka describe-topic-records`
1. Wait 30 seconds for another elb health check to be recorded 
1. Execute `console kafka describe-topic-records` again, Verify RECORDS increased between runs
1. Execute `console replay start` to start the replayer
1. Run `tail -f /shared-logs-output/traffic-replayer-default/*/tuples/tuples.log  | jq '.targetResponses[]."Status-Code"'` to confirm that the Kafka requests were sent to the the target and that it responded as expected... If responses don't appear
    * check that the migration-console can access the target cluster by running `./catIndices.sh`, which should show indices on the source and target.
    * confirm that messages are still being recorded to Kafka.
    * check for errors in the replayer logs ("/migration/STAGE/default/traffic-replayer-default") via CloudWatch
1. (Reset) Update Traffic Capture Proxy service desired count back to original value in ECS

### Limitations
* If source cluster requires auth, will not be able to verify beyond receiving 401/403 status code for ALB healthchecks

### Troubleshooting
* Verify Source Cluster allows traffic ingress from Capture Proxy Security Group
* Navigate to Traffic Capture Proxy ECS Tasks to investigate failing tasks
   * Change "Filter desired status" to "Any desired status" in order to see all tasks and navigate to logs for stopped tasks

### Related Links

* [Traffic Capture Proxy Failure Modes](https://github.com/opensearch-project/opensearch-migrations/blob/main/TrafficCapture/trafficCaptureProxyServer/README.md#failure-modes)

### How to Contribute
Cut a GitHub issue referencing page and assigning to

owner: @AndreKurait