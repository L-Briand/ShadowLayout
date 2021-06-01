package net.orandja.shadowlayout

import android.content.Context
import android.content.res.Resources
import android.graphics.*
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicBlur
import android.util.AttributeSet
import android.util.DisplayMetrics
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import androidx.annotation.ColorRes
import androidx.annotation.DimenRes
import androidx.annotation.Nullable
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.scaleMatrix
import androidx.core.graphics.times
import androidx.core.graphics.translationMatrix
import androidx.core.graphics.withMatrix
import kotlin.math.ceil

/** A CSS like shadow */
@Suppress("PropertyName")
class ShadowLayout @JvmOverloads constructor(
    context: Context,
    @Nullable attrs: AttributeSet? = null,
    defStyleAttr: Int = R.attr.shadowLayoutStyle,
    defStyleRes: Int = 0
) : FrameLayout(context, attrs, defStyleAttr, defStyleRes) {

    companion object {
        @JvmField
        val ratioDpToPixels =
            Resources.getSystem().displayMetrics.densityDpi.toFloat() / DisplayMetrics.DENSITY_DEFAULT

        @JvmField
        val ratioPixelsToDp: Float = (1.0 / ratioDpToPixels.toDouble()).toFloat()

        // Thanks to this hero https://stackoverflow.com/a/41322648/4681367
        const val cssRatio: Float = 5f / 3f
    }

    // BASIC FIELDS

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
    private val eraser = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }

    var shadow_color: Int
        get() = paint.color
        set(value) {
            if (paint.color == value) return
            paint.color = value
            postInvalidate()
        }

    fun setColorRes(@ColorRes color: Int) {
        shadow_color = ResourcesCompat.getColor(resources, color, context.theme)
    }

    var shadow_x_shift: Float by OnUpdate(0f)
    fun setXShift(@DimenRes shift: Int) {
        shadow_x_shift = context.resources.getDimension(shift)
    }

    var shadow_y_shift: Float by OnUpdate(0f)
    fun setYShift(@DimenRes shift: Int) {
        shadow_y_shift = context.resources.getDimension(shift)
    }

    var shadow_downscale: Float by OnUpdate(1f, { it.coerceAtLeast(0.1f) }) {
        realRadius = shadow_radius / it
        updateBitmap()
    }
    var shadow_radius: Float by OnUpdate(0f, { it.coerceAtLeast(0f) }) {
        realRadius = it / shadow_downscale
    }
    private var realRadius: Float by OnUpdate(
        0f, { it.coerceIn(0f, 25f) } // allowed blur size on ScriptIntrinsicBlur by android
    )

    var shadow_cast_only_background: Boolean by OnUpdate(false)
    var shadow_with_content: Boolean by OnUpdate(true)
    var shadow_with_color: Boolean by OnUpdate(false) {
        destroyBitmap()
        updateBitmap()
    }
    var shadow_with_dpi_scale: Boolean by OnUpdate(true) {
        destroyBitmap()
        updateBitmap()
    }
    var shadow_with_css_scale: Boolean by OnUpdate(true) {
        destroyBitmap()
        updateBitmap()
    }


    // IN VARIABLES

    private val ratioDpToPixels get() = if (shadow_with_dpi_scale) Companion.ratioDpToPixels else 1f
    private val ratioPixelsToDp get() = if (shadow_with_dpi_scale) Companion.ratioPixelsToDp else 1f
    private val cssRatio get() = if (shadow_with_css_scale) Companion.cssRatio else 1f

    // size in pixel of the blur spread
    private val pixelsOverBoundaries: Int get() = if (shadow_downscale < 1f) 25 else ceil(25f * shadow_downscale).toInt()
    private val viewBounds: Rect = Rect()
    private fun setViewBounds(width: Int, height: Int) {
        viewBounds.set(0, 0, width, height)
        updateBitmap()
    }

    private var blurBitmap: Bitmap? = null
    private var blurCanvas: Canvas? = null

    private var renderScript: RenderScript? = null;
    private var script: ScriptIntrinsicBlur? = null;
    private var inAlloc: Allocation? = null
    private var outAlloc: Allocation? = null

    private var lastWithColorScript: Boolean? = null
    private fun getScript(): Pair<ScriptIntrinsicBlur, RenderScript> {
        val renderScript = this.renderScript ?: RenderScript.create(context)
        if (lastWithColorScript != shadow_with_color) { // recreate script only if colors change
            lastWithColorScript = shadow_with_color
            script = null
        }
        if (script != null) return Pair(script!!, renderScript!!)
        val element = if (shadow_with_color) Element.U8_4(renderScript) else Element.U8(renderScript)
        script = ScriptIntrinsicBlur.create(renderScript, element)
        return Pair(script!!, renderScript!!)
    }

    private val lastBounds = Rect()
    private var lastScale = 0f
    private var lastWithColorBitmap: Boolean? = null
    private var lastWithDpi: Boolean? = null
    private var lastWithCss: Boolean? = null
    private fun updateBitmap() {
        // do not recreate if same specs.
        if (viewBounds.isEmpty || isAttachedToWindow
            && lastBounds == viewBounds
            && shadow_downscale == lastScale
            && shadow_with_color == lastWithColorBitmap
            && shadow_with_dpi_scale == lastWithDpi
            && shadow_with_css_scale == lastWithCss
        ) return
        lastBounds.set(viewBounds)
        lastScale = shadow_downscale
        lastWithColorBitmap = shadow_with_color
        lastWithColorBitmap = shadow_with_color
        lastWithDpi = shadow_with_dpi_scale
        lastWithCss = shadow_with_css_scale

        // create a receptacle for blur script. (MDPI / downscale) + (pixels * 2) cause blur spread in all directions
        blurBitmap?.recycle()
        blurBitmap = Bitmap.createBitmap(
            (ceil(
                (viewBounds.width().toFloat() * ratioPixelsToDp) / shadow_downscale / cssRatio
            ) + pixelsOverBoundaries * 2).toInt(),
            (ceil(
                (viewBounds.height().toFloat() * ratioPixelsToDp) / shadow_downscale / cssRatio
            ) + pixelsOverBoundaries * 2).toInt(),
            if (shadow_with_color) Bitmap.Config.ARGB_8888 else Bitmap.Config.ALPHA_8
        )

        blurCanvas = Canvas(blurBitmap!!)

        val (script, renderScript) = getScript()
        inAlloc?.destroy()
        inAlloc = Allocation.createFromBitmap(renderScript, blurBitmap)
        if (outAlloc?.type != inAlloc?.type) {
            outAlloc?.destroy()
            outAlloc = Allocation.createTyped(renderScript, inAlloc!!.type)
        }
        script.setInput(inAlloc)
    }

    private fun destroyBitmap() {
        blurBitmap?.recycle()
        blurBitmap = null
        blurCanvas = null
        script?.destroy()
        script = null
        inAlloc?.destroy()
        inAlloc = null
        outAlloc?.destroy()
        outAlloc = null
        lastBounds.setEmpty()
        lastScale = 0f
        lastWithColorScript = null
        lastWithColorBitmap = null
        lastWithDpi = null
        lastWithCss = null
    }

    /** Cause the default elevation rendering to not work */
    override fun getOutlineProvider(): ViewOutlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(view: View?, outline: Outline?) = Unit
    }

    // Overriding view

    init {
        if (!isInEditMode) {
            val attributes = context.obtainStyledAttributes(
                attrs, R.styleable.ShadowLayout, defStyleAttr, defStyleRes
            )

            shadow_color = attributes.getColor(R.styleable.ShadowLayout_shadow_color, 51 shl 24)
            shadow_with_color = attributes.getBoolean(R.styleable.ShadowLayout_shadow_with_color, false)
            shadow_with_content = attributes.getBoolean(R.styleable.ShadowLayout_shadow_with_content, true)
            shadow_with_dpi_scale = attributes.getBoolean(R.styleable.ShadowLayout_shadow_with_dpi_scale, true)
            shadow_with_css_scale = attributes.getBoolean(R.styleable.ShadowLayout_shadow_with_css_scale, true)
            shadow_x_shift = attributes.getDimension(R.styleable.ShadowLayout_shadow_x_shift, 0f)
            shadow_y_shift = attributes.getDimension(R.styleable.ShadowLayout_shadow_y_shift, 0f)
            shadow_downscale = attributes.getFloat(R.styleable.ShadowLayout_shadow_downscale, 1f)
            shadow_radius = attributes.getFloat(R.styleable.ShadowLayout_shadow_radius, 6f)
            shadow_cast_only_background = attributes.getBoolean(R.styleable.ShadowLayout_shadow_cast_only_background, false)

            attributes.recycle()
        }
        setWillNotDraw(false)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!isInEditMode) updateBitmap()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        if (!isInEditMode) destroyBitmap()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (!isInEditMode) setViewBounds(w, h)
    }

    private inline val blurTMatrix: Matrix // cause blur spreads
        get() = translationMatrix(pixelsOverBoundaries.toFloat(), pixelsOverBoundaries.toFloat())
    private inline val blurSMatrix: Matrix // to draw inside the small blurBitmap
        get() = scaleMatrix(ratioPixelsToDp / shadow_downscale / cssRatio, ratioPixelsToDp / shadow_downscale / cssRatio)

    private inline val drawTMatrix: Matrix // counterbalance for blur spread in canvas
        get() = translationMatrix(
            -(pixelsOverBoundaries * ratioDpToPixels * shadow_downscale * cssRatio),
            -(pixelsOverBoundaries * ratioDpToPixels * shadow_downscale * cssRatio)
        )
    private inline val drawSMatrix: Matrix // enlarge blur image to canvas size
        get() = scaleMatrix(
            ratioDpToPixels * shadow_downscale * cssRatio,
            ratioDpToPixels * shadow_downscale * cssRatio
        )
    private inline val shiftTMatrix: Matrix // User want a nice shifted shadow
        get() = translationMatrix(
            shadow_x_shift / shadow_downscale / cssRatio,
            shadow_y_shift / shadow_downscale / cssRatio
        )

    override fun draw(canvas: Canvas?) {
        canvas ?: return
        if (isInEditMode) {
            super.draw(canvas)
            return
        }
        if (blurCanvas != null) {
            blurCanvas!!.drawRect(blurCanvas!!.clipBounds, eraser)
            blurCanvas!!.withMatrix(blurTMatrix * blurSMatrix) {
                if (shadow_cast_only_background) {
                    background.bounds = viewBounds
                    background?.draw(blurCanvas!!)
                } else super.draw(blurCanvas)
            }
            if (realRadius > 0f) { // Do not blur if no radius
                val (script) = getScript()
                script.setRadius(realRadius)
                inAlloc?.copyFrom(blurBitmap)
                script.forEach(outAlloc)
                outAlloc?.copyTo(blurBitmap)
            }
            canvas.withMatrix(drawTMatrix * drawSMatrix * shiftTMatrix) {
                canvas.drawBitmap(blurBitmap!!, 0f, 0f, paint)
            }
        }
        if (shadow_with_content) super.draw(canvas)
    }

}