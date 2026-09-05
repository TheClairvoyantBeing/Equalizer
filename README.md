# Equalizer

> Android real-time 7-band audio equalizer using AudioRecord/AudioTrack with biquad IIR filters (0–8 kHz).

## Tech Stack
- **Language:** Kotlin, C++
- **Platform:** Android
- **Audio Engine:** AudioRecord / AudioTrack (Native DSP)

## Note
This is the v1 prototype that evolved into [HearWellKotlinS](https://github.com/TheClairvoyantBeing/HearWellKotlinS).

## Installation
```bash
./gradlew assembleDebug
```

## License
This project is licensed under the MIT License — see the [LICENSE](LICENSE) file for details.

## Repository Standardization

This repository was standardized on 2026-09-05 under the ownership of [TheClairvoyantBeing](https://github.com/TheClairvoyantBeing).

### Changes applied

- Added a consistent `.gitignore` hygiene section covering IDE metadata (`.idea/`, `.vscode/`), Python caches, Node dependencies, build output, virtual environments, coverage output, `.env` files, and `reviews.md`.
- Ensured `requirements.txt` exists. Existing dependency declarations were preserved; repositories without detected Python dependencies contain a clearly marked placeholder.
- Standardized the repository license to the GNU Affero General Public License v3 (AGPLv3), with TheClairvoyantBeing as the copyright holder. AGPLv3 requires corresponding source to remain available when covered software is distributed or provided as a network service.
- This section records the repository-level maintenance changes; existing project-specific setup, usage, architecture, and development documentation remains above.
