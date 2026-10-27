package app.vidia.browser

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Insets
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.RoundedCorner
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.io.ByteArrayInputStream
import kotlin.math.roundToInt

class MainActivity : Activity() {

  companion object {
    /** The start page is bundled in the app and served under this made-up https address. */
    private const val HOME_HOST = "start.vidia"
    const val HOME = "https://$HOME_HOST/"

    /** Streaming sites that only serve playable pages to desktop browsers. */
    private val DESKTOP_HOSTS = listOf(
      "netflix.com", "hulu.com", "disneyplus.com", "hbomax.com", "max.com",
      "primevideo.com", "amazon.com", "peacocktv.com", "paramountplus.com", "tv.apple.com",
      // Services in the start page's "Add a streaming service" list.
      "crunchyroll.com", "pluto.tv", "tubitv.com", "roku.com", "sling.com", "fubo.tv", "philo.com",
      "starz.com", "mgmplus.com", "amcplus.com", "britbox.com", "acorn.tv", "shudder.com",
      "discoveryplus.com", "espn.com", "pbs.org", "kanopy.com", "hoopladigital.com", "mubi.com",
      "criterionchannel.com", "curiositystream.com", "nebula.tv", "dailymotion.com", "dazn.com",
      "nba.com", "mlb.com", "nfl.com", "spotify.com", "pandora.com", "siriusxm.com",
      "iheart.com", "frndlytv.com", "xumo.com", "foxone.com", "plex.tv"
    )
    private const val PREFS = "browser"
    private const val KEY_DESKTOP = "desktop"
    private const val KEY_FILL = "fill"

    /** Right-edge margin set with long-press ⌂, in dp; absent means "use what the car reports". */
    private const val KEY_EDGE = "edge_right_dp"

    /**
     * Fill mode: zoom the main video (only) until it covers the whole screen, trimming the edges.
     * Uses a transform so the site's own player layout and controls are left untouched.
     *
     * The zoom lives in a stylesheet rule aimed at a data attribute on the video, never in the
     * video's own style attribute: players such as YouTube keep rewriting that attribute, which
     * wiped an inline zoom straight away (it showed for one frame each time it was re-applied).
     * The rule is !important, so it also outranks the player's own inline styles. If the page
     * made the <video> itself fullscreen, the browser pins its transform to none, so that case
     * crops with object-fit: cover instead. Re-checks on video size changes and every second,
     * because players resize and swap videos.
     */
    private const val FILL_JS = """
      (function () {
        var on = %s, ATTR = 'data-vidia-fill', SHEET = 'vidia-fill-css';
        var old = window.__vidiaFill;
        if (old) {
          clearInterval(old.timer);
          document.removeEventListener('loadedmetadata', old.kick, true);
          document.removeEventListener('resize', old.kick, true);
          window.removeEventListener('resize', old.kick);
          window.__vidiaFill = null;
        }
        var each = function (sel, fn) { Array.prototype.forEach.call(document.querySelectorAll(sel), fn); };
        var sheet = document.getElementById(SHEET);
        if (!on) {
          if (sheet) sheet.remove();
          each('video[' + ATTR + ']', function (v) { v.removeAttribute(ATTR); });
          return;
        }
        if (!sheet) { sheet = document.createElement('style'); sheet.id = SHEET; }
        var pick = function () {
          var main = document.querySelector('video.html5-main-video');
          if (main && main.videoWidth) return main;
          var best = null, area = 0;
          each('video', function (v) {
            var cs = getComputedStyle(v);
            if (cs.display === 'none' || cs.visibility === 'hidden' || parseFloat(cs.opacity) === 0) return;
            if (cs.filter && cs.filter !== 'none') return;
            if (!v.videoWidth) return;
            var a = v.offsetWidth * v.offsetHeight;
            if (a > area) { area = a; best = v; }
          });
          return best;
        };
        var apply = function () {
          var v = pick();
          each('video[' + ATTR + ']', function (o) { if (o !== v) o.removeAttribute(ATTR); });
          if (!v) return;
          var rule;
          if (v === (document.fullscreenElement || document.webkitFullscreenElement)) {
            rule = 'object-fit: cover !important;';
          } else {
            var w = v.offsetWidth, h = v.offsetHeight, vw = v.videoWidth, vh = v.videoHeight;
            if (!w || !h || !vw || !vh) return;
            var k = Math.min(w / vw, h / vh);
            var s = Math.max(window.innerWidth / (vw * k), window.innerHeight / (vh * k));
            if (!isFinite(s) || s < 1.001) s = 1;
            rule = 'transform: scale(' + s.toFixed(4) + ') !important; transform-origin: 50%% 50%% !important;';
          }
          var css = 'html video[' + ATTR + '][' + ATTR + '] { ' + rule + ' }';
          if (sheet.textContent !== css) sheet.textContent = css;
          if (!sheet.isConnected) (document.head || document.documentElement).appendChild(sheet);
          if (!v.hasAttribute(ATTR)) v.setAttribute(ATTR, '');
        };
        var kick = function () { requestAnimationFrame(apply); };
        document.addEventListener('loadedmetadata', kick, true);
        document.addEventListener('resize', kick, true);
        window.addEventListener('resize', kick);
        apply();
        window.__vidiaFill = { timer: setInterval(apply, 1000), kick: kick };
      })();
    """

    /** Window layout Flutter apps such as Plezy use: always drawn behind the car's status bar. */
    @Suppress("DEPRECATION")
    private const val BASE_UI_FLAGS =
      View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN

    /** Flutter's "immersive sticky": bars hidden, swipe from the edge to peek at them. */
    @Suppress("DEPRECATION")
    private const val IMMERSIVE_UI_FLAGS =
      View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN
  }

