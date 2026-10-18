# Builds the Burp AI Agent Bridge extension into .\AiAgentBridge.jar
#
# Needs JDK 17+ on PATH.
# Default: compiles against the Montoya API artifact from Maven Central.
# To compile against your exact Burp build:
#   $env:BURP_JAR="C:\Program Files\BurpSuitePro\burpsuite_pro.jar"; .\build.bat
param(
    [string]$MontyaVersion = "2025.5",
    [string]$BurpJar = $env:BURP_JAR
)
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot
New-Item -ItemType Directory -Force -Path build | Out-Null

if (-not $BurpJar) {
    $apiJar = Join-Path $PSScriptRoot "build\montoya-api-$MontyaVersion.jar"
    if (-not (Test-Path $apiJar)) {
        Write-Host "[*] fetching Montoya API $MontyaVersion from Maven Central..."
        [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
        Invoke-WebRequest `
          -Uri "https://repo1.maven.org/maven2/net/portswigger/burp/extensions/montoya-api/$MontyaVersion/montoya-api-$MontyaVersion.jar" `
          -OutFile $apiJar
    }
    $BurpJar = $apiJar
}

Write-Host "[*] compiling against: $BurpJar"
javac -cp $BurpJar -d build src\aibridge\AiAgentBridge.java
jar cf AiAgentBridge.jar -C build aibridge

New-Item -ItemType Directory -Force -Path build-test | Out-Null
javac -cp "$BurpJar;build;build-test" -d build-test test\aibridge\StateTest.java
java -cp "$BurpJar;build;build-test" aibridge.StateTest

Write-Host "[+] wrote $(Join-Path $PSScriptRoot 'AiAgentBridge.jar')"
Write-Host "    load it via: Burp -> Extender -> Extensions -> Add -> Java"
