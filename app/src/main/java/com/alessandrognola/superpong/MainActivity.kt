package com.alessandrognola.superpong

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var gameView: GameView

    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                val original = BitmapFactory.decodeStream(input)
                if (original != null) {
                    val scaled = Bitmap.createScaledBitmap(original, 256, 256, true)
                    gameView.setAvatarPhoto(scaled)
                }
            }
        } catch (e: Exception) {
            // ignore: user just keeps their current avatar
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hideSystemBars()
        LeaderboardManager.init(this)
        gameView = GameView(this)
        setContentView(gameView)
        BillingManager.init(this) { amount -> gameView.grantGems(amount) }
    }

    fun pickAvatarPhoto() {
        pickImageLauncher.launch("image/*")
    }

    fun showRenameDialog(currentName: String) {
        val input = EditText(this)
        input.setText(currentName)
        AlertDialog.Builder(this)
            .setTitle("Edit name")
            .setView(input)
            .setPositiveButton("Save") { _, _ -> gameView.setUsername(input.text.toString()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
    }

    override fun onPause() {
        super.onPause()
        gameView.pause()
    }

    override fun onResume() {
        super.onResume()
        gameView.resume()
    }
}
