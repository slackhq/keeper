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
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;

class EndOfCentralDirectory {
    private static final int SIGNATURE = 0x06054b50;
    static final int SIZE = 22;
    private static final long MAX_SIZE = com.slack.keeper.internal.zipflinger.Ints.USHRT_MAX + SIZE;
    static final short DISK_NUMBER = 0;

    private int numEntries;
    private Location location;
    private Location cdLocation;

    private EndOfCentralDirectory() {
        this.numEntries = 0;
        this.location = Location.INVALID;
        this.cdLocation = Location.INVALID;
    }

    private void parse(@NonNull ByteBuffer buffer) {
        // skip diskNumber (2) + cdDiskNumber (2) + #entries (2)
        buffer.position(buffer.position() + 6);
        numEntries = com.slack.keeper.internal.zipflinger.Ints.ushortToInt(buffer.getShort());
        long cdSize = com.slack.keeper.internal.zipflinger.Ints.uintToLong(buffer.getInt());
        long cdOffset = com.slack.keeper.internal.zipflinger.Ints.uintToLong(buffer.getInt());
        cdLocation = new Location(cdOffset, cdSize);
        buffer.position(buffer.position() + 2); // Skip comment length
    }

    @NonNull
    public Location getLocation() {
        return location;
    }

    @NonNull
    public Location getCdLocation() {
        return cdLocation;
    }

    public int numEntries() {
        return numEntries;
    }

    public void setLocation(@NonNull Location location) {
        this.location = location;
    }

    // Search the EOCD. If not found the returned object location will be set to Location.INVALID.
    @NonNull
    public static EndOfCentralDirectory find(@NonNull FileChannel channel) throws IOException {
        long fileSize = channel.size();

        EndOfCentralDirectory eocd = new EndOfCentralDirectory();
        if (fileSize < SIZE) {
            return eocd;
        }

        int sizeToRead = Math.toIntExact(Math.min(fileSize, MAX_SIZE));
        long offset = fileSize - sizeToRead;

        ByteBuffer buffer = ByteBuffer.allocate(sizeToRead).order(ByteOrder.LITTLE_ENDIAN);
        channel.read(buffer, offset);
        buffer.position(buffer.capacity() - SIZE);
        while (true) {
            int signature = buffer.getInt(); // Read 4 bytes.
            if (signature == EndOfCentralDirectory.SIGNATURE) {
                eocd.parse(buffer);
                eocd.setLocation(new Location(offset + buffer.position() - SIZE, SIZE));
                break;
            }
            if (buffer.position() <= 4) {
                break;
            }
            buffer.position(buffer.position() - Integer.BYTES - 1); // Backtrack  5 bytes.
        }
        return eocd;
    }

    @NonNull
    public static Location write(
            @NonNull com.slack.keeper.internal.zipflinger.ZipWriter writer, @NonNull Location cdLocation, long entriesCount)
            throws IOException {
        boolean isZip64 = Zip64.needZip64Footer(entriesCount, cdLocation);

        short numEntries = isZip64 ? Zip64.SHORT_MAGIC : com.slack.keeper.internal.zipflinger.Ints.longToUshort(entriesCount);
        int eocdSize = isZip64 ? Zip64.INT_MAGIC : com.slack.keeper.internal.zipflinger.Ints.longToUint(cdLocation.size());
        int eocdOffset = isZip64 ? Zip64.INT_MAGIC : com.slack.keeper.internal.zipflinger.Ints.longToUint(cdLocation.first);

        ByteBuffer eocd = ByteBuffer.allocate(SIZE).order(ByteOrder.LITTLE_ENDIAN);
        eocd.putInt(SIGNATURE);
        eocd.putShort(DISK_NUMBER);
        eocd.putShort((short) 0); // cd disk number
        eocd.putShort(numEntries);
        eocd.putShort(numEntries);
        eocd.putInt(eocdSize);
        eocd.putInt(eocdOffset);
        eocd.putShort((short) 0); // comment size

        eocd.rewind();
        long position = writer.position();
        writer.write(eocd);

        return new Location(position, SIZE);
    }

}
