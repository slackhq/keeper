/*
 * Copyright (C) 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.slack.keeper.internal.zipflinger;

import com.android.annotations.NonNull;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;

class ZipWriter implements Closeable {

    private final File file;
    private FileChannel channel;
    private boolean isOpen;

    public ZipWriter(File file) {
        this.file = file;
        isOpen = false;
    }

    @Override
    public void close() throws IOException {
        if (!isOpen) {
            return;
        }
        channel.close();
    }

    public void truncate(long size) throws IOException {
        ensureOpen();
        channel.truncate(size);
    }

    public void position(long position) throws IOException {
        ensureOpen();
        channel.position(position);
    }

    public long position() throws IOException {
        ensureOpen();
        return channel.position();
    }

    public int write(@NonNull ByteBuffer buffer, long position) throws IOException {
        ensureOpen();
        return channel.write(buffer, position);
    }

    public int write(@NonNull ByteBuffer buffer) throws IOException {
        ensureOpen();
        return channel.write(buffer);
    }

    public void transferFrom(@NonNull FileChannel src, long position, long count)
            throws IOException {
        ensureOpen();
        long copied = 0;
        while (copied != count) {
            copied += src.transferTo(position + copied, count - copied, channel);
        }
    }

    private void ensureOpen() throws IOException {
        if (isOpen) {
            return;
        }
        channel =
                FileChannel.open(
                        file.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        if (!channel.isOpen()) {
            throw new IllegalStateException("Unable to open Channel to " + file.getAbsolutePath());
        }
        isOpen = true;
    }
}
