package com.sidekick.snapshot;

import org.eclipse.jgit.errors.CorruptObjectException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.FileTreeIterator;

import java.io.File;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.CancellationException;

/** Interrupt Git directory traversal and large-file compression between reads. */
final class InterruptibleFileTreeIterator extends FileTreeIterator {
    InterruptibleFileTreeIterator(Repository repository) {
        super(repository);
    }

    private InterruptibleFileTreeIterator(InterruptibleFileTreeIterator parent, File directory) {
        super(parent, directory, parent.fs, parent.fileModeStrategy);
    }

    static void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("快照已取消");
        }
    }

    @Override
    protected AbstractTreeIterator enterSubtree() {
        checkInterrupted();
        return new InterruptibleFileTreeIterator(this, getEntryFile());
    }

    @Override
    public void next(int delta) throws CorruptObjectException {
        checkInterrupted();
        super.next(delta);
    }

    @Override
    public InputStream openEntryStream() throws IOException {
        checkInterrupted();
        return new FilterInputStream(super.openEntryStream()) {
            @Override
            public int read() throws IOException {
                checkInterrupted();
                return in.read();
            }

            @Override
            public int read(byte[] bytes, int offset, int length) throws IOException {
                checkInterrupted();
                return in.read(bytes, offset, length);
            }
        };
    }
}
