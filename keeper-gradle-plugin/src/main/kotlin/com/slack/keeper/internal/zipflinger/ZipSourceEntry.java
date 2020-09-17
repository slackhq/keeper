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

class ZipSourceEntry extends Source {
    // Location of the payload in the zipsource.
    private Location payloadLoc;
    private final com.slack.keeper.internal.zipflinger.ZipSource zipSource;

    protected ZipSourceEntry(@NonNull String name, @NonNull Entry entry, ZipSource zipSource) {
        super(name);
        this.zipSource = zipSource;
        compressedSize = entry.getCompressedSize();
        uncompressedSize = entry.getUncompressedSize();
        crc = entry.getCrc();
        compressionFlag = entry.getCompressionFlag();
        payloadLoc = entry.getPayloadLocation();
    }

    @Override
    void prepare() {}

    @Override
    long writeTo(@NonNull com.slack.keeper.internal.zipflinger.ZipWriter writer) throws IOException {
        writer.transferFrom(zipSource.getChannel(), payloadLoc.first, payloadLoc.size());
        return payloadLoc.size();
    }
}
