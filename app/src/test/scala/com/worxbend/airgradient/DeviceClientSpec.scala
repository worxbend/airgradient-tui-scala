package com.worxbend.airgradient

import org.scalatest.funsuite.AnyFunSuite
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.time.Duration

final class DeviceClientSpec extends AnyFunSuite:
  test("URLs normalize to the fixed endpoint and never retain credentials"):
    assert(
      DeviceAddress
        .parse("sensor.invalid:9925/path?x=y#z")
        .toOption
        .get
        .endpoint
        .toString == "http://sensor.invalid:9925/measures/current"
    )
    assert(DeviceAddress.parse("ftp://sensor.invalid").isLeft)
    assert(DeviceAddress.parse("http://user:secret@sensor.invalid").left.toOption.get.contains("credentials"))
    assert(!DeviceAddress.parse("http://user:secret@::").left.toOption.get.contains("secret"))
    assert(DeviceAddress.parse("").isLeft)

  private def withServer(body: String, status: Int = 200, delay: Long = 0)(check: DeviceAddress => Unit): Unit =
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext(
      "/measures/current",
      exchange =>
        val bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8)
        if status == 302 then exchange.getResponseHeaders.add("Location", "/never-follow")
        exchange.sendResponseHeaders(status, bytes.length)
        try
          if delay > 0 then Thread.sleep(delay)
          exchange.getResponseBody.write(bytes)
        catch case _: java.io.IOException => ()
        finally exchange.close()
    )
    server.start()
    try check(DeviceAddress.parse(s"http://127.0.0.1:${server.getAddress.getPort}").toOption.get)
    finally server.stop(0)

  test("real HTTP transport decodes device readings"):
    withServer("""{"rco2":805,"pm02":7.4}""") { address =>
      val client = DeviceClient()
      try assert(client.fetch(address).toOption.get.value(Metric.Co2).contains(805))
      finally client.close()
    }

  test("redirects surface as errors and oversized response streams are cancelled"):
    withServer("redirect", 302) { address =>
      val client = DeviceClient()
      try assert(client.fetch(address) == Left("device returned HTTP 302"))
      finally client.close()
    }
    withServer("x" * 4096) { address =>
      val client = DeviceClient(maxBytes = 128)
      try assert(client.fetch(address).left.toOption.get.contains("exceeds 128"))
      finally client.close()
    }

  test("the deadline covers a body that stalls after successful response headers"):
    withServer("""{"rco2":700}""", delay = 500) { address =>
      val client = DeviceClient(timeout = Duration.ofMillis(150))
      val start  = System.nanoTime()
      try
        assert(client.fetch(address).isLeft)
        assert((System.nanoTime() - start) / 1000000 < 1000)
      finally client.close()
    }
