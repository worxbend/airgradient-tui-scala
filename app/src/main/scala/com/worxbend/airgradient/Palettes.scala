package com.worxbend.airgradient

import io.worxbend.tui.dsl.*

object Palettes:
  val names: Vector[String]        = Vector("aurora", "nord", "mono")
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
  def apply(name: String): Theme = name match
    case "nord" => nord
    case "mono" => Theme.HighContrast.copy(name = "mono", glyphs = GlyphSupport.Ascii)
    case _      => aurora

  def bandStyle(band: Band)(using theme: Theme): Style = band match
    case Band.Unknown                        => theme.muted
    case Band.Good                           => theme.success
    case Band.Moderate | Band.Elevated       => theme.warning
    case Band.Unhealthy | Band.VeryUnhealthy => theme.error
