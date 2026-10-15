package com.worxbend.airgradient

import org.scalatest.funsuite.AnyFunSuite

final class TelemetrySpec extends AnyFunSuite:
  private def sample(seconds: Long, value: Option[Double]): Sample =
    Sample(seconds * 1000, "synthetic", Snapshot(value.map(Metric.Co2 -> _).toMap, false))

  test("aggregates ignore missing readings and retain elapsed time and explicit gaps"):
    val result = Telemetry.analyze(Vector(sample(0, Some(600)), sample(30, None), sample(60, Some(900))), 30)
    val trend  = result.metrics(Metric.Co2)
    assert(trend.count == 2)
    assert(trend.minimum.contains(600))
    assert(trend.maximum.contains(900))
    assert(trend.average.contains(750))
    assert(trend.delta.isEmpty)
    assert(trend.bands(Band.Unknown) == 1)
    assert(trend.bands(Band.Good) == 1)
    assert(trend.bands(Band.Moderate) == 1)
    assert(trend.segments == Vector(Vector(0.0 -> 600.0), Vector(1.0 -> 900.0)))
    assert(result.minutes == 1)

  test("missed polls break both the chart and the most-recent delta"):
    val result = Telemetry.analyze(Vector(sample(0, Some(600)), sample(120, Some(900))), 30)
    assert(result.metrics(Metric.Co2).segments.size == 2)
    assert(result.metrics(Metric.Co2).delta.isEmpty)

  test("a contiguous pair has a signed delta, while empty data has no fabricated summaries"):
    val result = Telemetry.analyze(Vector(sample(0, Some(700)), sample(30, Some(600))), 30)
    assert(result.metrics(Metric.Co2).delta.contains(-100))
    val empty = Telemetry.analyze(Vector.empty, 30).metrics(Metric.Co2)
    assert(empty.minimum.isEmpty && empty.maximum.isEmpty && empty.average.isEmpty && empty.delta.isEmpty)
    assert(empty.segments.isEmpty && empty.count == 0)
