package app.typelauncher

import android.os.Bundle
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.content.getSystemService

class MainActivity : AppCompatActivity() {
    private lateinit var appSearchInput: EditText
    private var keyboardRequested = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        appSearchInput = findViewById(R.id.app_search_input)
        appSearchInput.requestFocus()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !keyboardRequested) {
            requestKeyboardWithRetry(attempt = 0)
        }
    }

    private fun requestKeyboardWithRetry(attempt: Int) {
        appSearchInput.post {
            if (isFinishing || isDestroyed || !appSearchInput.isAttachedToWindow) {
                return@post
            }

            appSearchInput.requestFocus()
            ViewCompat.getWindowInsetsController(appSearchInput)
                ?.show(WindowInsetsCompat.Type.ime())
            getSystemService<InputMethodManager>()
                ?.showSoftInput(appSearchInput, InputMethodManager.SHOW_IMPLICIT)

            val imeVisible = appSearchInput.rootWindowInsets
                ?.isVisible(android.view.WindowInsets.Type.ime()) == true
            if (imeVisible || attempt >= 3) {
                keyboardRequested = true
                return@post
            }

            appSearchInput.postDelayed(
                { requestKeyboardWithRetry(attempt + 1) },
                75L,
            )
        }
    }
}
