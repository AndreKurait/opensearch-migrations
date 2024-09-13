The Migration Assistant includes an ALB for traffic routing to the capture proxy and/or target.

Upstream client traffic will need to be routed to this capture proxy in order later replay the requests.

## Assumptions

* Layer upstream from ALB is compatible with the certificate on the ALB listener (Whether that is clients or NLB)
    * albAcmCertArn in the cdk.context.json may need to be provided otherwise or clients to trust ALB certificate
* If NLB is used directly upstream from ALB, it must use a TLS listener
* Upstream resources/security groups allow network access to the Migration Assistant ALB

## Steps

1. Within the AWS Console, navigate to EC2 > Load Balancers > Migration Assistant ALB
2. Note down the ALB URL
3. If you are using an NLB -> ALB -> Cluster:
    1. Ensure you have provided the ingress directly to the ALB to the Capture Proxy
    1. Create a target group for the Migration Assistant ALB for port 9200, set the healthcheck to HTTPS
    1. Associate this target group with your existing NLB on a new listener (for testing)
    1. Verify the healthcheck success, perform smoke testing with some clients through the new listener port
    1. Once ready to migrate all clients, detach the Migration Assistant ALB Target Group from the testing NLB listener and modify the existing NLB listener to direct to this Target Group
    1. Now client requests will be sending through the proxy (once they establish a new connection), verify application metrics 
3. If you are using an NLB -> Cluster:
      1. If you would like to not modify application logic, add an ALB in front of your cluster and follow `NLB -> ALB -> Cluster` steps. Otherwise:
      1. Create a target group for the ALB for port 9200, set the healthcheck to HTTPS
      2. Associate this target group with your existing NLB on a new listener
      3. Verify the healthcheck success, perform smoke testing with some clients through the new listener port
      4. Once ready to migrate all clients, deploy a change to clients to hit the new listener
4. If you are not using an NLB:
      1. Make a client/dns change to route the clients to the Migration Assistant ALB on port 9200
5. On the Migration Console, execute `console kafka describe-topic-records`, note the records in the logging topic
6. Wait some period, execute `console kafka describe-topic-records` again, compare the records increased against the expected HTTP requests.

### Troubleshooting
* Investigate ALB Listener Security Policy, Security Groups, ALB Certs, Proxy Connection to Kafka

### Related Links
[ALB](https://github.com/opensearch-project/opensearch-migrations/blob/a6d2f66e3e1830e2ced1b646a6db7adca95a176c/docs/ClientTrafficSwinging.md)
TODO: ^ Update link once PR is merged

### How to Contribute

Cut a GitHub issue referencing page and assigning to

owner: @AndreKurait