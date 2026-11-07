$ErrorActionPreference = 'SilentlyContinue'
Add-Type -AssemblyName System.Drawing
$b = [System.Drawing.Bitmap]::FromFile('d:/4/now.png')
# 只取字形区域 x=100..1120, y=0..230
$left = 100; $right = 1120; $top = 0; $bot = 230
$cols = 110
$rows = 30
$cellW = ($right - $left) / $cols
$cellH = ($bot - $top) / $rows
$sb = New-Object System.Text.StringBuilder
for ($r = 0; $r -lt $rows; $r++) {
    $line = ""
    for ($c = 0; $c -lt $cols; $c++) {
        $x0 = [int]($left + $c * $cellW); $y0 = [int]($top + $r * $cellH)
        $x1 = [int]($left + ($c + 1) * $cellW); $y1 = [int]($top + ($r + 1) * $cellH)
        $dark = 0; $tot = 0
        for ($y = $y0; $y -lt $y1; $y += 2) {
            for ($x = $x0; $x -lt $x1; $x += 2) {
                $p = $b.GetPixel($x, $y); $tot++
                if ($p.R -lt 70 -and $p.G -lt 70 -and $p.B -lt 70) { $dark++ }
            }
        }
        if ($tot -eq 0) { $line += " "; continue }
        $ratio = $dark / $tot
        if ($ratio -gt 0.65) { $line += "#" }
        elseif ($ratio -gt 0.35) { $line += "+" }
        elseif ($ratio -gt 0.15) { $line += "." }
        else { $line += " " }
    }
    [void]$sb.AppendLine($line)
}
Write-Output $sb.ToString()
$b.Dispose()
