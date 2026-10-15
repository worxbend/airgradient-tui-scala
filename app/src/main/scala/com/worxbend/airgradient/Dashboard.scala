package com.worxbend.airgradient

import io.worxbend.tui.dsl.*
import io.worxbend.tui.widgets.{ChartOptions, TextInputState}
import scala.concurrent.duration.DurationInt

enum Page:
  case Overview, History, Settings

/** All mutable UI state lives on Glyphora's render owner. HTTP and filesystem work run in owned task scopes. */
final class Dashboard(
    initial: LoadedSettings,
    store: ConfigStore,
    historyStore: HistoryStore,
    sensor: SensorClient,
    demo: Boolean = false,
    initialHistory: Vector[Sample] = Vector.empty,
    now: () => Long = () => System.currentTimeMillis()
) extends TuiApp:
  val history: Signal[Vector[Sample]] = Signal(initialHistory.takeRight(2000))
  val page: Signal[Page]              = Signal(Page.Overview)
  val problem: Signal[Option[String]] = Signal(None)
  val fetching: Signal[Boolean]       = Signal(false)
  private val settings                = Signal(initial.settings)
  private val themeName               = Signal(
    if Palettes.names.contains(initial.settings.theme) then initial.settings.theme else "aurora"
  )
  private val clock                       = Signal(now())
  private val selectedMetric              = Signal(1)
  private val help                        = Signal(false)
  private val message                     = Signal(initial.warnings.headOption.getOrElse(""))
  private val urlInput                    = TextInputState(initial.settings.serverUrl)
  private val refreshInput                = TextInputState(initial.settings.refreshSeconds.toString)
  private var loaded                      = initial
  private var lifetime: Option[TaskScope] = None
  private var polling: Option[TaskScope]  = None
  private var generation                  = 0

  override def theme: Theme         = Palettes(themeName.peek)
  override def config: RunnerConfig = RunnerConfig(tickRate = Some(250.millis))
  override def onTick(): Unit       = clock.set(now() / 1000 * 1000)
  override def onStart(): Unit      =
    lifetime = Some(Async.scope())
    restartPolling()
  override def onStop(): Unit =
    generation += 1
    polling.foreach(_.close())
    lifetime.foreach(_.close())
    sensor.close()

  override def bindings: KeyBindings = KeyBindings(
    binding("q", "quit")(quit()),
    binding("esc", "back")(if help.peek then help.set(false)
    else if page.peek != Page.Overview then page.set(Page.Overview)
    else quit()),
    binding("r", "refresh")(refresh()),
    binding("h", "history")(page.set(if page.peek == Page.History then Page.Overview else Page.History)),
    binding("c", "settings")(openSettings()),
    binding("t", "theme")(cycleTheme()),
    binding("?", "help")(help.update(!_)),
    binding("ctrl+s", "save")(if page.peek == Page.Settings then saveSettings()),
    binding("j", "next metric")(selectedMetric.update(i => (i + 1) % Metric.values.length)).copy(showInHints = false),
    binding("k", "previous metric")(selectedMetric.update(i => (i + Metric.values.length - 1) % Metric.values.length))
      .copy(showInHints = false)
  )

  private def address: Option[DeviceAddress] =
    if demo then Some(Demo.Address) else DeviceAddress.parse(settings.peek.serverUrl).toOption
  private def source: String = if demo then "demo" else address.fold("")(_.toString)

  private def restartPolling(): Unit =
    generation += 1
    polling.foreach(_.close())
    sensor.cancelPending()
    polling = Some(Async.scope())
    fetching.set(false)
    refresh()
    val _ = polling.get.every(settings.peek.refreshSeconds.seconds)(refresh())

  private def refresh(): Unit =
    address match
      case None => problem.set(Some("No device configured. Press c to add its LAN address, or run with --demo."))
      case Some(device) if !fetching.peek =>
        fetching.set(true)
        val session      = generation
        val deviceSource = source
        val _            = polling.get.runCatching {
          sensor.fetch(device).map { snapshot =>
            val sample = Sample(now(), deviceSource, snapshot)
            (sample, historyStore.append(sample).left.toOption)
          }
        } { outcome =>
          if generation == session then
            fetching.set(false)
            outcome.fold(error => Left(Option(error.getMessage).getOrElse("request failed")), identity) match
              case Left(error)              => problem.set(Some(error))
              case Right((sample, warning)) =>
                val previous = history.peek.lastOption.map(_.snapshot.worst)
                history.update(samples => (samples :+ sample).takeRight(2000))
                clock.set(now())
                problem.set(None)
                warning.foreach(message.set)
                previous.foreach { band =>
                  if band.ordinal < sample.snapshot.worst.ordinal && sample.snapshot.worst.ordinal >= Band.Elevated.ordinal
                  then notify(s"Air readings are now ${sample.snapshot.worst.label}", NoticeLevel.Warning)
                  else if band.ordinal >= Band.Elevated.ordinal && sample.snapshot.worst == Band.Good then
                    notify("Air readings recovered to Good", NoticeLevel.Success)
                }
        }
      case _ => ()

  private def cycleTheme(): Unit =
    val next = Palettes.names((Palettes.names.indexOf(themeName.peek) + 1) % Palettes.names.size)
    themeName.set(next)
    message.set(s"Theme: $next (session only; save in Settings to keep)")

  private def openSettings(): Unit =
    page.set(Page.Settings)
    if demo then message.set("Demo settings are session-only; no configuration or history is written.")
    else
      val _ = lifetime.get.runCatching(store.load()) { outcome =>
        outcome.fold(error => Left(error.getMessage), identity) match
          case Left(error)   => message.set(error)
          case Right(config) =>
            loaded = config
            urlInput.clear()
            urlInput.insert(settings.peek.serverUrl)
            refreshInput.clear()
            refreshInput.insert(settings.peek.refreshSeconds.toString)
            requestRedraw()
      }

  private def saveSettings(): Unit =
    refreshInput.value.toIntOption match
      case None          => message.set("Refresh must be a whole number from 5 to 3600.")
      case Some(seconds) =>
        val next = Settings(urlInput.value.trim, seconds, themeName.peek)
        store.validate(next) match
          case Left(error)      => message.set(error)
          case Right(_) if demo =>
            settings.set(next)
            restartPolling()
            message.set("Demo settings applied for this session.")
          case Right(_) =>
            val expected = loaded.fingerprint
            val _        = lifetime.get.runCatching {
              store
                .save(next, expected)
                .map(config =>
                  (
                    config,
                    historyStore.load(DeviceAddress.parse(config.settings.serverUrl).toOption.fold("")(_.toString))
                  )
                )
            } { outcome =>
              outcome.fold(error => Left(error.getMessage), identity) match
                case Left(error)                   => message.set(error)
                case Right((config, savedHistory)) =>
                  val changed = config.settings.serverUrl != settings.peek.serverUrl
                  loaded = config
                  settings.set(config.settings)
                  if changed then history.set(savedHistory.getOrElse(Vector.empty))
                  restartPolling()
                  message.set("Settings saved. Unknown desktop settings were preserved.")
            }

  def view(using ReactiveScope, Theme): Element =
    given palette: Theme = Palettes(themeName.get)
    val _                = clock.get
    responsive { size =>
      if size.width < 48 || size.height < 18 then
        panel("AirGradient")(text("Please resize to at least 48 x 18."), text("q: quit")).rounded
      else
        val content = page.get match
          case Page.Overview => overview(size.width)
          case Page.History  => historyPage
          case Page.Settings => settingsPage
        val shell = column(
          topBar(
            "AIRGRADIENT",
            right = if demo then "DEMO / synthetic" else address.fold("LOCAL / unconfigured")(_.base.getAuthority)
          ).length(1),
          row(
            Page.values.map(p =>
              button(if page.get == p then s"[${p.toString}]" else p.toString) {
                if p == Page.Settings then openSettings() else page.set(p)
              }.length(15)
            )*
          ).length(1),
          connectionLine.length(1),
          content.fill,
          (if message.get.nonEmpty then text(message.get).styled(_ => palette.muted)
           else text("r refresh  h history  c settings  t theme  ? help  q quit").dim).length(1)
        ).styled(_.patch(palette.primary))
        if help.get then
          layers(
            shell,
            centered(math.min(size.width - 4, 64), 12)(
              panel("Keyboard guide")(
                text("r  refresh now       h  overview / history"),
                text("c  settings          t  preview next theme"),
                text("j/k  select history metric"),
                text("Tab / Shift+Tab  move between controls"),
                text("Ctrl+s  save settings     Esc  back / quit"),
                text("?  close this guide      q  quit"),
                text("Demo never writes your settings or history.").dim
              ).rounded.styled(_.patch(palette.surface))
            )
          )
        else shell
    }

  private def connectionLine(using ReactiveScope, Theme): Element =
    val latest = history.get.lastOption
    val age    = latest.fold("no reading yet")(s => s"updated ${math.max(0L, (clock.get - s.atMillis) / 1000)}s ago")
    problem.get match
      case Some(error) =>
        text(s"${if latest.nonEmpty then "STALE" else "OFFLINE"} / $error / $age").styled(_ => summon[Theme].error)
      case None if fetching.get => text(s"FETCHING / $age").styled(_ => summon[Theme].accent)
      case None                 =>
        text(
          s"${if demo then "DEMO" else "LIVE"} / $age / every ${settings.get.refreshSeconds}s / ${history.get.size} samples"
        ).styled(_ => summon[Theme].muted)

  private def overview(width: Int)(using ReactiveScope, Theme): Element =
    history.get.lastOption match
      case None =>
        panel("Your room, at a glance")(
          spacer,
          text(if fetching.get then "Connecting to your AirGradient..."
          else "Connect an AirGradient monitor on your LAN."),
          text("Press c for settings. Try --demo to explore without hardware.").dim,
          spacer
        ).rounded
      case Some(sample) =>
        val snapshot = sample.snapshot
        val aqi      = snapshot.value(Metric.Aqi)
        val band     = Metric.Aqi.classify(aqi)
        val hero     = panel("AIR QUALITY / PM2.5 snapshot")(
          row(
            text(s"${Metric.Aqi.format(aqi)} US AQI").bold.styled(_ => Palettes.bandStyle(band)).length(20),
            text(band.label).bold.styled(_ => Palettes.bandStyle(band)).fill
          ).length(1),
          gauge(aqi.getOrElse(0.0) / 500)
            .label(if aqi.isEmpty then "unavailable" else "0                                                500")
            .ramp(ColorRamp.Traffic)
            .length(1),
          text(if snapshot.aqiDerived then "Estimated from current PM2.5; not a 24-hour exposure average."
          else "AQI reported by the device.").dim.length(1)
        ).rounded.length(5)
        val metrics = Metric.values.filterNot(_ == Metric.Aqi).toVector
        if width < 90 then column(hero, panel("Measurements")(metrics.map(m => metricRow(m, snapshot))*).fill)
        else
          val cards = metrics
            .filterNot(_ == Metric.Particles)
            .grouped(4)
            .map(group => row(group.map(m => card(m, snapshot).fill)*).gap(1).length(5))
            .toSeq
          column((Seq(hero) ++ cards ++ Seq(panel("CO2 / recent samples (ppm)")(historyChart(Metric.Co2)).fill))*)

  private def metricRow(metric: Metric, snapshot: Snapshot)(using Theme): Element =
    val value = snapshot.value(metric)
    row(
      text(metric.label).length(17),
      text(s"${metric.format(value)} ${metric.unit}").length(19),
      text(metric.classify(value).label).styled(_ => Palettes.bandStyle(metric.classify(value))).fill
    ).length(1)

  private def card(metric: Metric, snapshot: Snapshot)(using ReactiveScope, Theme): Element =
    val value = snapshot.value(metric)
    val band  = metric.classify(value)
    panel(metric.label)(
      row(text(s"${metric.format(value)} ${metric.unit}").bold.fill, text(band.label).length(14)).length(1),
      (if value.isEmpty then text("not reported")
       else progressBar(value.get / metric.ceiling).bare.ramp(ColorRamp.Traffic)).length(1),
      sparkRow(metric).length(1)
    ).rounded.styled(_.patch(Palettes.bandStyle(band)))

  private def sparkRow(metric: Metric)(using ReactiveScope, Theme): Element =
    val series = history.get.takeRight(80).map(_.snapshot.value(metric))
    if series.exists(_.isEmpty) then text("gaps / some samples not reported").dim
    else
      sparkline(series.flatten.map(n => math.round(math.max(0, n) * 10)))
        .max(math.round(metric.ceiling * 10))
        .styled(_ => summon[Theme].accent)

  private def historyPage(using ReactiveScope, Theme): Element =
    val metric  = Metric.values(selectedMetric.get)
    val samples = history.get
    val valid   = samples.flatMap(s => s.snapshot.value(metric).map(n => s.atMillis -> n))
    if valid.isEmpty then
      panel("History")(text(s"No ${metric.label} samples yet."), text("j/k: choose another metric").dim)
    else
      panel(s"HISTORY / ${metric.label} (${metric.unit})")(
        row(
          button("Previous")(selectedMetric.update(i => (i + 9) % 10)).length(14),
          text(s"${metric.label} / j,k to switch").fill,
          button("Next")(selectedMetric.update(i => (i + 1) % 10)).length(10)
        ).length(1),
        historyChart(metric).fill,
        text(
          s"min ${metric.format(Some(valid.map(_._2).min))} / max ${metric.format(Some(valid.map(_._2).max))} / ${valid.size} samples / minutes since oldest sample"
        ).dim.length(1),
        text("Missing readings and missed polls break the line. History is local to this device.").dim.length(1)
      ).rounded

  private def historyChart(metric: Metric)(using ReactiveScope, Theme): Element =
    val samples = history.get
    val valid   = samples.flatMap(_.snapshot.value(metric))
    if valid.isEmpty then text("No samples available").dim
    else
      val start    = samples.head.atMillis
      val end      = math.max(1.0, (samples.last.atMillis - start) / 60000.0)
      val segments = samples
        .foldLeft(Vector(Vector.empty[(Double, Double)])) { (groups, sample) =>
          sample.snapshot.value(metric) match
            case None    => groups :+ Vector.empty
            case Some(n) =>
              val point = (sample.atMillis - start) / 60000.0 -> n
              if groups.last.lastOption.exists(p => point._1 - p._1 > settings.get.refreshSeconds * 1.5 / 60) then
                groups :+ Vector(point)
              else groups.init :+ (groups.last :+ point)
        }
        .filter(_.nonEmpty)
      val datasets = segments.map(points => Dataset(metric.label, points, style = summon[Theme].accent))
      chart(
        datasets,
        Bounds(0, end),
        Bounds(math.min(0, valid.min), math.max(metric.ceiling, valid.max * 1.05)),
        ChartOptions(showLegend = false)
      )

  private def settingsPage(using ReactiveScope, Theme): Element =
    panel("SETTINGS / shared with AirGradient desktop")(
      text("Device address").bold.length(1),
      input(urlInput, "airgradient.local or http://host:port").key("device-url").length(1),
      text("Refresh interval / seconds, 5–3600").length(1),
      input(refreshInput, "30").key("refresh-seconds").length(1),
      text(s"Theme: ${themeName.get} / press t or use Preview theme").length(1),
      row(button("Preview theme")(cycleTheme()).length(20), button("Save / Ctrl+s")(saveSettings()).length(22))
        .length(1),
      text(if demo then "Demo changes remain in memory." else s"Config: ${store.path}").dim.length(1),
      text("Tab moves focus. Esc returns. Session overrides are saved only here.").dim.length(1),
      spacer
    ).rounded
