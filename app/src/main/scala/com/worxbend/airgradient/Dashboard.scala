package com.worxbend.airgradient

import io.worxbend.tui.dsl.*
import io.worxbend.tui.widgets.{BigText, CanvasResolution, ChartOptions, DropdownState, Heatmap, TextInputState}
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
  private val analytics                   = Computed(Telemetry.analyze(history.get, settings.get.refreshSeconds))
  private val themeDropdown               = DropdownState()
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
    analytics.dispose()

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
        }
      case _ => ()

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
    val screen           = responsive { size =>
      if size.width < 48 || size.height < 18 then
        panel("AirGradient")(text("Please resize to at least 48 x 18."), text("q: quit")).rounded
      else
        val content = page.get match
          case Page.Overview => overview(size)
          case Page.History  => historyPage(size)
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
          row(
            Page.values.map(p =>
              button(if page.get == p then s"$activeMark $p" else p.toString) {
                if p == Page.Settings then openSettings() else page.set(p)
              }.length(15).styled(_ => if page.get == p then palette.accent.bold else palette.primary)
            )*
          ).length(1),
          connectionLine.length(1),
          content.fill,
          footer(size.width).length(1)
        ).styled(_ => palette.primary)
        if help.get then
          layers(
            shell,
            centered(math.min(size.width - 4, 70), 14)(
              panel("Keyboard guide / make yourself at home")(
                text("r  refresh now          h  overview / history"),
                text("c  settings             t  preview next theme"),
                text("j/k  select history metric"),
                text("Ctrl+p  search commands, metrics, and themes"),
                text("Tab / Shift+Tab  move between controls"),
                text("Ctrl+s  save settings   Esc  back / quit"),
                text("?  close this guide     q  quit outside editing"),
                rule(),
                text("Demo uses synthetic readings and never writes to disk.").styled(_ => palette.muted),
                text("AQI is a current reading, not a 24-hour average.").styled(_ => palette.muted)
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
          "r"  -> "refresh",
          "h"  -> "history",
          "c"  -> "settings",
          "t"  -> "theme",
          "^p" -> "commands",
          "?"  -> "help",
          "q"  -> "quit"
        ).fill,
        text(themeName.get).styled(_ => summon[Theme].accent).length(12)
      )

  private def connectionLine(using ReactiveScope, Theme): Element =
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
      val plot     = chart(
        datasets,
        Bounds(0, math.max(1.0, analysis.minutes)),
        Bounds(low, maximum + padding),
        ChartOptions(
          axisStyle = summon[Theme].muted,
          marker = if summon[Theme].glyphs == GlyphSupport.Ascii then "." else "•",
          resolution =
            if summon[Theme].glyphs == GlyphSupport.Ascii then CanvasResolution.Cell else CanvasResolution.Braille,
          showLabels = true,
          xLabels = Seq("0 min", s"${math.round(analysis.minutes)} min"),
          yLabels = Seq(metric.format(Some(low)), metric.format(Some(maximum + padding))),
          showLegend = false
        )
      )
      if summon[Theme].glyphs != GlyphSupport.Ascii then plot
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
          text("Other desktop fields are preserved.").styled(_ => summon[Theme].muted).length(1),
          text("Session overrides are saved only here.").styled(_ => summon[Theme].muted).length(1),
          spacer
        ).rounded.styled(_ => summon[Theme].surface).fill(2)
      ).gap(1)
