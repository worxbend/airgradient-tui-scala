package com.worxbend.airgradient

import java.util.Locale
import scala.util.control.NonFatal

enum Band(val label: String):
  case Unknown       extends Band("Unknown")
  case Good          extends Band("Good")
  case Moderate      extends Band("Moderate")
  case Elevated      extends Band("Elevated")
  case Unhealthy     extends Band("Unhealthy")
  case VeryUnhealthy extends Band("Very unhealthy")

enum Metric(val key: String, val label: String, val unit: String, val ceiling: Double, val decimals: Int):
  case Aqi         extends Metric("aqi", "AQI", "US AQI", 500, 0)
  case Co2         extends Metric("co2", "CO2", "ppm", 2000, 0)
  case Pm25        extends Metric("pm25", "PM2.5", "ug/m3", 125.4, 1)
  case Pm1         extends Metric("pm1", "PM1.0", "ug/m3", 125.4, 1)
  case Pm10        extends Metric("pm10", "PM10", "ug/m3", 354, 1)
  case Particles   extends Metric("pm03_count", "Particles >0.3", "#/dL", 5000, 0)
  case Tvoc        extends Metric("tvoc", "TVOC", "index", 400, 0)
  case Nox         extends Metric("nox", "NOx", "index", 100, 0)
  case Temperature extends Metric("temperature_c", "Temperature", "C", 40, 1)
  case Humidity    extends Metric("humidity", "Humidity", "%", 100, 1)

  def format(value: Option[Double]): String = value.fold("--")(n => String.format(Locale.ROOT, s"%.${decimals}f", n))

  def classify(value: Option[Double]): Band = value match
    case None    => Band.Unknown
    case Some(n) =>
      val limits = this match
        case Aqi                    => Vector(50.0, 100.0, 150.0, 200.0)
        case Co2                    => Vector(800.0, 1000.0, 1500.0, 2000.0)
        case Pm25 | Pm1             => Vector(9.0, 35.4, 55.4, 125.4)
        case Pm10                   => Vector(54.0, 154.0, 254.0, 354.0)
        case Particles              => Vector(500.0, 1000.0, 2000.0, 5000.0)
        case Tvoc                   => Vector(100.0, 200.0, 300.0, 400.0)
        case Nox                    => Vector(1.0, 20.0, 50.0, 100.0)
        case Temperature | Humidity => Vector.empty
      this match
        case Temperature =>
          if n >= 18 && n <= 26 then Band.Good
          else if n >= 16 && n <= 30 then Band.Moderate
          else Band.Elevated
        case Humidity =>
          if n >= 30 && n <= 60 then Band.Good
          else if n >= 20 && n <= 80 then Band.Moderate
          else Band.Unhealthy
        case _ =>
          val rank = limits.indexWhere(n <= _)
          if rank < 0 then Band.VeryUnhealthy else Band.values(rank + 1)

final case class Snapshot(values: Map[Metric, Double], aqiDerived: Boolean):
  def value(metric: Metric): Option[Double] = values.get(metric)
  def worst: Band                           = Metric.values.map(m => m.classify(value(m))).maxBy(_.ordinal)
  def json: ujson.Value                     =
    ujson.Obj.from(Metric.values.map(m => m.key -> value(m).fold[ujson.Value](ujson.Null)(ujson.Num(_))))

final case class Sample(atMillis: Long, source: String, snapshot: Snapshot)

