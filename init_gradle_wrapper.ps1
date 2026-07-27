$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$destination = Join-Path $projectRoot "gradle\wrapper\gradle-wrapper.jar"
$url = "https://github.com/gradle/gradle/raw/refs/tags/v8.13.0/gradle/wrapper/gradle-wrapper.jar"
$expectedSha256 = "81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f"

New-Item -ItemType Directory -Force -Path (Split-Path -Parent $destination) | Out-Null
Invoke-WebRequest -Uri $url -OutFile $destination

$actualSha256 = (Get-FileHash -Path $destination -Algorithm SHA256).Hash.ToLowerInvariant()
if ($actualSha256 -ne $expectedSha256) {
    Remove-Item -Force $destination -ErrorAction SilentlyContinue
    throw "Gradle wrapper checksum mismatch. Expected $expectedSha256, got $actualSha256."
}

Write-Host "Gradle wrapper installed and verified: $destination"
