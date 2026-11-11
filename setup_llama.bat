@echo off
REM Scarica llama.cpp alla versione verificata con questo progetto.
set TAG=b12282
cd /d "%~dp0"
if exist "third_party\llama.cpp\CMakeLists.txt" (
  echo llama.cpp gia' presente in third_party\llama.cpp
  pause
  exit /b 0
)
if not exist third_party mkdir third_party
git clone --depth 1 --branch %TAG% https://github.com/ggml-org/llama.cpp.git third_party\llama.cpp
if errorlevel 1 (
  echo ERRORE: git non trovato o download fallito. Installa Git da https://git-scm.com
  pause
  exit /b 1
)
echo Fatto: llama.cpp %TAG% scaricato.
pause
