package com.vozlivre.app

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView

object Brand {
    const val SIGNATURE = "VozLivre — Desenvolvido por Guido Horácio"
}

object Colors {
    val BG = 0xFF000000.toInt()
    val SURFACE = 0xFF06110B.toInt()
    val CARD = 0xFF0A1811.toInt()
    val CARD_HI = 0xFF0F241A.toInt()
    val STROKE = 0xFF18553A.toInt()
    val GREEN = 0xFF00E676.toInt()
    val GREEN_DIM = 0xFF0B6B3A.toInt()
    val TEXT = 0xFFE9F6EE.toInt()
    val MUTED = 0xFF8AA396.toInt()
    val RED = 0xFFFF5252.toInt()
    val AMBER = 0xFFFFB74D.toInt()
    fun alpha(c: Int, a: Int): Int = (c and 0x00FFFFFF) or (a shl 24)
}

/** Ponte entre os ecrãs e a Activity (permissões, avisos, navegação). */
interface Host {
    fun toast(m: String)
    fun withPerms(perms: List<String>, whenOk: () -> Unit)
    fun allGranted(perms: List<String>): Boolean
    fun p2pPerms(): List<String>
    fun btPerms(): List<String>
    fun micPerms(): List<String>
    fun notifPerms(): List<String>
    fun scanBluetooth()
    fun makeDiscoverable()
    fun scanAll()
    fun openBatterySettings()
    fun changeMode()
    fun goTo(index: Int)
    fun getVolume(): Int
    fun setVolume(pct: Int)
    fun registerLog(s: LogSection)
    fun openSub(title: String, icon: Int, sections: List<Section>)
}

/** Um bloco de interface com atualização própria. */
abstract class Section {
    abstract val view: View
    open fun refresh() {}
    /** Secções "vivas" são atualizadas a cada segundo (temporizadores). */
    open val live: Boolean get() = false
}

/** Envolve uma vista simples como secção. */
class StaticSection(override val view: View) : Section()

/** Fábrica de componentes no estilo escuro com verde elétrico. */
class Kit(val ctx: Context) {
    enum class Style { PRIMARY, SECONDARY, DANGER, GHOST }

    private val density = ctx.resources.displayMetrics.density
    fun dp(v: Int): Int = (v * density + 0.5f).toInt()

    fun lp(w: Int = -1, h: Int = -2, top: Int = 0, bottom: Int = 0, left: Int = 0, right: Int = 0, weight: Float = 0f): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(w, h, weight).apply {
            topMargin = dp(top); bottomMargin = dp(bottom); leftMargin = dp(left); rightMargin = dp(right)
        }

    fun text(s: CharSequence, size: Float = 14f, color: Int = Colors.TEXT, bold: Boolean = false): TextView {
        val t = TextView(ctx)
        t.text = s
        t.textSize = size
        t.setTextColor(color)
        t.typeface = if (bold) Typeface.create("sans-serif-medium", Typeface.BOLD) else Typeface.create("sans-serif", Typeface.NORMAL)
        return t
    }

    fun muted(s: CharSequence, size: Float = 13f): TextView = text(s, size, Colors.MUTED)

    fun mono(s: CharSequence, size: Float = 11f): TextView {
        val t = text(s, size, Colors.MUTED)
        t.typeface = Typeface.MONOSPACE
        return t
    }

