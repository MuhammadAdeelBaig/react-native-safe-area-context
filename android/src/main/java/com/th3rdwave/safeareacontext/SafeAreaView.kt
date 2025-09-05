package com.th3rdwave.safeareacontext

import android.content.Context
import android.os.Build
import android.util.Log
import android.view.View
import android.view.ViewTreeObserver
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsCompat.Type
import com.facebook.react.bridge.Arguments
import com.facebook.react.uimanager.StateWrapper
import com.facebook.react.uimanager.UIManagerModule
import com.facebook.react.views.view.ReactViewGroup

private const val TAG = "SafeAreaView"
private const val MAX_WAIT_TIME_NANO = 500_000_000L // kept for fallback path

class SafeAreaView(context: Context?) :
  ReactViewGroup(context),
  ViewTreeObserver.OnPreDrawListener {

  private var mMode = SafeAreaViewMode.PADDING
  private var mInsets: EdgeInsets? = null
  private var mEdges: SafeAreaViewEdges? = null
  private var mProviderView: View? = null
  private var mStateWrapper: StateWrapper? = null

  // WindowInsets listener token so we can remove it cleanly
  private var appliedInsetsListener: ((View, WindowInsetsCompat) -> WindowInsetsCompat)? = null

  fun getStateWrapper(): StateWrapper? = mStateWrapper
  fun setStateWrapper(stateWrapper: StateWrapper?) { mStateWrapper = stateWrapper }

  /**
   * NEW: Compute EdgeInsets via WindowInsetsCompat.
   * - Includes status bar + nav bar + IME handling.
   * - Uses display cutout for top/bottom when present (notches / curved corners).
   * - Works reliably on Android 15 (API 35) and Samsung devices.
   */
  private fun computeEdgeInsets(view: View): EdgeInsets {
    val root = view.rootView ?: view
    val insets = ViewCompat.getRootWindowInsets(root) ?: WindowInsetsCompat.CONSUMED

    // System bars (status + nav)
    val sysBars = insets.getInsets(Type.systemBars())

    // IME (keyboard) can overlap bottom; use the larger of nav vs IME when keyboard visible.
    val imeInsets = insets.getInsets(Type.ime())
    val isImeVisible = insets.isVisible(Type.ime())
    val bottomInset = if (isImeVisible) maxOf(sysBars.bottom, imeInsets.bottom) else sysBars.bottom

    // Display cutout (notch / waterfall / rounded corners)
    val displayCutout = insets.displayCutout
    val topCutout = displayCutout?.safeInsetTop ?: 0
    val bottomCutout = displayCutout?.safeInsetBottom ?: 0
    val leftCutout = displayCutout?.safeInsetLeft ?: 0
    val rightCutout = displayCutout?.safeInsetRight ?: 0

    // Prefer the larger between cutout safe insets and system bar insets
    val top = maxOf(sysBars.top, topCutout)
    val left = maxOf(sysBars.left, leftCutout)
    val right = maxOf(sysBars.right, rightCutout)
    val bottom = maxOf(bottomInset, bottomCutout)

    return EdgeInsets(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
  }

  /**
   * Binds state/localData to RN. On modern RN (Fabric), StateWrapper exists.
   * On older RN (Paper), fall back to localData + dispatchViewUpdates.
   */
  private fun updateInsets() {
    val insets = mInsets ?: return
    val edges = mEdges ?: SafeAreaViewEdges(
      SafeAreaViewEdgeModes.ADDITIVE,
      SafeAreaViewEdgeModes.ADDITIVE,
      SafeAreaViewEdgeModes.ADDITIVE,
      SafeAreaViewEdgeModes.ADDITIVE
    )

    val stateWrapper = getStateWrapper()
    if (stateWrapper != null) {
      val map = Arguments.createMap().apply {
        putMap("insets", edgeInsetsToJsMap(insets))
      }
      stateWrapper.updateState(map)
      return
    }

    // Paper fallback
    val localData = SafeAreaViewLocalData(insets = insets, mode = mMode, edges = edges)
    val reactContext = getReactContext(this)
    val uiManager = reactContext.getNativeModule(UIManagerModule::class.java) ?: return
    uiManager.setViewLocalData(id, localData)

    // ❗️Old code blocked the UI thread waiting for native modules queue.
    // On API 35+ this can lead to jank/ANRs. Instead, request a relayout asynchronously.
    reactContext.runOnNativeModulesQueueThread {
      try {
        uiManager.uiImplementation.dispatchViewUpdates(-1)
      } catch (_: Throwable) {
        // defensively ignore if UIManager impl differs
      }
    }
    // Kick a layout pass on next frame; no blocking.
    requestLayout()
    invalidate()
  }

  private fun maybeUpdateInsets(): Boolean {
    val provider = mProviderView ?: return false
    val edgeInsets = computeEdgeInsets(provider)
    if (mInsets != edgeInsets) {
      mInsets = edgeInsets
      updateInsets()
      return true
    }
    return false
  }

  private fun findProvider(): View {
    var current = parent
    while (current != null) {
      if (current is SafeAreaProvider) return current
      current = current.parent
    }
    // Fallback to self if no provider in ancestry
    return this
  }

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    mProviderView = findProvider()

    // 1) WindowInsets listener (primary path for API 21+; best for API 35 and Samsung)
    appliedInsetsListener = { v, insets ->
      val did = maybeUpdateInsets()
      // Do not consume—let children also see insets; SafeArea just reads them
      if (did) requestLayout()
      insets
    }.also { listener ->
      ViewCompat.setOnApplyWindowInsetsListener(mProviderView!!, listener)
    }

    // 2) Pre-draw listener as a safety net for older or odd OEM cases
    mProviderView?.viewTreeObserver?.addOnPreDrawListener(this)

    // Initial computation
    post { maybeUpdateInsets() }
  }

  override fun onDetachedFromWindow() {
    super.onDetachedFromWindow()
    try {
      mProviderView?.viewTreeObserver?.removeOnPreDrawListener(this)
    } catch (_: Throwable) { /* no-op */ }

    // Remove insets listener to avoid leaks
    if (mProviderView != null && appliedInsetsListener != null) {
      ViewCompat.setOnApplyWindowInsetsListener(mProviderView!!, null)
    }
    appliedInsetsListener = null
    mProviderView = null
  }

  override fun onPreDraw(): Boolean {
    // Keep this cheap: recompute if needed; never block UI thread.
    val didUpdate = maybeUpdateInsets()
    if (didUpdate) {
      // Returning false skips this draw and schedules another. This prevents a one-frame layout glitch.
      return false
    }
    return true
  }

  fun setMode(mode: SafeAreaViewMode) {
    mMode = mode
    updateInsets()
  }

  fun setEdges(edges: SafeAreaViewEdges) {
    mEdges = edges
    updateInsets()
  }
}
