# -*- coding: utf-8 -*-
"""
RANI AI Mobile App
==================
PC-তে চলা RANI-এর সাথে Voice + Text-এ কথা বলার Android app।
Auto-update: GitHub Releases থেকে নতুন APK নিজেই install করে।
"""

import threading
import base64
import json
import os

from kivy.app import App
from kivy.uix.boxlayout import BoxLayout
from kivy.uix.label import Label
from kivy.uix.button import Button
from kivy.uix.textinput import TextInput
from kivy.uix.scrollview import ScrollView
from kivy.uix.popup import Popup
from kivy.clock import Clock
from kivy.core.window import Window
from kivy.metrics import dp

try:
    from android.permissions import request_permissions, Permission
    from android import mActivity
    from jnius import autoclass
    IS_ANDROID = True
except ImportError:
    IS_ANDROID = False

PORT = 8765
CURRENT_VERSION = "1.0.0"
GITHUB_REPO     = "devjoynal410/rani-mobile-app"
GITHUB_API_URL  = f"https://api.github.com/repos/{GITHUB_REPO}/releases/latest"

# ── Colors ──────────────────────────────────────────────────────
BG    = (0.02, 0.03, 0.06, 1)
BG2   = (0.05, 0.08, 0.14, 1)
RED   = (0.91, 0.25, 0.42, 1)
CYAN  = (0.0,  0.85, 0.95, 1)
TEXT  = (0.91, 0.92, 0.96, 1)
GRAY  = (0.40, 0.40, 0.45, 1)


