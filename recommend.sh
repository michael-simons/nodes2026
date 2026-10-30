#!/usr/bin/env bash

set -euo pipefail

mkdir -p app/lib
cd app/lib
curl --skip-existing -O https://repo1.maven.org/maven2/com/tagtraum/japlscript-executor/3.4.14/japlscript-executor-3.4.14.jar
curl --skip-existing -O https://repo1.maven.org/maven2/com/tagtraum/japlscript-runtime/3.4.14/japlscript-runtime-3.4.14.jar
curl --skip-existing -O https://repo1.maven.org/maven2/com/tagtraum/obstmusic/0.9.10/obstmusic-0.9.10.jar
curl --skip-existing -O https://repo1.maven.org/maven2/info/picocli/picocli/4.7.7/picocli-4.7.7.jar
curl --skip-existing -O https://repo1.maven.org/maven2/org/commonmark/commonmark/0.31.0/commonmark-0.31.0.jar
curl --skip-existing -O https://repo1.maven.org/maven2/org/neo4j/neo4j-jdbc-bundle/6.15.2/neo4j-jdbc-bundle-6.15.2.jar
cd - > /dev/null

java \
  --sun-misc-unsafe-memory-access=allow \
  --enable-native-access=tagtraum.japlscript.executor \
  --module-path app/lib \
  app/src/recommendations/Application.java "$@"
