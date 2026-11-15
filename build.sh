#!/usr/bin/env bash
# Build TestSwitch.jar against a Velocity server jar (no Gradle needed).
# Usage: ./build.sh /path/to/velocity.jar
set -euo pipefail
VELOCITY_JAR="${1:?usage: ./build.sh /path/to/velocity.jar}"
rm -rf build && mkdir -p build/classes
javac --release 25 -nowarn -proc:none -cp "$VELOCITY_JAR" -d build/classes \
  src/main/java/th/park/testswitch/TestSwitch.java
cp src/main/resources/velocity-plugin.json build/classes/
jar cf build/TestSwitch.jar -C build/classes .
echo "built build/TestSwitch.jar"
