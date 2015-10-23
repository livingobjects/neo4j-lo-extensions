neo4j-lo-extensions
===================
[![Build Status](https://api.travis-ci.org/livingobjects/neo4j-lo-extensions.png)](https://travis-ci.org/livingobjects/neo4j-lo-extensions)

## Content

Contains 2 neo4j server extensions :

/warm-up : used to load all graph data in memory cache.

/load-csv : extension used to execute a LOAD CSV query using a remote CSV file that is uploaded.

## Load CSV

POST /load-csv

Requires a "Content-Type:multipart/mixed" with two body parts in this order:

- The cypher query with its parameters (json) :

The {file} token is automatically replaced by the uploaded CSV file URL.

```json
{
    "statement": "LOAD CSV WITH HEADERS FROM {file} AS csvFile\nCREATE (n:Node)",
    "parameters": {
        ...
    }
}
```

You can also use PERIODIC COMMIT :

```json
{
    "statement": "USING PERIODIC COMMIT 10000 LOAD CSV WITH HEADERS FROM {file} AS csvFile\nCREATE (n:Node)",
    "parameters": {
        ...
    }
}
```

- The CSV file to upload and import

Example REST call using curl :

```shell
curl -H "Content-Type:multipart/mixed" -F "content=@src/test/resources/query.json" -F "content=@src/test/resources/import.csv" -X POST http://localhost:7474/unmanaged/load-csv -i -v
```

The request returns a json file:

- When execution succeeds (code 200) :

```json
{
  "stats": {
    "nodes_deleted": 0,
    "relationships_created": 0,
    "relationships_deleted": 0,
    "properties_set": 0,
    "labels_added": 2,
    "labels_removed": 0,
    "indexes_added": 0,
    "indexes_removed": 0,
    "constraints_added": 0,
    "nodes_created": 2,
    "constraints_removed": 0,
    "deleted_nodes": 0,
    "deleted_relationships": 0
  }
}
```

- When execution fails (code 500) :

```json
{
  "error": {
    "code": "org.neo4j.kernel.impl.query.QueryExecutionKernelException",
    "message": "Invalid input ''': expected whitespace, comment, WITH or FROM (line 1, column 10 (offset: 9))\n\"LOAD CSV 'file:/tmp/rep4588354198555724947tmp' AS csvFile\"\n          ^"
  }
}
```

## How to use:

Copy neo4j-lo-extensions-1.1.jar file to neo4j plugins folder.

Edit neo4j-server.properties to setup extensions base url:

```properties
org.neo4j.server.thirdparty_jaxrs_classes=com.livingobjects.neo4j=/unmanaged
```