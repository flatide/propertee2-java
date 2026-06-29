package com.flatide.propertee2.interp;

import com.flatide.parser.ProperTeeParser.BlockContext;

import java.util.List;

/** A user-defined function: name, parameter names, and the (unexecuted) body parse tree. */
record UserFunction(String name, List<String> params, BlockContext body) {}
