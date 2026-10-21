package com.worxbend.airgradient

import java.net.URI
import java.net.URLEncoder
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration
import java.util.Locale
import java.util.concurrent.{CompletableFuture, TimeUnit, TimeoutException}
import java.util.concurrent.atomic.AtomicReference
import scala.util.control.NonFatal

/** A named point for outdoor data. Only these coordinates leave the machine, and only when a location is configured. */
final case class Location(name: String, latitude: Double, longitude: Double):
  def coordinates: String = String.format(Locale.ROOT, "%.4f,%.4f", latitude, longitude)

object Location:
  private val Pair = """^\s*(-?\d{1,3}(?:\.\d+)?)\s*[, ]\s*(-?\d{1,3}(?:\.\d+)?)\s*$""".r

  def validate(location: Location): Either[String, Location] =
    if !location.latitude.isFinite || location.latitude < -90 || location.latitude > 90 then
      Left("latitude must be from -90 to 90")
    else if !location.longitude.isFinite || location.longitude < -180 || location.longitude > 180 then
      Left("longitude must be from -180 to 180")
    else if location.name.isBlank then Left("location needs a name")
    else Right(location)

  /** None means the text is not a coordinate pair and should be treated as a place name. */
  def parseCoordinates(input: String): Option[Either[String, Location]] = input match
    case Pair(lat, lon) =>
      val location = Location("", lat.toDouble, lon.toDouble)
      Some(validate(location.copy(name = location.coordinates)))
    case _ => None

/** Open-Meteo variables shown on the outdoor pages. Units follow the app's ASCII conventions. */
enum OutdoorMetric(
    val key: String,
    val label: String,
    val unit: String,
    val decimals: Int,
    val min: Double,
    val max: Double
):
  case Temperature   extends OutdoorMetric("temperature_2m", "Temperature", "C", 1, -90, 60)
  case FeelsLike     extends OutdoorMetric("apparent_temperature", "Feels like", "C", 1, -100, 70)
  case Humidity      extends OutdoorMetric("relative_humidity_2m", "Humidity", "%", 0, 0, 100)
  case Wind          extends OutdoorMetric("wind_speed_10m", "Wind", "km/h", 1, 0, 500)
  case Gusts         extends OutdoorMetric("wind_gusts_10m", "Gusts", "km/h", 1, 0, 600)
  case Pressure      extends OutdoorMetric("surface_pressure", "Pressure", "hPa", 0, 300, 1100)
  case Clouds        extends OutdoorMetric("cloud_cover", "Cloud cover", "%", 0, 0, 100)
  case Precipitation extends OutdoorMetric("precipitation", "Precipitation", "mm", 1, 0, 500)
  case UsAqi         extends OutdoorMetric("us_aqi", "US AQI", "US AQI", 0, 0, 1000)
  case EuAqi         extends OutdoorMetric("european_aqi", "EU AQI", "EAQI", 0, 0, 1000)
  case Pm25          extends OutdoorMetric("pm2_5", "PM2.5", "ug/m3", 1, 0, 2000)
  case Pm10          extends OutdoorMetric("pm10", "PM10", "ug/m3", 1, 0, 3000)
  case No2           extends OutdoorMetric("nitrogen_dioxide", "NO2", "ug/m3", 0, 0, 5000)
  case O3            extends OutdoorMetric("ozone", "O3", "ug/m3", 0, 0, 5000)
  case So2           extends OutdoorMetric("sulphur_dioxide", "SO2", "ug/m3", 0, 0, 5000)
  case Co            extends OutdoorMetric("carbon_monoxide", "CO", "ug/m3", 0, 0, 100000)
  case Uv            extends OutdoorMetric("uv_index", "UV index", "", 1, 0, 30)

  def format(value: Option[Double]): String = value.fold("--")(n => String.format(Locale.ROOT, s"%.${decimals}f", n))

  /** US AQI and particulates reuse the indoor scales; EU pollutants use the European AQI bands; weather has none. */
  def classify(value: Option[Double]): Option[Band] =
    def ladder(limits: Vector[Double])(n: Double): Band =
      val rank = limits.indexWhere(n <= _)
      if rank < 0 then Band.VeryUnhealthy else Band.values(rank + 1)
    this match
      case UsAqi => Some(Metric.Aqi.classify(value))
      case Pm25  => Some(Metric.Pm25.classify(value))
      case Pm10  => Some(Metric.Pm10.classify(value))
      case EuAqi => Some(value.fold(Band.Unknown)(ladder(Vector(20, 40, 60, 80))))
      case No2   => Some(value.fold(Band.Unknown)(ladder(Vector(40, 90, 120, 230))))
      case O3    => Some(value.fold(Band.Unknown)(ladder(Vector(50, 100, 130, 240))))
      case So2   => Some(value.fold(Band.Unknown)(ladder(Vector(100, 200, 350, 500))))
      case Uv    => Some(value.fold(Band.Unknown)(ladder(Vector(2, 5, 7, 10))))
      case _     => None

