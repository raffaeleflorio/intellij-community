// "Opt in for 'AliasMarker' on 'AliasMarkerUsage'" "true"
// COMPILER_ARGUMENTS: -Xopt-in=kotlin.RequiresOptIn
// WITH_STDLIB
// ACTION: Add '-opt-in=AliasMarker' to module light_idea_test_case compiler arguments
// ACTION: Introduce import alias
// ACTION: Opt in for 'AliasMarker' in containing file 'typeAlias.kt'
// ACTION: Opt in for 'AliasMarker' on 'AliasMarkerUsage'

@RequiresOptIn
annotation class AliasMarker

@AliasMarker
class AliasTarget

typealias AliasMarkerUsage = <caret>AliasTarget
