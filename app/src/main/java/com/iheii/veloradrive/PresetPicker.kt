package com.iheii.veloradrive

import android.app.Activity
import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import com.iheii.veloradrive.databinding.DialogPresetsBinding
import com.iheii.veloradrive.databinding.ItemPresetTileBinding

/**
 * Selector de presets de audio (rejilla de 4 columnas). [activeId] se marca en el color de cabina.
 * Al final: "Ecualizador del estéreo" (abre la app EQ del estéreo) y, solo con OfflineTune
 * conectado, "Los del celular".
 */
object PresetPicker {

    fun show(
        activity: Activity,
        @ColorInt accent: Int,
        activeId: String?,
        linked: Boolean,
        onPick: (String) -> Unit,
        onStereoEq: () -> Unit,
        onPhone: () -> Unit,
    ) {
        val b = DialogPresetsBinding.inflate(activity.layoutInflater)
        val dialog = Dialog(activity).apply { requestWindowFeature(android.view.Window.FEATURE_NO_TITLE) }
        val d = activity.resources.displayMetrics.density
        val gap = (12 * d).toInt()

        fun addTile(index: Int, build: (ItemPresetTileBinding) -> Unit, onClick: () -> Unit) {
            val t = ItemPresetTileBinding.inflate(activity.layoutInflater, b.presetGrid, false)
            build(t)
            t.root.layoutParams = GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED),
                GridLayout.spec(GridLayout.UNDEFINED, 1f),
            ).apply {
                width = 0
                height = (86 * d).toInt()
                // Separación solo entre fichas, no en los bordes.
                marginStart = if (index % 4 == 0) 0 else gap
                topMargin = if (index < 4) 0 else gap
            }
            t.root.setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                dialog.dismiss()
                onClick()
            }
            b.presetGrid.addView(t.root)
        }

        var i = 0
        EqPresets.all.forEach { p ->
            val active = p.id == activeId
            addTile(i++, { t ->
                t.tileName.setText(p.label)
                t.tileName.setTextColor(if (active) accent else Color.WHITE)
                t.root.background = tileBg(d, if (active) accent else 0xFF262E38.toInt(),
                    if (active) ThemeManager.withAlpha(accent, 0x22) else 0xFF0F1318.toInt(), dashed = false)
                val sub = when {
                    active -> activity.getString(R.string.eq_active)
                    p.id == "normal" -> activity.getString(R.string.eq_normal_sub)
                    else -> null
                }
                if (sub != null) {
                    t.tileSub.text = sub
                    t.tileSub.visibility = View.VISIBLE
                }
            }) { onPick(p.id) }
        }
        addTile(i++, { t -> dashedTile(t, d, R.drawable.ic_eq, R.string.eq_stereo) }) { onStereoEq() }
        if (linked) addTile(i, { t -> dashedTile(t, d, R.drawable.ic_phone_signal, R.string.eq_phone) }) { onPhone() }

        b.presetClose.setOnClickListener { dialog.dismiss() }
        dialog.setContentView(b.root)
        dialog.window?.let { w ->
            w.setBackgroundDrawable(GradientDrawable().apply {
                setColor(0xFF12151B.toInt())
                setStroke((1.5f * d).toInt(), accent)
                cornerRadius = 20 * d
            })
            w.setLayout((760 * d).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dialog.show()
        // Inmersivo también con el diálogo abierto (si no, aparecen las barras del sistema).
        dialog.window?.let { w ->
            androidx.core.view.WindowInsetsControllerCompat(w, w.decorView).apply {
                hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
    }

    private fun dashedTile(t: ItemPresetTileBinding, d: Float, @DrawableRes icon: Int, label: Int) {
        t.tileIcon.setImageResource(icon)
        t.tileIcon.imageTintList = ColorStateList.valueOf(0xFF9AA6B2.toInt())
        t.tileIcon.visibility = View.VISIBLE
        t.tileName.setText(label)
        t.tileName.textSize = 13f
        t.tileName.setTextColor(0xFF9AA6B2.toInt())
        t.tileName.paint.isFakeBoldText = false
        t.tileName.typeface = android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.NORMAL)
        t.root.background = tileBg(d, 0xFF3A4552.toInt(), Color.TRANSPARENT, dashed = true)
    }

    private fun tileBg(d: Float, @ColorInt stroke: Int, @ColorInt fill: Int, dashed: Boolean) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = 14 * d
        if (dashed) setStroke((1.2f * d).toInt().coerceAtLeast(1), stroke, 5 * d, 4 * d)
        else setStroke((1.2f * d).toInt().coerceAtLeast(1), stroke)
    }
}
