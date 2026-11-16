@echo off
setlocal EnableDelayedExpansion
title Diagnostico de audio - Velora Drive
rem Solo lectura: no instala, no borra y no cambia nada en el celular ni en el estereo.

set "ADBDIR=%LOCALAPPDATA%\vd-adb\platform-tools"
set "ADB=adb"
where adb >nul 2>nul
if errorlevel 1 set "ADB=%ADBDIR%\adb.exe"

echo.
echo  ==========================================
echo   Diagnostico de audio - Velora Drive
echo  ==========================================
echo.
echo   P = Preparar (EN CASA, con internet y el celular conectado por cable)
echo   C = Diagnostico (EN EL COCHE, con Android Auto conectado)
echo.
choice /c PC /n /m "  Escribe P o C: "
if errorlevel 2 goto coche

:preparar
echo.
if exist "%ADB%" goto prep_ok
if /i "%ADB%"=="adb" goto prep_ok
echo  Descargando las herramientas de Android (unos 10 MB)...
mkdir "%LOCALAPPDATA%\vd-adb" 2>nul
curl -L -o "%LOCALAPPDATA%\vd-adb\pt.zip" https://dl.google.com/android/repository/platform-tools-latest-windows.zip
if errorlevel 1 (
  echo  No se pudo descargar. Revisa el internet y vuelve a intentar.
  pause & exit /b
)
tar -xf "%LOCALAPPDATA%\vd-adb\pt.zip" -C "%LOCALAPPDATA%\vd-adb"
del "%LOCALAPPDATA%\vd-adb\pt.zip"
:prep_ok
"%ADB%" start-server >nul 2>nul
echo.
echo  Conecta el celular por cable USB (con Depuracion USB activada).
echo  Si el celular pregunta "Permitir depuracion USB", marca "Permitir siempre" y acepta.
echo.
pause
"%ADB%" devices
echo.
echo  Si arriba aparece una linea que termina en "device", ya quedo listo.
echo  Si dice "unauthorized", acepta el aviso en el celular y vuelve a abrir este archivo.
echo.
pause
exit /b

:coche
if not exist "%ADB%" if /i not "%ADB%"=="adb" (
  echo.
  echo  Falta preparar. Abre este archivo en casa con internet y elige P.
  pause & exit /b
)
for /f %%t in ('powershell -NoProfile -Command "Get-Date -Format yyyyMMdd-HHmm"') do set "STAMP=%%t"
set "OUT=%~dp0diagnostico-coche-%STAMP%.txt"
echo.
echo  Antes de seguir:
echo   1. Android Auto conectado y una cancion sonando en OfflineTune (aunque no se escuche).
echo   2. Celular conectado a la laptop por cable USB.
echo   3. WiFi de la laptop conectado a la red del estereo (dira "Sin internet": es normal).
echo.
pause
"%ADB%" start-server >nul 2>nul
"%ADB%" connect 192.168.43.1:5555
timeout /t 2 >nul

set "PHONE="
set "STEREO="
for /f "skip=1 tokens=1,2" %%a in ('call "%ADB%" devices') do (
  if "%%b"=="device" (
    echo %%a | find ":" >nul && set "STEREO=%%a" || set "PHONE=%%a"
  )
)
echo.
if defined PHONE (echo   Celular: conectado) else (echo   Celular: NO conectado - revisa el cable y la Depuracion USB)
if defined STEREO (echo   Estereo: conectado) else (echo   Estereo: NO conectado - se sigue solo con el celular)
if not defined PHONE if not defined STEREO (
  echo.
  echo  No hay ningun aparato conectado. Revisa los pasos y vuelve a intentar.
  pause & exit /b
)

> "%OUT%" echo DIAGNOSTICO DE AUDIO - VELORA DRIVE  %STAMP%
>>"%OUT%" echo Celular: %PHONE%
>>"%OUT%" echo Estereo: %STEREO%

echo.
echo  MOMENTO 1: deja VELORA en pantalla (sin sonido). Cuando este asi, presiona una tecla.
pause >nul
echo  Guardando momento 1...
call :juntar 1

echo.
echo  MOMENTO 2: abre la app ANDROIDAUTO del estereo. Espera 5 segundos y presiona una tecla.
pause >nul
echo  Guardando momento 2...
call :juntar 2

echo.
echo  ==========================================
echo   LISTO. Reporte guardado en:
echo   %OUT%
echo  ==========================================
echo  Pasaselo a Claude cuando estes en casa.
echo.
pause
exit /b

:juntar
>>"%OUT%" echo.
>>"%OUT%" echo ################ MOMENTO %1 ################
if defined PHONE (
  >>"%OUT%" echo.
  >>"%OUT%" echo ===== CELULAR - modelo y Android Auto =====
  "%ADB%" -s %PHONE% shell getprop ro.product.model >>"%OUT%" 2>&1
  "%ADB%" -s %PHONE% shell getprop ro.build.version.release >>"%OUT%" 2>&1
  "%ADB%" -s %PHONE% shell "dumpsys package com.google.android.projection.gearhead | grep -i versionName" >>"%OUT%" 2>&1
  >>"%OUT%" echo ===== CELULAR - dumpsys audio =====
  "%ADB%" -s %PHONE% shell dumpsys audio >>"%OUT%" 2>&1
  >>"%OUT%" echo ===== CELULAR - dumpsys media_session =====
  "%ADB%" -s %PHONE% shell dumpsys media_session >>"%OUT%" 2>&1
  >>"%OUT%" echo ===== CELULAR - logcat audio =====
  "%ADB%" -s %PHONE% logcat -d -v time -t 4000 2>nul | findstr /I /L "audioservice audiopolicy audioflinger focus gearhead projection car.audio" | findstr /I /V /L "http token password contrase" >>"%OUT%"
)
if defined STEREO (
  >>"%OUT%" echo.
  >>"%OUT%" echo ===== ESTEREO - modelo =====
  "%ADB%" -s %STEREO% shell getprop ro.product.model >>"%OUT%" 2>&1
  "%ADB%" -s %STEREO% shell getprop ro.build.version.release >>"%OUT%" 2>&1
  >>"%OUT%" echo ===== ESTEREO - app en pantalla =====
  "%ADB%" -s %STEREO% shell "dumpsys activity activities | grep -iE 'mResumedActivity|topResumedActivity|mFocusedActivity'" >>"%OUT%" 2>&1
  >>"%OUT%" echo ===== ESTEREO - apps de enlace y musica =====
  "%ADB%" -s %STEREO% shell "pm list packages | grep -iE 'auto|link|bt|music|carplay|zlink|velora'" >>"%OUT%" 2>&1
  >>"%OUT%" echo ===== ESTEREO - dumpsys audio =====
  "%ADB%" -s %STEREO% shell dumpsys audio >>"%OUT%" 2>&1
  >>"%OUT%" echo ===== ESTEREO - dumpsys media_session =====
  "%ADB%" -s %STEREO% shell dumpsys media_session >>"%OUT%" 2>&1
  >>"%OUT%" echo ===== ESTEREO - logcat audio =====
  "%ADB%" -s %STEREO% logcat -d -v time -t 6000 2>nul | findstr /I /L "audio focus mcu channel source autolink cplink wireless mute" | findstr /I /V /L "http token password contrase" >>"%OUT%"
)
exit /b
