#!/usr/bin/env bash
# Builds the RoadTale Chronicle core prototype and runs the simulated-journey demo.
# Requires: a JDK (java) and the Kotlin compiler (kotlinc) on PATH. No Android SDK needed.
set -euo pipefail
cd "$(dirname "$0")"

mkdir -p build
echo "Compiling core + demo..."
kotlinc core/src/main/kotlin demo/src/main/kotlin -include-runtime -d build/roadtale-demo.jar

echo "Running demo (log -> demo-output.txt)..."
java -Dstdout.encoding=UTF-8 -Dfile.encoding=UTF-8 -jar build/roadtale-demo.jar | tee demo-output.txt
