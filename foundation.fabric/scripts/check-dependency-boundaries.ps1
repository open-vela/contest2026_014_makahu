$ErrorActionPreference = "Stop"
$metadata = cargo metadata --format-version 1 --no-deps | ConvertFrom-Json

$forbidden = @{
  "fabric-core" = @("tokio", "quinn", "sqlx", "mdns", "btleplug")
  "fabric-protocol" = @("fabric-storage")
  "fabric-identity" = @("fabric-policy")
  "fabric-hub" = @("foundation.audio", "foundation-audio")
}

$violations = @()
foreach ($package in $metadata.packages) {
  if ($forbidden.ContainsKey($package.name)) {
    foreach ($dependency in $package.dependencies) {
      if ($forbidden[$package.name] -contains $dependency.name) {
        $violations += "$($package.name) -> $($dependency.name)"
      }
    }
  }
  if ($package.name -eq "fabric-router") {
    foreach ($dependency in $package.dependencies) {
      if ($dependency.name -match "(^ability-|foundation[.-]audio)") {
        $violations += "$($package.name) -> $($dependency.name)"
      }
    }
  }
  if ($package.name -eq "fabric-clock") {
    foreach ($dependency in $package.dependencies) {
      if ($dependency.name -match "(audio|video)") {
        $violations += "$($package.name) -> $($dependency.name)"
      }
    }
  }
}

if ($violations.Count -gt 0) {
  throw "Forbidden dependency edges:`n$($violations -join "`n")"
}
Write-Host "Dependency boundaries OK"
