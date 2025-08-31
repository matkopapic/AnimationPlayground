package com.matkopapic.animationplayground.com.matkopapic.animationplayground

import android.view.MotionEvent
import android.view.View
import com.google.android.filament.utils.Float2
import com.google.android.filament.utils.Manipulator
import com.google.android.filament.utils.mix
import java.util.ArrayList

class OrbitGestureDetector(private val view: View, private val manipulator: Manipulator) {
    private enum class Gesture { NONE, ORBIT }

    // Simplified memento of MotionEvent, minimal but sufficient for our purposes.
    private data class TouchPair(var pt0: Float2, var pt1: Float2, var count: Int) {
        constructor() : this(Float2(0f), Float2(0f), 0)
        constructor(me: MotionEvent, height: Int) : this() {
            if (me.pointerCount >= 1) {
                this.pt0 = Float2(me.getX(0), height - me.getY(0))
                this.pt1 = this.pt0
                this.count++
            }
            if (me.pointerCount >= 2) {
                this.pt1 = Float2(me.getX(1), height - me.getY(1))
                this.count++
            }
        }
        val midpoint get() = mix(pt0, pt1, 0.5f)
        val x: Int get() = midpoint.x.toInt() / 8
        val y: Int get() = midpoint.y.toInt()
    }

    private var currentGesture = Gesture.NONE
    private val tentativeOrbitEvents = ArrayList<TouchPair>()

    private val kGestureConfidenceCount = 2

    fun onTouchEvent(event: MotionEvent) {
        val touch = TouchPair(event, view.height)
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {

                // CANCEL GESTURE DUE TO UNEXPECTED POINTER COUNT

                if ((event.pointerCount != 1 && currentGesture == Gesture.ORBIT)) {
                    endGesture()
                    return
                }

                // UPDATE EXISTING GESTURE

                if (currentGesture != Gesture.NONE) {
                    manipulator.grabUpdate(touch.x, 0)
                    return
                }

                // DETECT NEW GESTURE

                if (event.pointerCount == 1) {
                    tentativeOrbitEvents.add(touch)
                }

                if (isOrbitGesture()) {
                    manipulator.grabBegin(touch.x, 0, false)
                    currentGesture = Gesture.ORBIT
                    return
                }
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_UP -> {
                endGesture()
            }
        }
    }

    private fun endGesture() {
        tentativeOrbitEvents.clear()
        currentGesture = Gesture.NONE
        manipulator.grabEnd()
    }

    private fun isOrbitGesture(): Boolean {
        return tentativeOrbitEvents.size > kGestureConfidenceCount
    }
}