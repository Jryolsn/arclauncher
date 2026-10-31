"""Prints the download URL of the newest APK in a GitHub releases list (JSON on stdin).

argv[1] == "true" includes pre-releases. Prefers the armeabi-v7a APK (what a Chromecast runs).
"""
import json
import sys

include_pre = len(sys.argv) > 1 and sys.argv[1] == "true"
for release in json.load(sys.stdin):
    if release["draft"] or (release["prerelease"] and not include_pre):
        continue
    apks = [a["browser_download_url"] for a in release["assets"] if a["name"].endswith(".apk")]
    if apks:
        v7a = [u for u in apks if "armeabi-v7a" in u]
        print((v7a or apks)[0])
        break
