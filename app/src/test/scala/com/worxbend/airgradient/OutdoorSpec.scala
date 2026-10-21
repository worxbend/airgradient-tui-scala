package com.worxbend.airgradient

import com.sun.net.httpserver.HttpServer
import org.scalatest.funsuite.AnyFunSuite
import java.net.{InetSocketAddress, URI}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters.*

final class OutdoorSpec extends AnyFunSuite:
  private val place = Location("Testville", 10, 20)

  // Synthetic responses in Open-Meteo's documented shape. Values are invented, not captured readings.
  private val weather =
    """{"latitude":10.0,"longitude":20.0,"utc_offset_seconds":3600,"timezone":"Etc/Test",
      |"current":{"time":"2030-01-01T09:15","temperature_2m":12.3,"apparent_temperature":10.9,
      |"relative_humidity_2m":65,"wind_speed_10m":11.0,"wind_gusts_10m":null,"wind_direction_10m":92,
      |"surface_pressure":1009.5,"cloud_cover":75,"precipitation":0.2,"weather_code":61},
      |"hourly":{"time":["2030-01-01T09:00","2030-01-01T10:00","2030-01-01T11:00"],
      |"temperature_2m":[12.0,null,14.5],"precipitation_probability":[40,55,999]}}""".stripMargin
  private val air =
    """{"current":{"time":"2030-01-01T09:00","us_aqi":61,"european_aqi":35,"pm2_5":18.2,"pm10":25.0,
      |"nitrogen_dioxide":21.0,"ozone":44.0,"sulphur_dioxide":2.0,"carbon_monoxide":230.0,"uv_index":-3},
      |"hourly":{"time":["2030-01-01T09:00","2030-01-01T10:00","2030-01-01T11:00"],
      |"us_aqi":[61,48,null],"pm2_5":[18.2,11.5,9.0]}}""".stripMargin

  test("coordinates parse locally and out-of-range values are rejected before any request"):
    assert(Location.parseCoordinates("50.45, 30.52").get.toOption.get.coordinates == "50.4500,30.5200")
    assert(Location.parseCoordinates("-33.9 18.4").get.isRight)
    assert(Location.parseCoordinates("91,0").get.isLeft)
    assert(Location.parseCoordinates("0,181").get.isLeft)
    assert(Location.parseCoordinates("Kyiv").isEmpty)

  test("weather and air responses merge by hour, keep gaps, and drop impossible values"):
    val report =
      OpenMeteo.combine(place, 1000, OpenMeteo.parseWeather(weather), OpenMeteo.parseAir(air)).toOption.get
    assert(report.value(OutdoorMetric.Temperature).contains(12.3))
    assert(report.value(OutdoorMetric.UsAqi).contains(61))
    assert(report.value(OutdoorMetric.Gusts).isEmpty)
    assert(report.value(OutdoorMetric.Uv).isEmpty, "negative UV is out of range")
    assert(report.condition == "Light rain")
    assert(report.wind == "11.0 km/h E")
    assert(report.observedAt.contains("2030-01-01T09:15"))
    assert(report.hourly.map(_.label) == Vector("09:00", "10:00", "11:00"))
    assert(report.hourly(1).temperature.isEmpty)
    assert(report.hourly(2).precipitationProbability.isEmpty)
    assert(report.hourly(2).usAqi.isEmpty && report.hourly(2).pm25.contains(9.0))
    assert(report.warnings.isEmpty)

  test("one failed half still yields a report with a visible warning; both failing is an error"):
    val partial = OpenMeteo.combine(place, 1000, OpenMeteo.parseWeather(weather), Left("HTTP 503")).toOption.get
    assert(partial.value(OutdoorMetric.Temperature).nonEmpty)
    assert(partial.value(OutdoorMetric.UsAqi).isEmpty)
    assert(partial.warnings == Vector("air quality unavailable: HTTP 503"))
    assert(OpenMeteo.combine(place, 1000, Left("HTTP 500"), Left("HTTP 500")) == Left("HTTP 500"))
    assert(
      OpenMeteo.parseAir("""{"error":true,"reason":"Parameter is invalid"}""").left.toOption.get.contains("invalid")
    )
    assert(OpenMeteo.parseWeather("not json").isLeft)

  test("geocoding takes the first match and reports an unknown place in words"):
    val found = OpenMeteo.parseGeocoding(
      """{"results":[{"name":"Testville","latitude":1.5,"longitude":2.5,"country":"Testland"}]}""",
      "testville"
    )
    assert(found == Right(Location("Testville, Testland", 1.5, 2.5)))
    assert(OpenMeteo.parseGeocoding("""{"generationtime_ms":0.2}""", "Nowhere").left.toOption.get.contains("Nowhere"))

  test("outdoor bands follow the US and European AQI scales; weather has no band"):
    assert(OutdoorMetric.UsAqi.classify(Some(61)).contains(Band.Moderate))
    assert(OutdoorMetric.EuAqi.classify(Some(35)).contains(Band.Moderate))
    assert(OutdoorMetric.No2.classify(Some(250)).contains(Band.VeryUnhealthy))
    assert(OutdoorMetric.Uv.classify(Some(1)).contains(Band.Good))
    assert(OutdoorMetric.Temperature.classify(Some(20)).isEmpty)
    assert(OutdoorMetric.UsAqi.classify(None).contains(Band.Unknown))

  test("the Open-Meteo client sends only coordinates and requested fields, and survives a failing endpoint"):
    val queries                                              = ConcurrentLinkedQueue[String]()
    val server                                               = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    def serve(path: String, status: Int, body: String): Unit =
      server.createContext(
        path,
        exchange =>
          queries.add(s"$path?${exchange.getRequestURI.getRawQuery}")
          val bytes = body.getBytes(UTF_8)
          exchange.sendResponseHeaders(status, bytes.length)
          exchange.getResponseBody.write(bytes)
          exchange.close()
      )
    serve("/v1/forecast", 200, weather)
    serve("/v1/air-quality", 503, "unavailable")
    serve("/v1/search", 200, """{"results":[{"name":"Test Ville","latitude":3,"longitude":4,"admin1":"Region"}]}""")
    server.start()
    val base   = URI(s"http://127.0.0.1:${server.getAddress.getPort}/")
    val client = OpenMeteoClient(base, base, base)
    try
      val report = client.report(place, 42).toOption.get
      assert(report.receivedAt == 42)
      assert(report.value(OutdoorMetric.Pressure).contains(1009.5))
      assert(report.warnings.exists(_.contains("HTTP 503")))
      assert(client.geocode("Test Ville") == Right(Location("Test Ville, Region", 3, 4)))
      assert(client.geocode("x").isLeft)
      val sent = queries.asScala.toVector
      assert(sent.exists(q => q.startsWith("/v1/forecast?latitude=10.0000&longitude=20.0000&current=temperature_2m")))
      assert(sent.exists(q => q.startsWith("/v1/air-quality?") && q.contains("current=us_aqi")))
      assert(sent.exists(_ == "/v1/search?count=1&format=json&name=Test+Ville"))
      assert(sent.forall(q => !q.contains("serial") && !q.contains("sensor")))
    finally
      client.close()
      server.stop(0)

  test("an outdoor location round-trips through the shared config without touching other fields"):
    val file = Files.createTempDirectory("ag-outdoor").resolve("config.json")
    Files.writeString(file, """{"server_url":"sensor.invalid","desktop_only":{"keep":true}}""")
    val store  = ConfigStore(file)
    val loaded = store.load().toOption.get
    assert(loaded.settings.outdoor.isEmpty)
    val saved = store.save(loaded.settings.copy(outdoor = Some(place)), loaded.fingerprint).toOption.get
    assert(saved.settings.outdoor.contains(place))
    val json = ujson.read(Files.readString(file))
    assert(json(ConfigStore.OutdoorKey)("latitude").num == 10)
    assert(json("desktop_only")("keep").bool)
    val cleared = store.save(saved.settings.copy(outdoor = None), saved.fingerprint).toOption.get
    assert(cleared.settings.outdoor.isEmpty)
    assert(!ujson.read(Files.readString(file)).obj.contains(ConfigStore.OutdoorKey))
    Files.writeString(file, s"""{"${ConfigStore.OutdoorKey}":{"name":"Bad","latitude":200,"longitude":0}}""")
    val repaired = store.load().toOption.get
    assert(repaired.settings.outdoor.isEmpty)
    assert(repaired.warnings.exists(_.contains("outdoor data is off")))
    assert(store.validate(Settings(outdoor = Some(Location("Bad", 0, 999)))).isLeft)

  test("the demo outdoor client is deterministic and needs no network"):
    val client = DemoOutdoorClient()
    val first  = client.report(DemoOutdoor.Place, 1800000000000L)
    assert(first == client.report(DemoOutdoor.Place, 1800000000000L))
    assert(first.toOption.get.hourly.size == 24)
    assert(first.toOption.get.hourly.head.label == "08:00")
    assert(client.geocode("Anywhere").toOption.get.name == "Anywhere (demo)")
