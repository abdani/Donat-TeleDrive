@echo off
rem Builds (if needed) and starts Donat TeleDrive from a copy of its jar, so rebuilding while it runs can't break it.
rem If the app is already open, the copy step fails (jar in use) and the start just brings the open window up.
cd /d "%~dp0"
call gradlew.bat devSnapshot --console=plain -q
start "" "%JAVA_HOME%\bin\javaw.exe" @build\devrun\args.txt
