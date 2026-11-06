package com.example.casdonlink.packages

data class RepairPackage(
    val formatVersion: Int,
    val packageId: String,
    val name: String,
    val version: String,
    val description: String,
    val steps: List<RepairStep>
)

sealed interface RepairStep {
    val id: String
    val name: String
    val continueOnError: Boolean

    data class Shell(
        override val id: String,
        override val name: String,
        val command: String,
        override val continueOnError: Boolean = false,
        val timeoutSeconds: Long = 30
    ) : RepairStep

    data class File(
        override val id: String,
        override val name: String,
        val source: String,
        val target: String,
        val sha256: String?,
        override val continueOnError: Boolean = false
    ) : RepairStep
}
