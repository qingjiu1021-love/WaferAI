package com.example.waferai.data.analysis

/** Matrices are immutable after import. Keys must not scan every die on the UI thread. */
internal class MatrixIdentity(private val value: Any?) {
    override fun equals(other: Any?) = other is MatrixIdentity && value === other.value
    override fun hashCode() = System.identityHashCode(value)
}