object OutdoorMetric:
  val weather: Vector[OutdoorMetric] =
    Vector(Temperature, FeelsLike, Humidity, Wind, Gusts, Pressure, Clouds, Precipitation)
  val air: Vector[OutdoorMetric] = Vector(UsAqi, EuAqi, Pm25, Pm10, No2, O3, So2, Co, Uv)

/** One forecast hour. Times are wall-clock labels in the location's own time zone, never the machine's. */
final case class ForecastHour(
    time: String,
    temperature: Option[Double],
    precipitationProbability: Option[Double],
    usAqi: Option[Double],
    pm25: Option[Double]
):
  def label: String = if time.length >= 16 then time.substring(11, 16) else time

final case class OutdoorReport(
    location: Location,
    receivedAt: Long,
    observedAt: Option[String],
    values: Map[OutdoorMetric, Double],
    weatherCode: Option[Int],
    windDirection: Option[Double],
    hourly: Vector[ForecastHour],
    warnings: Vector[String] = Vector.empty
):
  def value(metric: OutdoorMetric): Option[Double] = values.get(metric)
  def condition: String                            = weatherCode.fold("conditions unavailable")(Weather.describe)
  def wind: String                                 =
    val speed = s"${OutdoorMetric.Wind.format(value(OutdoorMetric.Wind))} km/h"
    windDirection.fold(speed)(degrees => s"$speed ${Weather.compass(degrees)}")

object Weather:
  /** WMO weather interpretation codes as documented by Open-Meteo. */
  def describe(code: Int): String = code match
    case 0       => "Clear sky"
    case 1       => "Mainly clear"
    case 2       => "Partly cloudy"
    case 3       => "Overcast"
    case 45 | 48 => "Fog"
    case 51 | 53 => "Drizzle"
    case 55      => "Dense drizzle"
    case 56 | 57 => "Freezing drizzle"
    case 61      => "Light rain"
    case 63      => "Rain"
    case 65      => "Heavy rain"
    case 66 | 67 => "Freezing rain"
    case 71      => "Light snow"
    case 73      => "Snow"
    case 75      => "Heavy snow"
    case 77      => "Snow grains"
    case 80 | 81 => "Rain showers"
    case 82      => "Violent rain showers"
    case 85 | 86 => "Snow showers"
    case 95      => "Thunderstorm"
    case 96 | 99 => "Thunderstorm with hail"
    case _       => s"Weather code $code"

  private val points =
    Vector("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW")
  def compass(degrees: Double): String = points(math.floorMod(math.round(degrees / 22.5).toInt, 16))

