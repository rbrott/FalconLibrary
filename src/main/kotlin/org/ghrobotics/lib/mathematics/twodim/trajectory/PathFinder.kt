package org.ghrobotics.lib.mathematics.twodim.trajectory

import org.apache.commons.math3.analysis.interpolation.SplineInterpolator
import org.apache.commons.math3.geometry.euclidean.twod.Line
import org.apache.commons.math3.geometry.euclidean.twod.Vector2D
import org.ghrobotics.lib.mathematics.kEpsilon
import org.ghrobotics.lib.mathematics.twodim.geometry.Pose2d
import org.ghrobotics.lib.mathematics.twodim.geometry.Rectangle2d
import org.ghrobotics.lib.mathematics.twodim.geometry.Translation2d
import org.ghrobotics.lib.mathematics.units.*
import org.ghrobotics.lib.utils.*
import java.lang.Math.sqrt
import kotlin.math.pow

class PathFinder(
    private val robotSize: Length,
    vararg restrictedAreas: Rectangle2d
) {

    private val robotSizeCorner = sqrt(robotSize.value.pow(2.0) / 2.0)
    private val robotCornerTopLeft = Vector2D(-robotSizeCorner, robotSizeCorner)
    private val robotCornerTopRight = Vector2D(robotSizeCorner, robotSizeCorner)
    private val robotCornerBottomLeft = Vector2D(-robotSizeCorner, -robotSizeCorner)
    private val robotCornerBottomRight = Vector2D(robotSizeCorner, -robotSizeCorner)
    private val robotTopLeftOffset = Translation2d(-robotSize / 2.0, robotSize / 2.0)
    private val robotBottomRightOffset = Translation2d(robotSize / 2.0, -robotSize / 2.0)
    private val fieldRectangleWithOffset = Rectangle2d(
        kFieldRectangle.topLeft - robotTopLeftOffset,
        kFieldRectangle.bottomRight - robotBottomRightOffset
    )
    private val restrictedAreas = restrictedAreas.toList()

    @Suppress("SpreadOperator")
    fun findPath(
        start: Pose2d,
        end: Pose2d,
        vararg restrictedAreas: Rectangle2d
    ): List<Pose2d>? {
        val pathNodes = findPath(
            start.translation.toVector2d(),
            end.translation.toVector2d(),
            *restrictedAreas
        ) ?: return null

        val interpolator = SplineInterpolator()

        val samples = 4

        val distances = mutableListOf<Double>()
        distances.add(0.0)

        pathNodes.asSequence().zipWithNext().forEach { (a, b) ->
            distances += distances.last() + a.distance(b)
        }

        val distanceDelta = distances.last() / samples

        val splineX = interpolator.interpolate(distances.toDoubleArray(), pathNodes.map { it.x }.toDoubleArray())
        val splineDx = splineX.derivative()
        val splineY = interpolator.interpolate(distances.toDoubleArray(), pathNodes.map { it.y }.toDoubleArray())
        val splineDy = splineY.derivative()

        val interpolatedNodes = (1 until (samples - 1)).map { index ->
            val distanceTraveled = distanceDelta * index

            Pose2d(
                Translation2d(
                    splineX.value(distanceTraveled),
                    splineY.value(distanceTraveled)
                ),
                Rotation2d(
                    splineDx.value(distanceTraveled),
                    splineDy.value(distanceTraveled),
                    true
                )
            )
        }.toTypedArray()

        return listOf(
            start,
            *interpolatedNodes,
            end
        )
    }

    fun findPath(
        start: Vector2D,
        end: Vector2D,
        vararg restrictedAreas: Rectangle2d
    ): List<Vector2D>? {
        val effectiveRestrictedAreas = this.restrictedAreas.plusToSet(restrictedAreas)
        val worldNodes = createNodes(effectiveRestrictedAreas) + setOf(start, end)
        return optimize(
            start,
            end,
            worldNodes,
            effectiveRestrictedAreas
        )
    }

    // https://en.wikipedia.org/wiki/Dijkstra%27s_algorithm
    @Suppress("NestedBlockDepth", "UnsafeCallOnNullableType")
    private fun optimize(
        source: Vector2D,
        target: Vector2D,
        points: Set<Vector2D>,
        effectiveRestrictedAreas: Set<Rectangle2d>
    ): List<Vector2D>? {
        val Q = points.map {
            Node(it, Double.POSITIVE_INFINITY, null)
        }.toMutableSet()

        Q.first { it.point == source }.dist = 0.0

        while (Q.isNotEmpty()) {
            val u = Q.minBy { it.dist }!!
            Q -= u
            if (u.point == target) {
                val S = mutableListOf<Vector2D>()
                var c: Node? = u
                while (c != null) {
                    S.add(0, c.point)
                    c = c.prev
                }
                return S
            }

            val robotRectangle = u.point.toRobotRectangle()
            for (v in Q) {
                val toTranslation = (v.point.subtract(u.point)).toTranslation2d()
                if (effectiveRestrictedAreas.none { it.doesCollide(robotRectangle, toTranslation) }) {
                    val alt = u.dist + u.point.distance(v.point)
                    if (alt < v.dist) {
                        v.dist = alt
                        v.prev = u
                    }
                }
            }
        }

        return null
    }

    @Suppress("UseDataClass")
    private class Node(
        val point: Vector2D,
        var dist: Double,
        var prev: Node?
    )

    private fun Vector2D.toRobotRectangle() = Rectangle2d(
        (x - robotSize.value / 3).meter, (y - robotSize.value / 3).meter,
        (robotSize.value / 3 * 2).meter, (robotSize.value / 3 * 2).meter
    )

    private fun createNodes(restrictedAreas: Set<Rectangle2d>): Set<Vector2D> {
        val effectiveRestrictedAreasWithOffsets = restrictedAreas.mapToSet {
            Rectangle2d(it.topLeft + robotTopLeftOffset, it.bottomRight + robotBottomRightOffset)
        }
        val result = createLines(effectiveRestrictedAreasWithOffsets)
            .combinationPairs()
            .mapNotNullToSet { it.first.intersection(it.second) }
        val restrictedCorners = restrictedAreas.flatMapToSet(4) { rect ->
            arrayOf(
                rect.topLeft.toVector2d().add(robotCornerTopLeft),
                rect.topRight.toVector2d().add(robotCornerTopRight),
                rect.bottomLeft.toVector2d().add(robotCornerBottomLeft),
                rect.bottomRight.toVector2d().add(robotCornerBottomRight)
            ).asList()
        }
        return result.plusToSet(restrictedCorners)
            .filterNotToSet { point ->
                val translation = point.toTranslation2d()
                !fieldRectangleWithOffset.contains(translation) ||
                    restrictedAreas.any { it.contains(translation) }
            }
    }

    private fun createLines(restrictedAreas: Set<Rectangle2d>): Set<Line> {
        val restrictedWallLines = (restrictedAreas + fieldRectangleWithOffset)
            .flatMapToSet(4) { rect ->
                val topLeft = rect.topLeft.toVector2d()
                val topRight = rect.topRight.toVector2d()
                val bottomLeft = rect.bottomLeft.toVector2d()
                val bottomRight = rect.bottomRight.toVector2d()
                arrayOf(
                    topLeft to topRight,
                    topLeft to bottomLeft,
                    bottomRight to bottomLeft,
                    bottomRight to topRight
                ).asList()
            }.mapToSet { pair ->
                Triple(pair.first, pair.second, Line(pair.first, pair.second, kEpsilon))
            }
        return restrictedWallLines
            .combinationPairs()
            .mapNotNullToSet { (line1, line2) ->
                if (!line1.third.isParallelTo(line2.third) ||
                    line1.third.getOffset(line2.third) < robotSize.value / 2.0
                ) return@mapNotNullToSet null
                Line(
                    line1.first.add(line2.first).scalarMultiply(0.5),
                    line1.second.add(line2.second).scalarMultiply(0.5),
                    kEpsilon
                )
            }
    }

    private fun Translation2d.toVector2d() = Vector2D(x, y)
    private fun Vector2D.toTranslation2d() = Translation2d(x.meter, y.meter)

    companion object {
        private val kFieldRectangle = Rectangle2d(
            Translation2d(),
            Translation2d((54 / 2).feet, 27.feet)
        )
        val k2018LeftSwitch = Rectangle2d(
            Translation2d(140.inch, 85.25.inch),
            Translation2d(196.inch, 238.75.inch)
        )
        val k2018Platform = Rectangle2d(
            Translation2d(261.47.inch, 95.25.inch),
            Translation2d(386.53.inch, 228.75.inch)
        )
        val k2018CubesSwitch = Rectangle2d(
            Translation2d(196.inch, 85.25.inch),
            Translation2d(211.inch, 238.75.inch)
        )
    }
}
