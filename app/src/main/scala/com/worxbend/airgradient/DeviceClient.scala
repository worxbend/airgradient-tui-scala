package com.worxbend.airgradient

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration
import java.util.concurrent.{CompletableFuture, CompletionStage, Flow, TimeUnit, TimeoutException}
import java.util.concurrent.atomic.AtomicReference
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

final case class DeviceAddress private (base: URI):
  def endpoint: URI             = base.resolve("measures/current")
  override def toString: String = base.toString

object DeviceAddress:
  def parse(input: String): Either[String, DeviceAddress] =
    try
      val trimmed = input.trim
      val uri     = URI(if trimmed.contains("://") then trimmed else s"http://$trimmed")
      if !Set("http", "https").contains(uri.getScheme) then Left("device URL must use http or https")
      else if uri.getRawUserInfo != null || trimmed.contains('@') then Left("device URL must not contain credentials")
      else if uri.getHost == null || uri.getPort > 65535 || uri.getPort == 0 then
        Left("device URL needs a valid host and port")
      else Right(DeviceAddress(URI(uri.getScheme, null, uri.getHost, uri.getPort, "/", null, null)))
    catch case NonFatal(_) => Left("invalid device URL; use a hostname or http(s)://host:port")

trait SensorClient extends AutoCloseable:
  def fetch(address: DeviceAddress): Either[String, Snapshot]
  def cancelPending(): Unit  = ()
  override def close(): Unit = ()

/** One HTTP client per app. The deadline includes the body, which HttpRequest.timeout alone does not guarantee. */
final class DeviceClient(timeout: Duration = Duration.ofSeconds(5), maxBytes: Int = 1024 * 1024) extends SensorClient:
  private val http = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build()
  private val pending = new AtomicReference[CompletableFuture[?]]()

  def fetch(address: DeviceAddress): Either[String, Snapshot] =
    val request =
      HttpRequest.newBuilder(address.endpoint).timeout(timeout).header("Accept", "application/json").GET().build()
    val future = http.sendAsync(request, (_: HttpResponse.ResponseInfo) => BoundedBody(maxBytes))
    pending.set(future)
    try
      val response = future.get(timeout.toMillis, TimeUnit.MILLISECONDS)
      if response.statusCode() < 200 || response.statusCode() >= 300 then
        Left(s"device returned HTTP ${response.statusCode()}")
      else Measurements.parse(String(response.body(), UTF_8))
    catch
      case _: InterruptedException =>
        Thread.currentThread().interrupt()
        Left("request cancelled")
      case _: TimeoutException => Left(s"device did not respond within ${timeout.toMillis} ms")
      case NonFatal(error)     =>
        val reason = Option(error.getCause).getOrElse(error)
        Left(Option(reason.getMessage).getOrElse(reason.getClass.getSimpleName))
    finally
      val _ = future.cancel(true)
      val _ = pending.compareAndSet(future, null)

  override def cancelPending(): Unit =
    Option(pending.getAndSet(null)).foreach(_.cancel(true))
  override def close(): Unit =
    cancelPending()
    http.shutdownNow()

private final class BoundedBody(limit: Int) extends HttpResponse.BodySubscriber[Array[Byte]]:
  private val result                                  = new CompletableFuture[Array[Byte]]()
  private val bytes                                   = new java.io.ByteArrayOutputStream()
  private var subscription: Option[Flow.Subscription] = None
  def getBody(): CompletionStage[Array[Byte]]         = result
  def onSubscribe(value: Flow.Subscription): Unit     =
    subscription = Some(value)
    value.request(1)
  def onNext(chunks: java.util.List[ByteBuffer]): Unit =
    val incoming = chunks.asScala.map(_.remaining().toLong).sum
    if bytes.size().toLong + incoming > limit then
      subscription.foreach(_.cancel())
      val _ = result.completeExceptionally(IllegalArgumentException(s"device response exceeds $limit bytes"))
    else
      chunks.asScala.foreach { buffer =>
        val chunk = new Array[Byte](buffer.remaining())
        val _     = buffer.get(chunk)
        bytes.write(chunk)
      }
      subscription.foreach(_.request(1))
  def onError(error: Throwable): Unit = { val _ = result.completeExceptionally(error) }
  def onComplete(): Unit              = { val _ = result.complete(bytes.toByteArray) }

final class DemoClient extends SensorClient:
  private var tick                                            = 60
  def fetch(address: DeviceAddress): Either[String, Snapshot] =
    val _      = address
    val sample = Demo.snapshot(tick)
    tick += 1
    Right(sample)

object Demo:
  val Address: DeviceAddress        = DeviceAddress.parse("http://demo.invalid").toOption.get
  def snapshot(tick: Int): Snapshot =
    val wave = math.sin(tick / 12.0)
    val pm25 = 6.8 + wave * 2.4
    Snapshot(
      Map(
        Metric.Co2         -> (780 + wave * 180),
        Metric.Pm25        -> pm25,
        Metric.Aqi         -> Measurements.aqi(pm25),
        Metric.Pm1         -> (3.4 + wave),
        Metric.Pm10        -> (11.8 + wave * 3),
        Metric.Particles   -> (530 + wave * 100),
        Metric.Tvoc        -> (83 + wave * 18),
        Metric.Nox         -> 2.0,
        Metric.Temperature -> (22.6 + wave * 0.4),
        Metric.Humidity    -> (48.1 + wave * 2)
      ),
      aqiDerived = true
    )
  def history(now: Long): Vector[Sample] =
    Vector.tabulate(60)(n => Sample(now - (60 - n) * 30000L, "demo", snapshot(n)))
