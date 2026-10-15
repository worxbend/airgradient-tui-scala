package com.worxbend.airgradient

import org.scalatest.funsuite.AnyFunSuite
import java.nio.file.Files

final class PersistenceSpec extends AnyFunSuite:
  test("saving shared settings preserves unfamiliar desktop fields"):
    val file = Files.createTempDirectory("ag-config").resolve("config.json")
    Files.writeString(
      file,
      """{"server_url":"old.local","theme":"nord","notifications_enabled":false,"future":{"token":17}}"""
    )
    val store  = ConfigStore(file)
    val loaded = store.load().toOption.get
    assert(store.save(Settings("sensor.invalid", 10, "mono"), loaded.fingerprint).isRight)
    val result = ujson.read(Files.readString(file))
    assert(result("server_url").str == "http://sensor.invalid/")
    assert(result("future")("token").num == 17)
    assert(result("notifications_enabled") == ujson.Bool(false))

  test("a concurrent edit blocks saving and leaves the newer file intact"):
    val file = Files.createTempDirectory("ag-conflict").resolve("config.json")
    Files.writeString(file, """{"theme":"nord"}""")
    val store       = ConfigStore(file)
    val original    = store.load().toOption.get
    val replacement = """{"theme":"mono","future":true}"""
    Files.writeString(file, replacement)
    assert(store.save(Settings("sensor.invalid"), original.fingerprint).isLeft)
    assert(Files.readString(file) == replacement)

  test("malformed configuration is never overwritten and refresh repairs are visible"):
    val file = Files.createTempDirectory("ag-invalid").resolve("config.json")
    Files.writeString(file, "{broken")
    val store = ConfigStore(file)
    assert(store.load().isLeft)
    assert(store.save(Settings(), "").isLeft)
    assert(Files.readString(file) == "{broken")
    Files.writeString(file, """{"refresh_interval_secs":0}""")
    val loaded = store.load().toOption.get
    assert(loaded.settings.refreshSeconds == 30)
    assert(loaded.warnings.nonEmpty)

  test("history survives restart, skips truncated rows and separates device sessions"):
    val file    = Files.createTempDirectory("ag-history").resolve("history.jsonl")
    val history = FileHistory(file)
    assert(history.append(Sample(1000, "one", Demo.snapshot(0))).isRight)
    assert(history.append(Sample(2000, "two", Demo.snapshot(1))).isRight)
    Files.writeString(file, Files.readString(file) + "{truncated")
    assert(FileHistory(file).load("one").toOption.get.map(_.atMillis) == Vector(1000))
    assert(FileHistory(file).load("two").toOption.get.map(_.atMillis) == Vector(2000))

  test("stored history stays bounded at two thousand samples"):
    val file  = Files.createTempDirectory("ag-retention").resolve("history.jsonl")
    val lines = (1 to 2000)
      .map(n =>
        ujson
          .Obj("at_millis" -> n, "source" -> "test", "snapshot" -> Demo.snapshot(0).json, "aqi_derived" -> true)
          .render()
      )
      .mkString("", "\n", "\n")
    Files.writeString(file, lines)
    val history = FileHistory(file)
    assert(history.append(Sample(2001, "test", Demo.snapshot(0))).isRight)
    val loaded = history.load("test").toOption.get
    assert(loaded.size == 2000)
    assert(loaded.head.atMillis == 2 && loaded.last.atMillis == 2001)
