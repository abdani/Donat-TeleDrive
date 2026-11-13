param([string]$title = "Donat TeleDrive", [string]$name = "drag")
# Drags the window's right edge with the mouse (Windows' modal sizing loop, like a user) and grabs the screen
# while dragging: counts light pixels (#F0F0F0-ish) just inside the moving right edge.
Add-Type -AssemblyName System.Drawing
Add-Type @'
using System; using System.Runtime.InteropServices;
public class D {
  [DllImport("user32.dll")] public static extern bool SetWindowPos(IntPtr h, IntPtr a, int x, int y, int cx, int cy, uint f);
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
  [DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
  [DllImport("user32.dll")] public static extern void mouse_event(uint f, uint dx, uint dy, uint d, IntPtr e);
  public struct RECT { public int L, T, R, B; }
}
'@
$h = (Get-Process java, javaw -ErrorAction SilentlyContinue | Where-Object MainWindowTitle -eq $title | Select-Object -First 1).MainWindowHandle
[D]::SetWindowPos($h, [IntPtr]::Zero, 60, 40, 700, 600, 0x0040) | Out-Null; [D]::SetForegroundWindow($h) | Out-Null; Start-Sleep 2
$r = New-Object D+RECT; [D]::GetWindowRect($h, [ref]$r) | Out-Null
$x = $r.R - 4; $y = $r.T + 300
[D]::SetCursorPos($x, $y) | Out-Null; Start-Sleep -Milliseconds 300
[D]::mouse_event(2, 0, 0, 0, [IntPtr]::Zero); Start-Sleep -Milliseconds 200
$light = 0; $total = 0; $saved = $false
for ($i = 1; $i -le 40; $i++) {
  [D]::SetCursorPos($x + $i * 12, $y) | Out-Null
  Start-Sleep -Milliseconds 15
  $bmp = New-Object Drawing.Bitmap 40, 400
  $g = [Drawing.Graphics]::FromImage($bmp); $g.CopyFromScreen($x + $i * 12 - 44, $r.T + 120, 0, 0, $bmp.Size); $g.Dispose()
  $n = 0
  for ($py = 0; $py -lt 400; $py += 8) { for ($px = 0; $px -lt 40; $px += 4) { $c = $bmp.GetPixel($px, $py); $total++; if ($c.R -ge 230 -and $c.G -ge 230 -and $c.B -ge 230) { $light++; $n++ } } }
  if ($n -gt 50 -and -not $saved) { $bmp.Save((Join-Path $PSScriptRoot "$name-strip.png")); $saved = $true }
}
[D]::mouse_event(4, 0, 0, 0, [IntPtr]::Zero)
"light pixels while dragging: $light / $total"
