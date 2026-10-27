# Makes the 64x64 server-list icon from a logo image and puts it on the proxy (what players see) and every backend.
# Usage: powershell -File tools\set_icon.ps1 [path\to\logo.png]   (default: C:\MinecraftNetwork\branding\alloymc-logo.png)
param([string]$Src = "C:\MinecraftNetwork\branding\alloymc-logo.png")
Add-Type -AssemblyName System.Drawing
$img = [System.Drawing.Image]::FromFile($Src)
$bmp = New-Object System.Drawing.Bitmap 64, 64
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
$g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
$g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
$g.Clear([System.Drawing.Color]::Transparent)
$g.DrawImage($img, 0, 0, 64, 64)
$g.Dispose(); $img.Dispose()
foreach ($d in "proxy", "hub", "skyblock", "survival", "factions", "arcade") {
    $bmp.Save("C:\MinecraftNetwork\$d\server-icon.png", [System.Drawing.Imaging.ImageFormat]::Png)
}
$bmp.Dispose()
Write-Output "server-icon.png written (64x64) - restart the proxy to show it"
