param(
    [string]$Sdk = $env:ANDROID_SDK_ROOT,
    [string]$Jdk = $env:JAVA_HOME,
    [string]$BuildToolsVersion = '35.0.0',
    [string]$PlatformVersion = '36',
    [string]$KeyStore,
    [string]$KeyAlias,
    [switch]$TestBuild
)
$ErrorActionPreference = 'Stop'
if (!$Sdk -or !$Jdk) { throw 'Provide -Sdk and -Jdk, or ANDROID_SDK_ROOT and JAVA_HOME.' }
if (!$TestBuild -and (!$KeyStore -or !$KeyAlias -or !$env:NOVA_STORE_PASSWORD -or !$env:NOVA_KEY_PASSWORD)) {
    throw 'Release signing requires -KeyStore, -KeyAlias, NOVA_STORE_PASSWORD and NOVA_KEY_PASSWORD. Use -TestBuild only for local testing.'
}
if ($TestBuild -and $KeyStore) { throw 'Choose external release signing or -TestBuild, not both.' }
$projectRoot = [IO.Path]::GetFullPath($PSScriptRoot)
$buildRoot = Join-Path $projectRoot 'build'
if ((Test-Path -LiteralPath $buildRoot) -and ((Get-Item -LiteralPath $buildRoot).Attributes -band [IO.FileAttributes]::ReparsePoint)) {
    throw 'Build root cannot be a link'
}
$build = [IO.Path]::GetFullPath((Join-Path $buildRoot 'release'))
if ($build -ne (Join-Path $buildRoot 'release')) { throw 'Unexpected build output path' }
if (Test-Path -LiteralPath $build) {
    if ((Get-Item -LiteralPath $build).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Build directory cannot be a link' }
    Remove-Item -LiteralPath $build -Recurse -Force
}
$buildTools = Join-Path $Sdk "build-tools\$BuildToolsVersion"
$platform = Join-Path $Sdk "platforms\android-$PlatformVersion\android.jar"
$env:JAVA_HOME = $Jdk
function Check-Exit { if ($LASTEXITCODE -ne 0) { throw "Build step failed with exit code $LASTEXITCODE" } }
foreach ($folder in @($build, "$build\classes", "$build\dex", "$build\generated", "$build\assets\profiles")) {
    New-Item -ItemType Directory -Path $folder -Force | Out-Null
}
& "$buildTools\aapt2.exe" compile --dir "$projectRoot\res" -o "$build\resources.zip"
Check-Exit
& "$buildTools\aapt2.exe" link -I $platform --manifest "$projectRoot\AndroidManifest.xml" --java "$build\generated" "$build\resources.zip" -o "$build\unsigned.apk"
Check-Exit
$sourceFiles = @('CalibrationActivity.java', 'AeroTheme.java', 'ProfilePayload.java') | ForEach-Object { Join-Path "$projectRoot\src\local\nova\diagnostic" $_ }
$sourceFiles += @(Get-ChildItem -LiteralPath "$build\generated" -Recurse -Filter '*.java' | ForEach-Object FullName)
& "$Jdk\bin\javac.exe" --release 8 -encoding UTF-8 -classpath $platform -d "$build\classes" @sourceFiles
Check-Exit
$classFiles = @(Get-ChildItem -LiteralPath "$build\classes" -Recurse -Filter '*.class' | ForEach-Object FullName)
& "$buildTools\d8.bat" --lib $platform --min-api 26 --output "$build\dex" @classFiles
Check-Exit
# Only our calibration deltas and installer. Never package a factory JSON or diagnostic tool.
foreach ($asset in @('gamma22.npatch', 'srgb.npatch', 'profile-manifest.json', 'nova-system-profile.sh')) {
    Copy-Item -LiteralPath "$projectRoot\sourceprofiles\$asset" -Destination "$build\assets\profiles\$asset"
}
& "$Jdk\bin\jar.exe" uf "$build\unsigned.apk" -C "$build\dex" classes.dex -C $build assets
Check-Exit
& "$buildTools\zipalign.exe" -f -p 4 "$build\unsigned.apk" "$build\aligned.apk"
Check-Exit
if ($TestBuild) {
    $KeyStore = "$buildRoot\diagnostic.jks"
    $KeyAlias = 'diagnostic'
    $storePass = 'pass:android'
    $keyPass = 'pass:android'
    if (!(Test-Path -LiteralPath $KeyStore)) {
        & "$Jdk\bin\keytool.exe" -genkeypair -keystore $KeyStore -storepass android -keypass android -alias $KeyAlias -keyalg RSA -keysize 2048 -validity 3650 -dname 'CN=Nova Local Diagnostic'
        Check-Exit
    }
    Write-Warning 'Local test signature. Do not upload this APK as the public release.'
} else {
    $storePass = 'env:NOVA_STORE_PASSWORD'
    $keyPass = 'env:NOVA_KEY_PASSWORD'
}
$apk = Join-Path $build 'nova-calibration.apk'
& "$buildTools\apksigner.bat" sign --ks $KeyStore --ks-key-alias $KeyAlias --ks-pass $storePass --key-pass $keyPass --out $apk "$build\aligned.apk"
Check-Exit
& "$buildTools\apksigner.bat" verify $apk
Check-Exit
Write-Output $apk
