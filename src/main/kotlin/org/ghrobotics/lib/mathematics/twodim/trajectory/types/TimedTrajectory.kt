package org.ghrobotics.lib.mathematics.twodim.trajectory.types

import org.ghrobotics.lib.mathematics.epsilonEquals
import org.ghrobotics.lib.mathematics.lerp
import org.ghrobotics.lib.mathematics.twodim.geometry.Pose2d
import org.ghrobotics.lib.mathematics.twodim.geometry.Pose2dWithCurvature
import org.ghrobotics.lib.mathematics.twodim.trajectory.TrajectoryIterator
import org.ghrobotics.lib.mathematics.units.Time
import org.ghrobotics.lib.mathematics.units.derivedunits.LinearAcceleration
import org.ghrobotics.lib.mathematics.units.derivedunits.LinearVelocity
import org.ghrobotics.lib.mathematics.units.derivedunits.acceleration
import org.ghrobotics.lib.mathematics.units.derivedunits.velocity
import org.ghrobotics.lib.mathematics.units.meter
import org.ghrobotics.lib.mathematics.units.second
import org.ghrobotics.lib.types.VaryInterpolatable

class TimedTrajectory<S : VaryInterpolatable<S>>(
    points: List<TimedEntry<S>>
) : Trajectory<Time, TimedEntry<S>>(points) {

    override fun sample(interpolant: Time) = sample(interpolant.value)

    fun sample(interpolant: Double) = when {
        interpolant >= lastInterpolant.value -> TrajectorySamplePoint(getPoint(points.size - 1))
        interpolant <= firstInterpolant.value -> TrajectorySamplePoint(getPoint(0))
        else -> {
            val (index, entry) = points.asSequence()
                .withIndex()
                .first { (index, entry) -> index != 0 && entry.t.value >= interpolant }

            val prevEntry = points[index - 1]
            if (entry.t epsilonEquals prevEntry.t) TrajectorySamplePoint(entry, index, index)
            else TrajectorySamplePoint(
                prevEntry.interpolate(
                    entry,
                    (interpolant - prevEntry.t.value) / (entry.t.value - prevEntry.t.value)
                ),
                index - 1,
                index
            )
        }
    }

    override val firstState = points.first()
    override val lastState = points.last()

    override val firstInterpolant = firstState.t
    override val lastInterpolant = lastState.t

    override fun iterator() = TimedIterator(this)
}

data class TimedEntry<S : VaryInterpolatable<S>>(
    val state: S,
    val t: Time = 0.second,
    val velocity: LinearVelocity = 0.meter.velocity,
    val acceleration: LinearAcceleration = 0.meter.acceleration
) : VaryInterpolatable<TimedEntry<S>> {

    override fun interpolate(endValue: TimedEntry<S>, interpolant: Double): TimedEntry<S> {
        val newT = t.value.lerp(endValue.t.value, interpolant)
        val deltaT = newT - t.value
        if (deltaT < 0.0) return endValue.interpolate(this, 1.0 - interpolant)

        val velocity = this.velocity.value
        val acceleration = this.acceleration.value

        val reversing = velocity < 0.0 || velocity epsilonEquals 0.0 && acceleration < 0.0

        val newV = velocity + acceleration * deltaT
        val newS = (if (reversing) -1.0 else 1.0) * (velocity * deltaT + 0.5 * acceleration * deltaT * deltaT)

        return TimedEntry(
            state.interpolate(endValue.state, newS / state.distance(endValue.state)),
            newT.second,
            newV.meter.velocity,
            acceleration.meter.acceleration
        )
    }

    override fun distance(other: TimedEntry<S>) = state.distance(other.state)
}

class TimedIterator<S : VaryInterpolatable<S>>(
    trajectory: TimedTrajectory<S>
) : TrajectoryIterator<Time, TimedEntry<S>>(trajectory) {
    override fun addition(a: Time, b: Time) = a + b
}

fun TimedTrajectory<Pose2dWithCurvature>.mirror() = TimedTrajectory(
    points.map {
        TimedEntry(
            it.state.mirror,
            it.t,
            it.velocity,
            it.acceleration
        )
    }
)

fun TimedTrajectory<Pose2dWithCurvature>.transform(transform: Pose2d) = TimedTrajectory(
    points.map {
        TimedEntry(
            it.state + transform,
            it.t,
            it.velocity,
            it.acceleration
        )
    }
)