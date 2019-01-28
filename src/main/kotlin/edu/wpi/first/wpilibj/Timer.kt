package edu.wpi.first.wpilibj

object Timer {
    fun getFPGATimestamp() = System.nanoTime() / 1_000_000_000.0
}