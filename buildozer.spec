[app]
title = RANI AI
package.name = raniai
package.domain = com.joynal.rani
source.dir = .
source.include_exts = py,png,jpg,kv
version = 1.0.0

requirements = python3,kivy==2.3.0,websocket-client,numpy

orientation = portrait
fullscreen = 0

android.minapi = 21
android.api = 33
android.ndk = 25b
android.archs = arm64-v8a, armeabi-v7a

android.permissions = INTERNET, RECORD_AUDIO, MODIFY_AUDIO_SETTINGS

[buildozer]
log_level = 2
warn_on_root = 1
