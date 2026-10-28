# Home — an Apple-style Android TV launcher

A clean, ad-free home screen for Android TV, inspired by tvOS. No recommendation rows, no sponsored
tiles, no paywalled features, no tracking. Plain Kotlin on the Android framework, with no third-party libraries.

Built and tested on a Skyworth 50G3A (Android TV 10), and should work on any Android TV running Android 8 or later.

## Features

**Home screen**
- Full-screen wallpaper from any picture on the TV, with darkening and an optional slideshow
- Your apps in one row and HDMI inputs in another, with no headings or clutter. Show, hide, reorder and rename anything
- tvOS-style focus: tiles lift, tilt and catch a light glare when selected
- "Liquid Glass" surfaces (dock, panels) with adjustable frost, opacity and edge refraction

**Control Center** (the ⚙ button)
- HDMI inputs, mute, volume and darkness sliders, accent colour, and glass controls in one drop-down
- A full settings panel in the style of iOS Settings

**Widgets** (place them anywhere; they snap to the layout and push the app rows aside)
- Clock: big digital, analogue, stacked, in words or minimal
- Calendar: month, today or agenda, from the TV's calendar
- Trailers: official trailers playing silently, alternating between Apple TV and Netflix. Volume up
  turns the sound on. OK opens the title in its app

**Screen saver: "Movie posters"**
- Full-screen key art from what's on Apple TV and Netflix, one service at a time, slowly zooming
- Title, genre, year and description, plus the time. OK opens the title, Left/Right flip through
- Small shifts each time so nothing burns into the panel

**Remote buttons**
- Remap any remote button (such as a Prime Video button) to open an app, an HDMI input or Home
- Choose what opens when the TV turns on

**Backup & restore** of all settings to a file on the TV.

## Build and install

You need a Mac or Linux computer on the same network as the TV, and **network debugging** switched on
on the TV (Settings → Device Preferences → About → press Build 7 times, then Developer options → Network debugging).

```bash
cp tv.conf.example tv.conf        # then put your TV's IP address in it
./build-and-install.sh
```

The first run downloads Java 17, the Android SDK and the Inter font into `~/.tvlauncher-tools`
(about 1 GB, once only). The script then builds the app, installs it, grants its permissions, enables the
remote-button service and makes it the home screen.

You can also open the project in Android Studio. Without the Inter font in `app/src/main/assets/fonts/`,
the TV's default font is used.

Then set up the screen saver:

```bash
./scripts/set-screensaver.sh --now
```

### Helper scripts
| Script | What it does |
|---|---|
| `scripts/set-screensaver.sh` | Makes Movie posters the screen saver, and fixes TVs that go black instead |
| `scripts/stay-awake.sh on/off` | The TV's "Stay awake" setting (must be off for any screen saver to start) |
| `scripts/screenshot.sh` | Saves a screenshot of the TV to `out/` |

When you're done installing, switch **network debugging off** on the TV. While it's on, other devices on
your network can try to connect to the TV.

## Notes

- **Trailers and posters** come from public sources: Apple's iTunes top-movies feed, tv.apple.com pages,
  Netflix's official YouTube channel and netflix.com title pages. They depend on those pages staying as
  they are, and they're for personal use. Check the services' terms before redistributing anything.
- **Permissions:** storage (wallpapers, backup), calendar (calendar widget), TV listings (HDMI inputs),
  internet (trailers, posters), and an accessibility service that only filters remote-button presses.
  It cannot read the screen.
- **Privacy:** nothing is collected or sent anywhere. Settings stay on the TV.
- Some TVs block network access for apps not installed from the Play Store. The build script installs with
  the Play Store as the recorded installer so the trailers can load.

## Project layout

```
app/src/main/java/com/home/tvlauncher/
  MainActivity.kt      home screen, settings pages
  ControlCenter.kt     drop-down Control Center
  SidePanel.kt         iOS-style settings panel
  Glass.kt             Liquid Glass blur/refraction
  AppleStyle.kt        fonts, squircles, focus glare
  Widgets.kt           clock, calendar and trailer widgets
  Showcase.kt          Movie posters screen saver
  KeyMapService.kt     remote-button remapping
  Targets.kt           opening apps / HDMI inputs
  Prefs.kt             settings and app list
  WallpaperPickerActivity.kt
```

## License

MIT, see [LICENSE](LICENSE). App names and logos shown on screen belong to their owners, and this project
is not affiliated with Apple, Netflix, Google or Skyworth.
