### Assumptions
- Traffic Capture Proxy deployed in ECS (not within cluster nodes)
- Target Proxy Service is deployed

### Source Passthrough Mode Validation
The Migrations ALB is deployed with a listener which is able to shift traffic between the source and target clusters through proxy services running. This should start in Source Passthrough mode
1. Within AWS Console, navigate to EC2 > Load Balancers
1. Navigate to MigrationAssistant ALB
1. Examine Listener on port 9200, verify 100% of traffic is directed to Source Proxy
1. Navigate to Migration ECS Cluster in AWS Console
1. Navigate to Target Proxy Service
1. Verify > 0 desired count and running
   * if not, update service to increase to at least 1 and wait for startup
1. Within "Load balancer health" on "Health and Metrics" tab, verify all targets are reporting healthy
    * This means the ALB is able to connect to the target cluster through the target proxy
1. (Reset) Update Target Proxy service desired count back to original value in ECS

Note: Capture Proxy verification will occur in subsequent step

### Limitations
* Does not validate clients/upstream network infrastructure has access to Migrations ALB
* If source/target cluster requires auth, will not be able to verify in ALB health check beyond receiving 401/403 status code  

### Troubleshooting
* Verify Target Cluster allows traffic ingress from Target Proxy Security Group
* Navigate to Target Proxy ECS Tasks to investigate failing tasks
   * Change "Filter desired status" to "Any desired status" in order to see all tasks and navigate to logs for stopped tasks

### How to Contribute
Cut a GitHub issue referencing page and assigning to

owner: @AndreKurait