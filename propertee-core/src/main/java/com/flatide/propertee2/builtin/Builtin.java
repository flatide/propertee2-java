package com.flatide.propertee2.builtin;

import java.util.List;

/** A built-in function: takes already-evaluated argument values, returns a value. */
@FunctionalInterface
public interface Builtin {
    Object invoke(List<Object> args);
}
