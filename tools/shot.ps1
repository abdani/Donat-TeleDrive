# Screenshot of the app window (optionally resized first) via PrintWindow. Saves <name>.png next to this script.
param([string]$name = "shot", [int]$w = 0, [int]$h = 0, [string]$title = "DTD UI demo")
Add-Type -AssemblyName System.Drawing
Add-Type @'
using System; using System.Runtime.InteropServices;
public class W {
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
  [DllImport("user32.dll")] public static extern bool PrintWindow(IntPtr h, IntPtr dc, uint f);
  [DllImport("user32.dll")] public static extern bool SetWindowPos(IntPtr h, IntPtr a, int x, int y, int cx, int cy, uint f);
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
  public struct RECT { public int L, T, R, B; }
}
'@
$h0 = (Get-Process java, javaw -ErrorAction SilentlyContinue | Where-Object MainWindowTitle -eq $title | Select-Object -First 1).MainWindowHandle
if ($w -gt 0) { [W]::SetWindowPos($h0, [IntPtr]::Zero, 80, 40, $w, $h, 0x0040) | Out-Null; Start-Sleep -Milliseconds 1500 }
$r = New-Object W+RECT; [W]::GetWindowRect($h0, [ref]$r) | Out-Null
$bmp = New-Object Drawing.Bitmap ($r.R - $r.L), ($r.B - $r.T)
$g = [Drawing.Graphics]::FromImage($bmp); $dc = $g.GetHdc(); [W]::PrintWindow($h0, $dc, 2) | Out-Null; $g.ReleaseHdc($dc)
$p = Join-Path $PSScriptRoot "$name.png"; $bmp.Save($p); $p
