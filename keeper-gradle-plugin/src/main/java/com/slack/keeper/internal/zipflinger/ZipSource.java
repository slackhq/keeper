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
import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.Deflater;

public class ZipSource {
    public static final int COMPRESSION_NO_CHANGE = -2;
    private final File file;
    private FileChannel channel;
    private com.slack.keeper.internal.zipflinger.ZipMap map;

    private final List<com.slack.keeper.internal.zipflinger.Source> selectedEntries = new ArrayList<>();

    public ZipSource(@NonNull File file) throws IOException {
        this.map = ZipMap.from(file, false);
        this.file = file;
    }

    @NonNull
    public com.slack.keeper.internal.zipflinger.Source select(@NonNull String entryName, @NonNull String newName) {
        return select(entryName, newName, COMPRESSION_NO_CHANGE);
    }

    /**
     * Select an entry to be copied to the archive managed by zipflinger.
     *
     * <p>An entry will remain unchanged and zero-copy will happen when: - compression level is
     * COMPRESSION_NO_CHANGE. - compression level is 1-9 and the entry is already compressed. -
     * compression level is Deflater.NO_COMPRESSION and the entry is already uncompressed.
     *
     * <p>Otherwise, the entry is deflated/inflated accordingly via transfer to memory, crc
     * calculation , and written to the target archive.
     *
     * @param Name of the entry in the source zip.
     * @param Name of the entry in the destination zip.
     * @param The desired compression level.
     * @return
     */
    @NonNull
    public com.slack.keeper.internal.zipflinger.Source select(@NonNull String entryName, @NonNull String newName, int compressionLevel) {
        com.slack.keeper.internal.zipflinger.Entry entry = map.getEntries().get(entryName);
        if (entry == null) {
            throw new IllegalStateException(
                    String.format("Cannot find '%s' in archive '%s'", entryName, map.getFile()));
        }
        com.slack.keeper.internal.zipflinger.Source
            entrySource = newZipSourceEntryFor(newName, entry, this, compressionLevel);
        selectedEntries.add(entrySource);
        return entrySource;
    }

    public Map<String, com.slack.keeper.internal.zipflinger.Entry> entries() {
        return map.getEntries();
    }

    public static ZipSource selectAll(@NonNull File file) throws IOException {
        ZipSource source = new ZipSource(file);
        for (com.slack.keeper.internal.zipflinger.Entry e : source.entries().values()) {
            source.select(e.getName(), e.getName(), COMPRESSION_NO_CHANGE);
        }
        return source;
    }

    void open() throws IOException {
        channel = FileChannel.open(file.toPath(), StandardOpenOption.READ);
    }

    void close() throws IOException {
        if (channel != null) {
            channel.close();
        }
    }

    FileChannel getChannel() {
        return channel;
    }

    public List<? extends com.slack.keeper.internal.zipflinger.Source> getSelectedEntries() {
        return selectedEntries;
    }

    Source newZipSourceEntryFor(
            String newName, Entry entry, ZipSource zipSource, int compressionLevel) {
        // No change case.
        if (compressionLevel == COMPRESSION_NO_CHANGE
                || compressionLevel == Deflater.NO_COMPRESSION && !entry.isCompressed()
                || compressionLevel != Deflater.NO_COMPRESSION && entry.isCompressed()) {
            return new com.slack.keeper.internal.zipflinger.ZipSourceEntry(newName, entry, this);
        }

        // The entry needs to be inflated.
        if (compressionLevel == Deflater.NO_COMPRESSION) {
            return new com.slack.keeper.internal.zipflinger.ZipSourceEntryInflater(newName, entry, zipSource);
        }

        // The entry needs to be deflated.
        return new com.slack.keeper.internal.zipflinger.ZipSourceEntryDeflater(newName, entry, zipSource, compressionLevel);
    }

    String getName() {
        return file.getAbsolutePath();
    }
}
