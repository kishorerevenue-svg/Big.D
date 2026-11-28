@echo off
rem Habit AI: links this PC's brain (Ollama) to the Habit server, privately, with Windows' own SSH (no extra app, no network driver).
rem The server reaches Ollama through this link only; nobody else can. Close this window to stop the link.
title Habit home brain link - keep this window open
echo Habit home brain link is ON. Keep this window open (you can minimise it).
:loop
ssh -N -o ServerAliveInterval=30 -o ServerAliveCountMax=3 -o ExitOnForwardFailure=yes -o StrictHostKeyChecking=accept-new -R 11435:localhost:11434 root@bigdai.tech
echo Link dropped. Trying again in 20 seconds...
timeout /t 20 >nul
goto loop
