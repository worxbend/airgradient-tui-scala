package com.worxbend.airgradient

import io.worxbend.tui.dsl.*
import io.worxbend.tui.widgets.{BigText, CanvasResolution, ChartOptions, DropdownState, Heatmap, TextInputState}
import java.util.Locale
import scala.concurrent.duration.DurationInt

enum Page:
  case Overview, History, Summary, Comfort, Outdoor, Forecast, Settings

/** All mutable UI state lives on Glyphora's render owner. HTTP and filesystem work run in owned task scopes. */
final class Dashboard(
    initial: LoadedSettings,
    store: ConfigStore,
    historyStore: HistoryStore,
    sensor: SensorClient,
    demo: Boolean = false,
    initialHistory: Vector[Sample] = Vector.empty,
    now: () => Long = () => System.currentTimeMillis(),
    outdoorSource: Option[OutdoorClient] = None
) extends TuiApp:
  // Demo mode never builds a network client, so it stays offline even with a location configured.
  private val outdoorClient: OutdoorClient =
    outdoorSource.getOrElse(if demo then DemoOutdoorClient() else OpenMeteoClient())
  val history: Signal[Vector[Sample]] = Signal(initialHistory.takeRight(2000))
  val page: Signal[Page]              = Signal(Page.Overview)
  val problem: Signal[Option[String]] = Signal(None)
  val fetching: Signal[Boolean]       = Signal(false)
  private val settings                = Signal(initial.settings)
  private val themeName               = Signal(
    if Palettes.names.contains(initial.settings.theme) then initial.settings.theme else "aurora"
  )
  private val analytics                       = Computed(Telemetry.analyze(history.get, settings.get.refreshSeconds))
  private val themeDropdown                   = DropdownState()
  private val clock                           = Signal(now())
  private val selectedMetric                  = Signal(1)
  private val help                            = Signal(false)
  private val message                         = Signal(initial.warnings.headOption.getOrElse(""))
  private val urlInput                        = TextInputState(initial.settings.serverUrl)
  private val refreshInput                    = TextInputState(initial.settings.refreshSeconds.toString)
  private var loaded                          = initial
  private var lifetime: Option[TaskScope]     = None
  private var polling: Option[TaskScope]      = None
  private var generation                      = 0
  val outdoor: Signal[Option[OutdoorReport]]  = Signal(None)
  val outdoorProblem: Signal[Option[String]]  = Signal(None)
  private val outdoorFetching                 = Signal(false)
  private val locationInput                   = TextInputState(initial.settings.outdoor.fold("")(_.name))
  private var outdoorScope: Option[TaskScope] = None
  private var outdoorGeneration               = 0
  private var outdoorRequestedAt              = Long.MinValue
  private var lastAdvice: Option[String]      = None

  override def theme: Theme         = Palettes(themeName.peek)
  override def config: RunnerConfig = RunnerConfig(tickRate = Some(250.millis))
  override def onTick(): Unit       = clock.set(now() / 1000 * 1000)
  override def onStart(): Unit      =
    lifetime = Some(Async.scope())
    restartPolling()
    restartOutdoor()
  override def onStop(): Unit =
    generation += 1
    outdoorGeneration += 1
    polling.foreach(_.close())
    outdoorScope.foreach(_.close())
    lifetime.foreach(_.close())
    sensor.close()
    outdoorClient.close()
    analytics.dispose()

  override def bindings: KeyBindings = KeyBindings(
    binding("q", "quit")(quit()),
    binding("esc", "back")(if help.peek then help.set(false)
    else if page.peek != Page.Overview then page.set(Page.Overview)
    else quit()),
    binding("r", "refresh")({ refresh(); refreshOutdoor(force = false) }),
    binding("h", "history")(page.set(if page.peek == Page.History then Page.Overview else Page.History)),
    binding("c", "settings")(openSettings()),
    binding("o", "outdoor")(go(Page.Outdoor)),
    binding("f", "forecast")(go(Page.Forecast)),
    binding("t", "theme")(cycleTheme()),
    binding("?", "help")(help.update(!_)),
    binding("ctrl+s", "save")(if page.peek == Page.Settings then saveSettings()),
    binding("j", "next metric")(selectedMetric.update(i => (i + 1) % Metric.values.length)).copy(showInHints = false),
    binding("k", "previous metric")(selectedMetric.update(i => (i + Metric.values.length - 1) % Metric.values.length))
      .copy(showInHints = false)
  ) ++ KeyBindings(
    Page.values.toVector.map(p => binding((p.ordinal + 1).toString, s"Go to $p")(go(p)).copy(showInHints = false))*
  ) ++ KeyBindings(
    (Palettes.names
      .map(name => KeyBinding(Seq.empty, "theme", s"Use theme $name", () => previewTheme(name), showInHints = false)) ++
      Metric.values.map(metric =>
        KeyBinding(
          Seq.empty,
          "metric",
          s"Inspect ${metric.label} history",
          () => {
            selectedMetric.set(metric.ordinal)
            page.set(Page.History)
          },
          showInHints = false
        )
      ))*
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
                checkAdvice()
        }
      case _ => ()

  private def go(target: Page): Unit = if target == Page.Settings then openSettings() else page.set(target)
  private def step(delta: Int): Unit =
    go(Page.values(math.floorMod(page.peek.ordinal + delta, Page.values.length)))

  private def outdoorPlace(current: Settings): Option[Location] =
    current.outdoor.orElse(Option.when(demo)(DemoOutdoor.Place))

  /** One owned scope per outdoor location. Changing the place retires the scope, so late results cannot land. */
  private def restartOutdoor(): Unit =
    outdoorGeneration += 1
    outdoorScope.foreach(_.close())
    outdoorScope = None
    outdoorClient.cancelPending()
    outdoorFetching.set(false)
    outdoor.set(None)
    outdoorProblem.set(None)
    outdoorRequestedAt = Long.MinValue
    if outdoorPlace(settings.peek).nonEmpty then
      outdoorScope = Some(Async.scope())
      refreshOutdoor(force = true)
      val _ = outdoorScope.get.every(15.minutes)(refreshOutdoor(force = true))

  /** Manual refreshes are throttled to one request per 30 seconds; Open-Meteo updates at most every 15 minutes. */
  private def refreshOutdoor(force: Boolean): Unit =
    (outdoorPlace(settings.peek), outdoorScope) match
      case (Some(place), Some(scope)) if !outdoorFetching.peek && (force || now() - outdoorRequestedAt >= 30000) =>
        outdoorFetching.set(true)
        outdoorRequestedAt = now()
        val session = outdoorGeneration
        val at      = now()
        val _       = scope.runCatching(outdoorClient.report(place, at)) { outcome =>
          if outdoorGeneration == session then
            outdoorFetching.set(false)
            outcome.fold(error => Left(Option(error.getMessage).getOrElse("request failed")), identity) match
              case Left(error)   => outdoorProblem.set(Some(error))
              case Right(report) =>
                outdoor.set(Some(report))
                outdoorProblem.set(report.warnings.headOption)
                checkAdvice()
        }
      case _ => ()

  private def checkAdvice(): Unit =
    val advice = Advice.ventilation(history.peek.lastOption.map(_.snapshot), outdoor.peek)
    if lastAdvice.nonEmpty && !lastAdvice.contains(advice.headline) && advice.urgent then
      notify(s"${advice.headline}: ${advice.detail}", NoticeLevel.Warning)
    lastAdvice = Some(advice.headline)

  private def syncLocationInput(): Unit =
    locationInput.clear()
    locationInput.insert(settings.peek.outdoor.fold("")(_.name))

  /** Coordinates resolve locally; a place name needs one geocoding request, which runs off the render thread. */
  private def resolveLocation(query: String, current: Option[Location]): Either[String, Option[Location]] =
    if query.isEmpty then Right(None)
    else if current.exists(_.name == query) then Right(current)
    else
      Location.parseCoordinates(query) match
        case Some(result) => result.map(Some(_))
        case None         => outdoorClient.geocode(query).map(Some(_))

  private def cycleTheme(): Unit =
    val next = Palettes.names((Palettes.names.indexOf(themeName.peek) + 1) % Palettes.names.size)
    previewTheme(next)

  private def previewTheme(name: String): Unit =
    themeName.set(name)
    message.set(s"Theme: $name (session only; save in Settings to keep)")

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
            syncLocationInput()
            requestRedraw()
      }

  private def saveSettings(): Unit =
    refreshInput.value.toIntOption match
      case None          => message.set("Refresh must be a whole number from 5 to 3600.")
      case Some(seconds) =>
        val next    = Settings(urlInput.value.trim, seconds, themeName.peek)
        val query   = locationInput.value.trim
        val current = settings.peek.outdoor
        store.validate(next) match
          case Left(error)      => message.set(error)
          case Right(_) if demo =>
            resolveLocation(query, current) match
              case Left(error)  => message.set(error)
              case Right(place) =>
                settings.set(next.copy(outdoor = place))
                restartPolling()
                if place != current then restartOutdoor()
                syncLocationInput()
                message.set("Demo settings applied for this session.")
          case Right(_) =>
            val expected = loaded.fingerprint
            val _        = lifetime.get.runCatching {
              resolveLocation(query, current)
                .flatMap(place => store.save(next.copy(outdoor = place), expected))
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
                  val changed        = config.settings.serverUrl != settings.peek.serverUrl
                  val outdoorChanged = config.settings.outdoor != settings.peek.outdoor
                  loaded = config
                  settings.set(config.settings)
                  if changed then history.set(savedHistory.getOrElse(Vector.empty))
                  restartPolling()
                  if outdoorChanged then restartOutdoor()
                  syncLocationInput()
                  message.set("Settings saved. Unknown desktop settings were preserved.")
            }

  def view(using ReactiveScope, Theme): Element =
    given palette: Theme = Palettes(themeName.get)
    val _                = clock.get
    val screen           = responsive { size =>
      if size.width < 48 || size.height < 18 then
        panel("AirGradient")(text("Please resize to at least 48 x 18."), text("q: quit")).rounded
      else
        val content = page.get match
          case Page.Overview => overview(size)
          case Page.History  => historyPage(size)
          case Page.Summary  => summaryPage(size)
          case Page.Comfort  => comfortPage(size)
          case Page.Outdoor  => outdoorPage(size)
          case Page.Forecast => forecastPage(size)
          case Page.Settings => settingsPage(size)
        val activeMark = if palette.glyphs == GlyphSupport.Ascii then ">" else "›"
        val shell      = column(
          row(
            text(" AIRGRADIENT").bold.styled(_ => palette.accent).length(15),
            text(if size.width >= 100 then "air quality / local observatory" else "air quality")
              .styled(_ => palette.muted)
              .fill,
            (if size.width >= 110 then
               text(if demo then "offline demo" else address.fold("unconfigured")(_.base.getAuthority))
                 .styled(_ => palette.muted)
                 .length(25)
             else spacer.length(0)),
            badge(if demo then "DEMO / synthetic" else "LOCAL API").outline
          ).styled(_ => palette.surface).length(1),
          navigation(size.width, activeMark).length(1),
          connectionLine(size.width).length(1),
          content.fill,
          footer(size.width).length(1)
        ).styled(_ => palette.primary)
        if help.get then
          layers(
            shell,
            centered(math.min(size.width - 4, 70), 16)(
              panel("Keyboard guide / make yourself at home")(
                text("1-7  switch page        o / f  outdoor / forecast"),
                text("r  refresh now          h  overview / history"),
                text("c  settings             t  preview next theme"),
                text("j/k  select history metric"),
                text("Ctrl+p  search commands, metrics, and themes"),
                text("Tab / Shift+Tab  move between controls"),
                text("Ctrl+s  save settings   Esc  back / quit"),
                text("?  close this guide     q  quit outside editing"),
                rule(),
                text("Demo uses synthetic readings and never writes to disk.").styled(_ => palette.muted),
                text("AQI is a current reading, not a 24-hour average.").styled(_ => palette.muted),
                text("Outdoor data: Open-Meteo, only for a location you set.").styled(_ => palette.muted)
              ).rounded.styled(_ => palette.surface)
            )
          )
        else shell
    }
    if palette.name == "mono" then screen
    else
      // A root portal runs after ordinary painting, including Glyphora's command palette and toasts.
      // Resolve terminal defaults in the actual frame while preserving explicit panel/focus colors and input routing.
      // This ordinary render pass requires no extra animation ticks or terminal-wide color changes.
      layers(
        screen,
        responsive { size =>
          portal(0, 0, size.width, size.height)(
            widget((area, buffer) =>
              buffer.foreachIn(area) { (x, y, cell) =>
                if !cell.style.bg.exists(_ != Color.Reset) || !cell.style.fg.exists(_ != Color.Reset) then
                  val withoutResetFg = if cell.style.fg.contains(Color.Reset) then cell.style.withoutFg else cell.style
                  val explicit       =
                    if withoutResetFg.bg.contains(Color.Reset) then withoutResetFg.withoutBg else withoutResetFg
                  val style = palette.primary.patch(explicit)
                  buffer.set(x, y, cell.copy(style = style))
              }
            )
          )
        }
      )

  private def navigation(width: Int, activeMark: String)(using ReactiveScope, Theme): Element =
    val palette = summon[Theme]
    if width >= 104 then
      row(
        Page.values.map(p =>
          button(if page.get == p then s"$activeMark $p" else p.toString)(go(p))
            .length(p.toString.length + 7)
            .styled(_ => if page.get == p then palette.accent.bold else palette.primary)
        )*
      )
    else
      val (previous, next) = if palette.glyphs == GlyphSupport.Ascii then ("<", ">") else ("‹", "›")
      row(
        button(previous)(step(-1)).length(5),
        text(s" $activeMark ${page.get}  ${page.get.ordinal + 1}/${Page.values.length}").bold
          .styled(_ => palette.accent)
          .fill,
        text("1-7 jump ").styled(_ => palette.muted).length(9),
        button(next)(step(1)).length(5)
      )

  private def keyHints(gap: Int, hints: (String, String)*)(using Theme): Element =
    row(
      hints.map((key, label) =>
        row(
          text(key).bold.styled(_ => summon[Theme].accent).length(key.length),
          text(s" $label").styled(_ => summon[Theme].muted).length(label.length + 1)
        ).length(key.length + label.length + 1)
      )*
    ).gap(gap)

  private def footer(width: Int)(using ReactiveScope, Theme): Element =
    if message.get.nonEmpty then text(message.get).styled(_ => summon[Theme].muted)
    else if width < 75 then
      keyHints(2, "r" -> "refresh", "h" -> "history", "c" -> "settings", "?" -> "help", "q" -> "quit")
    else
      row(
        keyHints(
          3,
          (Vector("r" -> "refresh", "h" -> "history", "c" -> "settings") ++
            Option.when(width >= 104)("1-7" -> "pages") ++
            Vector("t" -> "theme", "^p" -> "commands", "?" -> "help", "q" -> "quit"))*
        ).fill,
        text(themeName.get).styled(_ => summon[Theme].accent).length(12)
      )

  private def connectionLine(width: Int)(using ReactiveScope, Theme): Element =
    val status = deviceStatus
    val brief  = outdoor.get.filter(_ => width >= 100).map { report =>
      val temperature = OutdoorMetric.Temperature.format(report.value(OutdoorMetric.Temperature))
      val aqi         = OutdoorMetric.UsAqi.format(report.value(OutdoorMetric.UsAqi))
      s"outside $temperature C / US AQI $aqi / ${report.condition}"
    }
    brief.fold(status)(text0 => row(status.fill, text(text0).styled(_ => summon[Theme].muted).length(text0.length)))

  private def deviceStatus(using ReactiveScope, Theme): Element =
    val latest = history.get.lastOption
    val age    = latest.fold("no reading yet")(s => s"updated ${math.max(0L, (clock.get - s.atMillis) / 1000)}s ago")
    problem.get match
      case Some(error) =>
        val retry = if fetching.get then " / RECONNECTING" else ""
        text(s"${if latest.nonEmpty then "STALE" else "OFFLINE"}$retry / $error / $age").styled(_ =>
          summon[Theme].error
        )
      case None if fetching.get => text(s"FETCHING / $age").styled(_ => summon[Theme].accent)
      case None                 =>
        val state = if demo then "DEMO" else "LIVE"
        row(
          text(state).bold
            .styled(_ => if demo then summon[Theme].accent else summon[Theme].success)
            .length(state.length),
          text(s" / $age / every ${settings.get.refreshSeconds}s / ${history.get.size} samples")
            .styled(_ => summon[Theme].muted)
            .fill
        )

  private def overview(size: Size)(using ReactiveScope, Theme): Element =
    history.get.lastOption match
      case None =>
        panel(if fetching.get then "CONNECTING / your room, at a glance" else "YOUR ROOM / ready when you are")(
          spacer,
          (if fetching.get then spinner("Connecting to your AirGradient...")
           else text("Connect an AirGradient monitor on your LAN.").bold).length(1),
          text("Press c for settings. Try --demo to explore without hardware.")
            .styled(_ => summon[Theme].muted)
            .length(1),
          text("Unavailable readings stay unavailable. Your data stays local.")
            .styled(_ => summon[Theme].muted)
            .length(1),
          row(button("Open settings")(openSettings()).length(20), button("Commands")(openPalette()).length(16))
            .length(1),
          spacer
        ).rounded.styled(_ => summon[Theme].surface)
      case Some(sample) =>
        val snapshot = sample.snapshot
        val metrics  = Vector(
          Metric.Co2,
          Metric.Pm25,
          Metric.Tvoc,
          Metric.Temperature,
          Metric.Humidity,
          Metric.Nox,
          Metric.Pm1,
          Metric.Pm10,
          Metric.Particles
        )
        if size.width < 90 || size.height < 28 then
          val tablePanel = panel("SENSOR INVENTORY / current readings")(
            metrics.map(m => metricRow(m, snapshot))*
          ).rounded.length(11)
          val summary = if size.height < 24 then
            row(
              text(s"${Metric.Aqi.format(snapshot.value(Metric.Aqi))} US AQI").bold
                .styled(_ => Palettes.bandStyle(Metric.Aqi.classify(snapshot.value(Metric.Aqi))))
                .length(15),
              text(Metric.Aqi.classify(snapshot.value(Metric.Aqi)).label).fill
            ).length(1)
          else aqiPanel(snapshot).length(5)
          if size.height >= 30 then column(summary, tablePanel, timeline(Metric.Co2, "CO2 / recent samples (ppm)").fill)
          else
            column(
              summary,
              tablePanel,
              text("h history / c settings / Ctrl+p commands").styled(_ => summon[Theme].muted).length(1),
              spacer
            )
        else
          val expanded = size.width >= 140 && size.height >= 42
          val summary  = row(
            (if expanded then largeAqiPanel(snapshot) else aqiPanel(snapshot)).fill(2),
            roomPanel(snapshot, expanded).fill(3)
          ).gap(1).length(if expanded then 9 else 5)
          val cards =
            metrics.grouped(3).map(group => row(group.map(m => card(m, snapshot).fill)*).gap(1).length(4)).toVector
          val charts = if size.width >= 140 && size.height >= 38 then
            row(
              timeline(Metric.Co2, "CO2 / ventilation trend (ppm)").fill(2),
              timeline(Metric.Pm25, "PM2.5 / particulate trend (ug/m3)").fill(2),
              reportingMatrix(size.width).fill
            ).gap(1)
          else if size.width >= 140 then
            row(
              timeline(Metric.Co2, "CO2 / ventilation trend (ppm)").fill,
              timeline(Metric.Pm25, "PM2.5 / particulate trend (ug/m3)").fill
            ).gap(1)
          else timeline(Metric.Co2, "CO2 / recent samples (ppm)")
          column((Vector(summary) ++ cards :+ charts.fill)*)

  private def reportingMatrix(width: Int)(using ReactiveScope, Theme): Element =
    val metrics = Metric.values.filterNot(_ == Metric.Aqi).toVector
    val samples = history.get.takeRight(math.min(12, math.max(1, width / 5 - 17)))
    val labels  = column(metrics.map(metric => text(metric.label).length(1))*).length(9)
    val matrix  = if summon[Theme].glyphs == GlyphSupport.Ascii then
      column(
        metrics.map(metric =>
          text(samples.map(s => if s.snapshot.value(metric).isDefined then "#" else ".").mkString).length(1)
        )*
      ).length(9)
    else
      widget(
        Heatmap(
          metrics.map(metric => samples.map(sample => if sample.snapshot.value(metric).isDefined then 1.0 else 0.0)),
          style = summon[Theme].accent
        )
      ).length(9)
    panel("REPORTING / recent samples")(
      row(labels.length(15), matrix.fill).length(9),
      text("Each column is a sample.").styled(_ => summon[Theme].muted).length(1),
      text(if summon[Theme].glyphs == GlyphSupport.Ascii then "# = available" else "Filled = available")
        .styled(_ => summon[Theme].muted)
        .length(1),
      text(if summon[Theme].glyphs == GlyphSupport.Ascii then ". = missing" else "Blank = missing")
        .styled(_ => summon[Theme].muted)
        .length(1),
      text(s"Last ${samples.size} successful samples.").styled(_ => summon[Theme].muted).length(1),
      spacer
    ).rounded.styled(_ => summon[Theme].surface)

  private def aqiPanel(snapshot: Snapshot)(using Theme): Element =
    val value = snapshot.value(Metric.Aqi)
    val band  = Metric.Aqi.classify(value)
    panel("AIR QUALITY / current AQI")(
      row(
        text(s"${Metric.Aqi.format(value)} US AQI").bold.styled(_ => Palettes.bandStyle(band)).fill,
        badge(band.label).outline.styled(_ => Palettes.bandStyle(band))
      ).length(1),
      (if value.isEmpty then text("not reported / unavailable")
       else gauge(value.get / 500).label("current AQI / 0 to 500").styled(_ => Palettes.bandStyle(band))).length(1),
      text(if snapshot.aqiDerived then "PM2.5 estimate / not a 24h average."
      else "AQI reported by the device.").styled(_ => summon[Theme].muted).length(1)
    ).rounded

  private def largeAqiPanel(snapshot: Snapshot)(using Theme): Element =
    val value  = snapshot.value(Metric.Aqi)
    val band   = Metric.Aqi.classify(value)
    val number = Metric.Aqi.format(value)
    panel("AIR QUALITY / current US AQI")(
      row(
        widget(
          BigText(
            number,
            style = Palettes.bandStyle(band),
            pixel = if summon[Theme].glyphs == GlyphSupport.Ascii then "#" else "█"
          )
        )
          .length(14),
        column(
          text(s"$number US AQI").bold.length(1),
          badge(band.label).outline.styled(_ => Palettes.bandStyle(band)).length(1),
          spacer,
          text("Current sample / US AQI scale").styled(_ => summon[Theme].muted).length(1),
          text("Lower AQI is better.").styled(_ => summon[Theme].muted).length(1)
        ).fill
      ).gap(2).length(5),
      (if value.isEmpty then text("not reported / unavailable")
       else gauge(value.get / 500).label("current AQI / 0 to 500").styled(_ => Palettes.bandStyle(band))).length(1),
      text(if snapshot.aqiDerived then "PM2.5 estimate / not a 24h average." else "AQI reported by the device.")
        .styled(_ => summon[Theme].muted)
        .length(1)
    ).rounded

  private def roomPanel(snapshot: Snapshot, expanded: Boolean)(using ReactiveScope, Theme): Element =
    val available = Metric.values.count(m => snapshot.value(m).nonEmpty)
    val trend     = analytics.get.metrics(Metric.Co2)
    val band      = snapshot.worst
    panel("ROOM SIGNAL / available readings")(
      row(
        text(band.label).bold.styled(_ => Palettes.bandStyle(band)).length(20),
        text(s"$available / ${Metric.values.length} available").styled(_ => summon[Theme].muted).fill
      ).length(1),
      text(if band.ordinal >= Band.Elevated.ordinal then "Elevated readings / inspect the sensor cards below."
      else if available < Metric.values.length then "Some readings are unavailable. No zeroes are substituted."
      else "All readings available / local API / no cloud account.").length(1),
      text(s"CO2 sample mean ${Metric.Co2.format(trend.average)} ppm / peak ${Metric.Co2.format(trend.maximum)} ppm")
        .styled(_ => summon[Theme].muted)
        .length(1),
      (if expanded then
         column(
           rule().length(1),
           text(
             s"${history.get.size} successful observations / ${math.round(analytics.get.minutes)} minutes of history"
           ).length(1),
           text("h / explore trends     Ctrl+p / jump to a sensor").styled(_ => summon[Theme].muted).length(1),
           text("Freshness is based on receipt time, not device time.").styled(_ => summon[Theme].muted).length(1)
         ).length(4)
       else spacer.length(0))
    ).rounded.styled(_ => summon[Theme].surface)

  private def metricRow(metric: Metric, snapshot: Snapshot)(using Theme): Element =
    val value = snapshot.value(metric)
    row(
      text(metric.label).length(14),
      text(s"${metric.format(value)} ${metric.unit}").bold.length(16),
      text(metric.classify(value).label).styled(_ => Palettes.bandStyle(metric.classify(value))).fill
    ).gap(1).length(1)

  private def card(metric: Metric, snapshot: Snapshot)(using ReactiveScope, Theme): Element =
    val value = snapshot.value(metric)
    val band  = metric.classify(value)
    panel(s"${metric.label} / ${metric.unit}")(
      row(
        text(s"${metric.format(value)} ${metric.unit}").bold.fill,
        text(band.label).styled(_ => Palettes.bandStyle(band)).length(14)
      ).length(1),
      (if value.isEmpty then text("not reported").styled(_ => summon[Theme].muted)
       else sparkRow(metric)).length(1)
    ).rounded.styled(_ => summon[Theme].surface)

  private def sparkRow(metric: Metric)(using ReactiveScope, Theme): Element =
    val series = analytics.get.metrics(metric).values.takeRight(80)
    if series.exists(_.isEmpty) then text("gaps / some samples not reported").styled(_ => summon[Theme].muted)
    else if series.flatten.exists(_ < 0) then text("below zero / see history").styled(_ => summon[Theme].muted)
    else
      val values     = series.flatten
      val minimum    = values.minOption.getOrElse(0.0)
      val maximum    = values.maxOption.getOrElse(0.0)
      val normalized =
        values.map(n => if maximum == minimum then 4.0 else 1.0 + (n - minimum) / (maximum - minimum) * 6.0)
      if summon[Theme].glyphs == GlyphSupport.Ascii then
        val shades = " .:-=+*#@"
        text(normalized.takeRight(24).map(n => shades(math.min(shades.length - 1, math.round(n).toInt))).mkString)
          .styled(_ => summon[Theme].accent)
      else
        sparkline(normalized.map(n => math.round(n * 100)))
          .max(800)
          .rightToLeft
          .styled(_ => Palettes.seriesStyle(metric))

  private def timeline(metric: Metric, title: String)(using ReactiveScope, Theme): Element =
    val trend = analytics.get.metrics(metric)
    panel(title)(
      historyChart(metric).fill,
      text(s"mean ${metric.format(trend.average)} / peak ${metric.format(trend.maximum)} / ${trend.count} reported")
        .styled(_ => summon[Theme].muted)
        .length(1)
    ).rounded

  private def historyPage(size: Size)(using ReactiveScope, Theme): Element =
    val metric     = Metric.values(selectedMetric.get)
    val trend      = analytics.get.metrics(metric)
    val navigation = row(
      button("Previous")(selectedMetric.update(i => (i + Metric.values.length - 1) % Metric.values.length)).length(14),
      text(s"${metric.label} / j,k to switch").bold.fill,
      button("Next")(selectedMetric.update(i => (i + 1) % Metric.values.length)).length(10)
    ).length(1)
    val stats = row(
      stat("CURRENT", metric.format(history.get.lastOption.flatMap(_.snapshot.value(metric))), metric.unit).fill,
      stat("SAMPLE MEAN", metric.format(trend.average), metric.unit).fill,
      stat("MINIMUM", metric.format(trend.minimum), metric.unit).fill,
      stat("PEAK", metric.format(trend.maximum), metric.unit).fill
    ).gap(1).length(4)
    val plot = panel(s"HISTORY / ${metric.label} (${metric.unit})")(
      historyChart(metric).fill,
      text(s"${trend.count} reported / auto-scaled / minutes since oldest sample")
        .styled(_ => summon[Theme].muted)
        .length(1),
      text("Missing readings and missed polls break the line.").styled(_ => summon[Theme].muted).length(1)
    ).rounded
    val graph = if size.width >= 120 then row(plot.fill(3), historyInspector(metric).fill).gap(1) else plot
    if size.width < 75 then column(navigation, graph.fill)
    else column(navigation, stats, graph.fill)

  private def stat(label: String, value: String, unit: String)(using Theme): Element =
    panel(label)(text(s"$value $unit").bold.styled(_ => summon[Theme].accent)).rounded.styled(_ =>
      summon[Theme].surface
    )

  private def historyInspector(metric: Metric)(using ReactiveScope, Theme): Element =
    val trend  = analytics.get.metrics(metric)
    val counts = Band.values.map(band => Seq(band.label, trend.bands.getOrElse(band, 0).toString)).toVector
    panel("OBSERVATIONS / sample counts")(
      (Vector(table(counts, Constraint.Fill(1), Constraint.Length(7)).header("Band", "Samples").length(7)) ++
        Vector(
          rule().length(1),
          text(s"Last change: ${metric.format(trend.delta)} ${metric.unit}")
            .styled(_ => summon[Theme].accent)
            .length(1),
          text("Missing values are Unknown.").styled(_ => summon[Theme].muted).length(1),
          text("Means use reported samples.").styled(_ => summon[Theme].muted).length(1),
          text("Not time-weighted exposure.").styled(_ => summon[Theme].muted).length(1),
          spacer
        ))*
    ).rounded.styled(_ => summon[Theme].surface)

  private def historyChart(metric: Metric)(using ReactiveScope, Theme): Element =
    val analysis = analytics.get
    val trend    = analysis.metrics(metric)
    if trend.count == 0 then text(s"No ${metric.label} samples available").styled(_ => summon[Theme].muted)
    else
      val minimum  = trend.minimum.get
      val maximum  = trend.maximum.get
      val padding  = math.max(1.0, (maximum - minimum) * 0.15)
      val low      = if metric == Metric.Temperature then minimum - padding else math.max(0, minimum - padding)
      val datasets = trend.segments.map(points => Dataset(metric.label, points, style = Palettes.seriesStyle(metric)))
      plotted(
        datasets,
        Bounds(0, math.max(1.0, analysis.minutes)),
        Bounds(low, maximum + padding),
        Seq("0 min", s"${math.round(analysis.minutes)} min"),
        Seq(metric.format(Some(low)), metric.format(Some(maximum + padding)))
      )

  /** Braille charts with labelled axes; ASCII themes get cell markers and plain axis characters. */
  private def plotted(
      datasets: Vector[Dataset],
      x: Bounds,
      y: Bounds,
      xLabels: Seq[String],
      yLabels: Seq[String]
  )(using Theme): Element =
    val ascii = summon[Theme].glyphs == GlyphSupport.Ascii
    val plot  = chart(
      datasets,
      x,
      y,
      ChartOptions(
        axisStyle = summon[Theme].muted,
        marker = if ascii then "." else "•",
        resolution = if ascii then CanvasResolution.Cell else CanvasResolution.Braille,
        showLabels = true,
        xLabels = xLabels,
        yLabels = yLabels,
        showLegend = false
      )
    )
    if !ascii then plot
    else
      widget { (area, buffer) =>
        plot.widget.render(area, buffer)
        buffer.foreachIn(area) { (x, y, cell) =>
          val symbol = cell.symbol.map {
            case '│' => '|'
            case '─' => '-'
            case '└' => '+'
            case c   => c
          }
          if symbol != cell.symbol then buffer.set(x, y, cell.copy(symbol = symbol))
        }
      }

  private def settingsPage(size: Size)(using ReactiveScope, Theme): Element =
    val form = panel("SETTINGS / shared with AirGradient desktop")(
      text("Device address").bold.length(1),
      input(urlInput, "airgradient.local or http://host:port").key("device-url").length(1),
      text("Refresh interval / seconds, 5–3600").length(1),
      input(refreshInput, "30").key("refresh-seconds").length(1),
      text("Outdoor location / city or lat,lon, optional").length(1),
      input(locationInput, "e.g. Kyiv or 50.45,30.52; blank turns outdoor data off")
        .key("outdoor-location")
        .length(1),
      text(s"Theme: ${themeName.get} / press t or choose below").length(1),
      (if summon[Theme].glyphs == GlyphSupport.Ascii then
         button("Next theme / t")(cycleTheme()).key("theme-picker").length(1)
       else
         DropdownElement(
           Palettes.names,
           Palettes.names.indexOf(themeName.get),
           themeDropdown,
           index => previewTheme(Palettes.names(index)),
           maxVisibleRows = if size.height < 24 then 2 else 5
         ).key("theme-picker")
      ),
      row(button("Preview theme")(cycleTheme()).length(20), button("Save / Ctrl+s")(saveSettings()).length(22))
        .length(1),
      text(if demo then "Demo changes remain in memory." else s"Config: ${store.path}")
        .styled(_ => summon[Theme].muted)
        .length(1),
      text("Tab moves focus. Esc returns.").styled(_ => summon[Theme].muted).length(1),
      spacer
    ).rounded
    if size.width < 110 then form
    else
      row(
        form.fill(3),
        panel("YOUR WORKSPACE / make it yours")(
          text(themeName.get).bold.styled(_ => summon[Theme].accent).length(1),
          text("15 palettes / dark, light, high contrast").length(1),
          rule().length(1),
          text("Ctrl+p / search a theme by name").length(1),
          text("t / preview the next palette").length(1),
          text("Enter / open the theme chooser").length(1),
          rule().length(1),
          (if summon[Theme].glyphs == GlyphSupport.Ascii then
             text(if demo then "Offline demo / no disk writes" else "Changes apply only when saved")
           else notice(if demo then "Offline demo / no disk writes" else "Changes apply only when saved")).length(1),
          text(s"Outdoor: ${outdoorPlace(settings.get).fold("off")(_.name)}").length(1),
          text("Outdoor data: Open-Meteo, coordinates only.").styled(_ => summon[Theme].muted).length(1),
          text("Other desktop fields are preserved.").styled(_ => summon[Theme].muted).length(1),
          text("Session overrides are saved only here.").styled(_ => summon[Theme].muted).length(1),
          spacer
        ).rounded.styled(_ => summon[Theme].surface).fill(2)
      ).gap(1)

  private def fixed(n: Double, decimals: Int): String = String.format(Locale.ROOT, s"%.${decimals}f", n)

  /** A change that rounds to zero shows as an unsigned zero, never +0 or -0.0. */
  private def signed(n: Double, decimals: Int): String =
    val shown = fixed(n, decimals)
    if !shown.exists(c => c.isDigit && c != '0') then fixed(0, decimals)
    else if n > 0 then s"+$shown"
    else shown
  private def ago(millis: Long): String =
    val seconds = math.max(0L, millis / 1000)
    if seconds < 60 then s"${seconds}s" else if seconds < 3600 then s"${seconds / 60} min" else s"${seconds / 3600} h"

  /** Label, bold value, and an optional status word in the band's color. */
  private def reading(label: String, value: String, band: Option[Band] = None, labelWidth: Int = 14)(using
      Theme
  ): Element =
    band match
      case None => row(text(label).styled(_ => summon[Theme].muted).length(labelWidth), text(value).bold.fill).length(1)
      case Some(b) =>
        row(
          text(label).styled(_ => summon[Theme].muted).length(labelWidth),
          text(value).bold.length(math.max(value.length + 2, 16)),
          text(b.label).styled(_ => Palettes.bandStyle(b)).fill
        ).length(1)

  private def outdoorReading(metric: OutdoorMetric, report: OutdoorReport)(using Theme): Element =
    val value = report.value(metric)
    val shown = if metric == OutdoorMetric.Wind then report.wind else s"${metric.format(value)} ${metric.unit}".trim
    reading(metric.label, shown, metric.classify(value))

  private def outdoorPlaceholder(title: String)(using ReactiveScope, Theme): Element =
    val place = outdoorPlace(settings.get)
    panel(title)(
      spacer,
      (if outdoorFetching.get then spinner(s"Fetching outdoor weather and air for ${place.fold("")(_.name)}...")
       else
         text(
           outdoorProblem.get.fold(if place.isEmpty then "Outdoor data is off." else "Waiting for outdoor data.")(
             error => s"Outdoor data unavailable: $error"
           )
         ).bold
      ).length(1),
      text("Add a city or lat,lon in Settings (c), or start with --location.")
        .styled(_ => summon[Theme].muted)
        .length(1),
      text("Open-Meteo is free and needs no account; only those coordinates are sent.")
        .styled(_ => summon[Theme].muted)
        .length(1),
      row(
        button("Open settings")(openSettings()).length(20),
        button("Retry / r")(refreshOutdoor(force = true)).length(14)
      )
        .length(1),
      spacer
    ).rounded.styled(_ => summon[Theme].surface)

  private def outdoorStatus(report: OutdoorReport)(using ReactiveScope, Theme): Element =
    val observed = report.observedAt.map(time => s" / model time ${ForecastHour(time, None, None, None, None).label}")
    val line = s"${report.location.name}${observed.getOrElse("")} / updated ${ago(clock.get - report.receivedAt)} ago"
    outdoorProblem.get match
      case Some(error) => text(s"STALE / $error / $line").styled(_ => summon[Theme].error)
      case None        =>
        text(if outdoorFetching.get then s"FETCHING / $line" else line).styled(_ => summon[Theme].muted)

  private def attribution(using Theme): Element =
    text(
      if demo then "Synthetic outdoor data / offline demo / no network requests"
      else "Weather and air data by Open-Meteo.com (CC BY 4.0) / model estimates, not local sensors"
    ).styled(_ => summon[Theme].muted)

  private def comparison(report: OutdoorReport)(using ReactiveScope, Theme): Element =
    val indoor                                          = history.get.lastOption.map(_.snapshot)
    def in(metric: Metric)                              = indoor.flatMap(_.value(metric))
    def out(metric: OutdoorMetric)                      = report.value(metric)
    def absolute(t: Option[Double], rh: Option[Double]) =
      for a <- t; b <- rh; n <- Comfort.absoluteHumidity(a, b) yield n
    def line(label: String, a: Option[Double], b: Option[Double], decimals: Int, unit: String) =
      Seq(
        label,
        a.fold("--")(n => s"${fixed(n, decimals)} $unit".trim),
        b.fold("--")(n => s"${fixed(n, decimals)} $unit".trim),
        (for x <- a; y <- b yield signed(y - x, decimals)).getOrElse("--")
      )
    val rows = Vector(
      line("Temperature", in(Metric.Temperature), out(OutdoorMetric.Temperature), 1, "C"),
      line("Humidity", in(Metric.Humidity), out(OutdoorMetric.Humidity), 0, "%"),
      line(
        "Water vapour",
        absolute(in(Metric.Temperature), in(Metric.Humidity)),
        absolute(out(OutdoorMetric.Temperature), out(OutdoorMetric.Humidity)),
        1,
        "g/m3"
      ),
      line("PM2.5", in(Metric.Pm25), out(OutdoorMetric.Pm25), 1, "ug/m3"),
      line("US AQI", in(Metric.Aqi), out(OutdoorMetric.UsAqi), 0, "")
    )
    val advice = Advice.ventilation(indoor, Some(report))
    panel("INDOOR VS OUTDOOR / should the window open?")(
      table(rows, Constraint.Length(14), Constraint.Length(13), Constraint.Length(13), Constraint.Fill(1))
        .header("Reading", "Indoor", "Outdoor", "Outside - in")
        .length(rows.size + 1),
      row(
        text(advice.headline).bold.styled(_ => Palettes.bandStyle(advice.band)).length(advice.headline.length + 3),
        text(advice.detail).styled(_ => summon[Theme].muted).fill
      ).length(1)
    ).rounded.styled(_ => summon[Theme].surface)

  private def outdoorPage(size: Size)(using ReactiveScope, Theme): Element =
    outdoor.get match
      case None         => outdoorPlaceholder("OUTDOOR / weather and air outside")
      case Some(report) =>
        if size.width < 100 then
          val advice = Advice.ventilation(history.get.lastOption.map(_.snapshot), Some(report))
          val rows   = Vector(
            OutdoorMetric.Temperature,
            OutdoorMetric.Humidity,
            OutdoorMetric.Wind,
            OutdoorMetric.UsAqi,
            OutdoorMetric.Pm25,
            OutdoorMetric.Uv
          ) ++ (if size.height >= 24 then Vector(OutdoorMetric.FeelsLike, OutdoorMetric.Pressure, OutdoorMetric.EuAqi)
                else Vector.empty)
          column(
            outdoorStatus(report).length(1),
            panel(s"OUTDOOR / ${report.condition}")(
              (rows.map(outdoorReading(_, report)) :+
                text(advice.headline).bold.styled(_ => Palettes.bandStyle(advice.band)).length(1))*
            ).rounded.styled(_ => summon[Theme].surface).length(rows.size + 3),
            attribution.length(1),
            spacer
          )
        else
          val weather = panel(s"WEATHER / ${report.condition}")(
            OutdoorMetric.weather.map(outdoorReading(_, report))*
          ).rounded.styled(_ => summon[Theme].surface)
          val air = panel("AIR OUTSIDE / modelled at street level")(
            OutdoorMetric.air.map(outdoorReading(_, report))*
          ).rounded.styled(_ => summon[Theme].surface)
          column(
            outdoorStatus(report).length(1),
            row(weather.fill, air.fill).gap(1).length(11),
            comparison(report).length(9),
            attribution.length(1),
            spacer
          )

  private def forecastChart(
      hours: Vector[ForecastHour],
      values: Vector[Option[Double]],
      decimals: Int,
      style: Style,
      floorAtZero: Boolean
  )(using Theme): Element =
    val points = values.flatten
    if points.isEmpty then text("No forecast values in the latest response.").styled(_ => summon[Theme].muted)
    else
      val segments = values.zipWithIndex
        .foldLeft(Vector(Vector.empty[(Double, Double)])) {
          case (groups, (None, _))        => groups :+ Vector.empty
          case (groups, (Some(value), i)) => groups.init :+ (groups.last :+ (i.toDouble -> value))
        }
        .filter(_.nonEmpty)
      val minimum = points.min
      val maximum = points.max
      val padding = math.max(1.0, (maximum - minimum) * 0.15)
      val low     = if floorAtZero then math.max(0, minimum - padding) else minimum - padding
      plotted(
        segments.map(points => Dataset("forecast", points, style = style)),
        Bounds(0, math.max(1.0, values.size - 1.0)),
        Bounds(low, maximum + padding),
        Seq(hours.head.label, hours.last.label),
        Seq(fixed(low, decimals), fixed(maximum + padding, decimals))
      )

  private def forecastTable(hours: Vector[ForecastHour], rows: Int)(using Theme): Element =
    val stride  = math.max(1, math.ceil(hours.size.toDouble / rows).toInt)
    val sampled = hours.indices.by(stride).map(hours).take(rows).toVector
    val data    = sampled.map(h =>
      Seq(
        h.label,
        h.temperature.fold("--")(t => s"${fixed(t, 1)} C"),
        h.precipitationProbability.fold("--")(p => s"${fixed(p, 0)}%"),
        h.usAqi.fold("--")(a => fixed(a, 0)),
        Metric.Aqi.classify(h.usAqi).label
      )
    )
    panel(s"NEXT HOURS / every ${stride} h")(
      table(
        data,
        Constraint.Length(7),
        Constraint.Length(9),
        Constraint.Length(6),
        Constraint.Length(5),
        Constraint.Fill(1)
      ).header("Time", "Temp", "Rain", "AQI", "Air").length(data.size + 1)
    ).rounded.styled(_ => summon[Theme].surface)

  private def forecastPage(size: Size)(using ReactiveScope, Theme): Element =
    outdoor.get match
      case None                                  => outdoorPlaceholder("FORECAST / next 24 hours")
      case Some(report) if report.hourly.isEmpty =>
        column(outdoorStatus(report).length(1), text("No hourly forecast in the latest response.").length(1), spacer)
      case Some(report) =>
        val hours = report.hourly
        val best  = Advice.bestHour(hours) match
          case None => text("No hourly air-quality forecast to pick a time from.").styled(_ => summon[Theme].muted)
          case Some(hour) =>
            val detail =
              s" / US AQI ${hour.usAqi.fold("--")(fixed(_, 0))} / ${hour.temperature.fold("--")(fixed(_, 1))} C"
            row(
              text("Best time to air out: ").styled(_ => summon[Theme].muted).length(22),
              text(hour.label).bold.styled(_ => summon[Theme].accent).length(hour.label.length),
              text(detail).styled(_ => summon[Theme].muted).fill
            )
        val rain     = hours.filter(_.precipitationProbability.nonEmpty).maxByOption(_.precipitationProbability.get)
        val rainLine = text(
          rain.fold("Rain chance unavailable")(h =>
            s"Highest rain chance: ${fixed(h.precipitationProbability.get, 0)}% at ${h.label}"
          )
        ).styled(_ => summon[Theme].muted)
        val temperature = panel("TEMPERATURE / next 24 h (C)")(
          forecastChart(hours, hours.map(_.temperature), 1, Palettes.seriesStyle(Metric.Temperature), false).fill
        ).rounded
        val aqi = panel("US AQI / next 24 h")(
          forecastChart(hours, hours.map(_.usAqi), 0, Palettes.seriesStyle(Metric.Pm25), true).fill
        ).rounded
        if size.width >= 110 then
          column(
            outdoorStatus(report).length(1),
            best.length(1),
            row(
              column(temperature.fill, aqi.fill).fill(3),
              column(forecastTable(hours, 8).length(11), rainLine.length(1), spacer).fill(2)
            ).gap(1).fill,
            attribution.length(1)
          )
        else
          val rows = if size.height < 24 then 3 else 6
          column(
            best.length(1),
            temperature.fill,
            forecastTable(hours, rows).length(rows + 3),
            rainLine.length(1)
          )

  private def summaryPage(size: Size)(using ReactiveScope, Theme): Element =
    val analysis = analytics.get
    val latest   = history.get.lastOption.map(_.snapshot)
    val wide     = size.width >= 90
    val rows     = Metric.values.toVector.map { metric =>
      val trend   = analysis.metrics(metric)
      val current = latest.flatMap(_.value(metric))
      val good    = Option.when(trend.count > 0)(trend.bands.getOrElse(Band.Good, 0) * 100.0 / trend.count)
      val base    = Seq(metric.label, s"${metric.format(current)} ${metric.unit}")
      val stats   =
        if wide then
          Seq(
            metric.format(trend.minimum),
            metric.format(trend.average),
            metric.format(trend.maximum),
            trend.delta.fold("--")(signed(_, metric.decimals)),
            good.fold("--")(n => s"${math.round(n)}%")
          )
        else Seq(metric.format(trend.average))
      base ++ stats :+ metric.classify(current).label
    }
    val constraints =
      if wide then
        Seq(
          Constraint.Length(15),
          Constraint.Length(15),
          Constraint.Length(9),
          Constraint.Length(9),
          Constraint.Length(9),
          Constraint.Length(8),
          Constraint.Length(6),
          Constraint.Fill(1)
        )
      else Seq(Constraint.Length(15), Constraint.Length(13), Constraint.Length(7), Constraint.Fill(1))
    val headers =
      if wide then Seq("Metric", "Current", "Min", "Mean", "Peak", "Change", "Good", "Status")
      else Seq("Metric", "Current", "Mean", "Status")
    val worst = latest.fold(Band.Unknown)(_.worst)
    panel(s"SUMMARY / ${history.get.size} samples over ${math.round(analysis.minutes)} min")(
      table(rows, constraints*).header(headers*).length(rows.size + 1),
      rule().length(1),
      row(
        text("Overall: ").styled(_ => summon[Theme].muted).length(9),
        text(worst.label).bold.styled(_ => Palettes.bandStyle(worst)).fill
      ).length(1),
      text(
        if wide then "Good = share of reported samples in the Good band. Change = since the previous sample."
        else "Mean of reported samples."
      ).styled(_ => summon[Theme].muted).length(1),
      text("Means describe samples, not time-weighted exposure.").styled(_ => summon[Theme].muted).length(1),
      spacer
    ).rounded

  private def comfortPage(size: Size)(using ReactiveScope, Theme): Element =
    history.get.lastOption match
      case None =>
        panel("COMFORT / derived from your readings")(text("Waiting for the first reading.").length(1), spacer).rounded
      case Some(sample) =>
        val snapshot    = sample.snapshot
        val temperature = snapshot.value(Metric.Temperature)
        val humidity    = snapshot.value(Metric.Humidity)
        val dew         = for t <- temperature; rh <- humidity; d <- Comfort.dewPoint(t, rh) yield d
        val absolute    = for t <- temperature; rh <- humidity; a <- Comfort.absoluteHumidity(t, rh) yield a
        val heat        = for t <- temperature; rh <- humidity; h <- Comfort.heatIndex(t, rh) yield h
        val verdict     = Comfort.verdict(snapshot)
        val samples     = history.get
        val mold        = Comfort.moldRisk(samples)
        val condensing  = Comfort.condensation(dew, outdoor.get.flatMap(_.value(OutdoorMetric.Temperature)))
        val rate        = Comfort.co2PerHour(samples)
        val above       = Comfort.shareAbove(samples, Metric.Co2, 1000)
        val advice      = Advice.ventilation(Some(snapshot), outdoor.get)
        def word(verdict: Comfort.Verdict) = text(verdict.label).bold.styled(_ => Palettes.bandStyle(verdict.band))
        def note(verdict: Comfort.Verdict) = text(verdict.detail).styled(_ => summon[Theme].muted)
        val co2                            = snapshot.value(Metric.Co2)
        val thermal                        = Vector(
          reading(
            "Temperature",
            s"${Metric.Temperature.format(temperature)} C",
            Some(Metric.Temperature.classify(temperature))
          ),
          reading("Humidity", s"${Metric.Humidity.format(humidity)} %", Some(Metric.Humidity.classify(humidity))),
          reading("Dew point", dew.fold("--")(d => s"${fixed(d, 1)} C")),
          reading("Water vapour", absolute.fold("--")(a => s"${fixed(a, 1)} g/m3")),
          reading("Heat index", heat.fold("n/a below 26.7 C")(h => s"${fixed(h, 1)} C"))
        )
        val ventilation = Vector(
          reading("CO2 now", s"${Metric.Co2.format(co2)} ppm", Some(Metric.Co2.classify(co2))),
          reading("CO2 trend", rate.fold("-- / needs 5 min")(r => s"${signed(r, 0)} ppm/h")),
          reading("Above 1000", above.fold("--")(a => s"${math.round(a * 100)}% of samples"))
        )
        if size.width < 100 then
          panel(s"COMFORT / ${verdict.label}")(
            (thermal.take(if size.height < 24 then 3 else 5) ++ ventilation.take(2) ++ Vector(
              row(text("Mold risk").styled(_ => summon[Theme].muted).length(14), word(mold).fill).length(1),
              text(advice.headline).bold.styled(_ => Palettes.bandStyle(advice.band)).length(1),
              spacer
            ))*
          ).rounded
        else
          column(
            row(
              panel("THERMAL COMFORT / derived")(
                (thermal ++ Vector(rule().length(1), word(verdict).length(1)))*
              ).rounded.styled(_ => summon[Theme].surface).fill,
              panel("MOISTURE / mold and condensation")(
                row(text("Mold risk").styled(_ => summon[Theme].muted).length(14), word(mold).fill).length(1),
                note(mold).length(1),
                row(text("Condensation").styled(_ => summon[Theme].muted).length(14), word(condensing).fill)
                  .length(1),
                note(condensing).length(1),
                rule().length(1),
                text("Mold: RH at 70 %+ for long spells").styled(_ => summon[Theme].muted).length(1),
                text("Wet glass: surface below dew point").styled(_ => summon[Theme].muted).length(1)
              ).rounded.styled(_ => summon[Theme].surface).fill,
              panel("VENTILATION / CO2")(
                (ventilation ++ Vector(
                  rule().length(1),
                  text(advice.headline).bold.styled(_ => Palettes.bandStyle(advice.band)).length(1),
                  text(advice.detail).styled(_ => summon[Theme].muted).length(1)
                ))*
              ).rounded.styled(_ => summon[Theme].surface).fill
            ).gap(1).length(9),
            panel("ABOUT THESE NUMBERS / estimates, not extra sensors")(
              text("Dew point: Magnus formula. Water vapour: absolute humidity in grams per cubic metre.").length(1),
              text("Heat index: NOAA regression, defined only at 26.7 C and 40 % RH or above.").length(1),
              text("CO2 trend: change across the last 30 minutes of successful samples.").length(1),
              text("Window advice compares indoor CO2 and PM2.5 with outdoor air when a location is set.")
                .styled(_ => summon[Theme].muted)
                .length(1)
            ).rounded.length(6),
            spacer
          )