object OpenMeteo:
  val weatherCurrent: Vector[String] =
    OutdoorMetric.weather.map(_.key) ++ Vector("weather_code", "wind_direction_10m")
  val airCurrent: Vector[String] = OutdoorMetric.air.map(_.key)

  private def number(node: ujson.Value): Option[Double] = node match
    case ujson.Num(n) if n.isFinite => Some(n)
    case _                          => None

  private def objectAt(root: ujson.Value, key: String): Option[collection.Map[String, ujson.Value]] =
    root match
      case obj: ujson.Obj => obj.value.get(key).collect { case child: ujson.Obj => child.value }
      case _              => None

  private def series(root: ujson.Value, key: String): Vector[Option[Double]] =
    objectAt(root, "hourly")
      .flatMap(_.get(key))
      .collect { case arr: ujson.Arr => arr.value.map(number).toVector }
      .getOrElse(Vector.empty)

  private def times(root: ujson.Value): Vector[String] =
    objectAt(root, "hourly")
      .flatMap(_.get("time"))
      .collect { case arr: ujson.Arr =>
        arr.value.collect { case ujson.Str(s) => s }.toVector
      }
      .getOrElse(Vector.empty)

  private def read(body: String): Either[String, ujson.Value] =
    try
      ujson.read(body) match
        case obj: ujson.Obj if obj.value.get("error").contains(ujson.Bool(true)) =>
          Left(obj.value.get("reason").collect { case ujson.Str(s) => s"Open-Meteo: $s" }.getOrElse("Open-Meteo error"))
        case obj: ujson.Obj => Right(obj)
        case _              => Left("Open-Meteo response must be a JSON object")
    catch case NonFatal(_) => Left("Open-Meteo returned malformed JSON")

  private def current(root: ujson.Value, metrics: Vector[OutdoorMetric]): Map[OutdoorMetric, Double] =
    val node = objectAt(root, "current").getOrElse(Map.empty)
    metrics.flatMap(m => node.get(m.key).flatMap(number).filter(n => n >= m.min && n <= m.max).map(m -> _)).toMap

  final case class Part(
      values: Map[OutdoorMetric, Double],
      observedAt: Option[String],
      weatherCode: Option[Int],
      windDirection: Option[Double],
      hourly: Map[String, Map[String, Option[Double]]],
      order: Vector[String]
  )

  private def part(root: ujson.Value, metrics: Vector[OutdoorMetric], hourlyKeys: Vector[String]): Part =
    val node  = objectAt(root, "current").getOrElse(Map.empty)
    val stamp = times(root)
    val table = hourlyKeys.map(key => key -> series(root, key)).toMap
    Part(
      current(root, metrics),
      node.get("time").collect { case ujson.Str(s) => s },
      node.get("weather_code").flatMap(number).filter(n => n.isWhole && n >= 0 && n < 100).map(_.toInt),
      node.get("wind_direction_10m").flatMap(number).filter(n => n >= 0 && n <= 360),
      stamp.zipWithIndex.map((time, i) => time -> table.view.mapValues(_.lift(i).flatten).toMap).toMap,
      stamp
    )

  def parseWeather(body: String): Either[String, Part] =
    read(body).map(part(_, OutdoorMetric.weather, Vector("temperature_2m", "precipitation_probability")))

  def parseAir(body: String): Either[String, Part] =
    read(body).map(part(_, OutdoorMetric.air, Vector("us_aqi", "pm2_5")))

  /** Either half may be missing; a report needs at least one of them. */
  def combine(
      location: Location,
      receivedAt: Long,
      weather: Either[String, Part],
      air: Either[String, Part]
  ): Either[String, OutdoorReport] =
    (weather, air) match
      case (Left(a), Left(b)) => Left(if a == b then a else s"$a; $b")
      case _                  =>
        val w     = weather.toOption
        val a     = air.toOption
        val order = (w.map(_.order).getOrElse(Vector.empty) ++ a.map(_.order).getOrElse(Vector.empty)).distinct.sorted
        val hours = order.map { time =>
          def pick(p: Option[Part], key: String) = p.flatMap(_.hourly.get(time)).flatMap(_.get(key)).flatten
          ForecastHour(
            time,
            pick(w, "temperature_2m").filter(n => n >= -90 && n <= 60),
            pick(w, "precipitation_probability").filter(n => n >= 0 && n <= 100),
            pick(a, "us_aqi").filter(n => n >= 0 && n <= 1000),
            pick(a, "pm2_5").filter(n => n >= 0 && n <= 2000)
          )
        }
        Right(
          OutdoorReport(
            location,
            receivedAt,
            w.flatMap(_.observedAt).orElse(a.flatMap(_.observedAt)),
            w.map(_.values).getOrElse(Map.empty) ++ a.map(_.values).getOrElse(Map.empty),
            w.flatMap(_.weatherCode),
            w.flatMap(_.windDirection),
            hours.take(24),
            Vector(
              weather.left.toOption.map(e => s"weather unavailable: $e"),
              air.left.toOption.map(e => s"air quality unavailable: $e")
            ).flatten
          )
        )

  def parseGeocoding(body: String, query: String): Either[String, Location] =
    read(body).flatMap { root =>
      root.obj
        .get("results")
        .collect { case arr: ujson.Arr => arr.value.toVector }
        .getOrElse(Vector.empty)
        .headOption match
        case Some(first: ujson.Obj) =>
          val fields  = first.value
          val name    = fields.get("name").collect { case ujson.Str(s) => s }.getOrElse(query.trim)
          val region  = fields.get("country").orElse(fields.get("admin1")).collect { case ujson.Str(s) => s }
          val located = for
            lat <- fields.get("latitude").flatMap(number)
            lon <- fields.get("longitude").flatMap(number)
          yield Location(region.fold(name)(r => s"$name, $r"), lat, lon)
          located.toRight("place has no coordinates").flatMap(Location.validate)
        case _ => Left(s"no place found for \"${query.trim}\"; try another spelling or lat,lon")
    }

