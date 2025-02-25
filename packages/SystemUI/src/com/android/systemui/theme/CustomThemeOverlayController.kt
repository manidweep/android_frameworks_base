/*
 * Copyright (C) 2021 The Proton AOSP Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.theme

import android.annotation.ColorInt
import android.app.WallpaperColors
import android.app.WallpaperManager
import android.content.Context
import android.content.om.FabricatedOverlay
import android.os.SystemProperties
import android.content.res.Resources
import android.os.Handler
import android.os.UserManager
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import com.android.internal.statusbar.IStatusBarService
import com.android.systemui.Dependency
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.dump.DumpManager
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.keyguard.WakefulnessLifecycle
import com.android.systemui.monet.ColorScheme
import com.android.systemui.monet.Style
import com.android.systemui.settings.UserTracker
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.statusbar.policy.DeviceProvisionedController
import com.android.systemui.theme.ThemeOverlayApplier
import com.android.systemui.theme.ThemeOverlayController
import com.android.systemui.tuner.TunerService
import com.android.systemui.tuner.TunerService.Tunable
import com.android.systemui.util.settings.SecureSettings
import com.android.systemui.util.settings.SystemSettings
import dev.kdrag0n.colorkt.Color
import dev.kdrag0n.colorkt.cam.Zcam
import dev.kdrag0n.colorkt.conversion.ConversionGraph.convert
import dev.kdrag0n.colorkt.data.Illuminants
import dev.kdrag0n.colorkt.rgb.Srgb
import dev.kdrag0n.colorkt.tristimulus.CieXyzAbs.Companion.toAbs
import dev.kdrag0n.colorkt.ucs.lab.CieLab
import dev.kdrag0n.monet.theme.ColorSwatch
import dev.kdrag0n.monet.theme.DynamicColorScheme
import dev.kdrag0n.monet.theme.MaterialYouTargets
import java.util.concurrent.Executor
import javax.inject.Inject
import kotlin.math.log10
import kotlin.math.pow

@SysUISingleton
class CustomThemeOverlayController @Inject constructor(
    private val context: Context,
    broadcastDispatcher: BroadcastDispatcher,
    @Background bgHandler: Handler,
    @Main mainExecutor: Executor,
    @Background bgExecutor: Executor,
    themeOverlayApplier: ThemeOverlayApplier,
    secureSettings: SecureSettings,
    wallpaperManager: WallpaperManager,
    userManager: UserManager,
    deviceProvisionedController: DeviceProvisionedController,
    userTracker: UserTracker,
    dumpManager: DumpManager,
    featureFlags: FeatureFlags,
    @Main resources: Resources,
    wakefulnessLifecycle: WakefulnessLifecycle,
    configurationController: ConfigurationController,
    systemSettings: SystemSettings,
    barService: IStatusBarService
) : ThemeOverlayController(
    context,
    broadcastDispatcher,
    bgHandler,
    mainExecutor,
    bgExecutor,
    themeOverlayApplier,
    secureSettings,
    wallpaperManager,
    userManager,
    deviceProvisionedController,
    userTracker,
    dumpManager,
    featureFlags,
    resources,
    wakefulnessLifecycle,
    configurationController,
    systemSettings,
    barService
), Tunable {
    private lateinit var cond: Zcam.ViewingConditions
    private lateinit var targets: MaterialYouTargets
    private lateinit var colorScheme: DynamicColorScheme

    private var colorOverride: Int = 0
    private var chromaFactor: Double = Double.MIN_VALUE
    private var accurateShades: Boolean = true
    private var whiteLuminance: Double = Double.MIN_VALUE
    private var linearLightness: Boolean = false
    private var customColor: Boolean = false
    private var dynamicColorScheme: DynamicColorScheme? = null
    private val mTunerService: TunerService = Dependency.get(TunerService::class.java)
    override fun start() {
        mTunerService.addTunable(this, PREF_COLOR_OVERRIDE, PREF_WHITE_LUMINANCE,
                PREF_CHROMA_FACTOR, PREF_ACCURATE_SHADES, PREF_LINEAR_LIGHTNESS, PREF_CUSTOM_COLOR)
        super.start()
    }

    override fun onTuningChanged(key: String?, newValue: String?) {
        key?.let {
            if (it.contains(PREF_PREFIX)) {
                customColor = Settings.Secure.getInt(mContext.contentResolver, PREF_CUSTOM_COLOR, 0) == 1
                colorOverride = Settings.Secure.getInt(mContext.contentResolver,
                        PREF_COLOR_OVERRIDE, -1)
                chromaFactor = (Settings.Secure.getFloat(mContext.contentResolver,
                        PREF_CHROMA_FACTOR, 100.0f) / 100f).toDouble()
                accurateShades = Settings.Secure.getInt(mContext.contentResolver, PREF_ACCURATE_SHADES, 1) != 0

                whiteLuminance = parseWhiteLuminanceUser(
                    Settings.Secure.getInt(mContext.contentResolver,
                            PREF_WHITE_LUMINANCE, WHITE_LUMINANCE_USER_DEFAULT)
                )
                linearLightness = Settings.Secure.getInt(mContext.contentResolver,
                        PREF_LINEAR_LIGHTNESS, 0) != 0
                reevaluateSystemTheme(true /* forceReload */)
            }
        }
    }

    // Seed colors
    override fun getNeutralColor(colors: WallpaperColors) = colors.primaryColor.toArgb()
    override fun getAccentColor(colors: WallpaperColors) = ColorScheme.getSeedColor(colors)

    override fun getOverlay(primaryColor: Int, type: Int): FabricatedOverlay {
        cond = Zcam.ViewingConditions(
            surroundFactor = Zcam.ViewingConditions.SURROUND_AVERAGE,
            // sRGB
            adaptingLuminance = 0.4 * whiteLuminance,
            // Gray world
            backgroundLuminance = CieLab(
                L = 50.0,
                a = 0.0,
                b = 0.0,
            ).toXyz().y * whiteLuminance,
            referenceWhite = Illuminants.D65.toAbs(whiteLuminance),
        )

        targets = MaterialYouTargets(
            chromaFactor = chromaFactor,
            useLinearLightness = linearLightness,
            cond = cond,
        )

        // Generate color scheme
        colorScheme = DynamicColorScheme(
            targets = targets,
            seedColor = if (customColor) Srgb(colorOverride) else Srgb(primaryColor),
            chromaFactor = chromaFactor,
            cond = cond,
            accurateShades = accurateShades,
        )

	dynamicColorScheme = colorScheme

        val (groupKey, colorsList) = when (type) {
            ACCENT -> "accent" to colorScheme.accentColors
            NEUTRAL -> "neutral" to colorScheme.neutralColors
            else -> error("Unknown type $type")
        }

	setBootAnimColors()

        return FabricatedOverlay.Builder(context.packageName, groupKey, "android").run {
            colorsList.forEachIndexed { index, swatch ->
                val group = "$groupKey${index + 1}"

                swatch.forEach { (shade, color) ->
                    setColor("system_${group}_$shade", color)
                }
            }

            // Override special modulated surface colors for performance and consistency
            if (type == NEUTRAL) {
                // surface light = neutral1 20 (L* 98)
                colorsList[0][20]?.let { setColor("surface_light", it) }

                // surface highlight dark = neutral1 650 (L* 35)
                colorsList[0][650]?.let { setColor("surface_highlight_dark", it) }

                // surface_header_dark_sysui = neutral1 950 (L* 5)
                colorsList[0][950]?.let { setColor("surface_header_dark_sysui", it) }
            }

            build()
        }
    }

    private fun setBootAnimColors() {
        try {
            val bootColors: List<Color> = getBootColors()

            for(i in 0..bootColors.size - 1) {
                val color: Int = bootColors[i].convert<Srgb>().toRgb8()
                SystemProperties.set("persist.bootanim.color${i + 1}", color.toString())
                Log.d("ThemeOverlayController", "Writing boot animation colors $i: $color")
            }
        } catch(e: RuntimeException) {
            Log.w("ThemeOverlayController", "Cannot set sysprop. Look for 'init' and 'dmesg' logs for more info.")
        }
    }

    private fun getBootColors(): List<Color> {
         // The four colors here are chosen based on a figma spec of POSP bootanim
         // If you plan on having your own custom animation you potentially want to change these colors
         return listOf(
             colorScheme.accent1[400]!!,
             colorScheme.accent1[200]!!,
             colorScheme.accent1[700]!!,
             colorScheme.accent2[900]!!,
         )
    }

    override protected fun getAccent1(): List<Int> {
        return getArgbColors(dynamicColorScheme?.accentColors?.get(0))
    }

    override protected fun getAccent2(): List<Int> {
        return getArgbColors(dynamicColorScheme?.accentColors?.get(1))
    }

    override protected fun getAccent3(): List<Int> {
        return getArgbColors(dynamicColorScheme?.accentColors?.get(2))
    }

    override protected fun getNeutral1(): List<Int> {
        return getArgbColors(dynamicColorScheme?.neutralColors?.get(0))
    }

    override protected fun getNeutral2(): List<Int> {
        return getArgbColors(dynamicColorScheme?.neutralColors?.get(1))
    }

    private fun getArgbColors(swatch: ColorSwatch?): List<Int> {
        return swatch?.values?.map { it.toArgb() } ?: emptyList()
    }

    companion object {
        private const val TAG = "CustomThemeOverlayController"

        private const val PREF_PREFIX = "monet_engine"
        private const val PREF_CUSTOM_COLOR = "${PREF_PREFIX}_custom_color"
        private const val PREF_COLOR_OVERRIDE = "${PREF_PREFIX}_color_override"
        private const val PREF_CHROMA_FACTOR = "${PREF_PREFIX}_chroma_factor"
        private const val PREF_ACCURATE_SHADES = "${PREF_PREFIX}_accurate_shades"
        private const val PREF_LINEAR_LIGHTNESS = "${PREF_PREFIX}_linear_lightness"
        private const val PREF_WHITE_LUMINANCE = "${PREF_PREFIX}_white_luminance_user"

        private const val WHITE_LUMINANCE_MIN = 1.0
        private const val WHITE_LUMINANCE_MAX = 10000.0
        private const val WHITE_LUMINANCE_USER_MAX = 1000
        private const val WHITE_LUMINANCE_USER_DEFAULT = 425 // ~200.0 divisible by step (decoded = 199.526)

        private fun parseWhiteLuminanceUser(userValue: Int): Double {
            val userSrc = userValue.toDouble() / WHITE_LUMINANCE_USER_MAX
            val userInv = 1.0 - userSrc
            return (10.0).pow(userInv * log10(WHITE_LUMINANCE_MAX))
                    .coerceAtLeast(WHITE_LUMINANCE_MIN)
        }

        private fun Color.toArgb(): Int {
            return convert<Srgb>().toRgb8() or (0xff shl 24)
        }

        private fun FabricatedOverlay.Builder.setColor(name: String, color: Color): FabricatedOverlay.Builder {
            return setResourceValue(
                "android:color/$name",
                TypedValue.TYPE_INT_COLOR_ARGB8,
                color.toArgb()
            )
        }
    }
}
