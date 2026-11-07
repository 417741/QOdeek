$ErrorActionPreference = 'Stop'
$path = 'd:/4/config.dat'
Copy-Item $path 'd:/4/config.dat.orig' -Force
$obj = Get-Content -Raw $path | ConvertFrom-Json
$obj.PSObject.Properties | ForEach-Object {
    if ($_.Value -is [bool]) { $obj.($_.Name) = $false }
}
$obj | ConvertTo-Json -Depth 5 | Set-Content -Path $path -Encoding UTF8
Write-Output 'done: all boolean switches set to false'
