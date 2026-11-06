$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName PresentationCore
Add-Type -AssemblyName WindowsBase

$video = 'D:\QOdeep\9a5d6de080beadfbd574c9378bc27969.mp4'

$player = New-Object System.Windows.Media.MediaPlayer
$player.ScrubbingEnabled = $true

$frame = New-Object System.Windows.Threading.DispatcherFrame
$script:done = $false
$script:err = $false
$player.add_MediaOpened({ $script:done = $true; $frame.Continue = $false })
$player.add_MediaFailed({ $script:err = $true; $frame.Continue = $false })
$player.Open([Uri]('file:///' + ($video -replace '\\','/')))

$timer = New-Object System.Windows.Threading.DispatcherTimer
$timer.Interval = [TimeSpan]::FromSeconds(15)
$timer.add_Tick({ $frame.Continue = $false })
$timer.Start()
[System.Windows.Threading.Dispatcher]::PushFrame($frame)
$timer.Stop()

if($script:err){ "MEDIA_FAILED"; exit 1 }
if(-not $script:done){ "OPEN_TIMEOUT"; exit 1 }

$w = $player.NaturalVideoWidth
$h = $player.NaturalVideoHeight
"INFO ${w}x${h} hasVideo=$($player.HasVideo) naturalDur=$($player.NaturalDuration)"

if($w -le 0 -or $h -le 0){ "BAD_SIZE"; exit 1 }

$points = @(0.2, 0.5, 1.0, 1.5, 2.0, 3.0, 4.5, 6.0, 7.5)
$i = 0
foreach($sec in $points){
    $player.Position = [TimeSpan]::FromSeconds($sec)
    [System.Threading.Thread]::Sleep(400)
    $dv = New-Object System.Windows.Media.DrawingVisual
    $dc = $dv.RenderOpen()
    $rect = [System.Windows.Rect]::new(0.0, 0.0, [double]$w, [double]$h)
    $dc.DrawVideo($player, $rect)
    $dc.Close()
    $rtb = New-Object System.Windows.Media.Imaging.RenderTargetBitmap($w,$h,96,96,[System.Windows.Media.PixelFormats]::Pbgra32)
    $rtb.Render($dv)
    $enc = New-Object System.Windows.Media.Imaging.PngBitmapEncoder
    $enc.Frames.Add([System.Windows.Media.Imaging.BitmapFrame]::Create($rtb))
    $fs = [System.IO.File]::Create("D:\QOdeep\frame_$i.png")
    $enc.Save($fs)
    $fs.Close()
    "SAVED frame_$i.png @want=${sec}s actualPos=$($player.Position)"
    $i++
}
$player.Close()
"DONE"
