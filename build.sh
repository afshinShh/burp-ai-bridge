#!/usr/bin/env sh
# Builds the Burp AI Agent Bridge extension into ./AiAgentBridge.jar
#
# Needs: JDK 17+.
# Compiles against the official Montoya API artifact from Maven Central
# (small download). To compile against your exact Burp build instead:
#
#   BURP_JAR=/path/to/burpsuite_pro.jar ./build.sh
set -eu

HERE="$(cd "$(dirname "$0")" && pwd)"
cd "$HERE"
mkdir -p build

if [ -n "${BURP_JAR:-}" ]; then
    API_JAR="$BURP_JAR"
else
    MONTYA_VERSION="${MONTYA_VERSION:-2025.5}"
    API_JAR="build/montoya-api-${MONTYA_VERSION}.jar"
    if [ ! -f "$API_JAR" ]; then
        echo "[*] fetching Montoya API ${MONTYA_VERSION} from Maven Central..."
        curl -sL -o "$API_JAR" \
          "https://repo1.maven.org/maven2/net/portswigger/burp/extensions/montoya-api/${MONTYA_VERSION}/montoya-api-${MONTYA_VERSION}.jar"
    fi
fi

echo "[*] compiling against: $API_JAR"
javac -cp "$API_JAR" -d build src/aibridge/AiAgentBridge.java
jar cf AiAgentBridge.jar -C build aibridge

mkdir -p build-test
javac -cp "$API_JAR:build:build-test" -d build-test test/aibridge/StateTest.java
java -cp "$API_JAR:build:build-test" aibridge.StateTest

echo "[+] wrote $(pwd)/AiAgentBridge.jar"
echo "    load it via: Burp -> Extender -> Extensions -> Add -> Java"
