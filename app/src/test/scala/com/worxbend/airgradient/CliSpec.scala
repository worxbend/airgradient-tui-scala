package com.worxbend.airgradient

import org.scalatest.funsuite.AnyFunSuite
import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.file.Files

final class CliSpec extends AnyFunSuite:
  test("demo JSON works offline and does not read or write a broken config"):
    val dir     = Files.createTempDirectory("ag-cli")
    val config  = dir.resolve("config.json")
    val history = dir.resolve("history.jsonl")
    Files.writeString(config, "broken on purpose")
    val out  = ByteArrayOutputStream()
    val err  = ByteArrayOutputStream()
    val code = Main.run(
      List("--demo", "--json", "--config", config.toString, "--history", history.toString),
      PrintStream(out),
      PrintStream(err)
    )
    assert(code == 0)
    val json = ujson.read(out.toString(java.nio.charset.StandardCharsets.UTF_8))
    assert(json("source").str == "demo")
    assert(json("measurements")("co2").num > 0)
    assert(json("outdoor")("location")("name").str == "Demo City (synthetic)")
    assert(json("outdoor")("current")("us_aqi").num == 38)
    assert(json("outdoor")("current")("olive_pollen").num == 0)
    assert(json("outdoor")("advice")("headline").str.nonEmpty)
    assert(json("outdoor")("heads_up").arr.map(_.str).contains("UV up to 6.4 today"))
    assert(Files.readString(config) == "broken on purpose")
    assert(!Files.exists(history))
    assert(err.size() == 0)

  test("invalid options return usage errors before any network request"):
    assert(Options.parse(List("--refresh", "4")).isLeft)
    assert(Options.parse(List("--refresh", "3601")).isLeft)
    assert(Options.parse(List("--theme", "unknown")).isLeft)
    assert(Options.parse(List("--url", "http://user:secret@sensor.invalid")).isLeft)
    assert(Options.parse(List("--config")).isLeft)
    assert(Options.parse(List("--unknown")).isLeft)
    assert(Options.parse(List("--refresh", "5")).toOption.get.refresh.contains(5))

  test("--location accepts a place name or valid coordinates and rejects impossible coordinates"):
    assert(Options.parse(List("--location", "Kyiv")).toOption.get.location.contains("Kyiv"))
    assert(Options.parse(List("--location", "10,20")).toOption.get.location.contains("10,20"))
    assert(Options.parse(List("--location", "95,20")).left.toOption.get.contains("latitude"))
    assert(Options.parse(List("--location", " ")).isLeft)
    assert(Main.Help.contains("--location"))

  test("the one-shot table adds outdoor readings and advice for the demo place"):
    val out  = ByteArrayOutputStream()
    val err  = ByteArrayOutputStream()
    val code = Main.run(List("--demo", "--once"), PrintStream(out), PrintStream(err))
    assert(code == 0)
    val text = out.toString(java.nio.charset.StandardCharsets.UTF_8)
    assert(text.contains("Outdoor / Demo City (synthetic) / Partly cloudy"))
    assert(text.contains("Window: "))
    assert(text.linesIterator.exists(line => line.startsWith("Wind") && line.trim.endsWith("SW")))
    assert(err.size() == 0)
