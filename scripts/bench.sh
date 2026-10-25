#!/usr/bin/env bash
# Runs the JMH engine benchmark. Extra arguments go to JMH, e.g. scripts/bench.sh -wi 1 -i 2 -f 1
set -euo pipefail
cd "$(dirname "$0")/.."
mvn -B -q -Pbench -DskipTests clean compile dependency:build-classpath -Dmdep.outputFile=target/bench-classpath.txt
case "$OSTYPE" in msys* | cygwin* | win*) sep=';' ;; *) sep=':' ;; esac
java -cp "target/classes${sep}$(cat target/bench-classpath.txt)" org.openjdk.jmh.Main "$@"
