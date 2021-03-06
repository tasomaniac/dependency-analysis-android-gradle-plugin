package com.autonomousapps.internal.advice

import com.autonomousapps.internal.Dependency

/**
 * Don't suggest anything to do with the following databinding dependencies:
 * * androidx.databinding:databinding-adapters
 * * androidx.databinding:databinding-runtime
 * * androidx.databinding:databinding-common
 */
internal class DataBindingFilter : DependencyFilter {

  companion object {
    private val databindingDependencies = listOf(
      "androidx.databinding:databinding-adapters",
      "androidx.databinding:databinding-runtime",
      "androidx.databinding:databinding-common"
    )
  }

  override val predicate: (Dependency) -> Boolean = { dependency ->
    !databindingDependencies.contains(dependency.identifier)
  }
}
