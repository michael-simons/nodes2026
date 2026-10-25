#!/usr/bin/env bash
#
# Downloads DuckDB if necessary and starts Neo4j Virtual Graph
#


mkdir -p drivers
cd drivers && curl --skip-existing  -O https://repo1.maven.org/maven2/org/duckdb/duckdb_jdbc/1.5.6.0/duckdb_jdbc-1.5.6.0.jar && cd - > /dev/null

DIR="$(dirname "$(realpath "$0")")"

docker run \
  --publish=7687:7687 \
  --publish=7474:7474 \
  -e 'NEO4J_AUTH=neo4j/verysecret' \
  -e 'NEO4J_ACCEPT_LICENSE_AGREEMENT=yes' \
  -e 'NEO4J_PLUGINS=["apoc", "graph-data-science"]' \
  -e 'NEO4J_internal_virtual__graph_enabled=true' \
  -e 'NEO4J_internal_virtual__graph_home=/var/ge-home' \
  -e 'NEO4J_server_jvm_additional=-Dcom.neo4j.arrow.GdsFeatureToggles.enableGds=true' \
  -v "$DIR"/drivers/duckdb_jdbc-1.5.6.0.jar:/var/lib/neo4j/lib/driver.jar \
  -v "$DIR":/var/ge-home/ \
  neo4j:2026.10.1-enterprise
