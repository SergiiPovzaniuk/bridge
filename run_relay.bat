@echo off
cd /d C:\Users\sergi\projects\open_ai\open_ai_api
java -jar target\open-ai-api-0.0.1-SNAPSHOT.jar > relay_stdout.log 2> relay_stderr.log
