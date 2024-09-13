The Replayer is a long-running process that makes requests to a target cluster so that it can both stay in sync with the source cluster and so that a user can compare the performance between the two clusters.  There are currently two main ways to determine how the target requests are being handled: through logs and through metrics.

### Result Logs 

HTTP Transactions from the source capture and those resent to the target cluster are combined into logfiles that are written to `/shared-logs-output/traffic-replayer-default/*/tuples/tuples.log`.  The /shared-logs-output is shared across containers, including the migration-console.  Users can access these files from the migration console with the same path, which will also include previous runs gzipped.  The file is a newline-delimited JSON file.  Each JSON record is a dictionary (or 'tuple') of the source/target request/response along with other details of the transactions, like the observed response times.  *

*Notice that these logs will contain the contents of all requests, including Authorization headers and the contents of all HTTP messages**.  Make sure that access to the migration environment is restricted.  These logs serve as a source of truth to determine exactly what happened for both the source and target clusters.  Note that the response times for the source are the time between the proxy sending the end of a request and getting the response.  While the replayer records the response times for the target in exactly the same manner, the relative locations between the capture proxy and the replayer and target may not be identical - and neither takes into account where the calling client was coming from.  These can still be useful metrics when taking additional factors into consideration.

Here is an example line for a `/_cat/indices?v` request made to both the source and target.

```
{"sourceRequest":{"Request-URI":"/_cat/indices?v","Method":"GET","HTTP-Version":"HTTP/1.1","Host":"capture-proxy:9200","Authorization":"Basic YWRtaW46YWRtaW4=","User-Agent":"curl/8.5.0","Accept":"*/*","body":""},"sourceResponse":{"HTTP-Version":{"keepAliveDefault":true},"Status-Code":200,"Reason-Phrase":"OK","response_time_ms":59,"content-type":"text/plain; charset=UTF-8","content-length":"214","body":"aGVhbHRoIHN0YXR1cyBpbmRleCAgICAgICB1dWlkICAgICAgICAgICAgICAgICAgIHByaSByZXAgZG9jcy5jb3VudCBkb2NzLmRlbGV0ZWQgc3RvcmUuc2l6ZSBwcmkuc3RvcmUuc2l6ZQpncmVlbiAgb3BlbiAgIHNlYXJjaGd1YXJkIHlKS1hQTUh0VFJPTklYU1pYQ193bVEgICAxICAgMCAgICAgICAgICA4ICAgICAgICAgICAgMCAgICAgNDQuN2tiICAgICAgICAgNDQuN2tiCg=="},"targetRequest":{"Request-URI":"/_cat/indices?v","Method":"GET","HTTP-Version":"HTTP/1.1","Host":"opensearchtarget","Authorization":"Basic YWRtaW46bXlTdHJvbmdQYXNzd29yZDEyMyE=","User-Agent":"curl/8.5.0","Accept":"*/*","body":""},"targetResponses":[{"HTTP-Version":{"keepAliveDefault":true},"Status-Code":200,"Reason-Phrase":"OK","response_time_ms":721,"content-type":"text/plain; charset=UTF-8","content-length":"484","body":"aGVhbHRoIHN0YXR1cyBpbmRleCAgICAgICAgICAgICAgICAgICAgIHV1aWQgICAgICAgICAgICAgICAgICAgcHJpIHJlcCBkb2NzLmNvdW50IGRvY3MuZGVsZXRlZCBzdG9yZS5zaXplIHByaS5zdG9yZS5zaXplCmdyZWVuICBvcGVuICAgLm9wZW5zZWFyY2gtb2JzZXJ2YWJpbGl0eSA4Vy1vWUhmYlN5U3JkeFFFX3NPbnpnICAgMSAgIDAgICAgICAgICAgMCAgICAgICAgICAgIDAgICAgICAgMjA4YiAgICAgICAgICAgMjA4YgpncmVlbiAgb3BlbiAgIC5wbHVnaW5zLW1sLWNvbmZpZyAgICAgICAgRjludnh2c2dSelNibG1mSnZ2aGptdyAgIDEgICAwICAgICAgICAgIDEgICAgICAgICAgICAwICAgICAgMy44a2IgICAgICAgICAgMy44a2IKZ3JlZW4gIG9wZW4gICAub3BlbmRpc3Ryb19zZWN1cml0eSAgICAgIDVmWHlhbkZuU2tDUUQ2bjFKUW1KTlEgICAxICAgMCAgICAgICAgIDEwICAgICAgICAgICAgMCAgICAgNzcuNWtiICAgICAgICAgNzcuNWtiCg=="}],"connectionId":"0242acfffe13000a-0000000a-00000005-1eb087a9beb83f3e-a32794b4.0","numRequests":1,"numErrors":0}
```

