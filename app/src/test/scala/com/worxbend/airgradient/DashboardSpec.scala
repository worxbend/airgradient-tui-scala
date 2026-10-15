package com.worxbend.airgradient

import io.worxbend.tui.core.Size
import io.worxbend.tui.terminal.HeadlessBackend
import io.worxbend.tui.testsupport.Pilot
import org.scalatest.funsuite.AnyFunSuite
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

final class DashboardSpec extends AnyFunSuite:
  private def withApp(script: Vector[Either[String, Snapshot]], size: Size = Size(120, 30))(
      check: (Dashboard, Pilot, HeadlessBackend, AtomicInteger) => Unit
  ): Unit =
    val config = ConfigStore(Files.createTempDirectory("ag-ui").resolve("config.json"))
    val closed = AtomicInteger()
    val index  = AtomicInteger()
    val client = new SensorClient:
      def fetch(address: DeviceAddress): Either[String, Snapshot] =
        val _ = address
        script(math.min(index.getAndIncrement(), script.size - 1))
      override def close(): Unit = { val _ = closed.incrementAndGet() }
    val app = Dashboard(
      LoadedSettings(Settings("sensor.invalid", 3600), "", Vector.empty),
      config,
      HistoryStore.Disabled,
      client
    )
    val backend = HeadlessBackend(size)
    Pilot.using(backend) { app.runWith(backend) } { pilot =>
      pilot.waitUntil("first successful frame")(pilot.screenText.contains("US AQI"))
      check(app, pilot, backend, closed)
    }
    assert(closed.get() == 1)

  test("the overview renders real values, units and status words"):
    withApp(Vector(Right(Demo.snapshot(0)))) { (_, pilot, _, _) =>
      val screen = pilot.screenText
      assert(screen.contains("780 ppm"))
      assert(screen.contains("6.8 ug/m3"))
      assert(screen.contains("83 index"))
      assert(screen.contains("Good"))
      assert(screen.contains("LIVE"))
      pilot.press("q")
      assert(pilot.awaitTermination())
    }

  test("a failed refresh keeps last-known readings visibly stale, then recovers"):
    withApp(Vector(Right(Demo.snapshot(0)), Left("sensor offline"), Right(Demo.snapshot(12)))) { (app, pilot, _, _) =>
      pilot.press("r").waitUntil("failed request frame")(pilot.screenText.contains("STALE"))
      assert(pilot.screenText.contains("STALE"))
      assert(pilot.screenText.contains("sensor offline"))
      assert(pilot.screenText.contains("780 ppm"))
      assert(app.history.peek.size == 1)
      pilot.press("r").waitUntil("recovery frame")(pilot.screenText.contains("LIVE") && app.history.peek.size == 2)
      assert(app.problem.peek.isEmpty)
      assert(pilot.screenText.contains("LIVE"))
    }

  test("history, settings, theme and help are keyboard accessible"):
    withApp(Vector(Right(Demo.snapshot(0)))) { (_, pilot, _, _) =>
      pilot.press("h").waitForIdle()
      assert(pilot.screenText.contains("HISTORY / CO2"))
      pilot.press("j").waitForIdle()
      assert(pilot.screenText.contains("HISTORY / PM2.5"))
      pilot.press("esc").press("c").waitForIdle()
      assert(pilot.screenText.contains("SETTINGS"))
      assert(pilot.screenText.contains("Refresh interval"))
      pilot.press("esc").press("t").press("?").waitForIdle()
      assert(pilot.screenText.contains("Keyboard guide"))
      pilot.press("esc").waitForIdle()
      assert(!pilot.screenText.contains("Keyboard guide"))
    }

  test("compact terminals retain all readings and resize back to cards"):
    withApp(Vector(Right(Demo.snapshot(0))), Size(60, 24)) { (_, pilot, _, _) =>
      assert(pilot.screenText.contains("Particles >0.3"))
      assert(pilot.screenText.contains("Humidity"))
      pilot.resize(120, 30).waitForIdle()
      assert(pilot.screenText.contains("CO2 / recent samples"))
      pilot.resize(30, 12).waitForIdle()
      assert(pilot.screenText.contains("Please resize"))
    }

  test("missing sensors display unavailable, not a fresh zero"):
    withApp(Vector(Right(Snapshot(Map(Metric.Co2 -> 700), false)))) { (_, pilot, _, _) =>
      assert(pilot.screenText.contains("-- US AQI"))
      assert(pilot.screenText.contains("not reported"))
      assert(pilot.screenText.contains("Unknown"))
    }

  test("every reading remains visible from a wide workstation to the minimum supported terminal"):
    withApp(Vector(Right(Demo.snapshot(0)))) { (_, pilot, _, _) =>
      Vector((160, 44), (120, 30), (90, 28), (80, 24), (60, 24), (48, 18)).foreach { (width, height) =>
        pilot.resize(width, height).waitForIdle()
        Metric.values.foreach(metric =>
          assert(pilot.screenText.contains(metric.label), s"${metric.label} at $width x $height")
        )
      }
    }

  test("command search selects a named theme and opens a metric's history"):
    withApp(Vector(Right(Demo.snapshot(0)))) { (app, pilot, _, _) =>
      pilot.press("ctrl+p").typeText("paper").waitForIdle()
      assert(pilot.screenText.contains("Use theme paper"))
      pilot.press("enter").waitForIdle()
      assert(app.theme.name == "paper")
      pilot.press("ctrl+p").typeText("Inspect PM2.5 history").press("enter").waitForIdle()
      assert(pilot.screenText.contains("HISTORY / PM2.5"))
    }

  test("the theme dropdown responds to mouse and keyboard, and Escape closes it before leaving settings"):
    withApp(Vector(Right(Demo.snapshot(0)))) { (app, pilot, _, _) =>
      pilot.press("c").waitForIdle()
      val lines = pilot.screenLines
      val y     = lines.indexWhere(_.contains("▸"))
      assert(y >= 0)
      pilot.click(lines(y).indexOf("aurora"), y).waitForIdle()
      assert(pilot.screenText.contains("grafana"))
      pilot.press("esc").waitForIdle()
      assert(pilot.screenText.contains("SETTINGS"))
      pilot.press("enter").press("down").press("enter").waitForIdle()
      assert(app.theme.name == "nord")
    }
