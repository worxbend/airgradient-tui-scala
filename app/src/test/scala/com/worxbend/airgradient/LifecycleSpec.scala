package com.worxbend.airgradient

import io.worxbend.tui.core.Size
import io.worxbend.tui.terminal.HeadlessBackend
import io.worxbend.tui.testsupport.Pilot
import org.scalatest.funsuite.AnyFunSuite
import java.nio.file.Files
import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import scala.concurrent.duration.DurationInt

final class LifecycleSpec extends AnyFunSuite:
  test("device changes restore read-only history and reject late results from the retired device"):
    val directory = Files.createTempDirectory("ag-session")
    val file      = directory.resolve("config.json")
    Files.writeString(file, """{"server_url":"old.invalid","refresh_interval_secs":3600,"other":{"keep":true}}""")
    val store   = ConfigStore(file)
    val records = FileHistory(directory.resolve("history.jsonl"))
    assert(records.append(Sample(1000, "http://new.invalid/", Demo.snapshot(0))).isRight)
    val blocked  = CountDownLatch(1)
    val entered  = CountDownLatch(1)
    val finished = AtomicBoolean(false)
    val oldCalls = AtomicInteger()
    val client   = new SensorClient:
      def fetch(address: DeviceAddress): Either[String, Snapshot] =
        if address.base.getHost == "old.invalid" && oldCalls.incrementAndGet() > 1 then
          entered.countDown()
          val _ = blocked.await(10, TimeUnit.SECONDS)
          finished.set(true)
          Right(Snapshot(Map(Metric.Co2 -> 9999), false))
        else Right(Demo.snapshot(0))
      override def close(): Unit = blocked.countDown()
    val app     = Dashboard(store.load().toOption.get, store, HistoryStore.readOnly(records), client)
    val backend = HeadlessBackend(Size(120, 30))
    Pilot.using(backend)(app.runWith(backend)) { pilot =>
      pilot.waitUntil("initial frame")(pilot.screenText.contains("780 ppm"))
      pilot.press("r")
      assert(entered.await(5, TimeUnit.SECONDS))
      pilot.press("c").waitUntil("settings frame")(pilot.screenText.contains("Device address"))
      val inputRow = pilot.screenLines.indexWhere(_.contains("Device address")) + 1
      pilot.click(6, inputRow).press("home")
      "old.invalid".foreach(_ => pilot.press("delete"))
      pilot.typeText("new.invalid").press("ctrl+s")
      pilot.waitUntil("saved new session")(pilot.screenText.contains("Settings saved") && app.history.peek.size >= 2)
      assert(app.history.peek.forall(_.source == "http://new.invalid/"))
      assert(app.history.peek.exists(_.atMillis == 1000))
      blocked.countDown()
      pilot.waitUntil("retired request completed")(finished.get())
      pilot.waitForIdle()
      assert(!app.history.peek.exists(_.snapshot.value(Metric.Co2).contains(9999)))
      assert(records.load("http://new.invalid/").toOption.get.map(_.atMillis) == Vector(1000))
      assert(records.load("http://old.invalid/").toOption.get.isEmpty)
      assert(ujson.read(Files.readString(file))("other")("keep").bool)
    }

  test("the poll timer fetches without keyboard input and is owned by the app lifecycle"):
    val store  = ConfigStore(Files.createTempDirectory("ag-timer").resolve("config.json"))
    val closed = AtomicBoolean(false)
    val client = new SensorClient:
      def fetch(address: DeviceAddress): Either[String, Snapshot] = { val _ = address; Right(Demo.snapshot(0)) }
      override def close(): Unit                                  = closed.set(true)
    val app =
      Dashboard(LoadedSettings(Settings("sensor.invalid", 5), "", Vector.empty), store, HistoryStore.Disabled, client)
    val backend = HeadlessBackend(Size(100, 26))
    Pilot.using(backend)(app.runWith(backend)) { pilot =>
      pilot.waitUntil("periodic second sample", 10.seconds)(app.history.peek.size >= 2)
      pilot.press("q")
      assert(pilot.awaitTermination())
    }
    assert(closed.get())
