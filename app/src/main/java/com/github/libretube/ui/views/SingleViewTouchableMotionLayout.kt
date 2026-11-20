package com.github.libretube.ui.views

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.constraintlayout.motion.widget.MotionLayout
import androidx.constraintlayout.motion.widget.TransitionAdapter
import com.github.libretube.R

class SingleViewTouchableMotionLayout(context: Context, attributeSet: AttributeSet? = null) :
    MotionLayout(context, attributeSet) {

    private val viewToDetectTouch by lazy {
        findViewById<View>(R.id.main_container) ?: findViewById(R.id.audio_player_container)
    }
    private val isAudioPlayer by lazy {
        viewToDetectTouch.id == R.id.audio_player_container
    }
    private val audioSwipeArea: View? by lazy { findViewById(R.id.artwork_frame) }
    private val scaledTouchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val viewRect = Rect()
    private val transitionListenerList = mutableListOf<TransitionListener?>()
    private val swipeDownListener = mutableListOf<() -> Unit>()
    private val gestureDetector = GestureDetector(context, Listener())

    private var startedMinimized = false
    private var isStrictlyDownSwipe = false
    private var touchInitialY = 0f
    private var isTouchDownInsideHitArea = false
    private var shouldInterceptTouchEvent = false

    init {
        super.setTransitionListener(object : TransitionAdapter() {
            override fun onTransitionChange(p0: MotionLayout?, p1: Int, p2: Int, p3: Float) {
                transitionListenerList.filterNotNull()
                    .forEach { it.onTransitionChange(p0, p1, p2, p3) }
            }

            override fun onTransitionCompleted(p0: MotionLayout?, p1: Int) {
                transitionListenerList.filterNotNull()
                    .forEach { it.onTransitionCompleted(p0, p1) }
            }
        })
    }

    override fun setTransitionListener(listener: TransitionListener?) {
        addTransitionListener(listener)
    }

    override fun addTransitionListener(listener: TransitionListener?) {
        transitionListenerList += listener
    }

    private inner class Listener : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            setTransitionDuration(200)
            transitionToStart()
            return true
        }

        override fun onScroll(
            e1: MotionEvent?,
            e2: MotionEvent,
            distanceX: Float,
            distanceY: Float
        ): Boolean {
            if (isStrictlyDownSwipe && distanceY > 0) {
                isStrictlyDownSwipe = false
            }

            if (isStrictlyDownSwipe && distanceY < -15F) {
                swipeDownListener.forEach { it.invoke() }
                return true
            }

            return false
        }
    }

    /**
     * Add a listener when the view is swiped down while the current transition's state is in
     * end state (minimized state)
     */
    fun addSwipeDownListener(listener: () -> Unit) = apply {
        swipeDownListener.add(listener)
    }

    /**
     * Whether a touch at screen position ([rawX], [rawY]) lands on the player: anywhere while it's
     * expanded, only on the card while it's minimised.
     */
    fun isTouchOnPlayer(rawX: Float, rawY: Float): Boolean {
        if (!isShown) return false
        if (progress == 0f) return true
        val rect = Rect()
        if (!viewToDetectTouch.getGlobalVisibleRect(rect)) return false
        return rect.contains(rawX.toInt(), rawY.toInt())
    }

    /** Whether the current gesture started outside the mini player while it was (partly) minimised. */
    private var passThroughGesture = false

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            viewToDetectTouch.getHitRect(viewRect)
            // While the player is minimised or moving, only the mini player itself may react.
            // Otherwise a strip of the hidden player page (e.g. its related videos) can stay
            // touchable next to the mini player and taps there would switch to another video.
            passThroughGesture =
                progress > 0f && !viewRect.contains(event.x.toInt(), event.y.toInt())
        }
        // returning false lets the touch reach the screen behind the player
        if (passThroughGesture) return false
        super.dispatchTouchEvent(event)
        // Touches on the player itself never fall through to the screen behind it, even where
        // nothing in the player reacts (e.g. on the data label): otherwise a video card behind
        // the open audio player could be tapped and start playing in the video player.
        return true
    }

    override fun onInterceptTouchEvent(event: MotionEvent?): Boolean {
        when (event?.action) {
            MotionEvent.ACTION_DOWN -> {
                shouldInterceptTouchEvent = false
                isTouchDownInsideHitArea = false

                if (isAudioPlayer) {
                    // audio player: like the video, a downward swipe on the artwork minimises it
                    // (only while expanded; elsewhere, e.g. in the description, touches stay
                    // with the content)
                    val artwork = audioSwipeArea ?: return false
                    if (progress != 0f) return false
                    artwork.getGlobalVisibleRect(viewRect)
                    val origin = IntArray(2).also { getLocationOnScreen(it) }
                    viewRect.offset(-origin[0], -origin[1])
                } else {
                    viewToDetectTouch.getHitRect(viewRect)
                }
                isTouchDownInsideHitArea = viewRect.contains(event.x.toInt(), event.y.toInt())
                touchInitialY = event.y
                startedMinimized = progress == 1F
                isStrictlyDownSwipe = true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!startedMinimized && !shouldInterceptTouchEvent && isTouchDownInsideHitArea) {
                    val deltaY = event.y - touchInitialY

                    // swipe down detected
                    if (deltaY > scaledTouchSlop) {
                        // start intercepting and consume the event ourselves in onTouchEvent()
                        shouldInterceptTouchEvent = true

                        // inject down MotionEvent from current position to properly trigger
                        // motion scene's swipe action
                        MotionEvent.obtain(event).apply {
                            action = MotionEvent.ACTION_DOWN
                            setLocation(event.x, event.y)
                        }.also { downEvent ->
                            onTouchEvent(downEvent)
                            downEvent.recycle()
                        }
                    }
                }
            }
        }

        return shouldInterceptTouchEvent
    }
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (isTouchDownInsideHitArea && startedMinimized) {
            // detect gesture only when the player is in minimized state
            gestureDetector.onTouchEvent(event)
        }

        return isTouchDownInsideHitArea && super.onTouchEvent(event)
    }
}
