package com.autonomousapps.internal.utils

import java.util.*
import kotlin.collections.HashSet

internal inline fun <T> Iterable<T>.filterToSet(predicate: (T) -> Boolean): Set<T> {
  return filterTo(HashSet(), predicate)
}

internal inline fun <T> Iterable<T>.filterToOrderedSet(predicate: (T) -> Boolean): Set<T> {
  return filterTo(TreeSet(), predicate)
}

internal inline fun <T, R> Iterable<T>.mapToSet(transform: (T) -> R): HashSet<R> {
  return mapTo(HashSet(collectionSizeOrDefault(10)), transform)
}

internal inline fun <T, R> Iterable<T>.mapToOrderedSet(transform: (T) -> R): TreeSet<R> {
  return mapTo(TreeSet(), transform)
}

internal fun <T> Iterable<T>.collectionSizeOrDefault(default: Int): Int = if (this is Collection<*>) this.size else default
