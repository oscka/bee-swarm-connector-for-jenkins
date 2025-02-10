#!/bin/bash

docker build -t bee-swarm-connector-for-jenkins .
docker run -dit --rm --name bee-swarm-connector-for-jenkins bee-swarm-connector-for-jenkins
docker cp bee-swarm-connector-for-jenkins:/opt/bee-swarm-connector-for-jenkins/target/bee.plugin.swarm.connector.hpi bee.plugin.swarm.connector.hpi
docker stop bee-swarm-connector-for-jenkins
