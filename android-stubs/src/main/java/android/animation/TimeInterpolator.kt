package android.animation

interface TimeInterpolator {
    fun getInterpolation(input: Float): Float
}
