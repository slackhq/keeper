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
import java.io.IOException;
import java.nio.ByteBuffer;

class ZipSourceEntryDeflater extends Source {

    private final Location loc;
    private final com.slack.keeper.internal.zipflinger.ZipSource zipSource;
    private final int compressionLevel;
    private ByteBuffer compressedByteBuffer;

    ZipSourceEntryDeflater(String newName, Entry entry, ZipSource zipSource, int compressionLevel) {
        super(newName);
        loc = entry.getPayloadLocation();
        this.zipSource = zipSource;
        this.compressionLevel = compressionLevel;
        crc = entry.getCrc();
    }

    @Override
    void prepare() throws IOException {
        ByteBuffer uncompressedBytes = ByteBuffer.allocate(Math.toIntExact(loc.size()));
        zipSource.getChannel().read(uncompressedBytes, loc.first);

        compressedByteBuffer = Compressor.deflate(uncompressedBytes.array(), compressionLevel);
        compressedSize = compressedByteBuffer.limit();
        uncompressedSize = uncompressedBytes.limit();
        compressionFlag = com.slack.keeper.internal.zipflinger.LocalFileHeader.COMPRESSION_DEFLATE;
    }

    @Override
    long writeTo(@NonNull com.slack.keeper.internal.zipflinger.ZipWriter writer) throws IOException {
        return writer.write(compressedByteBuffer);
    }
}
