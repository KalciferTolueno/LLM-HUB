# Genera ic_launcher / ic_launcher_foreground / ic_launcher_round (PNG) por densidad
# desde un PNG cuadrado. Elimina los .webp equivalentes para que Gradle use PNG.
# Requiere Windows PowerShell + System.Drawing.

param(
  [string]$SourceLogo = (Join-Path (Split-Path $PSScriptRoot -Parent) "logo.png"),
  [string]$ResRoot = (Join-Path (Split-Path $PSScriptRoot -Parent) "android\app\src\main\res")
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path -LiteralPath $SourceLogo)) {
  Write-Error "No existe el logo: $SourceLogo"
}

Add-Type -AssemblyName System.Drawing

function Save-SquareIcon {
  param([string]$SrcPath, [int]$Px, [string]$DestPath)
  $img = [System.Drawing.Image]::FromFile($SrcPath)
  try {
    $bmp = New-Object System.Drawing.Bitmap $Px, $Px
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    try {
      $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
      $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
      $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
      $g.Clear([System.Drawing.Color]::Transparent)
      $g.DrawImage($img, 0, 0, $Px, $Px)
    } finally { $g.Dispose() }
    $dir = Split-Path $DestPath -Parent
    if (-not (Test-Path -LiteralPath $dir)) {
      New-Item -ItemType Directory -Path $dir | Out-Null
    }
    $bmp.Save($DestPath, [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
  } finally { $img.Dispose() }
}

# 108dp * densidad para capa foreground adaptive
$densities = @{
  "mipmap-mdpi"    = 108
  "mipmap-hdpi"    = 162
  "mipmap-xhdpi"   = 216
  "mipmap-xxhdpi"  = 324
  "mipmap-xxxhdpi" = 432
}

foreach ($folder in $densities.Keys) {
  $px = $densities[$folder]
  $dir = Join-Path $ResRoot $folder
  foreach ($w in @("ic_launcher.webp", "ic_launcher_foreground.webp", "ic_launcher_round.webp")) {
    $p = Join-Path $dir $w
    if (Test-Path -LiteralPath $p) { Remove-Item -LiteralPath $p -Force }
  }
  $fg = Join-Path $dir "ic_launcher_foreground.png"
  Save-SquareIcon -SrcPath $SourceLogo -Px $px -DestPath $fg
  Copy-Item -LiteralPath $fg -Destination (Join-Path $dir "ic_launcher.png") -Force
  Copy-Item -LiteralPath $fg -Destination (Join-Path $dir "ic_launcher_round.png") -Force
}

Write-Host "Iconos generados desde: $SourceLogo"
