#!/usr/bin/env bash

VERSION=`mvn org.apache.maven.plugins:maven-help-plugin:2.1.1:evaluate -Dexpression=project.version | grep -v '\[' | grep -v "Downloaded:" | grep -v "Downloading:"`

mvn clean install && rm -f ~/applications/neo4j/plugins/* && tar xvfpz target/neo4j-lo-extensions-${VERSION}-plugin.tar.gz -C ~/applications/neo4j/plugins

/home/lo/applications/neo4j/bin/neo4j stop

/home/lo/applications/neo4j/bin/neo4j start