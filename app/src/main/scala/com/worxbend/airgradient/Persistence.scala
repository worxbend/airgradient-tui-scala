package com.worxbend.airgradient

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.channels.FileChannel
import java.nio.file.{Files, Path, StandardCopyOption}
import java.nio.file.StandardOpenOption.{CREATE, WRITE}
import java.security.MessageDigest
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

object LocalPaths:
  private def root(env: String, fallback: String): Path =
    sys.env.get(env).filter(_.nonEmpty).map(Path.of(_)).getOrElse(Path.of(System.getProperty("user.home"), fallback))
  def config: Path  = root("XDG_CONFIG_HOME", ".config").resolve("airgradient-desktop/config.json")
  def history: Path = root("XDG_DATA_HOME", ".local/share").resolve("airgradient-tui-scala/history.jsonl")

final case class Settings(
    serverUrl: String = "",
    refreshSeconds: Int = 30,
    theme: String = "aurora",
    outdoor: Option[Location] = None
)
final case class LoadedSettings(settings: Settings, fingerprint: String, warnings: Vector[String])

private[airgradient] object AtomicFile:
  def replace(path: Path, content: String, beforeCommit: () => Unit = () => ()): Unit =
    val target    = path.toAbsolutePath
    val _         = Files.createDirectories(target.getParent)
    val temporary = Files.createTempFile(target.getParent, s".${target.getFileName}", ".tmp")
    try
      val _ = Files.writeString(temporary, content, UTF_8)
      beforeCommit()
      val _ = Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    finally { val _ = Files.deleteIfExists(temporary) }

final class ConfigStore(val path: Path):
  private def contents: String                   = if Files.exists(path) then Files.readString(path, UTF_8) else ""
  private def fingerprint(value: String): String =
    java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(UTF_8)))

  def load(): Either[String, LoadedSettings] =
    try
      val raw  = contents
      val root = if raw.isEmpty then ujson.Obj() else ujson.read(raw)
      root match
        case obj: ujson.Obj =>
          val url      = obj.value.get("server_url").collect { case ujson.Str(s) => s }.getOrElse("")
          val interval = obj.value.get("refresh_interval_secs").collect {
            case ujson.Num(n) if n.isWhole && n >= 5 && n <= 3600 => n.toInt
          }
          val theme    = obj.value.get("theme").collect { case ujson.Str(s) => s }.getOrElse("aurora")
          val outdoor  = obj.value.get(ConfigStore.OutdoorKey).filterNot(_ == ujson.Null).map(ConfigStore.location)
          val warnings = Vector(
            Option.when(obj.value.contains("refresh_interval_secs") && interval.isEmpty)(
              "invalid refresh interval; using 30s"
            ),
            outdoor.flatMap(_.left.toOption).map(error => s"invalid outdoor location ($error); outdoor data is off")
          ).flatten
          Right(
            LoadedSettings(
              Settings(url, interval.getOrElse(30), theme, outdoor.flatMap(_.toOption)),
              fingerprint(raw),
              warnings
            )
          )
        case _ => Left("config must contain a JSON object; repair it before saving")
    catch case NonFatal(_) => Left(s"cannot read config at $path; repair it or use --config with another file")

  /** Merge only owned fields. Fingerprints detect edits since the settings screen opened; other clients do not lock. */
  def save(settings: Settings, expected: String): Either[String, LoadedSettings] =
    for
      _      <- validate(settings)
      result <-
        try
          val raw = contents
          if fingerprint(raw) != expected then Left("settings changed in another app; reopen Settings and try again")
          else
            val root = if raw.isEmpty then ujson.Obj().obj else ujson.read(raw).obj
            root("server_url") = if settings.serverUrl.isBlank then ujson.Null
            else ujson.Str(DeviceAddress.parse(settings.serverUrl).toOption.get.toString)
            root("refresh_interval_secs") = ujson.Num(settings.refreshSeconds)
            root("theme") = ujson.Str(settings.theme)
            settings.outdoor match
              case Some(place) =>
                root(ConfigStore.OutdoorKey) = ujson.Obj(
                  "name"      -> place.name,
                  "latitude"  -> place.latitude,
                  "longitude" -> place.longitude
                )
              case None => val _ = root.remove(ConfigStore.OutdoorKey)
            val json = ujson.Obj.from(root).render(indent = 2) + "\n"
            AtomicFile.replace(
              path,
              json,
              () =>
                if fingerprint(contents) != expected then
                  throw IllegalStateException("settings changed during save; nothing was written")
            )
            load()
        catch case NonFatal(error) => Left(Option(error.getMessage).getOrElse("cannot save settings"))
    yield result

  def validate(settings: Settings): Either[String, Unit] =
    if settings.refreshSeconds < 5 || settings.refreshSeconds > 3600 then Left("refresh must be 5–3600 seconds")
    else if !Palettes.names.contains(settings.theme) then Left(s"theme must be ${Palettes.names.mkString(", ")}")
    else if settings.outdoor.exists(Location.validate(_).isLeft) then
      Left(Location.validate(settings.outdoor.get).left.toOption.get)
    else if settings.serverUrl.isBlank then Right(())
    else DeviceAddress.parse(settings.serverUrl).map(_ => ())

