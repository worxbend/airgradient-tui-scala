package com.worxbend.airgradient

import org.scalatest.funsuite.AnyFunSuite
import scala.io.Source
import scala.util.Using

final class MeasurementsSpec extends AnyFunSuite:
  private def fixture(name: String): Snapshot =
    val text = Using.resource(Source.fromResource(s"fixtures/$name.json"))(_.mkString)
    Measurements.parse(text).toOption.get

  test("current firmware values use compensated temperature and humidity"):
    val values = fixture("current_airgradient_payload")
    assert(values.value(Metric.Co2).contains(612))
    assert(values.value(Metric.Temperature).contains(22.6))
    assert(values.value(Metric.Humidity).contains(48.1))
    assert(values.value(Metric.Tvoc).contains(83))
    assert(values.aqiDerived)

  test("nested aliases and numeric strings follow the reference CLI"):
    val alternate = fixture("alternate_field_names_payload")
    assert(alternate.value(Metric.Co2).contains(805))
    assert(alternate.value(Metric.Aqi).contains(58))
    assert(!alternate.aqiDerived)
    val nested = fixture("nested_conflicting_payload")
    assert(nested.value(Metric.Co2).contains(610))
    assert(nested.value(Metric.Temperature).contains(22.4))
    assert(nested.value(Metric.Tvoc).contains(301))

  test("invalid compensated values fall back to valid raw values"):
    val reading = fixture("compensated_fallback_payload")
    assert(reading.value(Metric.Temperature).contains(19.8))
    assert(reading.value(Metric.Humidity).contains(42.4))

  test("particulate correction follows the monitor display switch"):
    val corrected = Measurements.parse("""{"pm02":10,"pm02Compensated":4}""").toOption.get
    val raw       =
      Measurements.parse("""{"pm02":10,"pm02Compensated":4,"monitorDisplayCompensatedValues":false}""").toOption.get
    assert(corrected.value(Metric.Pm25).contains(4))
    assert(raw.value(Metric.Pm25).contains(10))
    assert(corrected.value(Metric.Aqi) != raw.value(Metric.Aqi))

  test("missing, null, sentinels and nonnumeric readings stay missing while zero and negative temperature survive"):
    val reading = Measurements
      .parse("""{"rco2":-1,"pm02":0,"tvocIndex":null,"noxIndex":"NaN","atmp":-12,"rhum":101}""")
      .toOption
      .get
    assert(reading.value(Metric.Co2).isEmpty)
    assert(reading.value(Metric.Pm25).contains(0))
    assert(reading.value(Metric.Temperature).contains(-12))
    assert(reading.value(Metric.Humidity).isEmpty)
    assert(reading.json("co2") == ujson.Null)
    assert(Metric.Co2.classify(None) == Band.Unknown)

  test("digit runs are not conflated when normalizing alias separators"):
    val reading = Measurements.parse("""{"PM 02":7,"pm025":999,"pm2":888}""").toOption.get
    assert(reading.value(Metric.Pm25).contains(7))

  test("AQI interpolation and upper-inclusive bands match the reference"):
    assert(Measurements.aqi(9) == 50)
    assert(Measurements.aqi(35.4) == 100)
    assert(Measurements.aqi(1000) == 500)
    assert(Metric.Co2.classify(Some(800)) == Band.Good)
    assert(Metric.Co2.classify(Some(801)) == Band.Moderate)
    assert(Metric.Humidity.classify(Some(85)) == Band.Unhealthy)
    assert(Metric.Temperature.classify(Some(17)) == Band.Moderate)

  test("invalid JSON and non-object bodies fail without fabricating readings"):
    assert(Measurements.parse("not JSON").isLeft)
    assert(Measurements.parse("[]").isLeft)
    assert(Measurements.parse("{}").toOption.get.values.isEmpty)

  test("decimal formatting does not depend on the machine locale"):
    val before = java.util.Locale.getDefault
    try
      java.util.Locale.setDefault(java.util.Locale.GERMANY)
      assert(Metric.Pm25.format(Some(7.4)) == "7.4")
    finally java.util.Locale.setDefault(before)