    fun shape(fill: Int, radius: Int, strokeColor: Int = 0, strokeDp: Int = 0): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(fill)
        g.cornerRadius = dp(radius).toFloat()
        if (strokeDp > 0) g.setStroke(dp(strokeDp), strokeColor)
        return g
    }

    /** Brilho suave por baixo do elemento (Android 9 ou superior). */
    fun glow(v: View, color: Int = Colors.GREEN, elevationDp: Int = 4) {
        if (Build.VERSION.SDK_INT >= 28) {
            v.elevation = dp(elevationDp).toFloat()
            v.outlineSpotShadowColor = Colors.alpha(color, 0xAA)
            v.outlineAmbientShadowColor = Colors.alpha(color, 0x66)
        }
    }

    fun card(highlight: Boolean = false): LinearLayout {
        val c = LinearLayout(ctx)
        c.orientation = LinearLayout.VERTICAL
        c.setPadding(dp(16), dp(14), dp(16), dp(14))
        c.background = shape(
            if (highlight) Colors.CARD_HI else Colors.CARD, 16,
            if (highlight) Colors.alpha(Colors.GREEN, 0x99) else Colors.STROKE, 1
        )
        if (highlight) glow(c, Colors.GREEN, 3)
        return c
    }

    fun icon(res: Int, tint: Int = Colors.GREEN, sizeDp: Int = 22): ImageView {
        val i = ImageView(ctx)
        i.setImageResource(res)
        i.setColorFilter(tint)
        i.layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        return i
    }

    fun sectionTitle(iconRes: Int, title: String, right: View? = null): LinearLayout {
        val r = LinearLayout(ctx)
        r.orientation = LinearLayout.HORIZONTAL
        r.gravity = Gravity.CENTER_VERTICAL
        r.addView(icon(iconRes))
        r.addView(text(title, 16f, Colors.TEXT, true), lp(0, -2, left = 10, weight = 1f))
        if (right != null) r.addView(right)
        return r
    }

    private fun dotDrawable(color: Int): LayerDrawable {
        val halo = GradientDrawable()
        halo.shape = GradientDrawable.OVAL
        halo.setColor(Colors.alpha(color, 0x44))
        val core = GradientDrawable()
        core.shape = GradientDrawable.OVAL
        core.setColor(color)
        val ld = LayerDrawable(arrayOf<Drawable>(halo, core))
        ld.setLayerInset(1, dp(3), dp(3), dp(3), dp(3))
        return ld
    }

    fun dot(color: Int, sizeDp: Int = 14): View {
        val v = View(ctx)
        v.background = dotDrawable(color)
        v.layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        return v
    }

    fun recolorDot(v: View, color: Int) { v.background = dotDrawable(color) }

    fun pill(label: String, color: Int = Colors.GREEN): TextView {
        val t = text(label, 11f, color, true)
        t.setPadding(dp(8), dp(3), dp(8), dp(3))
        t.background = shape(Colors.alpha(color, 0x22), 20, Colors.alpha(color, 0x66), 1)
        return t
    }

    fun button(label: String, style: Style = Style.PRIMARY, compact: Boolean = false, onClick: () -> Unit): Button {
        val b = Button(ctx)
        b.text = label
        b.isAllCaps = false
        b.textSize = if (compact) 12f else 14f
        b.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        b.stateListAnimator = null
        b.minHeight = dp(if (compact) 36 else 44)
        b.minimumHeight = dp(if (compact) 36 else 44)
        b.setPadding(dp(if (compact) 12 else 16), dp(6), dp(if (compact) 12 else 16), dp(6))
        when (style) {
            Style.PRIMARY -> { b.setTextColor(Color.BLACK); b.background = shape(Colors.GREEN, 12); glow(b, Colors.GREEN, 6) }
            Style.SECONDARY -> { b.setTextColor(Colors.GREEN); b.background = shape(Colors.alpha(Colors.GREEN, 0x14), 12, Colors.alpha(Colors.GREEN, 0x88), 1) }
            Style.DANGER -> { b.setTextColor(Colors.RED); b.background = shape(Colors.alpha(Colors.RED, 0x1F), 12, Colors.alpha(Colors.RED, 0x88), 1) }
            Style.GHOST -> { b.setTextColor(Colors.TEXT); b.background = shape(Colors.CARD_HI, 12, Colors.STROKE, 1) }
        }
        b.setOnClickListener { onClick() }
        return b
    }

    fun input(hint: String, password: Boolean = false): EditText {
        val e = EditText(ctx)
        e.hint = hint
        e.setSingleLine(true)
        e.setTextColor(Colors.TEXT)
        e.setHintTextColor(Colors.MUTED)
        e.textSize = 15f
        e.background = shape(Colors.SURFACE, 12, Colors.STROKE, 1)
        e.setPadding(dp(14), dp(10), dp(14), dp(10))
        if (password) e.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        return e
    }

    fun inputRow(input: View, action: View): LinearLayout {
        val r = LinearLayout(ctx)
        r.orientation = LinearLayout.HORIZONTAL
        r.gravity = Gravity.CENTER_VERTICAL
        r.addView(input, lp(0, -2, weight = 1f))
        r.addView(action, lp(-2, -2, left = 8))
        return r
    }

    /** Linha com texto e interruptor. O Switch fica em [LinearLayout.getTag]. */
    fun switchRow(label: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit): LinearLayout {
        val r = LinearLayout(ctx)
        r.orientation = LinearLayout.HORIZONTAL
        r.gravity = Gravity.CENTER_VERTICAL
        val col = LinearLayout(ctx)
        col.orientation = LinearLayout.VERTICAL
        col.addView(text(label, 15f))
        if (sub.isNotEmpty()) col.addView(muted(sub, 12f))
        r.addView(col, lp(0, -2, weight = 1f))
        val sw = Switch(ctx)
        sw.isChecked = checked
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        sw.thumbTintList = ColorStateList(states, intArrayOf(Colors.GREEN, Colors.MUTED))
        sw.trackTintList = ColorStateList(states, intArrayOf(Colors.GREEN_DIM, 0xFF263229.toInt()))
        sw.setOnCheckedChangeListener { _, v -> onChange(v) }
        r.addView(sw)
        r.tag = sw
        return r
    }

    fun avatar(name: String, sizeDp: Int = 44, color: Int = Colors.GREEN): TextView {
        val initial = name.trim().firstOrNull()?.uppercase() ?: "?"
        val t = text(initial, sizeDp / 2.4f, color, true)
        t.gravity = Gravity.CENTER
        t.background = shape(Colors.alpha(color, 0x1F), sizeDp, Colors.alpha(color, 0x77), 1)
        t.layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        return t
    }

    fun kv(key: String, value: TextView): LinearLayout {
        val r = LinearLayout(ctx)
        r.orientation = LinearLayout.HORIZONTAL
        r.gravity = Gravity.CENTER_VERTICAL
        r.addView(muted(key, 13f), lp(0, -2, weight = 1f))
        value.gravity = Gravity.END
        r.addView(value, lp(-2, -2))
        return r
    }

    fun kv(key: String, value: String, color: Int = Colors.TEXT): LinearLayout = kv(key, text(value, 13f, color, true))

    fun iconCircle(iconRes: Int, sizeDp: Int = 40, color: Int = Colors.GREEN): FrameLayout {
        val f = FrameLayout(ctx)
        f.background = shape(Colors.alpha(color, 0x1F), sizeDp, Colors.alpha(color, 0x88), 1)
        val i = ImageView(ctx)
        i.setImageResource(iconRes)
        i.setColorFilter(color)
        f.addView(i, FrameLayout.LayoutParams(dp(sizeDp * 5 / 9), dp(sizeDp * 5 / 9), Gravity.CENTER))
        f.layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        return f
    }

    fun deviceTile(sizeDp: Int = 42, color: Int = Colors.GREEN, iconRes: Int = R.drawable.ic_device): FrameLayout {
        val f = FrameLayout(ctx)
        f.background = shape(Colors.alpha(color, 0x1F), 12, Colors.alpha(color, 0x66), 1)
        val i = ImageView(ctx)
        i.setImageResource(iconRes)
        i.setColorFilter(color)
        f.addView(i, FrameLayout.LayoutParams(dp(sizeDp * 5 / 9), dp(sizeDp * 5 / 9), Gravity.CENTER))
        f.layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        return f
    }

    /** Cabeçalho de ecrã: ícone e título, com seta de voltar opcional. */
    fun titleHeader(iconRes: Int, title: String, onBack: (() -> Unit)? = null): LinearLayout {
        val r = LinearLayout(ctx)
        r.orientation = LinearLayout.HORIZONTAL
        r.gravity = Gravity.CENTER_VERTICAL
        if (onBack != null) {
            val back = icon(R.drawable.ic_chevron, Colors.GREEN, 26)
            back.rotation = 180f
            back.setPadding(dp(2), dp(2), dp(2), dp(2))
            back.setOnClickListener { onBack() }
            r.addView(back)
        }
        r.addView(icon(iconRes, Colors.GREEN, 26), lp(dp(26), dp(26), left = if (onBack != null) 8 else 0))
        r.addView(text(title, 22f, Colors.TEXT, true), lp(0, -2, left = 10, weight = 1f))
        return r
    }

    fun segmented(labels: List<String>, selected: Int, onSelect: (Int) -> Unit): LinearLayout {
        val c = LinearLayout(ctx)
        c.orientation = LinearLayout.HORIZONTAL
        c.background = shape(Colors.SURFACE, 12, Colors.STROKE, 1)
        c.setPadding(dp(3), dp(3), dp(3), dp(3))
        for ((i, label) in labels.withIndex()) {
            val t = text(label, 13f, if (i == selected) Colors.GREEN else Colors.TEXT, i == selected)
            t.gravity = Gravity.CENTER
            t.setPadding(dp(6), dp(8), dp(6), dp(8))
            if (i == selected) t.background = shape(Colors.alpha(Colors.GREEN, 0x26), 10, Colors.alpha(Colors.GREEN, 0x99), 1)
            t.setOnClickListener { onSelect(i) }
            c.addView(t, lp(0, -2, left = if (i > 0) 4 else 0, weight = 1f))
        }
        return c
    }

    /** Linha de lista com ícone, título, subtítulo e seta. */
    fun navRow(iconRes: Int, title: String, sub: String, onClick: () -> Unit): LinearLayout {
        val r = LinearLayout(ctx)
        r.orientation = LinearLayout.HORIZONTAL
        r.gravity = Gravity.CENTER_VERTICAL
        r.background = shape(Colors.CARD, 14, Colors.STROKE, 1)
        r.setPadding(dp(12), dp(10), dp(12), dp(10))
        r.addView(iconCircle(iconRes, 40))
        val col = LinearLayout(ctx)
        col.orientation = LinearLayout.VERTICAL
        col.addView(text(title, 15f, Colors.TEXT, true))
        if (sub.isNotEmpty()) col.addView(muted(sub, 12f))
        r.addView(col, lp(0, -2, left = 12, weight = 1f))
        r.addView(icon(R.drawable.ic_chevron, Colors.MUTED, 18))
        r.isClickable = true
        r.setOnClickListener { onClick() }
        return r
    }

    /** Linha informativa: ícone, rótulo pequeno e valor. */
    fun infoRow(iconRes: Int, label: String, value: TextView): LinearLayout {
        val r = LinearLayout(ctx)
        r.orientation = LinearLayout.HORIZONTAL
        r.gravity = Gravity.CENTER_VERTICAL
        r.background = shape(Colors.CARD, 14, Colors.STROKE, 1)
        r.setPadding(dp(12), dp(10), dp(12), dp(10))
        r.addView(iconCircle(iconRes, 40))
        val col = LinearLayout(ctx)
        col.orientation = LinearLayout.VERTICAL
        col.addView(muted(label, 12f))
        value.textSize = 15f
        col.addView(value)
        r.addView(col, lp(0, -2, left = 12, weight = 1f))
        return r
    }

    fun statTile(iconRes: Int, label: String, value: TextView): LinearLayout {
        val t = LinearLayout(ctx)
        t.orientation = LinearLayout.VERTICAL
        t.background = shape(Colors.CARD, 16, Colors.STROKE, 1)
        t.setPadding(dp(14), dp(14), dp(14), dp(14))
        t.addView(iconCircle(iconRes, 38))
        t.addView(muted(label, 12f), lp(top = 10))
        value.textSize = 20f
        t.addView(value, lp(top = 2))
        return t
    }

    fun divider(): View {
        val v = View(ctx)
        v.setBackgroundColor(Colors.STROKE)
        v.layoutParams = LinearLayout.LayoutParams(-1, 1)
        return v
    }
}

/** Ondas verdes discretas atrás do logótipo. */
class WaveView(ctx: Context) : View(ctx) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f * ctx.resources.displayMetrics.density
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        for (n in 0 until 3) {
            paint.color = Colors.alpha(Colors.GREEN, 0x55 - n * 0x16)
            val path = Path()
            path.moveTo(0f, h * 0.6f)
            var x = 0f
            while (x <= w) {
                val phase = (x / w) * 6.2832f * (1.1f + n * 0.35f) + n * 1.3f
                val y = h * 0.6f + kotlin.math.sin(phase) * h * (0.22f - n * 0.04f)
                path.lineTo(x, y)
                x += 6f
            }
            c.drawPath(path, paint)
        }
    }
}
