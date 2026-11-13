# Run from anywhere: powershell -File tools\mutate.ps1   (results: tools\mutation-results.txt)
# Mutation test: undo one fix at a time, run PentestTest, expect it to FAIL ("killed"). Sources are restored from a
# backup after every mutant and again at the end, whatever happens.
$ErrorActionPreference = "Continue"
$proj = Split-Path $PSScriptRoot
$src = "$proj\src\jvmMain\kotlin\donat\teledrive"
$out = Join-Path $PSScriptRoot "mutation-results.txt"
$utf8 = New-Object Text.UTF8Encoding $false
$files = @{ DS = "$src\DriveService.kt"; LAN = "$src\LanServer.kt"; SS = "$src\StreamServer.kt"; SEC = "$src\Secrets.kt"; TG = "$src\telegram\Telegram.kt"; MAIN = "$src\Main.kt" }
$backup = @{}; foreach ($k in $files.Keys) { $backup[$k] = [IO.File]::ReadAllText($files[$k]) }

$mutants = @(
  @('M01 download path: uniqueFile without safeName', 'DS', @(,@('    val name = safeName(raw)', '    val name = raw'))),
  @('M02 preview cache path without safeName', 'DS', @(,@('apply { mkdirs() }, safeName(file.name))', 'apply { mkdirs() }, file.name)'))),
  @('M03 safeName: Windows device names allowed', 'DS', @(,@('WINDOWS_DEVICES.matches(s) -> "_$s"', 'false -> "_$s"'))),
  @('M04 safeName: path separators allowed', 'DS', @(,@('it in "\\/:*?\"<>|"', 'it in "*?"'))),
  @('M05 old 48-bit LAN key kept', 'LAN', @(,@('Prefs["lanKey"]?.takeIf { it.length >= 32 } ?: newKey()', 'Prefs["lanKey"] ?: newKey()'))),
  @('M06 ?f= reflected into the page (XSS)', 'LAN', @(,@('folder?.name, folder?.parentId, folder?.id,', 'folder?.name, folder?.parentId, ex.requestURI.query?.removePrefix("f="),'))),
  @('M07 folder of another drive accepted', 'LAN', @(,@('id?.let { service.dao.folder(it) }?.takeIf { it.driveId == service.drive.id && it.trashedAt == null }', 'id?.let { service.dao.folder(it) }'))),
  @('M08 trashed file served', 'LAN', @(,@('it.driveId == service.drive.id && it.trashedAt == null && it.versionOf', 'it.driveId == service.drive.id && it.versionOf'))),
  @('M09 file of another drive served', 'LAN', @(,@('it.driveId == service.drive.id && it.trashedAt == null && it.versionOf', 'it.trashedAt == null && it.versionOf'))),
  @('M10 HTML/SVG served inline', 'LAN', @(@('internal fun activeContent(f: DriveFile) =', 'internal fun activeContent(f: DriveFile) = false && ('), @('"js", "mjs")', '"js", "mjs"))'))),
  @('M11 no nosniff header', 'LAN', @(,@('ex.responseHeaders.add("X-Content-Type-Options", "nosniff")', 'Unit'))),
  @('M12 share link not re-checked', 'LAN', @(,@('val f = runBlocking { liveFile(service, share.file.id) } ?: return text(ex, 404, "This file is no longer shared.")', 'val f = share.file'))),
  @('M13 upload name not sanitised', 'LAN', @(,@('safeName(rest[2])', 'rest[2]'))),
  @('M14 upload into any folder id', 'LAN', @(,@('service.enqueueUpload(dest, folder?.id)', 'service.enqueueUpload(dest, rest[1].takeIf { it != "root" })'))),
  @('M15 key compared with == (timing only; expected to survive)', 'LAN', @(,@('java.security.MessageDigest.isEqual(given.toByteArray(), key.toByteArray())', 'given == key'))),
  @('M16 key/token written to the log', 'LAN', @(,@('"/${rawPath.split(''/'').getOrNull(1).orEmpty()}/…"', 'rawPath'))),
  @('M17 stream loops on an empty read', 'SS', @(,@('if (chunk.isEmpty()) throw StorageException("No data at $pos")', 'if (false) throw StorageException("No data at $pos")'))),
  @('M18 0-byte file sent chunked', 'SS', @(,@('if (end < start) -1 else end - start + 1', 'end - start + 1'))),
  @('M19 PIN misses not persisted', 'SEC', @(,@('Prefs["pinFails"] = "$n"', 'Unit'))),
  @('M20 PIN brake not doubling', 'SEC', @(,@('30_000L * (1L shl minOf(n / 5 - 1, 5))', '30_000L'))),
  @('M21 right PIN keeps the brake', 'SEC', @(,@('.also { if (it) { Prefs.remove("pinFails"); Prefs.remove("pinWait") } }', ''))),
  @('M22 old data-channel title not recognised', 'TG', @(,@('title.startsWith(DATA_TITLE) || title.startsWith("$OLD_NAME data")', 'title.startsWith(DATA_TITLE)'))),
  @('M23 old drive mark not recognised', 'TG', @(,@('description.startsWith(DRIVE_MARK) || description.startsWith("$OLD_NAME storage.")', 'description.startsWith(DRIVE_MARK)'))),
  @('M24 app data not moved to the new name', 'MAIN', @(,@('return if (!dir.exists() && old.isDirectory && !old.renameTo(dir)) old else dir', 'return dir')))
)

Set-Content $out "mutation run $(Get-Date -Format s)" -Encoding utf8
Push-Location $proj
try {
  foreach ($m in $mutants) {
    $name, $key, $pairs = $m
    $text = $backup[$key]
    $bad = $null
    foreach ($p in $pairs) {
      $n = ([regex]::Matches($text, [regex]::Escape($p[0]))).Count
      if ($n -ne 1) { $bad = "pattern found $n times: $($p[0])" }
      $text = $text.Replace($p[0], $p[1])
    }
    if ($bad) { Add-Content $out "SKIP   $name — $bad" -Encoding utf8; continue }
    [IO.File]::WriteAllText($files[$key], $text, $utf8)
    $log = & .\gradlew.bat jvmTest --tests donat.teledrive.PentestTest --console=plain -q 2>&1 | Out-String
    $code = $LASTEXITCODE
    [IO.File]::WriteAllText($files[$key], $backup[$key], $utf8)
    $verdict = if ($code -eq 0) { "SURVIVED" } elseif ($log -match '(?m)^e: ') { "NOCOMPILE" } else { "KILLED" }
    $why = ""
    if ($verdict -eq "KILLED") {
      $why = (Get-ChildItem build\test-results\jvmTest\*PentestTest*.xml | ForEach-Object { [xml]$x = Get-Content $_ -Raw; $x.testsuite.testcase | Where-Object { $_.failure } | ForEach-Object { $_.name -replace '\[jvm\]', '' } }) -join ','
    }
    if ($verdict -eq "NOCOMPILE") { $why = ($log -split "`n" | Where-Object { $_ -match '^e: ' } | Select-Object -First 1) }
    Add-Content $out ("{0,-9} {1}  [{2}]" -f $verdict, $name, $why) -Encoding utf8
  }
} finally {
  foreach ($k in $files.Keys) { [IO.File]::WriteAllText($files[$k], $backup[$k], $utf8) }
  Pop-Location
  Add-Content $out "sources restored $(Get-Date -Format s)" -Encoding utf8
}