Notice that the contents of the HTTP message bodies are Base64 encoded so that the file can represent any types of traffic.  For example, if the traffic was compressed, observed contents will be binary encoded.  To view bodies in a completely decoded form, the migration-console includes a script humanReadableLogs.py.  Running `./humanReadableLogs.py /shared-logs-output/traffic-replayer-default/d3a4b31e1af4/tuples/tuples.log` will output a `readable-tuples.log` in the same directory.  If the body of an HTTP message was JSON, then the body will be inlined rather than escaped.  The tuples line above is converted into the following line.

```
{"sourceRequest": {"Request-URI": "/_cat/indices?v", "Method": "GET", "HTTP-Version": "HTTP/1.1", "Host": "capture-proxy:9200", "Authorization": "Basic YWRtaW46YWRtaW4=", "User-Agent": "curl/8.5.0", "Accept": "*/*", "body": ""}, "sourceResponse": {"HTTP-Version": {"keepAliveDefault": true}, "Status-Code": 200, "Reason-Phrase": "OK", "response_time_ms": 59, "content-type": "text/plain; charset=UTF-8", "content-length": "214", "body": "health status index       uuid                   pri rep docs.count docs.deleted store.size pri.store.size\ngreen  open   searchguard yJKXPMHtTRONIXSZXC_wmQ   1   0          8            0     44.7kb         44.7kb\n"}, "targetRequest": {"Request-URI": "/_cat/indices?v", "Method": "GET", "HTTP-Version": "HTTP/1.1", "Host": "opensearchtarget", "Authorization": "Basic YWRtaW46bXlTdHJvbmdQYXNzd29yZDEyMyE=", "User-Agent": "curl/8.5.0", "Accept": "*/*", "body": ""}, "targetResponses": [{"HTTP-Version": {"keepAliveDefault": true}, "Status-Code": 200, "Reason-Phrase": "OK", "response_time_ms": 721, "content-type": "text/plain; charset=UTF-8", "content-length": "484", "body": "health status index       uuid                   pri rep docs.count docs.deleted store.size pri.store.size\ngreen  open   searchguard yJKXPMHtTRONIXSZXC_wmQ   1   0          8            0     44.7kb         44.7kb\n"}], "connectionId": "0242acfffe13000a-0000000a-00000005-1eb087a9beb83f3e-a32794b4.0", "numRequests": 1, "numErrors": 0}
```

### Metrics

A variety of OpenTelemetry metrics are emitted into CloudWatch and traces are sent through AWS X-Ray.  The Replayer emits dozens of types of metrics and traces.  Below are some useful metrics to show how a cluster is doing.

<img width="442" alt="Screenshot 2024-09-06 at 11 06 49 PM" src="https://github.com/user-attachments/assets/679006fc-871d-4d30-942c-2dfd023355fa">

"sourceStatusCode" will have attributes (dimensions) for the HTTP verb (GET, POST, etc) and for the source and target status code families (200-299 are all recorded as 200).  Having different dimensions allows for a quick understanding of why status codes may not be matching (DELETE 200s are now 4xxs, GET 4xx errors are now 5xx errors, etc).  Notice that the extra dimensions are not automatically folded together with CloudWatch, but the greater granularity allows for more precision.

<img width="448" alt="Screenshot 2024-09-06 at 11 06 34 PM" src="https://github.com/user-attachments/assets/83538739-f36e-4382-85ce-d9da3594c758">

lagBetweenSourceAndTargetRequests will show the delay for requests to hit the target cluster.  With a speedup-factor > 1 and a target cluster that can service requests quickly enough, this value should go downward as a replay progresses.  This value will show the "staleness" of the replayed data on the target cluster.

Other statistics that can be tracked include 
* bytesWrittenToTarget and bytesReadFromTarget for the throughput to the cluster.  
* numRetriedRequests shows the number of requests that were retried (due to a worse status-code result than what the source returned)
* (*)Count shows the number of specific events that have completed.  For each of these events, there is also a trace that can be observed in X-Ray.
* (*)Duration shows the duration that each step has taken.  Like all CloudWatch/OpenTelemetry metrics, these will be quantized as histograms.
* (*)ExceptionCount will show the number of exceptions that have occurred for each phase of processing.

The OpenSearch and Kafka resources managed by AWS also emit metrics to CloudWatch.  These can also be used to understand the performance and and as an input for tuning.

Generally, the configuration that pushes logs to CloudWatch may have around a 5 minute visibility lag.  CloudWatch also expires higher-resolution (smaller sampling periods) data earlier than lower-resolution data.  See [CloudWatch's Metrics retention policies](https://docs.aws.amazon.com/AmazonCloudWatch/latest/monitoring/cloudwatch_concepts.html) for more details.

### Limitations

### Troubleshooting

### Related Links

### How to Contribute

owner: @gregschohn