class RaniApp(App):
    def build(self):
        Window.clearcolor = BG
        if IS_ANDROID:
            request_permissions([Permission.RECORD_AUDIO, Permission.INTERNET])

        self._ws        = None
        self._connected = False
        self._recording = False
        self._pa        = None
        self._mic_stream = None

        root = BoxLayout(orientation="vertical", padding=dp(8), spacing=dp(6))

        # ── Header ──────────────────────────────────────────────
        hdr = BoxLayout(size_hint_y=None, height=dp(44), spacing=dp(8))
        self._dot = Label(text="●", color=GRAY, font_size=dp(16),
                          size_hint_x=None, width=dp(20))
        self._stat = Label(text="Not connected", color=GRAY,
                           font_size=dp(12), halign="left")
        self._stat.bind(size=self._stat.setter("text_size"))
        hdr.add_widget(self._dot)
        hdr.add_widget(self._stat)
        root.add_widget(hdr)

        # ── IP row ───────────────────────────────────────────────
        ip_row = BoxLayout(size_hint_y=None, height=dp(40), spacing=dp(8))
        self._ip = TextInput(
            text="192.168.0.161", hint_text="PC IP",
            multiline=False, font_size=dp(13),
            foreground_color=TEXT, background_color=BG2,
            size_hint_x=0.65,
        )
        self._cbtn = Button(
            text="Connect", font_size=dp(13),
            background_color=RED, size_hint_x=0.35,
        )
        self._cbtn.bind(on_press=self._toggle_connect)
        ip_row.add_widget(self._ip)
        ip_row.add_widget(self._cbtn)
        root.add_widget(ip_row)

        # ── Chat scroll ──────────────────────────────────────────
        scroll = ScrollView()
        self._chat = BoxLayout(
            orientation="vertical", size_hint_y=None,
            spacing=dp(6), padding=[dp(4), dp(4)],
        )
        self._chat.bind(minimum_height=self._chat.setter("height"))
        scroll.add_widget(self._chat)
        root.add_widget(scroll)
        self._scroll = scroll

        # ── Live label ───────────────────────────────────────────
        self._live = Label(
            text="", color=CYAN, font_size=dp(13),
            text_size=(Window.width - dp(24), None),
            halign="left", valign="top",
            size_hint_y=None, height=dp(0),
        )
        root.add_widget(self._live)

        # ── Bottom bar ───────────────────────────────────────────
        bot = BoxLayout(size_hint_y=None, height=dp(52), spacing=dp(8))
        self._inp = TextInput(
            hint_text="Message লিখুন...", multiline=False,
            font_size=dp(13), foreground_color=TEXT,
            background_color=BG2, size_hint_x=0.60,
        )
        self._inp.bind(on_text_validate=self._send_text)
        self._sbtn = Button(
            text="Send", font_size=dp(13),
            background_color=RED, size_hint_x=0.15,
        )
        self._sbtn.bind(on_press=self._send_text)
        self._mbtn = Button(
            text="🎤", font_size=dp(20),
            background_color=BG2, size_hint_x=0.25,
        )
        self._mbtn.bind(on_press=self._toggle_mic)
        bot.add_widget(self._inp)
        bot.add_widget(self._sbtn)
        bot.add_widget(self._mbtn)
        root.add_widget(bot)

        # ── Update banner (hidden by default) ───────────────────
        self._update_bar = Button(
            text="", size_hint_y=None, height=dp(0),
            background_color=[0.1, 0.5, 0.2, 1],
            font_size=dp(12), color=TEXT,
        )
        self._update_bar.bind(on_press=self._do_update)
        root.add_widget(self._update_bar)
        self._update_apk_url = ""

        # Check for update after 3 seconds
        Clock.schedule_once(lambda dt: threading.Thread(
            target=self._check_update, daemon=True).start(), 3)

        return root

    # ── Connect / Disconnect ─────────────────────────────────────

    def _toggle_connect(self, *_):
        if self._connected:
            self._do_disconnect()
        else:
            ip = self._ip.text.strip()
            if ip:
                self._cbtn.text = "..."
                threading.Thread(target=self._ws_thread, args=(ip,),
                                 daemon=True).start()

    def _ws_thread(self, ip):
        import websocket as wsc
        url = f"ws://{ip}:{PORT}"
        self._ws = wsc.WebSocketApp(
            url,
            on_open=self._on_open,
            on_message=self._on_message,
            on_error=self._on_error,
            on_close=self._on_close,
        )
        self._ws.run_forever(ping_interval=20, ping_timeout=10)

    def _do_disconnect(self):
        try:
            if self._ws:
                self._ws.close()
        except Exception:
            pass

    def _on_open(self, ws):
        self._connected = True
        Clock.schedule_once(lambda dt: self._set_status(True, "Connected ✓"), 0)

    def _on_close(self, ws, *_):
        self._connected = False
        Clock.schedule_once(lambda dt: self._set_status(False, "Disconnected"), 0)

    def _on_error(self, ws, err):
        Clock.schedule_once(
            lambda dt, e=err: self._set_status(False, f"Error: {e}"), 0)

    def _on_message(self, ws, raw):
        try:
            msg = json.loads(raw)
        except Exception:
            return
        t = msg.get("type")
        if t == "text":
            text  = msg.get("text", "")
            final = msg.get("final", False)
            Clock.schedule_once(
                lambda dt, tx=text, fn=final: self._handle_text(tx, fn), 0)
        elif t == "audio":
            threading.Thread(target=self._play_audio,
                             args=(msg.get("data", ""),), daemon=True).start()
        elif t == "status":
            state = msg.get("state", "")
            Clock.schedule_once(
                lambda dt, s=state: self._handle_status(s), 0)

    # ── UI helpers ───────────────────────────────────────────────

    def _set_status(self, ok, text):
        self._dot.color  = CYAN if ok else GRAY
        self._stat.text  = text
        self._cbtn.text  = "Disconnect" if ok else "Connect"

    def _handle_text(self, text, final):
        if final:
            if text.strip():
                self._add_bubble("RANI", text, CYAN)
            self._live.text   = ""
            self._live.height = dp(0)
        else:
            self._live.text   = text
            self._live.height = None

    def _handle_status(self, state):
        labels = {
            "listening": ("● Listening...", [0.1, 0.9, 0.4, 1]),
            "thinking":  ("● Thinking...",  [0.9, 0.7, 0.1, 1]),
            "speaking":  ("● Speaking...",  list(CYAN)),
            "standby":   ("● Standby",      list(GRAY)),
        }
        txt, color = labels.get(state, ("● " + state, list(GRAY)))
        self._stat.text  = txt
        self._stat.color = color

    def _add_bubble(self, who, text, color):
        lbl = Label(
            text=f"[b]{who}:[/b] {text}", markup=True,
            color=color, font_size=dp(14),
            text_size=(Window.width - dp(40), None),
            halign="left", valign="top", size_hint_y=None,
        )
        lbl.bind(texture_size=lambda *a: setattr(lbl, "height",
                                                  lbl.texture_size[1] + dp(8)))
        self._chat.add_widget(lbl)
        self._scroll.scroll_to(lbl)

    def _add_sys(self, text):
        lbl = Label(
            text=f"[color=666677]{text}[/color]", markup=True,
            font_size=dp(11), size_hint_y=None, height=dp(22),
        )
        self._chat.add_widget(lbl)

    # ── Send text ────────────────────────────────────────────────

    def _send_text(self, *_):
        text = self._inp.text.strip()
        if not text:
            return
        if not self._connected:
            self._add_sys("First connect to RANI!")
            return
        self._inp.text = ""
        self._add_bubble("You", text, RED)
        try:
            self._ws.send(json.dumps({"type": "text", "text": text}))
        except Exception as e:
            self._add_sys(f"Send failed: {e}")

    # ── Microphone ───────────────────────────────────────────────

    def _toggle_mic(self, *_):
        if not self._connected:
            self._add_sys("First connect to RANI!")
            return
        if self._recording:
            self._stop_mic()
        else:
            self._start_mic()

    def _start_mic(self):
        self._recording = True
        self._mbtn.text             = "⏹"
        self._mbtn.background_color = [0.9, 0.15, 0.15, 1]
        threading.Thread(target=self._mic_loop, daemon=True).start()

    def _stop_mic(self):
        self._recording             = False
        self._mbtn.text             = "🎤"
        self._mbtn.background_color = list(BG2)

    def _mic_loop(self):
        try:
            import pyaudio
            CHUNK = 1024
            pa = pyaudio.PyAudio()
            stream = pa.open(
                format=pyaudio.paInt16, channels=1,
                rate=16000, input=True, frames_per_buffer=CHUNK,
            )
            while self._recording:
                data = stream.read(CHUNK, exception_on_overflow=False)
                if self._ws and self._connected:
                    b64 = base64.b64encode(data).decode()
                    try:
                        self._ws.send(json.dumps({"type": "audio", "data": b64}))
                    except Exception:
                        break
            stream.stop_stream()
            stream.close()
            pa.terminate()
        except Exception as e:
            Clock.schedule_once(
                lambda dt, err=e: self._add_sys(f"Mic: {err}"), 0)
        finally:
            Clock.schedule_once(lambda dt: self._stop_mic(), 0)

    # ── Audio playback ────────────────────────────────────────────

    def _play_audio(self, data_b64):
        try:
            import pyaudio
            raw = base64.b64decode(data_b64)
            pa  = pyaudio.PyAudio()
            st  = pa.open(format=pyaudio.paInt16, channels=1,
                          rate=16000, output=True)
            st.write(raw)
            st.stop_stream()
            st.close()
            pa.terminate()
        except Exception as e:
            print(f"[AudioPlay] {e}")

    # ── Auto Update ───────────────────────────────────────────────

    def _check_update(self):
        try:
            import urllib.request
            req = urllib.request.Request(
                GITHUB_API_URL,
                headers={"User-Agent": "RANI-Mobile/1.0"}
            )
            with urllib.request.urlopen(req, timeout=10) as r:
                data = json.loads(r.read().decode())

            latest_tag  = data.get("tag_name", "")
            latest_ver  = latest_tag.split("-")[0].lstrip("v")  # "v1.2.0-build5" → "1.2.0"
            assets      = data.get("assets", [])
            apk_asset   = next((a for a in assets if a["name"].endswith(".apk")), None)

            if not apk_asset:
                return

            if self._is_newer(latest_ver, CURRENT_VERSION):
                apk_url = apk_asset["browser_download_url"]
                Clock.schedule_once(
                    lambda dt, v=latest_ver, u=apk_url: self._show_update_bar(v, u), 0)
        except Exception as e:
            print(f"[Update] Check failed: {e}")

    def _is_newer(self, latest: str, current: str) -> bool:
        try:
            def parts(v): return [int(x) for x in v.strip().split(".")]
            return parts(latest) > parts(current)
        except Exception:
            return False

    def _show_update_bar(self, version: str, apk_url: str):
        self._update_apk_url = apk_url
        self._update_bar.text   = f"🔄  New update v{version} available — Tap to install!"
        self._update_bar.height = dp(40)

    def _do_update(self, *_):
        if not self._update_apk_url:
            return
        self._update_bar.text = "⬇️  Downloading update..."
        threading.Thread(target=self._download_and_install,
                         args=(self._update_apk_url,), daemon=True).start()

    def _download_and_install(self, url: str):
        try:
            import urllib.request
            from pathlib import Path

            # Download APK
            apk_path = Path("/sdcard/Download/RANI-AI-update.apk")
            if IS_ANDROID:
                apk_path = Path("/sdcard/Download/RANI-AI-update.apk")
            else:
                apk_path = Path.home() / "Downloads" / "RANI-AI-update.apk"

            Clock.schedule_once(
                lambda dt: setattr(self._update_bar, "text",
                                   "⬇️  Downloading... please wait"), 0)

            urllib.request.urlretrieve(url, str(apk_path))

            if IS_ANDROID:
                self._install_apk_android(str(apk_path))
            else:
                Clock.schedule_once(
                    lambda dt: setattr(self._update_bar, "text",
                                       f"✅ Downloaded: {apk_path}"), 0)

        except Exception as e:
            Clock.schedule_once(
                lambda dt, err=e: setattr(
                    self._update_bar, "text", f"❌ Download failed: {err}"), 0)

    def _install_apk_android(self, apk_path: str):
        try:
            Intent   = autoclass("android.content.Intent")
            Uri      = autoclass("android.net.Uri")
            File     = autoclass("java.io.File")
            Build    = autoclass("android.os.Build")
            Settings = autoclass("android.provider.Settings")

            intent = Intent(Intent.ACTION_VIEW)
            f = File(apk_path)

            if Build.VERSION.SDK_INT >= 24:
                FileProvider = autoclass(
                    "androidx.core.content.FileProvider")
                ctx = mActivity.getApplicationContext()
                uri = FileProvider.getUriForFile(
                    ctx,
                    ctx.getPackageName() + ".fileprovider",
                    f
                )
                intent.setDataAndType(uri,
                    "application/vnd.android.package-archive")
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            else:
                uri = Uri.fromFile(f)
                intent.setDataAndType(uri,
                    "application/vnd.android.package-archive")

            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            mActivity.startActivity(intent)
        except Exception as e:
            Clock.schedule_once(
                lambda dt, err=e: setattr(
                    self._update_bar, "text", f"❌ Install error: {err}"), 0)


if __name__ == "__main__":
    RaniApp().run()
