# Captures the README screenshots from the UI demo (start it first:
#   gradlew jvmTest --tests donat.teledrive.UiDemoTest -PuiDemo=true). Raw shots land in tools\raw\.
$proj = Split-Path $PSScriptRoot
$sig = Join-Path $proj "build\test-appdata\Donat TeleDrive\ui.signal"
$raw = Join-Path $PSScriptRoot "raw"; New-Item -ItemType Directory -Force $raw | Out-Null
function Send($cmd) { Set-Content $sig $cmd; $i = 0; while ((Test-Path $sig) -and $i -lt 30) { Start-Sleep -Milliseconds 100; $i++ }; Start-Sleep -Milliseconds 700 }
function Shot($name, $w, $h) { & (Join-Path $PSScriptRoot "shot.ps1") -name "raw\$name" -w $w -h $h | Out-Null; Start-Sleep -Milliseconds 300 }

$i = 0; while (-not (Get-Process java -ErrorAction SilentlyContinue | Where-Object MainWindowTitle -eq 'DTD UI demo') -and $i -lt 300) { Start-Sleep 1; $i++ }
Start-Sleep 5

Send "theme:GRAPHITE"; Send "view:grid"; Send "screen:Home";      Shot "home" 1440 900
Send "screen:Gallery";                                             Shot "gallery" 1280 820
Send "screen:Transfers";                                           Shot "transfers" 1280 820
Send "theme:OCEAN"; Send "screen:Insights";                        Shot "insights" 1280 820
Send "theme:LAVENDER"; Send "view:details"; Send "screen:Files";   Shot "files" 1280 820
Send "theme:SUNSET"; Send "screen:Settings";                       Shot "settings" 1280 900
Send "theme:GRAPHITE"; Send "view:grid"; Send "screen:Home"; Send "preview:f-IMG20260712181530jpg"; Start-Sleep 3; Shot "preview" 1280 820
Send "screen:Home"
foreach ($t in "DAYLIGHT", "OCEAN", "SAKURA", "NEON") { Send "theme:$t"; Shot "theme-$t" 1280 820 }
Send "theme:DAYLIGHT"; Send "view:details";                        Shot "phone-light" 470 900
Send "theme:FOREST"; Send "view:grid";                             Shot "phone-dark" 470 900
Send "theme:AMOLED"; Send "lock";                                  Shot "lock" 1000 720
Send "lock"; Send "theme:DAYLIGHT"; Send "auth:qr";                Shot "login" 1000 820
Send "quit"
Get-ChildItem $raw | Select-Object Name, Length
