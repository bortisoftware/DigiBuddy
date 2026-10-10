[Español](README.md) | [English](README.en.md)

![DigiBuddy: dual-screen Digimon World](docs/banner.png)

<p align="center">
  <a href="#installation">Installation</a> ·
  <a href="#features">Features</a> ·
  <a href="#compatible-games">Compatible games</a> ·
  <a href="https://github.com/bortisoftware/DigiBuddy/issues">Report an issue</a>
</p>

DigiBuddy is a **PlayStation Digimon World emulator** for dual-screen Android handhelds. Play on the upper screen and use the lower touchscreen to check your partner, bag, map, city prosperity and evolutions in real time.

[![▶ Watch the DigiBuddy presentation on YouTube (Spanish)](docs/video-preview.png)](https://www.youtube.com/watch?v=glKcUstdL5Q)

## Screenshots

Real screenshots from an Anbernic RG DS. The interface may vary between versions.

| Upper screen · game | Lower screen · partner |
| --- | --- |
| ![Digimon World on the upper screen](docs/screenshots/game.png) | ![Agumon's companion panel and stats](docs/screenshots/companion.png) |

<details>
<summary>Startup and save selection</summary>

![DigiBuddy startup screen](docs/screenshots/startup.png)

</details>

## Devices

| Device | Status |
| --- | --- |
| AYN Thor | Tested |
| Anbernic RG DS | Tested, including saving and loading states |

Requires **Android 8.0 or later and ARM64**. Other dual-screen devices have not been tested.

## Installation

Download the APK from [Releases](https://github.com/bortisoftware/DigiBuddy/releases/latest).

1. Install the APK on your handheld. If Android asks, allow installation from your browser or file manager.
2. Open DigiBuddy and choose Español or English. Your system language is preselected. The setup wizard then asks for your PlayStation BIOS and game.
3. Start a new game or load a save from the original in-game menu.

**No BIOS or games are included. Each user must provide their own files.**

## Languages

The interface, item names and effects, recruitment hints and messages are available in **English and Spanish**. Change the language under **Settings → App language**: Español or English. The system language only preselects an option on first launch. Your choice is remembered, and changing it does not restart the game.

The game's language depends on your ROM. This setting translates DigiBuddy's companion interface.

## Features

### Companion · lower screen

- **Partner:** stats, HP, MP and your Digimon's care needs.
- **Bag:** items and icons. Tap an item to view its quantity, effect and availability before confirming use.
- **Map:** your position, exits, toilets, collectible items and enemies with front-facing portraits. Tap an enemy for stats and estimated difficulty. When portraits overlap, choose which Digimon to inspect. Difficulty is an estimate. Detected recruitable Digimon have a “!” badge; tap them to open their hint.
- **Prosperity:** city progress and hints for nearby Digimon you have yet to recruit. Tap a portrait for its location, clue and known requirements.
- **Evolution:** compact paths. Tap one to see met and pending requirements. Trigger an eligible evolution after confirmation. Undoing it returns to the previous form with the original animation without rewinding your game.
- **Cheats:** eight helpers, each requiring confirmation. Undoing a cheat restores its backup and discards progress made after that backup.

### Game · upper screen

- Physical controls and sound.
- **Settings → Controls → Remap buttons:** tap a button on the PlayStation controller diagram, then press the physical button or direction you want to assign. Bindings are saved per controller. You can cancel or reset defaults. The game pauses during configuration.
- Original **4:3** aspect ratio or stretched full screen.
- Up to **8× internal resolution**, filters and PGXP with SwanStation OpenGL ES.
- Software rendering and PCSX ReARMed alternatives.

Graphics enhancements mainly affect 3D models. Backgrounds and videos retain their original detail. Performance depends on your device and settings.

## Updates

DigiBuddy checks the official GitHub releases for a new APK. Tap Update to download and verify it inside the app, then open Android's installer. The first time, allow installation from DigiBuddy. Save your progress before installing. You can also check manually in Settings or disable automatic checks. The game works offline.

Settings uses collapsible groups: App language, Saves, Graphics and sound, Controls, Screens, Files and Updates. Play and Settings stay accessible on every tab, including the map.

## Saves

- **Continue latest session:** restores the latest emulator state, if available.
- **Load in-game save:** starts the game normally so you can load your memory card from the original menu.
- **New game:** starts from the beginning without deleting your card or previous states.

Save, load, import and export states under Settings → Saves. You can also export your memory card. States show their date, and loading one during a session requires confirmation. Imported states must match the game and emulator core; they are kept as additional copies. Remember to save inside the game if you want to load from its original menu.

## Compatible games

The panel recognizes **Digimon World USA (SLUS-01032)** and **the Japanese edition (SLPS-01797) with the reference Spanish translation patch**. Memory fingerprints verify the version before reading data or performing actions. Other editions and patches may run in the emulator, but the companion panel needs a compatible profile.

Item use confirms with ✕ in the USA edition and ○ in the Japanese edition. Saves and states are separated by disc image. Changing editions does not automatically transfer your progress.

<details>
<summary>Identify the reference Spanish-patched edition</summary>

Disc image SHA-256:

```text
b5ff9ed251bced70c20eb911eab8ac3e5b77d7a140983d3b5dc31decac4837af
```

</details>

## Development and credits

Build instructions are in [DESIGN.md](DESIGN.md) (Spanish).

**SwanStation and PCSX ReARMed:** emulator cores. [Licenses](THIRD_PARTY_NOTICES.md) · [Provenance](CORE_PROVENANCE.md).

Original code is licensed under [GPL-3.0-or-later](LICENSE). A fan project with no affiliation with Bandai Namco, AYN or Anbernic.

## AI tooling note

This project may use AI-assisted development tools, such as **Codex**, to help with code generation, refactoring and documentation. All changes are still reviewed by the maintainer and validated with the project's existing checks before release.
