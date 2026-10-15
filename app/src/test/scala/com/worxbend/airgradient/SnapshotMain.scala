package com.worxbend.airgradient

import io.worxbend.tui.core.{Color, Size, Style}
import io.worxbend.tui.terminal.HeadlessBackend
import io.worxbend.tui.testsupport.Pilot
import io.worxbend.tui.dsl.Theme
import java.nio.file.{Files, Path}

/** Writes the actual Glyphora cell buffer as SVG and text using deterministic, explicitly synthetic readings. */
object SnapshotMain:
  def main(args: Array[String]): Unit =
    val directory = Path.of(args.headOption.getOrElse("docs/assets"))
    val _         = Files.createDirectories(directory)
    val _         = Files.createDirectories(directory.resolve("themes"))
    Palettes.names.foreach(name => render(name, directory))
    renderDisconnected(directory, retainedHistory = true)
    renderDisconnected(directory, retainedHistory = false)
    val _ = Files.writeString(directory.resolve("themes.json"), ujson.write(Palettes.names, indent = 2) + "\n")

  private def renderDisconnected(directory: Path, retainedHistory: Boolean): Unit =
    val fixedNow = 1800000000000L
    val client   = new SensorClient:
      def fetch(address: DeviceAddress): Either[String, Snapshot] =
        val _ = address
        Left("Synthetic device offline / retry with r")
    val app = Dashboard(
      LoadedSettings(Settings(theme = "grafana"), "", Vector.empty),
      ConfigStore(directory.resolve("unused-config.json")),
      HistoryStore.Disabled,
      client,
      demo = true,
      if retainedHistory then Demo.history(fixedNow - 60000) else Vector.empty,
      () => fixedNow
    )
    val backend = HeadlessBackend(Size(120, 30))
    Pilot.using(backend) { app.runWith(backend) } { pilot =>
      pilot.waitUntil("synthetic connection failure")(app.problem.peek.nonEmpty)
      capture(if retainedHistory then "state-stale" else "state-offline", directory, backend, pilot, app.theme)
    }

  private def render(themeName: String, directory: Path): Unit =
    val fixedNow = 1800000000000L
    val config   = ConfigStore(directory.resolve("unused-config.json"))
    val app      = Dashboard(
      LoadedSettings(Settings(theme = themeName), "", Vector.empty),
      config,
      HistoryStore.Disabled,
      DemoClient(),
      demo = true,
      Demo.history(fixedNow),
      () => fixedNow
    )
    val backend = HeadlessBackend(Size(120, 30))
    Pilot.using(backend) { app.runWith(backend) } { pilot =>
      pilot.waitUntil("demo frame")(pilot.screenText.contains("61 samples"))
      capture(s"themes/$themeName", directory, backend, pilot, app.theme)
      if themeName == "aurora" then
        capture("dashboard", directory, backend, pilot, app.theme)
        pilot.press("h").waitForIdle()
        capture("history", directory, backend, pilot, app.theme)
        pilot.press("esc").press("c").waitForIdle()
        capture("settings", directory, backend, pilot, app.theme)
        val lines = pilot.screenLines
        val y     = lines.indexWhere(_.contains("▸"))
        pilot.click(lines(y).indexOf("aurora"), y).waitForIdle()
        capture("theme-picker", directory, backend, pilot, app.theme)
        pilot.press("esc").press("esc").press("?").waitForIdle()
        capture("help", directory, backend, pilot, app.theme)
        pilot.press("esc").press("ctrl+p").typeText("paper").waitForIdle()
        capture("commands", directory, backend, pilot, app.theme)
      if themeName == "grafana" then
        pilot.resize(160, 44).waitForIdle()
        capture("dashboard-wide", directory, backend, pilot, app.theme)
        pilot.resize(60, 24).waitForIdle()
        capture("dashboard-compact", directory, backend, pilot, app.theme)
        pilot.resize(48, 18).waitForIdle()
        capture("dashboard-minimal", directory, backend, pilot, app.theme)
    }

  private def capture(name: String, directory: Path, backend: HeadlessBackend, pilot: Pilot, theme: Theme): Unit =
    val _                            = Files.writeString(directory.resolve(s"$name.txt"), pilot.screenText + "\n")
    val frame                        = backend.lastDrawn.get
    val width                        = frame.area.width * 9 + 36
    val height                       = frame.area.height * 18 + 36
    def css(color: Color): String    = "#" + String.format(java.util.Locale.ROOT, "%06x", Color.toInt(color))
    val background                   = theme.primary.bg.filterNot(_ == Color.Reset).fold("#000000")(css)
    val defaultForeground            = theme.primary.fg.filterNot(_ == Color.Reset).fold("#ffffff")(css)
    def escape(text: String): String =
      text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    val cells = for
      y <- 0 until frame.area.height
      x <- 0 until frame.area.width
      cell = frame.get(x, y)
      if cell.symbol.nonEmpty
    yield
      val fg         = cell.style.fg.filterNot(_ == Color.Reset).fold(defaultForeground)(css)
      val bg         = cell.style.bg.filterNot(_ == Color.Reset).fold(background)(css)
      val reversed   = (cell.style.modifiers & Style.Default.reverse.modifiers) == Style.Default.reverse.modifiers
      val foreground = if reversed then bg else fg
      val fill       = if reversed then fg else bg
      val weight = if (cell.style.modifiers & Style.Default.bold.modifiers) == Style.Default.bold.modifiers then "bold"
      else "normal"
      s"""<rect x="${x * 9 + 18}" y="${y * 18 + 18}" width="9" height="18" fill="$fill"/><text x="${x * 9 + 18}" y="${y * 18 + 32}" fill="$foreground" font-weight="$weight">${escape(
          cell.symbol
        )}</text>"""
    val svg =
      s"""<svg xmlns="http://www.w3.org/2000/svg" width="$width" height="$height" viewBox="0 0 $width $height"><title>AirGradient TUI / synthetic demo / $name</title><rect width="$width" height="$height" rx="12" fill="$background"/><g font-family="DejaVu Sans Mono,monospace" font-size="15">${cells.mkString}</g></svg>"""
    val _ = Files.writeString(directory.resolve(s"$name.svg"), svg)
