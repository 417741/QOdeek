$ErrorActionPreference = 'SilentlyContinue'
Add-Type -AssemblyName System.Drawing
$b = [System.Drawing.Bitmap]::FromFile('d:/4/now.png')
Write-Output ("Size: {0}x{1}" -f $b.Width, $b.Height)
$minX = $b.Width; $minY = $b.Height; $maxX = 0; $maxY = 0; $count = 0
$limit = [Math]::Min(320, $b.Height)
for ($y = 0; $y -lt $limit; $y++) {
    for ($x = 0; $x -lt $b.Width; $x += 2) {
        $p = $b.GetPixel($x, $y)
        if ($p.R -lt 60 -and $p.G -lt 60 -and $p.B -lt 60) {
            $count++
            if ($x -lt $minX) { $minX = $x }
            if ($x -gt $maxX) { $maxX = $x }
            if ($y -lt $minY) { $minY = $y }
            if ($y -gt $maxY) { $maxY = $y }
        }
    }
}
Write-Output ("DarkPixelsInTop320: {0}" -f $count)
Write-Output ("BBox: x={0}..{1}  y={2}..{3}" -f $minX, $maxX, $minY, $maxY)
$b.Dispose()