object ConfigStore:
  /** Owned by this app only; other AirGradient clients ignore it, and its absence means outdoor data is off. */
  val OutdoorKey = "tui_outdoor_location"

  def location(node: ujson.Value): Either[String, Location] = node match
    case obj: ujson.Obj =>
      val fields = obj.value
      (
        fields.get("name").collect { case ujson.Str(s) => s },
        fields.get("latitude").collect { case ujson.Num(n) => n },
        fields.get("longitude").collect { case ujson.Num(n) => n }
      ) match
        case (Some(name), Some(lat), Some(lon)) => Location.validate(Location(name, lat, lon))
        case _                                  => Left("needs name, latitude, and longitude")
    case _ => Left("must be an object")

trait HistoryStore:
  def load(source: String): Either[String, Vector[Sample]]
  def append(sample: Sample): Either[String, Unit]

object HistoryStore:
  def readOnly(store: HistoryStore): HistoryStore = new HistoryStore:
    def load(source: String): Either[String, Vector[Sample]] = store.load(source)
    def append(sample: Sample): Either[String, Unit]         = { val _ = sample; Right(()) }

  val Disabled: HistoryStore = new HistoryStore:
    def load(source: String): Either[String, Vector[Sample]] = { val _ = source; Right(Vector.empty) }
    def append(sample: Sample): Either[String, Unit]         = { val _ = sample; Right(()) }

final class FileHistory(val path: Path) extends HistoryStore:
  private val limit                 = 2000
  private def all(): Vector[Sample] =
    if !Files.exists(path) then Vector.empty
    else if Files.size(path) > 4 * 1024 * 1024 then
      throw IllegalStateException("history exceeds 4 MiB; move it aside before recording")
    else
      val lines = Files.readAllLines(path, UTF_8).asScala
      lines.iterator
        .flatMap { line =>
          try
            val json   = ujson.read(line)
            val values = Measurements.parse(json("snapshot").render()).toOption.get.values
            val stamp  = json("at_millis") match
              case ujson.Num(n) if n.isFinite && n >= 0 && n <= 9007199254740991.0 => n.toLong
              case ujson.Str(s)                                                    => s.toLong
              case _ => throw IllegalArgumentException("invalid timestamp")
            Some(
              Sample(
                stamp,
                json("source").str,
                Snapshot(values, json.obj.get("aqi_derived").contains(ujson.Bool(true)))
              )
            )
          catch case NonFatal(_) => None // a truncated final line must not discard the good records
        }
        .toVector
        .takeRight(limit)

  def load(source: String): Either[String, Vector[Sample]] = synchronized {
    try Right(all().filter(_.source == source))
    catch case NonFatal(error) => Left(s"cannot load history: ${error.getMessage}")
  }

  def append(sample: Sample): Either[String, Unit] = synchronized {
    var channel: Option[FileChannel]             = None
    var lock: Option[java.nio.channels.FileLock] = None
    try
      val _      = Files.createDirectories(path.toAbsolutePath.getParent)
      val opened = FileChannel.open(path.resolveSibling(path.getFileName.toString + ".lock"), CREATE, WRITE)
      channel = Some(opened)
      lock = Option(opened.tryLock())
      if lock.isEmpty then throw IllegalStateException("another instance is writing history; this sample was not saved")
      val records = (all() :+ sample).takeRight(limit)
      val text    = records
        .map(s =>
          ujson
            .Obj(
              "at_millis"   -> ujson.Num(s.atMillis.toDouble),
              "source"      -> s.source,
              "snapshot"    -> s.snapshot.json,
              "aqi_derived" -> s.snapshot.aqiDerived
            )
            .render()
        )
        .mkString("", "\n", "\n")
      AtomicFile.replace(path, text)
      Right(())
    catch case NonFatal(error) => Left(s"history not saved: ${error.getMessage}")
    finally
      lock.foreach(_.release())
      channel.foreach(_.close())
  }
