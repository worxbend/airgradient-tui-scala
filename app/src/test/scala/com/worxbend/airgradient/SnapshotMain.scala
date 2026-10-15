package com.worxbend.airgradient

import io.worxbend.tui.core.{Color, Size}
import io.worxbend.tui.terminal.HeadlessBackend
import io.worxbend.tui.testsupport.Pilot
import java.nio.file.{Files, Path}

/** Writes the actual Glyphora cell buffer as SVG and text using deterministic, explicitly synthetic readings. */
object SnapshotMain:
  def main(args: Array[String]): Unit =
    val directory = Path.of(args.headOption.getOrElse("docs/assets"))
    val _         = Files.createDirectories(directory)
    val fixedNow  = 1800000000000L
    val config    = ConfigStore(directory.resolve("unused-config.json"))
    val app       = Dashboard(
      LoadedSettings(Settings(), "", Vector.empty),
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
      capture("dashboard", directory, backend, pilot)
      pilot.press("h").waitForIdle()
      capture("history", directory, backend, pilot)
      pilot.press("esc").press("c").waitForIdle()
      capture("settings", directory, backend, pilot)
    }

  private def capture(name: String, directory: Path, backend: HeadlessBackend, pilot: Pilot): Unit =
    val _                         = Files.writeString(directory.resolve(s"$name.txt"), pilot.screenText + "\n")
    val frame                     = backend.lastDrawn.get
    def css(color: Color): String =
      if color == Color.Reset then "#0b1220" else "#" + String.format(java.util.Locale.ROOT, "%06x", Color.toInt(color))
    def escape(text: String): String =
      text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    val cells = for
      y <- 0 until 30
      x <- 0 until 120
      cell = frame.get(x, y)
      if cell.symbol.nonEmpty
    yield
      val foreground = cell.style.fg.filterNot(_ == Color.Reset).fold("#dbe7ef")(css)
      s"""<rect x="${x * 9 + 18}" y="${y * 18 + 18}" width="9" height="18" fill="${css(
          cell.style.bg.getOrElse(Color.Reset)
        )}"/><text x="${x * 9 + 18}" y="${y * 18 + 32}" fill="$foreground">${escape(cell.symbol)}</text>"""
    val svg =
      s"""<svg xmlns="http://www.w3.org/2000/svg" width="1116" height="576" viewBox="0 0 1116 576"><title>AirGradient TUI / synthetic demo / $name</title><rect width="1116" height="576" rx="12" fill="#0b1220"/><g font-family="DejaVu Sans Mono,monospace" font-size="15">${cells.mkString}</g></svg>"""
    val _ = Files.writeString(directory.resolve(s"$name.svg"), svg)
