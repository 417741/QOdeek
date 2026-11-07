$ErrorActionPreference = 'SilentlyContinue'
Add-Type -AssemblyName System.Drawing
$b = [System.Drawing.Bitmap]::FromFile('d:/4/now.png')
# 顶部区域 y=0..260, 全宽
$top = 0
$bot = 260
$left = 0
$right = 1220
# 降采样成 ASCII：宽度 120 列，高度 26 行
$cols = 120
$rows = 26
$cellW = ($right - $left) / $cols
$cellH = ($bot - $top) / $rows
$sb = New-Object System.Text.StringBuilder
for ($r = 0; $r -lt $rows; $r++) {
    $line = ""
    for ($c = 0; $c -lt $cols; $c++) {
        $dark = 0; $tot = 0
        $x0 = [int]($left + $c * $cellW)
        $y0 = [int]($top + $r * $cellH)
        $x1 = [int]($left + ($c + 1) * $cellW)
        $y1 = [int]($top + ($r + 1) * $cellH)
        for ($y = $y0; $y -lt $y1; $y += 3) {
            for ($x = $x0; $x -lt $x1; $x += 3) {
                $p = $b.GetPixel($x, $y); $tot++
                if ($p.R -lt 80 -and $p.G -lt 80 -and $p.B -lt 80) { $dark++ }
            }
        }
        if ($tot -eq 0) { $line += " "; continue }
        $ratio = $dark / $tot
        if ($ratio -gt 0.6) { $line += "#" }
        elseif ($ratio -gt 0.3) { $line += "+" }
        elseif ($ratio -gt 0.12) { $line += "." }
        else { $line += " " }
    }
    [void]$sb.AppendLine($line)
}
Write-Output $sb.ToString()
$b.Dispose()
