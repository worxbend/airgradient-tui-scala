# AirGradient TUI · Scala

**Your air. Your terminal. Built with [Glyphora](https://github.com/worxbend/glyphora).**

[![CI](https://github.com/worxbend/airgradient-tui-scala/actions/workflows/ci.yml/badge.svg)](https://github.com/worxbend/airgradient-tui-scala/actions/workflows/ci.yml)
[![MIT](https://img.shields.io/badge/license-MIT-72e5b1)](LICENSE)

A standalone Scala 3 application for AirGradient monitors on your local network.
Live measurements, history charts, shared desktop settings, and an offline demo.
No cloud account, API token, or GitHub Packages credentials.

![Actual Glyphora dashboard rendered with synthetic demo data](docs/assets/dashboard.svg)

*Captured from the actual Glyphora cell buffer with synthetic data. These are not hardware readings.*

## Run

Install **JDK 25 or newer**, then:

```sh
git clone https://github.com/worxbend/airgradient-tui-scala.git
cd airgradient-tui-scala
./mill app.run --demo
```

Connect your own monitor:

```sh
./mill app.run --url airgradient.local
# Or use the device's IP address; the address above is an example.
```

With no flags, the dashboard reads the shared AirGradient desktop configuration.
Press **c** to edit the address, refresh interval, and theme. **Ctrl+s** saves.
Command-line overrides apply to the current session until you explicitly save them.
Refresh intervals are 5–3600 seconds; the default is 30.

For scripts and non-interactive sessions:

```sh
./mill app.run --url airgradient.local --once
./mill app.run --url airgradient.local --json
./mill app.run --demo --json
./mill app.run --help
```

The device is read directly at `/measures/current`, following the
[AirGradient Local API](https://www.airgradient.com/documentation/kb/where-do-i-access-the-api-documentation-api-token-and-local-api).
The dashboard needs an interactive terminal; `--once` and `--json` work in pipelines.

## What it does

- Live AQI, CO2, particulate measurements, TVOC and NOx indices, temperature, and humidity.
- Responsive cards on wide terminals, a compact table on smaller terminals, and explicit resize guidance below 48×18.
- History charts with actual elapsed time, selectable metrics, and breaks for missing samples or missed polls.
- Aurora, Nord, and monochrome palettes; every status has a word as well as a color.
- Last-known values stay on screen after a failed refresh, marked **STALE** with their age.
- Quiet in-app notifications when readings worsen into elevated bands or recover to Good.
- Session settings and an editor that merges changes into the shared desktop JSON.
- A deterministic demo that performs no network requests, config reads, or history writes.

| Key | Action |
| --- | --- |
| r | Refresh now |
| h | Switch overview / history |
| j / k | Next / previous history metric |
| c | Settings |
| t | Preview the next theme |
| Tab / Shift+Tab | Move between controls |
| Ctrl+s | Save settings |
| ? | Keyboard guide |
| Esc | Close help, return to overview, or quit |
| q | Quit (outside text editing) |

Buttons also support mouse activation. The theme preview is session-only until saved.

![History view rendered with synthetic demo data](docs/assets/history.svg)

## Local data

**Configuration:** `$XDG_CONFIG_HOME/airgradient-desktop/config.json`, falling back to
`~/.config/airgradient-desktop/config.json`. Compatible keys are `server_url`,
`refresh_interval_secs`, and `theme`. Unfamiliar desktop fields survive updates.
An unknown saved theme falls back to Aurora. Invalid refresh settings produce a visible warning.
Malformed JSON is reported and is never silently overwritten.

Config saves use an atomic replacement and a content fingerprint to reject edits made
since the editor opened. Other AirGradient clients do not share a lock, so a very narrow
check-to-rename race remains. This is conflict detection, not a cross-client transaction.

**History:** `$XDG_DATA_HOME/airgradient-tui-scala/history.jsonl`, falling back to
`~/.local/share/airgradient-tui-scala/history.jsonl`. Up to 2000 successful samples are
retained across device sessions; charts filter by the normalized device address.
The Rust CLI's history file is kept separate. Timestamps represent receipt time.
Malformed or truncated records are skipped. History errors stay visible without losing the live reading.

```sh
# Isolated settings and history
./mill app.run --config ./my-config.json --history ./my-history.jsonl

# Read existing history but do not record new samples
./mill app.run --no-history
# Equivalent: AIRGRADIENT_TUI_NO_HISTORY=1
```

The parser follows [airgradient-cli](https://github.com/worxbend/airgradient-cli):
numeric strings and nested aliases, compensated temperature/humidity, the monitor's
particulate compensation switch, bounded sensor values, and explicit AQI before PM2.5-derived AQI.
Missing values are `--` in the UI and `null` in JSON. TVOC/NOx values are indices.
The displayed PM2.5-derived AQI is an estimate from the current sample, not a 24-hour exposure average.
See [architecture and contracts](docs/architecture.md).

## A real Glyphora consumer

```scala
def mvnDeps = Seq(
  mvn"com.worxbend::tui-dsl:0.18.3",
  mvn"com.lihaoyi::ujson:4.4.3",
)
```

The build resolves released artifacts from public Maven Central. There is no sibling
checkout, Git submodule, copied toolkit source, or local publish step.
`tui-test` is a test-only dependency.

The application demonstrates Glyphora signals, owned async task scopes, lifecycle hooks,
responsive layouts, themed metric cards, gauges, sparklines, charts, inputs, buttons,
keyboard bindings, notifications, and headless Pilot tests.

## Build and verify

Pins: Mill **1.1.9**, Scala **3.9.0**, Glyphora **0.18.3**, uJSON **4.4.3**,
ScalaTest **3.2.20**, GraalVM CE **25.0.2**. Mill's build process defaults to Temurin 25.

```sh
./mill app.test
python3 -m pip install --requirement scripts/requirements.txt
./mill mill.scalalib.scalafmt.ScalafmtModule/checkFormatAll __.sources
python3 scripts/verify-dependencies.py
python3 scripts/check-snapshots.py

# JVM distribution
python3 scripts/package.py
java --enable-native-access=ALL-UNNAMED -jar dist/airgradient-tui.jar --demo
python3 scripts/smoke-pty.py --jar dist/airgradient-tui.jar
python3 scripts/smoke-http.py --jar dist/airgradient-tui.jar

# Native executable on the current platform
python3 scripts/package.py --native
./dist/airgradient-tui --demo
python3 scripts/smoke-pty.py --binary dist/airgradient-tui
python3 scripts/smoke-http.py --binary dist/airgradient-tui
```

CI runs the test suite and packaged JAR on JDK 25 and 26, checks published dependency
resolution and screenshot reproducibility, then builds and exercises a Linux amd64
native executable. Runnable artifacts and SHA256 checksums are attached to successful CI runs.
Native builds target GraalVM's compatibility CPU baseline rather than the builder's newer instruction set.
Release tags matching `Main.Version` can publish the same validated artifacts through the release workflow.

The network tests use a local mock HTTP server. Terminal lifecycle tests use real
pseudo-terminals and check that terminal settings are restored. Live AirGradient
hardware has not been tested for this application.

Regenerate screenshots after intentional UI changes:

```sh
python3 scripts/capture-snapshots.py
```

## Credits

Device contracts and interaction references:
[worxbend/airgradient-cli](https://github.com/worxbend/airgradient-cli).
The synthetic firmware fixtures are adapted from that MIT-licensed project.
UI and testing: [Glyphora](https://github.com/worxbend/glyphora).
JSON parsing: [uJSON](https://com-lihaoyi.github.io/upickle/).

[MIT license](LICENSE). An independent community project; not an official AirGradient product.
