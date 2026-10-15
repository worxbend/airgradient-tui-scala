package com.worxbend.airgradient

/** Presentation aggregates use only successful, reported observations. Missing values never become zeroes. */
final case class MetricTrend(
    values: Vector[Option[Double]],
    segments: Vector[Vector[(Double, Double)]],
    minimum: Option[Double],
    maximum: Option[Double],
    average: Option[Double],
    delta: Option[Double],
    bands: Map[Band, Int]
):
  val count: Int = values.count(_.nonEmpty)

final case class Telemetry(minutes: Double, metrics: Map[Metric, MetricTrend])

object Telemetry:
  def analyze(samples: Vector[Sample], refreshSeconds: Int): Telemetry =
    val start     = samples.headOption.fold(0L)(_.atMillis)
    val gapMillis = refreshSeconds * 1500L
    val metrics   = Metric.values.map { metric =>
      val values   = samples.map(_.snapshot.value(metric))
      val valid    = values.flatten
      val segments = samples
        .zip(values)
        .foldLeft(Vector(Vector.empty[(Double, Double)])) {
          case (groups, (_, None))             => groups :+ Vector.empty
          case (groups, (sample, Some(value))) =>
            val point = (sample.atMillis - start) / 60000.0 -> value
            if groups.last.lastOption.exists(p => point._1 - p._1 > gapMillis / 60000.0) then groups :+ Vector(point)
            else groups.init :+ (groups.last :+ point)
        }
        .filter(_.nonEmpty)
      val delta = samples.takeRight(2) match
        case Vector(previous, current) if current.atMillis - previous.atMillis <= gapMillis =>
          for a <- previous.snapshot.value(metric); b <- current.snapshot.value(metric) yield b - a
        case _ => None
      metric -> MetricTrend(
        values,
        segments,
        valid.minOption,
        valid.maxOption,
        Option.when(valid.nonEmpty)(valid.sum / valid.size),
        delta,
        values.groupMapReduce(metric.classify)(_ => 1)(_ + _)
      )
    }.toMap
    Telemetry(samples.lastOption.fold(0.0)(s => (s.atMillis - start) / 60000.0), metrics)
