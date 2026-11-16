@echo off
cd /d "%~dp0"
javac --release 11 -encoding UTF-8 -d classes src\MyPet.java || exit /b
jar cfe MyPet.jar MyPet -C classes .
echo done. run: java -jar MyPet.jar