object Measurements:
  private def normalized(key: String): String = key.filter(c => c.isLetterOrDigit && c <= 127).toLowerCase(Locale.ROOT)
  private def digitRuns(key: String): Vector[String]           = "[0-9]+".r.findAllIn(key).toVector
  private def matches(actual: String, wanted: String): Boolean =
    normalized(actual) == normalized(wanted) && digitRuns(actual) == digitRuns(wanted)

  // Direct fields win over nested fields, then aliases are tried in the CLI's documented order.
  private def find[A](root: ujson.Value, aliases: Seq[String])(convert: ujson.Value => Option[A]): Option[A] =
    def loop(node: ujson.Value, depth: Int): Option[A] =
      if depth > 64 then None
      else
        node match
          case obj: ujson.Obj =>
            aliases.iterator
              .flatMap(alias =>
                obj.value.iterator
                  .filter((key, _) => matches(key, alias))
                  .flatMap((_, value) => convert(value))
              )
              .nextOption()
              .orElse(
                obj.value.toVector.sortBy(_._1).iterator.flatMap((_, child) => loop(child, depth + 1)).nextOption()
              )
          case arr: ujson.Arr => arr.value.iterator.flatMap(child => loop(child, depth + 1)).nextOption()
          case _              => None
    loop(root, 0)

  def parse(body: String): Either[String, Snapshot] =
    try
      val root = ujson.read(body)
      if !root.isInstanceOf[ujson.Obj] then Left("device response must be a JSON object")
      else
        def number(keys: String*)(min: Double = 0, max: Double = 1000): Option[Double] =
          find(root, keys) {
            case ujson.Num(n) => Option.when(n.isFinite && n >= min && n <= max)(n)
            case ujson.Str(s) => s.trim.toDoubleOption.filter(n => n.isFinite && n >= min && n <= max)
            case _            => None
          }
        val corrected = find(root, Seq("monitorDisplayCompensatedValues")) {
          case ujson.Bool(b) => Some(b)
          case ujson.Str(s)  =>
            s.trim.toLowerCase(Locale.ROOT) match
              case "true"  => Some(true)
              case "false" => Some(false)
              case _       => None
          case _ => None
        }.getOrElse(true)
        def particulate(compensated: Seq[String], raw: Seq[String]): Option[Double] =
          (if corrected then number(compensated*)() else None).orElse(number(raw*)())
        val pm25 = particulate(
          Seq("pm02Compensated", "pm25Compensated", "pm2_5Compensated"),
          Seq("pm02", "pm2_5", "pm25", "pm2.5", "pm2_5_ugm3")
        )
        val explicitAqi = number("aqi", "pm02Aqi", "pm25Aqi", "pm2_5Aqi", "pm2_5_aqi", "usAqi", "us_aqi")(max = 500)
        val fields      = Vector(
          Metric.Aqi  -> explicitAqi.orElse(pm25.map(aqi)),
          Metric.Co2  -> number("rco2", "co2", "co2Ppm", "co2_ppm")(max = 40000),
          Metric.Pm25 -> pm25,
          Metric.Pm1  -> particulate(
            Seq("pm01Compensated", "pm1Compensated"),
            Seq("pm01", "pm1", "pm1_0", "pm1.0", "pm1_0_ugm3")
          ),
          Metric.Pm10      -> particulate(Seq("pm10Compensated"), Seq("pm10", "pm10_0", "pm10.0", "pm10_ugm3")),
          Metric.Particles -> number(
            "pm003Count",
            "pm03Count",
            "pm0_3Count",
            "pm0.3Count",
            "pm003_count",
            "pm0_3_count"
          )(max = 1000000),
          Metric.Tvoc        -> number("tvocIndex", "tvoc", "vocIndex", "voc_index", "tvoc_index")(max = 500),
          Metric.Nox         -> number("noxIndex", "nox", "nox_index")(max = 500),
          Metric.Temperature -> number(
            "atmpCompensated",
            "temperatureCompensated",
            "temperature_compensated",
            "tempCompensated"
          )(min = -40, max = 85)
            .orElse(number("atmp", "temperature", "temperatureC", "temperature_c", "temp")(min = -40, max = 85)),
          Metric.Humidity -> number("rhumCompensated", "humidityCompensated", "humidity_compensated")(max = 100)
            .orElse(number("rhum", "humidity", "relativeHumidity", "relative_humidity")(max = 100))
        )
        Right(
          Snapshot(fields.collect { case (metric, Some(n)) => metric -> n }.toMap, explicitAqi.isEmpty && pm25.nonEmpty)
        )
    catch case NonFatal(_) => Left("device returned malformed JSON")

  def aqi(pm25: Double): Double =
    val n      = math.floor(pm25 * 10) / 10
    val ranges = Vector(
      (0.0, 9.0, 0.0, 50.0),
      (9.1, 35.4, 51.0, 100.0),
      (35.5, 55.4, 101.0, 150.0),
      (55.5, 125.4, 151.0, 200.0),
      (125.5, 225.4, 201.0, 300.0),
      (225.5, 325.4, 301.0, 500.0)
    )
    ranges.find((lo, hi, _, _) => n >= lo && n <= hi).fold(500.0) { (lo, hi, lowIndex, highIndex) =>
      math.round((highIndex - lowIndex) / (hi - lo) * (n - lo) + lowIndex).toDouble
    }
