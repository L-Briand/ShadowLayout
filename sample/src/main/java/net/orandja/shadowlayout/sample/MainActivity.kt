package net.orandja.shadowlayout.sample

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import net.orandja.shadowlayout.ShadowLayout

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        ValueAnimator.ofFloat(0f, 0f, 25f, 25f).apply {
            duration = 2000
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
            addUpdateListener { findViewById<ShadowLayout>(R.id.__rainbow).shadow_radius = it.animatedValue as Float }
        }.start()
    }
}
