#!/usr/bin/env sh
# Builds loglens.jar with nothing but a JDK (17+). No Maven or Gradle required.
set -e
cd "$(dirname "$0")"
rm -rf build
mkdir -p build/classes
javac --release 17 -encoding UTF-8 -d build/classes src/main/java/dev/loglens/*.java
jar --create --file loglens.jar --main-class dev.loglens.Main -C build/classes .
echo "Built $(pwd)/loglens.jar"
echo "Try:  java -jar loglens.jar --demo"
