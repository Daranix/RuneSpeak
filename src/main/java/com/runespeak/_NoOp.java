package com.runespeak;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;

public final class _NoOp {
    public static final PrintStream out = new PrintStream(new OutputStream() {
        @Override
        public void write(int b) {
        }
    });

    public static final PrintStream err = new PrintStream(new OutputStream() {
        @Override
        public void write(int b) {
        }
    });

    public static final InputStream in = new InputStream() {
        @Override
        public int read() {
            return -1;
        }
    };

    private _NoOp() {
    }
}
