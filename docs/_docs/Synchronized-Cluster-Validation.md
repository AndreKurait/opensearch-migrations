### Assumptions
* The Migration Assistant has been deployed with support for replaying captured traffic (see [trafficReplayerServiceEnabled](../Configuration-Options)).
* The Capture Proxy is capturing traffic, writing it to the Kafka cluster.
* The Target Cluster is running and accepting requests

### Replayer Configurations

[Replayer settings](Configuration-Options) are configured with the deployment of the Migration Assistant.  

Make sure to set the authentication mode for the replayer so that it works with the target cluster.  See the Limitations below for how this setting will be interpreted for various forms of traffic.

Also of note is the --speedup-factor that is passed via trafficReplayerExtraArgs.  The speedup factor will cause the replayer to speed up wait times between requests.  For example, if requests are sent to the source cluster once a minute and a speedup factor of 2 is used, the replayer will send a request, then the next request 30 seconds after the previous request rather than 60 seconds.  A scale factor of 0.5 would cause the same stream of source requests to be spaced 2 minutes apart.  This value can be used to stress test a cluster and to 'catch up' to real time traffic to prepare for source-target client switchover.

### When to run the Replayer

Upon the initial deployment of the solution, the replayer task isn't running.  After all metadata and documents have been migrated, the user can start the Replayer.  The Replayer should only be started after those other migration processes have finished to guarantee that the most recent changes to the source cluster will also be the latest changes to the target cluster.  Consider the case where a document may have been deleted _after_ a snapshot was made.  If the Replayer has started before the Document Migration has finished, the replayed deletion request may run before the document has been added to the target.  Running the Replayer after everything else guarantees that once the replayed traffic passes the point that any snapshots were performed, the target cluster should be consistent with the source cluster at the time that traffic was captured.

### Using the Replayer

Running/Stopping the replayer is controlled via the `console replay` command.  `console replay start` will start the replayer with the options that were specified at deployment time.  To check if it is running or not, run `console replay status`.  Notice that responses will include the terms "Running", "Pending", and "Desired".  The Running value indicates how many container instances are actively running.  Pending indicates the number of instances that ECS is provisioning.  Pending nodes should transition to Running instances.  Desired will indicate the number of instances that should be running once all hardware has been provisioned and tasks begin.

Notice that the Replayer pulls traffic from Kafka and advances its commit cursor after messages have been committed to the target cluster, making the Replayer resilient to instance failures, providing an "at least once" delivery guarantee.  This guarantee is that requests will be replayed, not that their final result will be successful.  Users will need to observe metrics, tuple output, or use external validation to confirm that the contents of the target cluster are as expected.

Example interactions to manage the Replay are as follows.
```
root@ip-10-0-2-66:~# console replay status
(<ReplayStatus.STOPPED: 4>, 'Running=0\nPending=0\nDesired=0')
```
```
root@ip-10-0-2-66:~# console replay start
Replayer started successfully.
Service migration-dev-traffic-replayer-default set to 1 desired count. Currently 0 running and 0 pending.
```
```
root@ip-10-0-2-66:~# console replay stop
Replayer stopped successfully.
Service migration-dev-traffic-replayer-default set to 0 desired count. Currently 0 running and 0 pending.
```

### Time Scaling

The Replayer replays requests in the same order that requests were received on each distinct connection to the source.  For example, assume that there are two connections to a server whose requests are using keepalive.  One connection is a sequence of PUT requests sent every minute.  The other connection has GET requests sent every second.

In any case, the Replayer will send the PUT and GET requests on separate connections to the target cluster.  If a connection breaks, the Replayer will reestablish a connection but it will never have two requests from the same source connection in-flight simultaneously.  Ordering within a request is also guaranteed.  However, relative timing between the connections isn't guaranteed.

Assume that for the stated scenario, that the source cluster responds to all requests (GETs and POSTs) within 100ms.  Assume that the target cluster will respond in the same amount of time.  With a speedup factor of 1 (no change in rates from the source), the target will experience the same rates and same quiescent periods between each request as the source experienced.  With a speedup factor of 2, the both the POSTs and GETs will run at 2x the speed.  This is because the time for the target to respond is still less than the accelerated interval time of 500ms for GETs and 30s for PUTs is still less than the 100ms that the target will need to respond to the requests.

