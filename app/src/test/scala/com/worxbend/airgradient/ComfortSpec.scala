package com.worxbend.airgradient

import org.scalatest.funsuite.AnyFunSuite

final class ComfortSpec extends AnyFunSuite:
  private def near(actual: Option[Double], expected: Double, tolerance: Double): Boolean =
    actual.exists(n => math.abs(n - expected) <= tolerance)

  private def indoor(co2: Double, pm25: Double = 5): Snapshot =
    Snapshot(Map(Metric.Co2 -> co2, Metric.Pm25 -> pm25, Metric.Temperature -> 21, Metric.Humidity -> 45), false)

  private def outside(aqi: Double, pm25: Double = 5, temperature: Double = 15): OutdoorReport =
    DemoOutdoor
      .report(DemoOutdoor.Place, 0)
      .copy(values =
        Map(OutdoorMetric.UsAqi -> aqi, OutdoorMetric.Pm25 -> pm25, OutdoorMetric.Temperature -> temperature)
      )

  test("derived humidity metrics match published reference values"):
    assert(near(Comfort.dewPoint(20, 50), 9.3, 0.1))
    assert(near(Comfort.dewPoint(30, 80), 26.2, 0.2))
    assert(Comfort.dewPoint(20, 0).isEmpty)
    assert(near(Comfort.absoluteHumidity(20, 50), 8.65, 0.05))
    assert(near(Comfort.heatIndex(32, 70), 40.5, 1.0))
    assert(Comfort.heatIndex(25, 50).isEmpty, "the regression is undefined below 26.7 C")

  test("comfort verdicts name each problem and keep missing readings unknown"):
    assert(Comfort.verdict(indoor(600)).label == "Comfortable")
    val muggy = Snapshot(Map(Metric.Temperature -> 28.0, Metric.Humidity -> 72.0), false)
    assert(Comfort.verdict(muggy).label == "Too warm, too humid")
    assert(Comfort.verdict(Snapshot(Map.empty, false)).band == Band.Unknown)

  test("window advice weighs indoor CO2 against outdoor air"):
    assert(Advice.ventilation(None, None).band == Band.Unknown)
    assert(Advice.ventilation(Some(indoor(600)), Some(outside(30))).headline == "No action needed")
    assert(Advice.ventilation(Some(indoor(1200)), Some(outside(30))).headline == "Open a window")
    assert(Advice.ventilation(Some(indoor(1200)), Some(outside(30, temperature = -4))).headline == "Burst-ventilate")
    assert(Advice.ventilation(Some(indoor(1200)), Some(outside(160))).headline == "Ventilate briefly")
    assert(Advice.ventilation(Some(indoor(600)), Some(outside(160))).headline == "Keep windows closed")
    assert(Advice.ventilation(Some(indoor(600, pm25 = 4)), Some(outside(60, pm25 = 20))).headline == "Keep windows closed")
    assert(Advice.ventilation(Some(indoor(900)), Some(outside(30))).headline == "Ventilate soon")
    assert(Advice.ventilation(Some(indoor(1200)), None).detail.contains("no outdoor data"))
    assert(Advice.ventilation(Some(indoor(1200)), Some(outside(30))).urgent)
    assert(!Advice.ventilation(Some(indoor(600)), Some(outside(160))).urgent)

  test("the best hour prefers clean air at a mild temperature, earliest first"):
    val hours = Vector(
      ForecastHour("2030-01-01T01:00", Some(-2), None, Some(10), None),
      ForecastHour("2030-01-01T02:00", Some(12), None, Some(30), None),
      ForecastHour("2030-01-01T03:00", Some(13), None, Some(30), None),
      ForecastHour("2030-01-01T04:00", Some(14), None, None, None)
    )
    assert(Advice.bestHour(hours).map(_.label).contains("02:00"))
    assert(Advice.bestHour(hours.take(1)).map(_.label).contains("01:00"))
    assert(Advice.bestHour(hours.drop(3)).isEmpty)

  test("CO2 trend, exceedance share, and mold risk read only reported samples"):
    def at(minutes: Int, co2: Option[Double], humidity: Double = 50) =
      Sample(minutes * 60000L, "test", Snapshot(co2.map(Metric.Co2 -> _).toMap + (Metric.Humidity -> humidity), false))
    val samples = Vector(at(0, Some(600)), at(5, None), at(10, Some(700), 75))
    assert(near(Comfort.co2PerHour(samples), 600, 0.001))
    assert(Comfort.co2PerHour(samples.take(1)).isEmpty)
    assert(Comfort.shareAbove(samples, Metric.Co2, 650).contains(0.5))
    assert(Comfort.moldRisk(samples).band == Band.Unhealthy)
    assert(Comfort.moldRisk(Vector.empty).band == Band.Unknown)
    assert(Comfort.condensation(Some(10), Some(4)).band == Band.Elevated)
    assert(Comfort.condensation(Some(10), Some(20)).band == Band.Good)
    assert(Comfort.condensation(Some(10), None).detail.contains("outdoor location"))
    assert(Comfort.moldRisk(samples).detail == "33% of samples at 70 % RH+")
