class X {
  int switchTest(Object obj) {
    return switch (obj) {
      case (String <error descr="Patterns in switch are not supported at language level '16'">s</error>) -> 1;
      case Integer <error descr="Patterns in switch are not supported at language level '16'">i</error> && predicate() -> 2;
      case Integer <error descr="Patterns in switch are not supported at language level '16'">i</error> -> 3;
      case <error descr="Patterns in switch are not supported at language level '16'">default</error> -> 4;
      case <error descr="Patterns in switch are not supported at language level '16'">null</error> -> 10;
    };
  }

  int instanceofTest(Object obj) {
    if (obj instanceof (<error descr="Guarded and parenthesized patterns are not supported at language level '16'">Integer i && predicate()</error>)) {
      return 1;
    }
    if (obj instanceof <error descr="Guarded and parenthesized patterns are not supported at language level '16'">(String s)</error>) {
      return 3;
    }
    return 2;
  }

  native static boolean predicate();
}