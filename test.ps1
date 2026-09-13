param([string]$Jdk = $env:JAVA_HOME, [string]$FactoryJson)
$ErrorActionPreference = 'Stop'
if (!$Jdk) { throw 'Provide -Jdk or JAVA_HOME.' }
$output = Join-Path $PSScriptRoot 'build\host-tests'
New-Item -ItemType Directory -Path $output -Force | Out-Null
& "$Jdk\bin\javac.exe" --release 8 -encoding UTF-8 -d $output "$PSScriptRoot\src\local\nova\diagnostic\ProfilePayload.java" "$PSScriptRoot\tests\ProfilePayloadTest.java"
if ($LASTEXITCODE -ne 0) { throw 'Test compilation failed' }
$extra = @()
if ($FactoryJson) { $extra = @($FactoryJson, "$PSScriptRoot\sourceprofiles") }
& "$Jdk\bin\java.exe" -cp $output local.nova.diagnostic.ProfilePayloadTest @extra
if ($LASTEXITCODE -ne 0) { throw 'Profile reconstruction tests failed' }
