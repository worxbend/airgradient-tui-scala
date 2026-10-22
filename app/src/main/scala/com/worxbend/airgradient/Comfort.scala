package com.worxbend.airgradient

import java.util.Locale

/** Derived indoor metrics. These are standard approximations from temperature and relative humidity, not sensor
  * readings.
  */
object Comfort:
  private def fmt(n: Double, decimals: Int = 1): String = String.format(Locale.ROOT, s"%.${decimals}f", n)

  /** Magnus formula (Sonntag 1990 constants); accurate to about 0.35 C between -45 and 60 C. */
  def dewPoint(celsius: Double, humidity: Double): Option[Double] =
    Option.when(humidity > 0 && humidity <= 100) {
      val gamma = math.log(humidity / 100) + 17.62 * celsius / (243.12 + celsius)
      243.12 * gamma / (17.62 - gamma)
    }

  /** Grams of water vapour per cubic metre of air. */
  def absoluteHumidity(celsius: Double, humidity: Double): Option[Double] =
    Option.when(humidity >= 0 && humidity <= 100) {
      6.112 * math.exp(17.67 * celsius / (celsius + 243.5)) * humidity * 2.1674 / (273.15 + celsius)
    }

  /** NOAA Rothfusz regression. It is defined only for warm, humid air (at least 26.7 C and 40 %). */
  def heatIndex(celsius: Double, humidity: Double): Option[Double] =
    Option.when(celsius >= 26.7 && humidity >= 40 && humidity <= 100) {
      val t = celsius * 9 / 5 + 32
      val r = humidity
      val f = -42.379 + 2.04901523 * t + 10.14333127 * r - 0.22475541 * t * r - 0.00683783 * t * t -
        0.05481717 * r * r + 0.00122874 * t * t * r + 0.00085282 * t * r * r - 0.00000199 * t * t * r * r
      (f - 32) * 5 / 9
    }

  final case class Verdict(label: String, band: Band, detail: String = "")

  def verdict(snapshot: Snapshot): Verdict =
    (snapshot.value(Metric.Temperature), snapshot.value(Metric.Humidity)) match
      case (None, _) | (_, None) => Verdict("Needs temperature and humidity", Band.Unknown)
      case (Some(t), Some(rh))   =>
        val issues = Vector(
          Option.when(t < 18)("too cold"),
          Option.when(t > 26)("too warm"),
          Option.when(rh < 30)("too dry"),
          Option.when(rh > 60)("too humid")
        ).flatten
        val band =
          Vector(Metric.Temperature.classify(Some(t)), Metric.Humidity.classify(Some(rh))).maxBy(_.ordinal)
        if issues.isEmpty then Verdict("Comfortable", Band.Good)
        else Verdict(issues.mkString(", ").capitalize, band)

  /** Share of reported humidity samples at or above 70 %, the usual threshold for sustained mold growth. */
  def moldRisk(samples: Vector[Sample]): Verdict =
    val readings = samples.flatMap(_.snapshot.value(Metric.Humidity))
    if readings.isEmpty then Verdict("Unknown", Band.Unknown, "no humidity history yet")
    else
      val share  = readings.count(_ >= 70).toDouble / readings.size
      val detail = s"${math.round(share * 100)}% of samples at 70 % RH+"
      if share == 0 then Verdict("Low", Band.Good, detail)
      else if share < 0.25 then Verdict("Moderate", Band.Moderate, detail)
      else Verdict("High", Band.Unhealthy, detail)

  /** Cold glass collects condensation when its surface drops below the indoor dew point. */
  def condensation(indoorDewPoint: Option[Double], outdoorCelsius: Option[Double]): Verdict =
    (indoorDewPoint, outdoorCelsius) match
      case (Some(dew), Some(outside)) =>
        val detail = s"outside ${fmt(outside)} C, dew point ${fmt(dew)} C"
        if outside < dew then Verdict("Likely on cold glass", Band.Elevated, detail)
        else if outside < dew + 5 then Verdict("Possible on single glazing", Band.Moderate, detail)
        else Verdict("Unlikely", Band.Good, detail)
      case (None, _) => Verdict("Unknown", Band.Unknown, "needs temperature and humidity")
      case _         => Verdict("Unknown", Band.Unknown, "set an outdoor location")

  /** CO2 change per hour across the last 30 minutes of successful samples. */
  def co2PerHour(samples: Vector[Sample]): Option[Double] =
    samples.lastOption.flatMap { last =>
      val window =
        samples.filter(s => last.atMillis - s.atMillis <= 30 * 60000L && s.snapshot.value(Metric.Co2).nonEmpty)
      for
        first <- window.headOption
        end   <- window.lastOption
        if end.atMillis - first.atMillis >= 5 * 60000L
        a <- first.snapshot.value(Metric.Co2)
        b <- end.snapshot.value(Metric.Co2)
      yield (b - a) / ((end.atMillis - first.atMillis) / 3600000.0)
    }

  def shareAbove(samples: Vector[Sample], metric: Metric, limit: Double): Option[Double] =
    val readings = samples.flatMap(_.snapshot.value(metric))
    Option.when(readings.nonEmpty)(readings.count(_ >= limit).toDouble / readings.size)