trait OutdoorClient extends AutoCloseable:
  def report(location: Location, receivedAt: Long): Either[String, OutdoorReport]
  def geocode(query: String): Either[String, Location]
  def cancelPending(): Unit  = ()
  override def close(): Unit = ()

/** Free Open-Meteo endpoints: no account or key. Requests are bounded by the same deadline and size rules as the
  * device.
  */
final class OpenMeteoClient(
    forecastBase: URI = URI("https://api.open-meteo.com/"),
    airBase: URI = URI("https://air-quality-api.open-meteo.com/"),
    geocodingBase: URI = URI("https://geocoding-api.open-meteo.com/"),
    timeout: Duration = Duration.ofSeconds(8),
    maxBytes: Int = 512 * 1024
) extends OutdoorClient:
  private val http = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build()
  private val pending = new AtomicReference[Vector[CompletableFuture[?]]](Vector.empty)

  private def query(location: Location, current: Vector[String], hourly: Vector[String]): String =
    String.format(
      Locale.ROOT,
      "latitude=%.4f&longitude=%.4f&current=%s&hourly=%s&forecast_hours=24&timezone=auto",
      location.latitude,
      location.longitude,
      current.mkString(","),
      hourly.mkString(",")
    )

  private def get(uri: URI): Either[String, String] =
    val request = HttpRequest
      .newBuilder(uri)
      .timeout(timeout)
      .header("Accept", "application/json")
      .header("User-Agent", s"airgradient-tui-scala/${Main.Version}")
      .GET()
      .build()
    val future = http.sendAsync(request, (_: HttpResponse.ResponseInfo) => BoundedBody(maxBytes))
    val _      = pending.updateAndGet(_ :+ future)
    try
      val response = future.get(timeout.toMillis, TimeUnit.MILLISECONDS)
      if response.statusCode() < 200 || response.statusCode() >= 300 then Left(s"HTTP ${response.statusCode()}")
      else Right(String(response.body(), UTF_8))
    catch
      case _: InterruptedException =>
        Thread.currentThread().interrupt()
        Left("request cancelled")
      case _: TimeoutException => Left(s"no response within ${timeout.toMillis} ms")
      case NonFatal(error)     =>
        val reason = Option(error.getCause).getOrElse(error)
        Left(Option(reason.getMessage).getOrElse(reason.getClass.getSimpleName))
    finally
      val _ = future.cancel(true)
      val _ = pending.updateAndGet(_.filterNot(_ eq future))

  def report(location: Location, receivedAt: Long): Either[String, OutdoorReport] =
    val weather = get(
      forecastBase.resolve(
        "v1/forecast?" + query(
          location,
          OpenMeteo.weatherCurrent,
          Vector("temperature_2m", "precipitation_probability")
        )
      )
    ).flatMap(OpenMeteo.parseWeather)
    val air = get(
      airBase.resolve("v1/air-quality?" + query(location, OpenMeteo.airCurrent, Vector("us_aqi", "pm2_5")))
    ).flatMap(OpenMeteo.parseAir)
    OpenMeteo.combine(location, receivedAt, weather, air)

  def geocode(text: String): Either[String, Location] =
    val name = text.trim
    if name.length < 2 then Left("place name is too short")
    else
      get(geocodingBase.resolve(s"v1/search?count=1&format=json&name=${URLEncoder.encode(name, UTF_8)}"))
        .flatMap(OpenMeteo.parseGeocoding(_, name))

  override def cancelPending(): Unit = pending.getAndSet(Vector.empty).foreach(_.cancel(true))
  override def close(): Unit         =
    cancelPending()
    http.shutdownNow()

