@file:Suppress("LeakingThis")

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
import androidx.annotation.ColorInt
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
open class ShadowLayout @JvmOverloads constructor(
    context: Context,
    @Nullable attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
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

    open fun getColor(): Int = paint.color
    open fun setColor(@ColorInt color: Int) {
        if (paint.color == color) return
        paint.color = color
        postInvalidate()
    }

    open fun setColorRes(@ColorRes color: Int) {
        setColor(ResourcesCompat.getColor(resources, color, context.theme))
    }

    open var xShift: Float = 0f
        set(value) {
            if (field == value) return
            field = value
            postInvalidate()
        }

    open fun setXShift(@DimenRes shift: Int) {
        xShift = context.resources.getDimension(shift)
    }

    open var yShift: Float = 0f
        set(value) {
            if (field == value) return
            field = value
            postInvalidate()
        }

    open fun setYShift(@DimenRes shift: Int) {
        yShift = context.resources.getDimension(shift)
    }

    open var downscale: Float = 1f
        set(value) {
            if (field == value) return
            field = value.coerceAtLeast(0.1f)
            realRadius = radius / field
            updateBitmap()
            postInvalidate()
        }

    open var radius: Float = 0f
        set(value) {
            if (field == value) return
            field = value.coerceAtLeast(0f)
            realRadius = field / downscale
            postInvalidate()
        }

    private var realRadius: Float = 0f
        set(value) {
            if (field == value) return
            field = value.coerceIn(0f, 25f) // allowed blur size on ScriptIntrinsicBlur by android
        }

    open var withContent: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            postInvalidate()
        }

    open var withColor: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            destroyBitmap()
            updateBitmap()
            postInvalidate()
        }

    open var withDpi: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            destroyBitmap()
            updateBitmap()
            postInvalidate()
        }

    open var withCss: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            destroyBitmap()
            updateBitmap()
            postInvalidate()
        }

    open var castBackground: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            postInvalidate()
        }

    // IN VARIABLES

    private val ratioDpToPixels get() = if (withDpi) Companion.ratioDpToPixels else 1f
    private val ratioPixelsToDp get() = if (withDpi) Companion.ratioPixelsToDp else 1f
    private val cssRatio get() = if (withCss) Companion.cssRatio else 1f

    // size in pixel of the blur spread
    private val pixelsOverBoundaries: Int get() = if (downscale < 1f) 25 else ceil(25f * downscale).toInt()
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
        if (lastWithColorScript != withColor) { // recreate script only if colors change
            lastWithColorScript = withColor
            script = null
        }
        if (script != null) return Pair(script!!, renderScript!!)
        val element = if (withColor) Element.U8_4(renderScript) else Element.U8(renderScript)
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
            && downscale == lastScale
            && withColor == lastWithColorBitmap
            && withDpi == lastWithDpi
            && withCss == lastWithCss
        ) return
        lastBounds.set(viewBounds)
        lastScale = downscale
        lastWithColorBitmap = withColor
        lastWithColorBitmap = withColor
        lastWithDpi = withDpi
        lastWithCss = withCss

        // create a receptacle for blur script. (MDPI / downscale) + (pixels * 2) cause blur spread in all directions
        blurBitmap?.recycle()
        blurBitmap = Bitmap.createBitmap(
            (ceil(
                (viewBounds.width().toFloat() * ratioPixelsToDp) / downscale / cssRatio
            ) + pixelsOverBoundaries * 2).toInt(),
            (ceil(
                (viewBounds.height().toFloat() * ratioPixelsToDp) / downscale / cssRatio
            ) + pixelsOverBoundaries * 2).toInt(),
            if (withColor) Bitmap.Config.ARGB_8888 else Bitmap.Config.ALPHA_8
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

    override fun getOutlineProvider(): ViewOutlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(view: View?, outline: Outline?) = Unit
    }

    // Overriding view

    init {
        if (!isInEditMode) {
            val attributes = context.obtainStyledAttributes(
                attrs, R.styleable.ShadowLayout, defStyleAttr, defStyleRes
            )
            setColor(attributes.getColor(R.styleable.ShadowLayout_shadow_color, 51 shl 24))
            withColor = attributes.getBoolean(R.styleable.ShadowLayout_shadow_with_color, false)
            withContent = attributes.getBoolean(R.styleable.ShadowLayout_shadow_with_content, true)
            withDpi = attributes.getBoolean(R.styleable.ShadowLayout_shadow_with_dpi_scale, true)
            withCss = attributes.getBoolean(R.styleable.ShadowLayout_shadow_with_css_scale, true)
            xShift = attributes.getDimension(R.styleable.ShadowLayout_shadow_x_shift, 0f)
            yShift = attributes.getDimension(R.styleable.ShadowLayout_shadow_y_shift, 0f)
            downscale = attributes.getFloat(R.styleable.ShadowLayout_shadow_downscale, 1f)
            radius = attributes.getFloat(R.styleable.ShadowLayout_shadow_radius, 6f)
            castBackground = attributes.getBoolean(R.styleable.ShadowLayout_shadow_cast_background, false)

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

    private val blurTMatrix: Matrix // cause blur spreads
        get() = translationMatrix(pixelsOverBoundaries.toFloat(), pixelsOverBoundaries.toFloat())
    private val blurSMatrix: Matrix // to draw inside the small blurBitmap
        get() = scaleMatrix(ratioPixelsToDp / downscale / cssRatio, ratioPixelsToDp / downscale / cssRatio)

    private val drawTMatrix: Matrix // counterbalance for blur spread in canvas
        get() = translationMatrix(
            -(pixelsOverBoundaries * ratioDpToPixels * downscale * cssRatio),
            -(pixelsOverBoundaries * ratioDpToPixels * downscale * cssRatio)
        )
    private val drawSMatrix: Matrix // enlarge blur image to canvas size
        get() = scaleMatrix(
            ratioDpToPixels * downscale * cssRatio,
            ratioDpToPixels * downscale * cssRatio
        )
    private val shiftTMatrix: Matrix // User want a nice shifted shadow
        get() = translationMatrix(
            xShift / downscale / cssRatio,
            yShift / downscale / cssRatio
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
                if (castBackground) {
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
        if (withContent) super.draw(canvas)
    }

}