  private lateinit var root: FrameLayout
  private lateinit var safeArea: FrameLayout
  private var lastInsets: WindowInsets? = null
  private lateinit var webView: WebView
  private lateinit var urlBar: EditText
  private lateinit var progress: ProgressBar
  private lateinit var desktopButton: TextView
  private lateinit var fillToolbarButton: TextView
  private lateinit var mobileUserAgent: String
  private lateinit var desktopUserAgent: String

  private var fullscreenView: View? = null
  private var fullscreenFrame: FrameLayout? = null
  private var fillButton: TextView? = null
  private var fillMode = false
  private val handler = Handler(Looper.getMainLooper())
  private val hideFillButton = Runnable { fillButton?.animate()?.alpha(0f)?.setDuration(250)?.start() }
  private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
  private var desktopMode = false

  private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    desktopMode = prefs.getBoolean(KEY_DESKTOP, false)
    fillMode = prefs.getBoolean(KEY_FILL, false)
    buildUi()
    applyBaseWindowLayout()
    configureWebView()

    if (savedInstanceState != null) {
      webView.restoreState(savedInstanceState)
    } else {
      // Always open on the start page unless another app handed us a link.
      val start = intent?.data?.toString() ?: HOME
      load(start)
    }
  }

  // ---------- UI ----------

  private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

  private fun toolbarButton(label: String, onClick: () -> Unit) = TextView(this).apply {
    text = label
    textSize = 26f
    setTextColor(Color.WHITE)
    gravity = Gravity.CENTER
    minWidth = dp(64)
    setPadding(dp(12), 0, dp(12), 0)
    isClickable = true
    isFocusable = true
    setBackgroundResource(android.R.drawable.list_selector_background)
    setOnClickListener { onClick() }
  }

  private fun buildUi() {
    val toolbar = LinearLayout(this).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
      setBackgroundColor(Color.rgb(32, 33, 36))
      setPadding(dp(8), dp(6), dp(8), dp(6))
    }

    urlBar = EditText(this).apply {
      setSingleLine()
      textSize = 20f
      setTextColor(Color.WHITE)
      setHintTextColor(Color.GRAY)
      hint = "Search or type a web address"
      imeOptions = EditorInfo.IME_ACTION_GO
      inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
      setSelectAllOnFocus(true)
      setPadding(dp(16), dp(10), dp(16), dp(10))
      background = android.graphics.drawable.GradientDrawable().apply {
        cornerRadius = dp(24).toFloat()
        setColor(Color.rgb(60, 64, 67))
      }
      setOnEditorActionListener { _, actionId, event ->
        val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
        if (actionId == EditorInfo.IME_ACTION_GO || enter) {
          navigate(text.toString()); true
        } else false
      }
    }

    desktopButton = toolbarButton("") { toggleDesktop() }.apply { textSize = 16f }
    fillToolbarButton = toolbarButton("") { setFill(!fillMode) }.apply {
      textSize = 16f
      typeface = Typeface.DEFAULT_BOLD
    }
    updateDesktopLabel()

    toolbar.addView(toolbarButton("←") { if (webView.canGoBack()) webView.goBack() })
    toolbar.addView(toolbarButton("→") { if (webView.canGoForward()) webView.goForward() })
    toolbar.addView(toolbarButton("⟳") { webView.reload() })
    toolbar.addView(toolbarButton("⌂") { load(HOME) }.apply {
      // Long-press: how far the browser stays from the screen's right edge (curved car screens).
      setOnLongClickListener { showEdgeDialog(); true }
    })
    toolbar.addView(urlBar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
      marginStart = dp(8); marginEnd = dp(8)
    })
    toolbar.addView(fillToolbarButton)
    toolbar.addView(desktopButton)

    progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
      max = 100
      visibility = View.GONE
    }

    webView = WebView(this)

    val column = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setBackgroundColor(Color.BLACK)
      addView(toolbar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(72)))
      addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4)))
      addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    // Keeps the browser inside the part of the screen you can see (see applySafeArea). Any margin
    // it leaves is black, so it blends into the screen's edge.
    safeArea = FrameLayout(this).apply {
      setBackgroundColor(Color.BLACK)
      addView(column)
    }

    // The area behind the car's status bar shows the toolbar colour.
    root = FrameLayout(this).apply {
      setBackgroundColor(Color.rgb(32, 33, 36))
      addView(safeArea)
    }
    setContentView(root)
  }

  private fun updateDesktopLabel() {
    desktopButton.text = if (desktopMode) "Desktop" else "Mobile"
    desktopButton.typeface = Typeface.DEFAULT_BOLD
    updateFillButton()
  }

  // ---------- WebView ----------

  @SuppressLint("SetJavaScriptEnabled")
  private fun configureWebView() {
    with(webView.settings) {
      javaScriptEnabled = true
      domStorageEnabled = true
      databaseEnabled = true
      mediaPlaybackRequiresUserGesture = false
      loadWithOverviewMode = true
      useWideViewPort = true
      builtInZoomControls = true
      displayZoomControls = false
      setSupportMultipleWindows(false)
      javaScriptCanOpenWindowsAutomatically = false
      mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
      // Drop the "wv" WebView marker so sites serve their normal Chrome pages.
      userAgentString = userAgentString.replace("; wv", "").replace(Regex(" Version/\\d+\\.\\d+"), "")
      mobileUserAgent = userAgentString
      desktopUserAgent = mobileUserAgent
        .replace(Regex("\\(Linux; Android [^)]*\\)"), "(X11; Linux x86_64)")
        .replace(" Mobile", "")
    }
    webView.settings.userAgentString = if (desktopMode) desktopUserAgent else mobileUserAgent

    CookieManager.getInstance().apply {
      setAcceptCookie(true)
      setAcceptThirdPartyCookies(webView, true)
    }

    webView.webViewClient = object : WebViewClient() {
      override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val scheme = request.url.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return true // ignore intent://, market://, etc.
        if (!request.isForMainFrame) return false
        // Switch user agent (and YouTube host) when crossing into a site that needs it.
        val url = request.url.toString()
        val uaBefore = view.settings.userAgentString
        val target = prepareFor(url)
        return if (target != url || view.settings.userAgentString != uaBefore) {
          view.loadUrl(target); true
        } else false
      }

      override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        if (request.url.host != HOME_HOST) return null
        val path = request.url.path ?: "/"
        return if (path == "/" || path == "/index.html") {
          WebResourceResponse("text/html", "utf-8", assets.open("home.html"))
        } else {
          WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(),
            ByteArrayInputStream(ByteArray(0)))
        }
      }

      override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        if (!urlBar.hasFocus()) urlBar.setText(displayUrl(url))
      }

      override fun onPageFinished(view: WebView, url: String) {
        if (!urlBar.hasFocus()) urlBar.setText(displayUrl(url))
        CookieManager.getInstance().flush()
      }

      // Sites like YouTube change pages without a full load, so keep the address bar in step.
      override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
        if (!urlBar.hasFocus()) urlBar.setText(displayUrl(url))
      }
    }

    webView.webChromeClient = object : WebChromeClient() {
      override fun onProgressChanged(view: WebView, newProgress: Int) {
        progress.progress = newProgress
        progress.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
      }

      override fun onShowCustomView(view: View, callback: CustomViewCallback) {
        if (fullscreenView != null) { callback.onCustomViewHidden(); return }
        fullscreenView = view
        fullscreenCallback = callback
        enterFullscreen(view)
      }

      override fun onHideCustomView() = exitFullscreen()

      // Lets Netflix, Hulu etc. use the car's built-in copy-protection (Widevine) for playback.
      // Nothing else (camera, microphone) is ever granted.
      override fun onPermissionRequest(request: PermissionRequest) {
        if (PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID in request.resources) {
          request.grant(arrayOf(PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID))
        } else {
          request.deny()
        }
      }
    }
  }

  private fun displayUrl(url: String) = if (Uri.parse(url).host == HOME_HOST) "" else url

  private fun isDesktopHost(host: String?) =
    host != null && DESKTOP_HOSTS.any { host == it || host.endsWith(".$it") }

  /** Picks the right user agent for [url] and returns the address to actually load. */
  private fun prepareFor(url: String): String {
    val target = if (desktopMode) url.replace("://m.youtube.com", "://www.youtube.com")
                 else url.replace("://www.youtube.com", "://m.youtube.com")
    val wantDesktop = desktopMode || isDesktopHost(Uri.parse(target).host)
    val ua = if (wantDesktop) desktopUserAgent else mobileUserAgent
    if (webView.settings.userAgentString != ua) webView.settings.userAgentString = ua
    return target
  }

  private fun load(url: String) = webView.loadUrl(prepareFor(url))

  private fun toggleDesktop() {
    desktopMode = !desktopMode
    prefs.edit().putBoolean(KEY_DESKTOP, desktopMode).apply()
    updateDesktopLabel()
    load(webView.url ?: HOME)
  }

  /**
   * Fullscreen video goes on the window's top-level view (not inside the app layout), drawn
   * edge to edge over the space normally kept for the system bars, with a Fill/Fit button.
   */
  private fun enterFullscreen(video: View) {
    video.setBackgroundColor(Color.BLACK)
    val frame = object : FrameLayout(this) {
      // Any touch brings the Fill button back for a few seconds; the touch still reaches the video.
      override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) showFillButton()
        return super.dispatchTouchEvent(ev)
      }
    }.apply {
      setBackgroundColor(Color.BLACK)
      fitsSystemWindows = false
    }
    video.fitsSystemWindows = false
    video.setPadding(0, 0, 0, 0)
    frame.addView(video, FrameLayout.LayoutParams(
      ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

    val button = TextView(this).apply {
      textSize = 20f
      typeface = Typeface.DEFAULT_BOLD
      setTextColor(Color.WHITE)
      gravity = Gravity.CENTER
      setPadding(dp(28), dp(14), dp(28), dp(14))
      background = android.graphics.drawable.GradientDrawable().apply {
        cornerRadius = dp(28).toFloat()
        setColor(Color.argb(170, 32, 33, 36))
      }
      setOnClickListener { setFill(!fillMode) }
      // Long-press shows the fullscreen size and reserved edges (handy when tuning a new car).
      setOnLongClickListener { showFullscreenInfo(); true }
    }
    frame.addView(button, FrameLayout.LayoutParams(
      ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
      Gravity.TOP or Gravity.START).apply { setMargins(dp(24), dp(24), 0, 0) })
    // Keep the button clear of the car's status/system areas, even while they're hidden.
    frame.setOnApplyWindowInsetsListener { _, insets ->
      val (left, top) = reservedTopLeft(insets)
      (button.layoutParams as FrameLayout.LayoutParams).setMargins(left + dp(24), top + dp(24), 0, 0)
      button.requestLayout()
      consumeAll(insets)
    }
    fillButton = button
    updateFillButton()

    (window.decorView as ViewGroup).addView(frame, FrameLayout.LayoutParams(
      ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    fullscreenFrame = frame
    frame.requestApplyInsets()
    setFullscreenUi(true)
    applyFill()
    showFillButton()
  }

  private fun exitFullscreen() {
    fullscreenView ?: return
    handler.removeCallbacks(hideFillButton)
    fullscreenFrame?.let { (window.decorView as ViewGroup).removeView(it) }
    fullscreenFrame = null
    fillButton = null
    fullscreenView = null
    fullscreenCallback?.onCustomViewHidden()
    fullscreenCallback = null
    setFullscreenUi(false)
    webView.evaluateJavascript(FILL_JS.format("false"), null)
  }

  private fun setFill(on: Boolean) {
    fillMode = on
    prefs.edit().putBoolean(KEY_FILL, on).apply()
    updateFillButton()
    if (fullscreenView != null) applyFill()
    showFillButton()
  }

  private fun applyFill() = webView.evaluateJavascript(FILL_JS.format(fillMode.toString()), null)

  private fun updateFillButton() {
    // In fullscreen the label says what tapping will do; the toolbar shows the current setting.
    fillButton?.text = if (fillMode) "Fit video" else "Fill screen"
    if (::fillToolbarButton.isInitialized) fillToolbarButton.text = if (fillMode) "Fill" else "Fit"
  }

  /** Space the car keeps for its bars and any curved or cut screen edge, shown or hidden. */
  @Suppress("DEPRECATION")
  private fun reservedEdges(insets: WindowInsets): Insets =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      insets.getInsetsIgnoringVisibility(
        WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
    } else {
      val cut = insets.displayCutout
      Insets.of(
        maxOf(insets.stableInsetLeft, cut?.safeInsetLeft ?: 0),
        maxOf(insets.stableInsetTop, cut?.safeInsetTop ?: 0),
        maxOf(insets.stableInsetRight, cut?.safeInsetRight ?: 0),
        maxOf(insets.stableInsetBottom, cut?.safeInsetBottom ?: 0))
    }

  private fun reservedTopLeft(insets: WindowInsets): Pair<Int, Int> =
    reservedEdges(insets).let { it.left to it.top }

  private fun showFullscreenInfo() {
    val frame = fullscreenFrame ?: return
    val r = frame.rootWindowInsets?.let { reservedEdges(it) } ?: Insets.NONE
    val loc = IntArray(2).also { frame.getLocationOnScreen(it) }
    val screen = resources.displayMetrics
    Toast.makeText(this,
      "Fullscreen ${frame.width}×${frame.height} at ${loc[0]},${loc[1]} · " +
        "screen ${screen.widthPixels}×${screen.heightPixels} · " +
        "reserved left ${r.left}, top ${r.top}, right ${r.right}, bottom ${r.bottom}",
      Toast.LENGTH_LONG).show()
  }

  private fun showFillButton() {
    val b = fillButton ?: return
    b.animate().cancel()
    b.alpha = 1f
    handler.removeCallbacks(hideFillButton)
    handler.postDelayed(hideFillButton, 3000)
  }

  @Suppress("DEPRECATION")
  private fun applyBaseWindowLayout() {
    // Same setup FlutterActivity (and so Plezy) uses: the window always extends behind the car's
    // status bar, which floats over it. That lets fullscreen video use the bar's space once the
    // bar hides. Our own UI is kept inside the visible area by applySafeArea.
    window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
    window.statusBarColor = Color.TRANSPARENT
    window.decorView.systemUiVisibility = BASE_UI_FLAGS
    root.setOnApplyWindowInsetsListener { _, insets ->
      lastInsets = insets
      applySafeArea()
      insets
    }
    root.requestApplyInsets()
  }

  /**
   * Keeps the toolbar and page inside the part of the screen you can actually see. The window
   * draws behind the car's bars (see applyBaseWindowLayout), so every side is padded, not just
   * the top: below the status bar, clear of any bar, curved edge or cutout the car reports on the
   * other sides, and above the on-screen keyboard. A right-edge margin set with long-press ⌂
   * replaces the reported one, for screens like the Blazer EV's slanted right edge when the car
   * doesn't report it. Fullscreen video is separate and still uses the whole panel.
   */
  private fun applySafeArea() {
    val insets = lastInsets ?: return
    val edges = reservedEdges(insets)
    val manualRight = prefs.getInt(KEY_EDGE, -1)
    root.setPadding(0, statusBarTop(insets), 0, 0)
    safeArea.setPadding(
      edges.left,
      0,
      if (manualRight >= 0) dp(manualRight) else edges.right,
      maxOf(edges.bottom, imeBottom(insets)))
  }

  @Suppress("DEPRECATION")
  private fun imeBottom(insets: WindowInsets): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) insets.getInsets(WindowInsets.Type.ime()).bottom
    else insets.systemWindowInsetBottom

  private fun pxToDp(px: Int) = (px / resources.displayMetrics.density).roundToInt()

  /**
   * Long-press ⌂: a slider for how far the browser stays from the screen's right edge, applied
   * live while dragging and remembered. Also shows what the car itself reports for that edge.
   */
  private fun showEdgeDialog() {
    val insets = lastInsets
    val reported = insets?.let { pxToDp(reservedEdges(it).right) } ?: 0
    val corner = if (insets != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
      insets.getRoundedCorner(RoundedCorner.POSITION_TOP_RIGHT)?.let { pxToDp(it.radius) } else null
    val start = prefs.getInt(KEY_EDGE, -1).takeIf { it >= 0 } ?: reported

    val builder = AlertDialog.Builder(this)
    val ctx = builder.context
    val label = TextView(ctx).apply {
      textSize = 18f
      setPadding(dp(24), dp(12), dp(24), 0)
    }
    fun describe(value: Int) {
      label.text = "Right edge: $value dp\n" +
        "Drag until the toolbar's right-hand buttons are fully visible.\n" +
        "The car reports $reported dp" + (corner?.let { ", top-right corner radius $it dp" } ?: "") + "."
    }
    describe(start)
    val slider = SeekBar(ctx).apply {
      max = 300
      progress = start
      setPadding(dp(32), dp(24), dp(32), dp(24))
      setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
          if (!fromUser) return
          prefs.edit().putInt(KEY_EDGE, value).apply()
          applySafeArea()
          describe(value)
        }
        override fun onStartTrackingTouch(bar: SeekBar) {}
        override fun onStopTrackingTouch(bar: SeekBar) {}
      })
    }
    val content = LinearLayout(ctx).apply {
      orientation = LinearLayout.VERTICAL
      addView(label)
      addView(slider)
    }
    builder
      .setTitle("Screen edge")
      .setView(content)
      .setPositiveButton("Done", null)
      .setNeutralButton("Use the car's edge") { _, _ ->
        prefs.edit().remove(KEY_EDGE).apply()
        applySafeArea()
      }
      .show()
      .window?.setDimAmount(0.1f) // keep the toolbar visible behind it as a live preview
  }

  @Suppress("DEPRECATION")
  private fun statusBarTop(insets: WindowInsets): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
      insets.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars()).top
    else insets.stableInsetTop

  @Suppress("DEPRECATION")
  private fun consumeAll(insets: WindowInsets): WindowInsets =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) WindowInsets.CONSUMED
    else insets.consumeSystemWindowInsets().consumeStableInsets().consumeDisplayCutout()

  @Suppress("DEPRECATION")
  private fun setFullscreenUi(on: Boolean) {
    // Flutter's immersiveSticky / edge-to-edge flags, which GM's system honours for Plezy.
    window.decorView.systemUiVisibility = if (on) IMMERSIVE_UI_FLAGS else BASE_UI_FLAGS
  }

  private fun navigate(input: String) {
    val text = input.trim()
    if (text.isEmpty()) return
    val url = when {
      text.startsWith("http://") || text.startsWith("https://") -> text
      !text.contains(' ') && text.contains('.') -> "https://$text"
      else -> "https://www.google.com/search?q=" + Uri.encode(text)
    }
    load(url)
    urlBar.clearFocus()
    (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
      .hideSoftInputFromWindow(urlBar.windowToken, 0)
    webView.requestFocus()
  }

  // ---------- Lifecycle ----------

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    intent.data?.let { load(it.toString()) }
  }

  @Deprecated("Deprecated in Java")
  override fun onBackPressed() {
    when {
      fullscreenView != null -> exitFullscreen()
      webView.canGoBack() -> webView.goBack()
      else -> @Suppress("DEPRECATION") super.onBackPressed()
    }
  }

  override fun onSaveInstanceState(outState: Bundle) {
    super.onSaveInstanceState(outState)
    webView.saveState(outState)
  }

  override fun onPause() {
    super.onPause()
    webView.onPause()
    CookieManager.getInstance().flush()
  }

  override fun onResume() {
    super.onResume()
    webView.onResume()
  }

  override fun onDestroy() {
    webView.destroy()
    super.onDestroy()
  }
}
