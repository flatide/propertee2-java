package com.flatide.propertee2.interp;

import com.flatide.propertee2.parser.ProperTeeParser.BlockContext;

import java.util.List;

/** A user-defined function and its owning imported module ({@code null} for entry functions). */
record UserFunction(String name, List<String> params, BlockContext body, ModuleInstance owner) {}
