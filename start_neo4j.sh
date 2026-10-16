#!/usr/bin/env bash
#
# Downloads DuckDB if necessary and starts Neo4j Virtual Graph
#

if [ ! -f "duckdb_jdbc-1.5.6.0.jar" ]; then
  curl -O https://repo1.maven.org/maven2/org/duckdb/duckdb_jdbc/1.5.6.0/duckdb_jdbc-1.5.6.0.jar
fi

docker run \
  --publish=7687:7687 \
  --publish=7474:7474 \
  -e 'NEO4J_AUTH=neo4j/verysecret' \
  -e 'NEO4J_ACCEPT_LICENSE_AGREEMENT=yes' \
  -e 'NEO4J_PLUGINS=["apoc", "graph-data-science"]' \
  -e 'NEO4J_internal_virtual__graph_enabled=true' \
  -e 'NEO4J_internal_virtual__graph_home=/var/ge-home' \
  -e 'NEO4J_server_jvm_additional=-Dcom.neo4j.arrow.GdsFeatureToggles.enableGds=true' \
  -v /Users/msimons/tmp/nodes2026/duckdb_jdbc-1.5.6.0.jar:/var/lib/neo4j/lib/driver.jar \
  -v /Users/msimons/tmp/nodes2026:/var/ge-home/ \
  neo4j:2026.09.0-enterprise
