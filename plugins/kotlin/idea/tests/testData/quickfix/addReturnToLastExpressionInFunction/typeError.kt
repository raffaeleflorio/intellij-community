// "Add 'return' to last expression" "false"
// WITH_STDLIB
// ACTION: Introduce local variable
// ACTION: Remove explicitly specified return type of enclosing function 'some'
// ERROR: A 'return' expression required in a function with a block body ('{...}')
// ERROR: Unresolved reference: FunctionReference

fun some(): FunctionReference {
    Int::class
}<caret>
