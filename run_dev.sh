#!/bin/bash

java -cp target/everynet-1.0-SNAPSHOT-fat.jar io.vertx.core.Launcher run "service:uk.ac.cam.everynet.jsonrpc_feed.A" -cluster >/dev/null 2>>/home/ijl20/log/jsonrpc_feed.A.err & disown