final case class HeadsUp(text: String, band: Band)

/** Window advice from indoor CO2 and particulates against outdoor air. A heuristic, not a health recommendation. */
final case class Advice(headline: String, detail: String, band: Band):
  def urgent: Boolean = band.ordinal >= Band.Elevated.ordinal && !headline.startsWith("Keep")

object Advice:
  private def fmt(n: Double, decimals: Int = 0): String = String.format(Locale.ROOT, s"%.${decimals}f", n)

  def ventilation(indoor: Option[Snapshot], outdoor: Option[OutdoorReport]): Advice =
    indoor match
      case None =>
        Advice("Waiting for indoor readings", "Advice starts after the first successful sample.", Band.Unknown)
      case Some(snapshot) =>
        val co2         = snapshot.value(Metric.Co2)
        val indoorPm    = snapshot.value(Metric.Pm25)
        val outsideAqi  = outdoor.flatMap(_.value(OutdoorMetric.UsAqi))
        val outsidePm   = outdoor.flatMap(_.value(OutdoorMetric.Pm25))
        val outsideTemp = outdoor.flatMap(_.value(OutdoorMetric.Temperature))
        val co2High     = co2.exists(_ >= 1000)
        val co2Rising   = co2.exists(n => n >= 800 && n < 1000)
        val poorOutside = outsideAqi.exists(_ > 100) || outsidePm.exists(_ > 35.4)
        val dirtier     = (for i <- indoorPm; o <- outsidePm yield o > i + 5).getOrElse(false)
        val harsh       = outsideTemp.exists(t => t < 5 || t > 30)
        // Details stay short enough for a one-third-width panel at 120 columns.
        val aqiText = outsideAqi.fold("outside AQI unknown")(n => s"outside AQI ${fmt(n)}")
        val co2Text = co2.fold("CO2 unknown")(n => s"CO2 ${fmt(n)} ppm")
        if outdoor.isEmpty && co2High then Advice("Ventilate", s"$co2Text; no outdoor data.", Band.Elevated)
        else if poorOutside && co2High then Advice("Ventilate briefly", s"Air out 5 min; $aqiText.", Band.Elevated)
        else if poorOutside then Advice("Keep windows closed", s"Outside air is poor: $aqiText.", Band.Unhealthy)
        else if co2High && harsh then
          Advice("Burst-ventilate", s"${fmt(outsideTemp.get, 1)} C outside: open wide 5-10 min.", Band.Elevated)
        else if co2High then Advice("Open a window", s"$co2Text; $aqiText.", Band.Elevated)
        else if dirtier then
          Advice(
            "Keep windows closed",
            s"Outside PM2.5 ${fmt(outsidePm.get, 1)} > inside ${fmt(indoorPm.get, 1)}.",
            Band.Moderate
          )
        else if co2Rising then Advice("Ventilate soon", s"$co2Text, nearing 1000 ppm.", Band.Moderate)
        else if co2.isEmpty then Advice("No CO2 reading", "Advice needs a CO2 reading.", Band.Unknown)
        else Advice("No action needed", s"Fresh indoors; $aqiText.", Band.Good)

  /** The first notable event of each kind in the next 12 forecast hours, plus today's UV peak. */
  def headsUp(report: OutdoorReport): Vector[HeadsUp] =
    val next = report.hourly.take(12)
    Vector(
      next
        .find(_.usAqi.exists(_ > 100))
        .map(h => HeadsUp(s"US AQI ${fmt(h.usAqi.get)} by ${h.label}", Metric.Aqi.classify(h.usAqi))),
      next
        .find(_.precipitationProbability.exists(_ >= 70))
        .map(h => HeadsUp(s"Rain ${fmt(h.precipitationProbability.get)}% around ${h.label}", Band.Moderate)),
      next
        .find(_.temperature.exists(_ <= 0))
        .map(h => HeadsUp(s"Frost ${fmt(h.temperature.get, 1)} C around ${h.label}", Band.Moderate)),
      report.daily.headOption
        .flatMap(_.uvMax)
        .filter(_ >= 6)
        .map(uv => HeadsUp(s"UV up to ${fmt(uv, 1)} today", OutdoorMetric.Uv.classify(Some(uv)).get))
    ).flatten

  /** The forecast hour with the cleanest air, preferring mild temperatures; earliest wins ties. */
  def bestHour(hours: Vector[ForecastHour]): Option[ForecastHour] =
    val rated = hours.filter(_.usAqi.nonEmpty)
    val mild  = rated.filter(_.temperature.exists(t => t >= 5 && t <= 28))
    (if mild.nonEmpty then mild else rated).minByOption(_.usAqi.get)
