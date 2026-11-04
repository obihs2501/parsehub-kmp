param([string]$Repo = "C:/softwares/claudecode/parsehub-kmp")

Add-Type -AssemblyName System.Drawing

function New-IconBitmap {
    param([int]$Size, [bool]$Round)

    $bmp = New-Object System.Drawing.Bitmap -ArgumentList $Size, $Size
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g.Clear([System.Drawing.Color]::Transparent)

    $r = [Math]::Max(4, [int]($Size * 0.22))
    $path = New-Object System.Drawing.Drawing2D.GraphicsPath
    if ($Round) {
        $path.AddEllipse(0, 0, $Size - 1, $Size - 1)
    }
    else {
        $path.AddArc(0, 0, $r * 2, $r * 2, 180, 90)
        $path.AddArc($Size - $r * 2 - 1, 0, $r * 2, $r * 2, 270, 90)
        $path.AddArc($Size - $r * 2 - 1, $Size - $r * 2 - 1, $r * 2, $r * 2, 0, 90)
        $path.AddArc(0, $Size - $r * 2 - 1, $r * 2, $r * 2, 90, 90)
        $path.CloseFigure()
    }

    $p1 = New-Object System.Drawing.Point -ArgumentList 0, 0
    $p2 = New-Object System.Drawing.Point -ArgumentList $Size, $Size
    $c1 = [System.Drawing.Color]::FromArgb(255, 76, 125, 255)
    $c2 = [System.Drawing.Color]::FromArgb(255, 154, 92, 255)
    $brush = New-Object System.Drawing.Drawing2D.LinearGradientBrush -ArgumentList $p1, $p2, $c1, $c2
    $g.FillPath($brush, $path)

    $white = New-Object System.Drawing.SolidBrush -ArgumentList ([System.Drawing.Color]::White)
    $cx = $Size / 2.0
    $cy = $Size / 2.0
    $w = $Size * 0.30
    $h = $Size * 0.34
    $pts = @(
        (New-Object System.Drawing.PointF -ArgumentList ([single]($cx - $w * 0.5)), ([single]($cy - $h * 0.5))),
        (New-Object System.Drawing.PointF -ArgumentList ([single]($cx + $w * 0.62)), ([single]$cy)),
        (New-Object System.Drawing.PointF -ArgumentList ([single]($cx - $w * 0.5)), ([single]($cy + $h * 0.5)))
    )
    $g.FillPolygon($white, $pts)

    $g.Dispose()
    return $bmp
}

function Save-IconPng {
    param($Bitmap, [string]$Path)
    $dir = [System.IO.Path]::GetDirectoryName($Path)
    New-Item -ItemType Directory -Force $dir | Out-Null
    $Bitmap.Save($Path, [System.Drawing.Imaging.ImageFormat]::Png)
}

$density = [ordered]@{ mdpi = 48; hdpi = 72; xhdpi = 96; xxhdpi = 144; xxxhdpi = 192 }
foreach ($key in $density.Keys) {
    $size = $density[$key]
    foreach ($name in @("ic_launcher", "ic_launcher_round")) {
        $round = ($name -eq "ic_launcher_round")
        $bmp = New-IconBitmap -Size $size -Round $round
        Save-IconPng -Bitmap $bmp -Path (Join-Path $Repo "app-android/src/main/res/mipmap-$key/$name.png")
        $bmp.Dispose()
    }
}

$preview = New-IconBitmap -Size 512 -Round $false
Save-IconPng -Bitmap $preview -Path (Join-Path $Repo "docs/icon-512.png")
$preview.Dispose()

$tmp = Join-Path ([System.IO.Path]::GetTempPath()) "parsehub_icon"
New-Item -ItemType Directory -Force $tmp | Out-Null
$sizes = @(256, 128, 64, 48, 32, 16)
$pngPaths = @()
foreach ($s in $sizes) {
    $bmp = New-IconBitmap -Size $s -Round $false
    $p = Join-Path $tmp "icon_$s.png"
    Save-IconPng -Bitmap $bmp -Path $p
    $bmp.Dispose()
    $pngPaths += $p
}

$ms = New-Object System.IO.MemoryStream
$bw = New-Object System.IO.BinaryWriter -ArgumentList $ms
$bw.Write([UInt16]0)
$bw.Write([UInt16]1)
$bw.Write([UInt16]$sizes.Count)
$payloads = @()
$offset = 6 + 16 * $sizes.Count
for ($i = 0; $i -lt $sizes.Count; $i++) {
    $bytes = [System.IO.File]::ReadAllBytes($pngPaths[$i])
    $payloads += , $bytes
    $s = $sizes[$i]
    if ($s -ge 256) { $dim = 0 } else { $dim = $s }
    $bw.Write([Byte]$dim)
    $bw.Write([Byte]$dim)
    $bw.Write([Byte]0)
    $bw.Write([Byte]0)
    $bw.Write([UInt16]1)
    $bw.Write([UInt16]32)
    $bw.Write([UInt32]$bytes.Length)
    $bw.Write([UInt32]$offset)
    $offset += $bytes.Length
}
foreach ($bytes in $payloads) { $bw.Write($bytes) }
$bw.Flush()
$icoPath = Join-Path $Repo "app-desktop/src/main/resources/icon.ico"
[System.IO.File]::WriteAllBytes($icoPath, $ms.ToArray())
$bw.Dispose()
$ms.Dispose()

Write-Output "ico: $icoPath $((Get-Item $icoPath).Length) bytes"
Get-ChildItem (Join-Path $Repo "app-android/src/main/res") -Recurse -Filter *.png | ForEach-Object { Write-Output "png: $($_.FullName.Replace($Repo,'')) $($_.Length) bytes" }
