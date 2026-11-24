package com.termux.terminal;

import android.annotation.SuppressLint;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;

import java.util.UUID;

/**
 * A terminal session whose other end is a remote byte stream (an SSH channel) instead of a local
 * subprocess. Based on the Termux class of the same name; the pty/JNI part is replaced by
 * {@link Transport}.
 *
 * Bytes from the remote side come in through {@link #feed(byte[], int, int)} (any thread) and are
 * handed to the {@link TerminalEmulator} on the main thread. Bytes typed by the user go out through
 * the transport.
 */
public final class TerminalSession extends TerminalOutput {

    /** Where typed bytes and size changes go. Calls arrive on the main thread and must not block. */
    public interface Transport {
        void write(byte[] data, int offset, int count);

        void resize(int columns, int rows, int cellWidthPixels, int cellHeightPixels);
    }

    private static final int MSG_NEW_INPUT = 1;

    public final String mHandle = UUID.randomUUID().toString();

    TerminalEmulator mEmulator;

    final ByteQueue mRemoteToTerminalQueue = new ByteQueue(256 * 1024);
    private final byte[] mUtf8InputBuffer = new byte[5];

    TerminalSessionClient mClient;
    private volatile Transport mTransport;
    private final Integer mTranscriptRows;

    public String mSessionName;

    final Handler mMainThreadHandler = new MainThreadHandler();

    public TerminalSession(Integer transcriptRows, TerminalSessionClient client) {
        this.mTranscriptRows = transcriptRows;
        this.mClient = client;
    }

    public void setTransport(Transport transport) {
        mTransport = transport;
    }

    public void updateTerminalSessionClient(TerminalSessionClient client) {
        mClient = client;
        if (mEmulator != null) mEmulator.updateTerminalSessionClient(client);
    }

    /** Inform the attached transport about the new size and reflow or initialize the emulator. */
    public void updateSize(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
        if (mEmulator == null) {
            mEmulator = new TerminalEmulator(this, columns, rows, cellWidthPixels, cellHeightPixels, mTranscriptRows, mClient);
        } else {
            mEmulator.resize(columns, rows, cellWidthPixels, cellHeightPixels);
        }
        Transport t = mTransport;
        if (t != null) t.resize(columns, rows, cellWidthPixels, cellHeightPixels);
    }

    public String getTitle() {
        return (mEmulator == null) ? null : mEmulator.getTitle();
    }

    /** Bytes from the remote side. May be called from any thread; blocks while the queue is full. */
    public void feed(byte[] data, int offset, int count) {
        if (count <= 0) return;
        byte[] chunk = data;
        if (offset != 0) {
            chunk = new byte[count];
            System.arraycopy(data, offset, chunk, 0, count);
        }
        if (mRemoteToTerminalQueue.write(chunk, 0, count)) mMainThreadHandler.sendEmptyMessage(MSG_NEW_INPUT);
    }

    /** Write data to the remote side (typed keys, terminal replies). */
    @Override
    public void write(byte[] data, int offset, int count) {
        Transport t = mTransport;
        if (t != null) t.write(data, offset, count);
    }

    /** Write the Unicode code point to the terminal encoded in UTF-8. */
    public void writeCodePoint(boolean prependEscape, int codePoint) {
        if (codePoint > 1114111 || (codePoint >= 0xD800 && codePoint <= 0xDFFF)) {
            throw new IllegalArgumentException("Invalid code point: " + codePoint);
        }

        int bufferPosition = 0;
        if (prependEscape) mUtf8InputBuffer[bufferPosition++] = 27;

        if (codePoint <= 0b1111111) {
            mUtf8InputBuffer[bufferPosition++] = (byte) codePoint;
        } else if (codePoint <= 0b11111111111) {
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11000000 | (codePoint >> 6));
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        } else if (codePoint <= 0b1111111111111111) {
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11100000 | (codePoint >> 12));
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 6) & 0b111111));
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        } else {
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11110000 | (codePoint >> 18));
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 12) & 0b111111));
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 6) & 0b111111));
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        }
        write(mUtf8InputBuffer, 0, bufferPosition);
    }

    public TerminalEmulator getEmulator() {
        return mEmulator;
    }

    protected void notifyScreenUpdate() {
        mClient.onTextChanged(this);
    }

    /** Reset state for terminal emulator state. */
    public void reset() {
        if (mEmulator == null) return;
        mEmulator.reset();
        notifyScreenUpdate();
    }

    @Override
    public void titleChanged(String oldTitle, String newTitle) {
        mClient.onTitleChanged(this);
    }

    @Override
    public void onCopyTextToClipboard(String text) {
        mClient.onCopyTextToClipboard(this, text);
    }

    @Override
    public void onPasteTextFromClipboard() {
        mClient.onPasteTextFromClipboard(this);
    }

    @Override
    public void onBell() {
        mClient.onBell(this);
    }

    @Override
    public void onColorsChanged() {
        mClient.onColorsChanged(this);
    }

    @SuppressLint("HandlerLeak")
    class MainThreadHandler extends Handler {

        final byte[] mReceiveBuffer = new byte[64 * 1024];

        MainThreadHandler() {
            super(Looper.getMainLooper());
        }

        @Override
        public void handleMessage(Message msg) {
            if (mEmulator == null) return;
            int bytesRead = mRemoteToTerminalQueue.read(mReceiveBuffer, false);
            if (bytesRead > 0) {
                mEmulator.append(mReceiveBuffer, bytesRead);
                notifyScreenUpdate();
                // More may be waiting than fits one buffer.
                if (bytesRead == mReceiveBuffer.length) sendEmptyMessage(MSG_NEW_INPUT);
            }
        }
    }
}
