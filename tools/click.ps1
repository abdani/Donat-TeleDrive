# Left-click at (x, y) inside the app window (offsets from the window's top-left corner).
param([int]$x, [int]$y, [string]$title = "DTD UI demo")
Add-Type @'
using System; using System.Runtime.InteropServices;
public class M {
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
  [DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
  [DllImport("user32.dll")] public static extern void mouse_event(uint f, uint dx, uint dy, uint d, IntPtr e);
  public struct RECT { public int L, T, R, B; }
}
'@
$h = (Get-Process java, javaw -ErrorAction SilentlyContinue | Where-Object MainWindowTitle -eq $title | Select-Object -First 1).MainWindowHandle
$r = New-Object M+RECT; [M]::GetWindowRect($h, [ref]$r) | Out-Null
[M]::SetForegroundWindow($h) | Out-Null; Start-Sleep -Milliseconds 300
[M]::SetCursorPos($r.L + $x, $r.T + $y) | Out-Null; Start-Sleep -Milliseconds 150
[M]::mouse_event(2, 0, 0, 0, [IntPtr]::Zero); [M]::mouse_event(4, 0, 0, 0, [IntPtr]::Zero)
Start-Sleep -Milliseconds 900
