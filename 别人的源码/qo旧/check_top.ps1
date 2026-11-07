Add-Type -AssemblyName System.Drawing
$b = [System.Drawing.Bitmap]::FromFile('d:/4/now2.png')
Write-Output ("Size: " + $b.Width + "x" + $b.Height)
$minX=$b.Width; $minY=$b.Height; $maxX=0; $maxY=0; $count=0
for($y=0; $y -lt [Math]::Min(260,$b.Height); $y++){
  for($x=0; $x -lt $b.Width; $x+=2){
    $p=$b.GetPixel($x,$y)
    if($p.R -lt 60 -and $p.G -lt 60 -and $p.B -lt 60){
      $count++
      if($x -lt $minX){$minX=$x}; if($x -gt $maxX){$maxX=$x}
      if($y -lt $minY){$minY=$y}; if($y -gt $maxY){$maxY=$y}
    }
  }
}
Write-Output ("DarkPixelsInTop260: " + $count)
Write-Output ("BBox: x=" + $minX + ".." + $maxX + "  y=" + $minY + ".." + $maxY)
$b.Dispose()
