@echo off
echo Starting DestinyRenderer Benchmark Sequence...
echo This will automatically load into "New World" and start the 20-iteration benchmark.
cd /d "%~dp0"
.\gradlew runClient -Pbenchmark
pause