If the speed is increased to 10x, the server will still be able to respond to PUTs fast enough that subsequent requests can be sent out on the accelerated schedule.  As long as the GET requests take 100ms to respond, the Replayer will be able to keep the same target pace for the GET traffic.  10x is the largest speedup value for this scenario where the GET and PUT requests on these two persistent connections can keep the same relative time and sequencing.  

If the responses begin to take longer for GETs, retries are required, or if the speedup factor is increased, so that the previous requests aren't completed by the time that the next request should have been scheduled, the Replayer is forced to wait for the previous request _on that connection_ to finish before sending the next request.  In those cases for this example, the global relative ordering will be different between the target and the server since relative times are maintained on a per-connection basis.  Furthermore, the target won't be able to 'keep-up' with the requested rate.

### Transformations

The exact structure of requests may need to change between versions.  Customers will need to determine how clients will handle those differences, especially as clients are switched over to make requests to the target server.  For example, in previous versions of Elasticsearch, indices could support documents with multiple type mappings.  In newer versions of OpenSearch, this is no longer true.  Customers may need to map those documents to multiple indices, excising the contents under the prior type mapping element.  While planning for the client change is outside of the scope of the Migration Assistant, the Replayer supports rewriting requests so that the data can stay in sync between the source and target clusters.  Running transformed results also let's the user understand the performance of the new cluster for the requests that it will need to handle.

The Replayer rewrites host headers and auth headers automatically (as per configurations).  For more extensive transformation needs, users can pass extra arguments to the Replayer to configure custom transformations.  Extra command line options can be passed via the CDK context at deployment time via [[trafficReplayerExtraArgs|Configuration-Options]].  Specifically, through the `--transformer-config` options (as described [here](https://github.com/opensearch-project/opensearch-migrations/blob/c3d25958a44ec2e7505892b4ea30e5fbfad4c71b/TrafficCapture/trafficReplayer/README.md#transformations)), users can configure a sequence of JMESPath and Jolt transformations.  Notice that these transformations apply to every request, so care must be taken to write generic transforms applicable for all types of requests.

Let's assume that there's a source request that has a "tagToExcise" element that should be removed, with its children taking its place.  Let's also assume that we want to rewrite the URI so that "extraThingToRemove" is removed from the path.  An example source HTTP request, as it is sent to any configured transformer is as follows (notice that the request's headers, verb, etc are included at the top-level of the json).

```
{
  "method": "PUT",
  "protocol": "HTTP/1.0",
  "URI": "/oldStyleIndex/extraThingToRemove/moreStuff",
  "headers": {
    "host": "127.0.0.1"
  },
  "payload": {
    "inlinedJsonBody": {
      "top": {
        "tagToExcise": {
          "properties": {
            "field1": {
              "type": "text"
            },
            "field2": {
              "type": "keyword"
            }
          }
        }
      }
    }
  }
}
```

The following Jolt script can be passed as a configuration option to rewrite the URI and to remove the tag.
```
[{ "JsonJoltTransformerProvider":
[
  {
    "script": {
      "operation": "shift",
      "spec": {
        "payload": {
          "inlinedJsonBody": {
            "top": {
              "tagToExcise": {
                "*": "payload.inlinedJsonBody.top.&" 
              },
              "*": "payload.inlinedJsonBody.top.&"
            },
            "*": "payload.inlinedJsonBody.&"
          },
          "*": "payload.&"
        },
        "*": "&"
      }
    }
  }, 
 {
   "script": {
     "operation": "modify-overwrite-beta",
     "spec": {
       "URI": "=split('/extraThingToRemove',@(1,&))"
     }
  }
 },
 {
   "script": {
     "operation": "modify-overwrite-beta",
     "spec": {
       "URI": "=join('',@(1,&))"
     }
  }
 }
]
}]
```

The resulting message will go to the target server as follows.
```
PUT /oldStyleIndex/moreStuff HTTP/1.0
host: testhostname

{"top":{"properties":{"field1":{"type":"text"},"field2":{"type":"keyword"}}}}
```

Because Jolt, JMESPath, or any other kind of script configuration can be non-trivial expressions, users can Base64 encode the script configuration and pass the encoded string via `--transformer-config-base64`.

### Limitations



### Troubleshooting

### Related Links

### How to Contribute

owner: gregschohn