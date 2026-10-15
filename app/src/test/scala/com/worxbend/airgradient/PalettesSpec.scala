package com.worxbend.airgradient

import io.worxbend.tui.dsl.*
import io.worxbend.tui.terminal.HeadlessBackend
import io.worxbend.tui.testsupport.Pilot
import org.scalatest.funsuite.AnyFunSuite
import java.nio.file.Files

final class PalettesSpec extends AnyFunSuite:
  test("the catalog offers distinct palettes and preserves the existing fallback"):
    assert(Palettes.names.size >= 15)
    assert(Palettes.names.distinct == Palettes.names)
    assert(
      Palettes.names.map(name => (Palettes(name).primary, Palettes(name).accent)).distinct.size == Palettes.names.size
    )
    assert(Palettes("unknown") == Palettes("aurora"))
    assert(Palettes("mono").glyphs == GlyphSupport.Ascii)

  private def luminance(color: Color): Double =
    val value    = Color.toInt(color)
    val channels = Vector((value >> 16) & 255, (value >> 8) & 255, value & 255).map { n =>
      val c = n / 255.0
      if c <= 0.04045 then c / 12.92 else math.pow((c + 0.055) / 1.055, 2.4)
    }
    channels.zip(Vector(0.2126, 0.7152, 0.0722)).map(_ * _).sum

  private def contrast(a: Color, b: Color): Double =
    val low  = math.min(luminance(a), luminance(b))
    val high = math.max(luminance(a), luminance(b))
    (high + 0.05) / (low + 0.05)

  test("new palettes keep semantic text and boundaries readable on both surfaces"):
    Palettes.names.filterNot(Set("aurora", "nord", "mono")).foreach { name =>
      val theme       = Palettes(name)
      val backgrounds = Vector(theme.primary.bg.get, theme.surface.bg.get)
      Vector(theme.primary, theme.accent, theme.muted, theme.success, theme.warning, theme.error).foreach { style =>
        backgrounds.foreach(bg => assert(contrast(style.fg.get, bg) >= 4.5, s"$name: $style against $bg"))
      }
      backgrounds.foreach(bg => assert(contrast(theme.border.fg.get, bg) >= 3.0, s"$name border"))
      assert(contrast(theme.focus.fg.get, theme.focus.bg.get) >= 4.5, s"$name focus")
    }

  test("the full theme cycle wraps, previews without saving, and keeps the demo offline"):
    val directory = Files.createTempDirectory("ag-palettes")
    val path      = directory.resolve("config.json")
    val app       = Dashboard(
      LoadedSettings(Settings(), "", Vector.empty),
      ConfigStore(path),
      HistoryStore.Disabled,
      DemoClient(),
      demo = true
    )
    val backend = HeadlessBackend(Size(120, 30))
    Pilot.using(backend) { app.runWith(backend) } { pilot =>
      pilot.waitUntil("demo frame")(pilot.screenText.contains("US AQI"))
      pilot.press("c").waitForIdle()
      (Palettes.names.tail :+ Palettes.names.head).foreach { name =>
        pilot.press("t").waitForIdle()
        assert(pilot.screenText.contains(s"Theme: $name /"))
        assert(app.theme.name == name)
      }
      assert(!Files.exists(path))
    }

  test("all themes can be selected on the CLI and saved without losing other clients' fields"):
    val path = Files.createTempDirectory("ag-palette-config").resolve("config.json")
    Files.writeString(path, """{"future":{"enabled":true}}""")
    val store = ConfigStore(path)
    Palettes.names.foreach { name =>
      assert(Options.parse(List("--theme", name)).toOption.get.theme.contains(name))
      assert(Main.Help.contains(name))
      val loaded = store.load().toOption.get
      assert(store.save(Settings("sensor.invalid", 30, name), loaded.fingerprint).isRight)
      assert(store.load().toOption.get.settings.theme == name)
      assert(ujson.read(Files.readString(path))("future")("enabled").bool)
    }
