package com.worxbend.airgradient

import io.worxbend.tui.dsl.*

object Palettes:
  private def rgb(hex: Int): Color = Color.Rgb((hex >> 16) & 255, (hex >> 8) & 255, hex & 255)
  private val aurora               = Theme.Dark.copy(
    name = "aurora",
    primary = Style.Default.withFg(rgb(0xdbe7ef)).withBg(rgb(0x0b1220)),
    accent = Style.Default.withFg(rgb(0x5ad6ff)),
    muted = Style.Default.withFg(rgb(0x8b9eb3)),
    surface = Style.Default.withBg(rgb(0x131f30)).withFg(rgb(0xdbe7ef)),
    border = Style.Default.withFg(rgb(0x41566e)),
    success = Style.Default.withFg(rgb(0x72e5b1)),
    warning = Style.Default.withFg(rgb(0xffd580)),
    error = Style.Default.withFg(rgb(0xff859d))
  )
  private val nord = aurora.copy(
    name = "nord",
    primary = Style.Default.withFg(rgb(0xe5e9f0)).withBg(rgb(0x2e3440)),
    accent = Style.Default.withFg(rgb(0x88c0d0)),
    surface = Style.Default.withBg(rgb(0x3b4252)).withFg(rgb(0xe5e9f0)),
    border = Style.Default.withFg(rgb(0x81a1c1))
  )
  private def palette(
      name: String,
      light: Boolean,
      background: Int,
      foreground: Int,
      surface: Int,
      accent: Int,
      muted: Int,
      border: Int,
      success: Int,
      warning: Int,
      error: Int
  ): Theme =
    val accentStyle  = Style.Default.withFg(rgb(accent))
    val mutedStyle   = Style.Default.withFg(rgb(muted))
    val surfaceStyle = Style.Default.withFg(rgb(foreground)).withBg(rgb(surface))
    (if light then Theme.Light else Theme.Dark).copy(
      name = name,
      primary = Style.Default.withFg(rgb(foreground)).withBg(rgb(background)),
      surface = surfaceStyle,
      accent = accentStyle,
      muted = mutedStyle,
      border = Style.Default.withFg(rgb(border)),
      success = Style.Default.withFg(rgb(success)),
      warning = Style.Default.withFg(rgb(warning)),
      error = Style.Default.withFg(rgb(error)),
      focus = Style.Default.withFg(rgb(background)).withBg(rgb(accent)).bold,
      loading = LoadingTheme.from(accentStyle, mutedStyle, surfaceStyle)
    )

  private val themes = Vector(
    aurora,
    nord,
    Theme.HighContrast.copy(name = "mono", glyphs = GlyphSupport.Ascii),
    palette(
      name = "grafana",
      light = false,
      background = 0x181b1f,
      foreground = 0xe8eef5,
      surface = 0x22252b,
      accent = 0xffb357,
      muted = 0xb0bccb,
      border = 0x718195,
      success = 0x73d39c,
      warning = 0xffd166,
      error = 0xff8189
    ),
    palette(
      name = "ocean",
      light = false,
      background = 0x071f2a,
      foreground = 0xd5f3fa,
      surface = 0x0d303d,
      accent = 0x63dbe6,
      muted = 0x9ebcc9,
      border = 0x6997a8,
      success = 0x75d9b1,
      warning = 0xf2cc7d,
      error = 0xff9eac
    ),
    palette(
      name = "forest",
      light = false,
      background = 0x10231d,
      foreground = 0xe2f3e8,
      surface = 0x19382d,
      accent = 0x8be0a4,
      muted = 0xa7c2b0,
      border = 0x81aa91,
      success = 0x76e6a2,
      warning = 0xf5d083,
      error = 0xff9b98
    ),
    palette(
      name = "ember",
      light = false,
      background = 0x271a16,
      foreground = 0xfae9db,
      surface = 0x3a2820,
      accent = 0xffb570,
      muted = 0xccb3a0,
      border = 0xb49178,
      success = 0xa6dea0,
      warning = 0xffd08a,
      error = 0xff9f93
    ),
    palette(
      name = "sunset",
      light = false,
      background = 0x281b32,
      foreground = 0xf6e5f3,
      surface = 0x3c2847,
      accent = 0xffb7a4,
      muted = 0xc9aecb,
      border = 0xb593bc,
      success = 0x9edfc2,
      warning = 0xf6d38c,
      error = 0xff98af
    ),
    palette(
      name = "lavender",
      light = false,
      background = 0x201e35,
      foreground = 0xece8ff,
      surface = 0x302c4a,
      accent = 0xc7b0ff,
      muted = 0xbbb3d6,
      border = 0x9c8dbb,
      success = 0x98e2c0,
      warning = 0xf4d58c,
      error = 0xff9ab6
    ),
    palette(
      name = "rose",
      light = false,
      background = 0x2b1b26,
      foreground = 0xf9e5ee,
      surface = 0x402736,
      accent = 0xffb2d0,
      muted = 0xcfafc1,
      border = 0xbb8da7,
      success = 0x98dfb8,
      warning = 0xf6d28d,
      error = 0xff9d9d
    ),
    palette(
      name = "cobalt",
      light = false,
      background = 0x0c1b35,
      foreground = 0xe0edff,
      surface = 0x142c4b,
      accent = 0x8bbdff,
      muted = 0xa6bad7,
      border = 0x7898c2,
      success = 0x80dfb0,
      warning = 0xffd482,
      error = 0xff9ea7
    ),
    palette(
      name = "paper",
      light = true,
      background = 0xf7f9fc,
      foreground = 0x172a3a,
      surface = 0xe8eef5,
      accent = 0x185bb0,
      muted = 0x4e6074,
      border = 0x62758a,
      success = 0x1c6740,
      warning = 0x805300,
      error = 0xab243a
    ),
    palette(
      name = "sand",
      light = true,
      background = 0xfbf5e9,
      foreground = 0x372c22,
      surface = 0xeee3cf,
      accent = 0x8c421d,
      muted = 0x695947,
      border = 0x8a7359,
      success = 0x326135,
      warning = 0x795000,
      error = 0xa52c32
    ),
    palette(
      name = "frost",
      light = true,
      background = 0xeef7fb,
      foreground = 0x183642,
      surface = 0xdcecf2,
      accent = 0x075c79,
      muted = 0x446570,
      border = 0x547886,
      success = 0x146340,
      warning = 0x795000,
      error = 0xa62340
    ),
    palette(
      name = "mint",
      light = true,
      background = 0xeff9f2,
      foreground = 0x173b2c,
      surface = 0xdceee2,
      accent = 0x146447,
      muted = 0x446957,
      border = 0x5c7e69,
      success = 0x1c6136,
      warning = 0x755200,
      error = 0xa62340
    )
  )

  val names: Vector[String] = themes.map(_.name)

  def apply(name: String): Theme = themes.find(_.name == name).getOrElse(aurora)

  def seriesStyle(metric: Metric)(using theme: Theme): Style =
    if theme.glyphs == GlyphSupport.Ascii then theme.accent
    else
      val partner = metric match
        case Metric.Pm25 | Metric.Pm1 | Metric.Pm10 => theme.success
        case Metric.Tvoc | Metric.Nox               => theme.error
        case Metric.Temperature                     => theme.warning
        case Metric.Humidity | Metric.Particles     => theme.primary
        case _                                      => theme.accent
      val a                        = Color.toInt(theme.accent.fg.getOrElse(Color.White))
      val b                        = Color.toInt(partner.fg.getOrElse(Color.White))
      def channel(shift: Int): Int = math.round(((a >> shift) & 255) * 0.35 + ((b >> shift) & 255) * 0.65).toInt
      Style.Default.withFg(Color.Rgb(channel(16), channel(8), channel(0)))

  def bandStyle(band: Band)(using theme: Theme): Style = band match
    case Band.Unknown                        => theme.muted
    case Band.Good                           => theme.success
    case Band.Moderate | Band.Elevated       => theme.warning
    case Band.Unhealthy | Band.VeryUnhealthy => theme.error
