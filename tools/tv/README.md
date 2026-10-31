# TV setup tools

Scripts that set up a Chromecast with Google TV the way ours is, over adb from a computer (no root):

- **Debloat**: disable telemetry/ads/unused Google packages (`lists/disable.txt`) and remove unwanted apps
  (`lists/uninstall.txt`). `lists/never-touch.txt` is a safety list the scripts refuse to touch.
- **Arc as home screen**, installed from this repo's GitHub releases, with its overlay and accessibility
  service switched on (Dim screen, quick panel on hold-Home / the YouTube button, app lock).
- **Phone casting kept** with [Cast Fix](https://github.com/thayford/castfix): Google's home screen can
  only be turned off together with the setup wizard, which the Cast receiver needs at boot.

## Use

1. On the TV: Settings > System > About > tap *Android TV OS build* 7 times, then
   Developer options > USB debugging (or Wireless debugging). Connect with `adb connect <ip>:<port>` or USB.
2. From this repo:

   ```sh
   TV_SERIAL=<serial from adb devices> tools/tv/setup-tv.sh          # all steps, asks before each
   TV_SERIAL=<serial> tools/tv/setup-tv.sh --pre                     # same, Arc from the newest pre-release
   TV_SERIAL=<serial> tools/tv/setup-tv.sh 6                         # one step only
   ```

   Step 5 asks for the pairing port and code that the TV shows under
   Developer options > Wireless debugging > *Pair device with pairing code*.
3. Updates: install [Obtainium](https://github.com/ImranR98/Obtainium) on the TV and add
   `https://github.com/Jryolsn/arclauncher`. The test TV includes pre-releases (every `dev` build);
   the main TV follows releases only (builds from `main`).

## Undo

```sh
TV_SERIAL=<serial> tools/tv/restore-tv.sh        # everything
TV_SERIAL=<serial> tools/tv/restore-tv.sh home   # only bring Google's home screen back
```

What the scripts changed per TV is kept in `tools/tv/state/<serial>/` (not committed).

## Notes

- The setup wizard owns Wi-Fi networks saved during first setup, and Android forgets them when it is
  disabled. Step 6 lists them first; if the TV is on one, forget it and connect again in Settings.
- Volume keys only reach the TV over HDMI-CEC; a PC monitor without CEC shows no volume bar.
- Tested on Chromecast with Google TV HD (boreal), Google TV 14, build UTTC.250917.004.