/** Deterministic synthetic outdoor data. It never touches the network, so demo mode stays offline. */
final class DemoOutdoorClient extends OutdoorClient:
  def report(location: Location, receivedAt: Long): Either[String, OutdoorReport] =
    Right(DemoOutdoor.report(location, receivedAt))
  def geocode(query: String): Either[String, Location] =
    Location.validate(Location(s"${query.trim} (demo)", 0, 0))

object DemoOutdoor:
  val Place: Location = Location("Demo City (synthetic)", 0, 0)

  def report(location: Location, receivedAt: Long): OutdoorReport =
    val firstHour = math.floorMod(receivedAt / 3600000L, 24L).toInt
    val hours     = Vector.tabulate(24) { n =>
      val hour = (firstHour + n) % 24
      val wave = math.sin((hour - 9) / 24.0 * 2 * math.Pi)
      ForecastHour(
        f"2030-01-01T$hour%02d:00",
        Some(math.round((9.4 + wave * 4.2) * 10) / 10.0),
        Some(math.max(0.0, math.round(20 - wave * 18).toDouble)),
        Some(math.round(38 - wave * 14).toDouble),
        Some(math.round((6.1 - wave * 2.2) * 10) / 10.0)
      )
    }
    OutdoorReport(
      location,
      receivedAt,
      Some(hours.head.time),
      Map(
        OutdoorMetric.Temperature   -> 9.4,
        OutdoorMetric.FeelsLike     -> 7.8,
        OutdoorMetric.Humidity      -> 72,
        OutdoorMetric.Wind          -> 14.2,
        OutdoorMetric.Gusts         -> 26.5,
        OutdoorMetric.Pressure      -> 1014,
        OutdoorMetric.Clouds        -> 40,
        OutdoorMetric.Precipitation -> 0,
        OutdoorMetric.UsAqi         -> 38,
        OutdoorMetric.EuAqi         -> 27,
        OutdoorMetric.Pm25          -> 6.1,
        OutdoorMetric.Pm10          -> 11.3,
        OutdoorMetric.No2           -> 18,
        OutdoorMetric.O3            -> 52,
        OutdoorMetric.So2           -> 3,
        OutdoorMetric.Co            -> 210,
        OutdoorMetric.Uv            -> 1.2
      ),
      Some(2),
      Some(225),
      hours
    )
