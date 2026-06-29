package com.flatide.propertee2.interp;

/**
 * Non-local control flow for the tree-walk (break / continue / return), as lightweight
 * exceptions (no stack trace, no message) so they are cheap to throw across recursion.
 */
final class Signals {
    private Signals() {}

    static final class Break extends RuntimeException {
        static final Break INSTANCE = new Break();
        private Break() { super(null, null, false, false); }
    }

    static final class Continue extends RuntimeException {
        static final Continue INSTANCE = new Continue();
        private Continue() { super(null, null, false, false); }
    }

    static final class Return extends RuntimeException {
        final Object value;
        Return(Object value) { super(null, null, false, false); this.value = value; }
    }
}
