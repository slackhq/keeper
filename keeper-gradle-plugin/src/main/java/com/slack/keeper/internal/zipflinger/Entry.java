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
import java.nio.charset.StandardCharsets;

public class Entry {

    // The location (in file space) of the zip entry which includes the LFH, payload and
    // Data descriptor.
    private Location location = Location.INVALID;

    // The location (in CD space) of this entry in the CD.
    private Location cdLocation = Location.INVALID;

    // The location (in file space) of the zip entry payload (the actual file data).
    private Location payloadLocation = Location.INVALID;

    private String name = "";
    private int crc;
    private long compressedSize;
    private long uncompressedSize;
    private short compressionFlag;

    Entry() {}

    public short getCompressionFlag() {
        return compressionFlag;
    }

    public long getCompressedSize() {
        return compressedSize;
    }

    public long getUncompressedSize() {
        return uncompressedSize;
    }

    public String getName() {
        return name;
    }

    public int getCrc() {
        return crc;
    }

    public boolean isDirectory() {
        return name.charAt(name.length() - 1) == '/';
    }

    public boolean isCompressed() {
        return compressionFlag != com.slack.keeper.internal.zipflinger.LocalFileHeader.COMPRESSION_NONE;
    }

    @NonNull
    Location getCdLocation() {
        return cdLocation;
    }

    @NonNull
    Location getLocation() {
        return location;
    }

    @NonNull
    Location getPayloadLocation() {
        return payloadLocation;
    }

    void setCdLocation(@NonNull Location cdLocation) {
        this.cdLocation = cdLocation;
    }

    void setNameBytes(@NonNull byte[] nameBytes) {
        this.name = new String(nameBytes, StandardCharsets.UTF_8);
    }

    void setCrc(int crc) {
        this.crc = crc;
    }

    void setPayloadLocation(@NonNull Location payloadLocation) {
        this.payloadLocation = payloadLocation;
    }

    void setCompressionFlag(short compressionFlag) {
        this.compressionFlag = compressionFlag;
    }

    void setCompressedSize(long compressedSize) {
        this.compressedSize = compressedSize;
    }

    void setUncompressedSize(long ucompressedSize) {
        this.uncompressedSize = ucompressedSize;
    }

    void setLocation(@NonNull Location location) {
        this.location = location;
    }
}
