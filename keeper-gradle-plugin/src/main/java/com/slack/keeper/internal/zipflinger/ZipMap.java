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
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;

public class ZipMap {
    private final Map<String, com.slack.keeper.internal.zipflinger.Entry> entries = new HashMap<>();
    private com.slack.keeper.internal.zipflinger.CentralDirectory cd = null;

    // To build an accurate location of entries in the zip payload, data descriptors must be read.
    // This is not useful if an user only wants a list of entries in the zip but it is mandatory
    // if zip entries are deleted/added.
    private final boolean accountDataDescriptors;

    private File file;
    private long fileSize;

    private com.slack.keeper.internal.zipflinger.Location payloadLocation;
    private com.slack.keeper.internal.zipflinger.Location cdLocation;
    private com.slack.keeper.internal.zipflinger.Location eocdLocation;

    private ZipMap(@NonNull File file, boolean accountDataDescriptors) {
        this.file = file;
        this.accountDataDescriptors = accountDataDescriptors;
    }

    @NonNull
    public static ZipMap from(@NonNull File zipFile, boolean accountDataDescriptors)
            throws IOException {
        return from(zipFile, accountDataDescriptors, com.slack.keeper.internal.zipflinger.Zip64.Policy.ALLOW);
    }

    @NonNull
    public static ZipMap from(
            @NonNull File zipFile, boolean accountDataDescriptors, com.slack.keeper.internal.zipflinger.Zip64.Policy policy)
            throws IOException {
        ZipMap map = new ZipMap(zipFile, accountDataDescriptors);
        map.parse(policy);
        return map;
    }

    @NonNull
    public com.slack.keeper.internal.zipflinger.Location getPayloadLocation() {
        return payloadLocation;
    }

    @NonNull
    public com.slack.keeper.internal.zipflinger.Location getCdLoc() {
        return cdLocation;
    }

    @NonNull
    public com.slack.keeper.internal.zipflinger.Location getEocdLoc() {
        return eocdLocation;
    }

    private void parse(com.slack.keeper.internal.zipflinger.Zip64.Policy policy) throws IOException {
        try (FileChannel channel = FileChannel.open(file.toPath(), StandardOpenOption.READ)) {

            fileSize = channel.size();

            com.slack.keeper.internal.zipflinger.EndOfCentralDirectory
                eocd = com.slack.keeper.internal.zipflinger.EndOfCentralDirectory.find(channel);
            if (eocd.getLocation() == com.slack.keeper.internal.zipflinger.Location.INVALID) {
                throw new IllegalStateException(String.format("Could not find EOCD in '%s'", file));
            }
            eocdLocation = eocd.getLocation();
            cdLocation = eocd.getCdLocation();

            // Check if this is a zip64 archive
            com.slack.keeper.internal.zipflinger.Zip64Locator locator = Zip64Locator.find(channel, eocd);
            if (locator.getLocation() != com.slack.keeper.internal.zipflinger.Location.INVALID) {
                if (policy == com.slack.keeper.internal.zipflinger.Zip64.Policy.FORBID) {
                    String message = String.format("Cannot parse forbidden zip64 archive %s", file);
                    throw new IllegalStateException(message);
                }
                com.slack.keeper.internal.zipflinger.Zip64Eocd zip64EOCD = Zip64Eocd.parse(channel, locator.getOffsetToEOCD64());
                cdLocation = zip64EOCD.getCdLocation();
                if (cdLocation == com.slack.keeper.internal.zipflinger.Location.INVALID) {
                    String message = String.format("Zip64Locator led to bad EOCD64 in %s", file);
                    throw new IllegalStateException(message);
                }
            }

            if (cdLocation == com.slack.keeper.internal.zipflinger.Location.INVALID) {
                throw new IllegalStateException(String.format("Could not find CD in '%s'", file));
            }

            parseCentralDirectory(channel, cdLocation, policy);

            payloadLocation = new com.slack.keeper.internal.zipflinger.Location(0, cdLocation.first);
        }
    }

    private void parseCentralDirectory(
            @NonNull FileChannel channel, @NonNull
        com.slack.keeper.internal.zipflinger.Location location, com.slack.keeper.internal.zipflinger.Zip64.Policy policy)
            throws IOException {
        if (location.size() > Integer.MAX_VALUE) {
            throw new IllegalStateException("CD larger than 2GiB not supported");
        }
        int size = Math.toIntExact(location.size());
        ByteBuffer buf = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        channel.read(buf, location.first);
        buf.rewind();

        while (buf.remaining() >= 4 && buf.getInt() == com.slack.keeper.internal.zipflinger.CentralDirectoryRecord.SIGNATURE) {
            com.slack.keeper.internal.zipflinger.Entry entry = new com.slack.keeper.internal.zipflinger.Entry();
            parseCentralDirectoryRecord(buf, channel, entry);
            if (!entry.getName().isEmpty()) {
                entries.put(entry.getName(), entry);
            }
            checkPolicy(entry, policy);
        }

        cd = new com.slack.keeper.internal.zipflinger.CentralDirectory(buf, entries);

        sanityCheck(location);
    }

    private static void checkPolicy(@NonNull
        com.slack.keeper.internal.zipflinger.Entry entry, com.slack.keeper.internal.zipflinger.Zip64.Policy policy) {
        if (policy == com.slack.keeper.internal.zipflinger.Zip64.Policy.ALLOW) {
            return;
        }

        if (entry.getUncompressedSize() > com.slack.keeper.internal.zipflinger.Zip64.LONG_MAGIC
                || entry.getCompressedSize() > com.slack.keeper.internal.zipflinger.Zip64.LONG_MAGIC
                || entry.getLocation().first > com.slack.keeper.internal.zipflinger.Zip64.LONG_MAGIC) {
            String message =
                    String.format(
                            "Entry %s infringes forbidden zip64 policy (size=%d, csize=%d, loc=%s)",
                            entry.getName(),
                            entry.getUncompressedSize(),
                            entry.getCompressedSize(),
                            entry.getLocation());
            throw new IllegalStateException(message);
        }
    }

    private void sanityCheck(com.slack.keeper.internal.zipflinger.Location cdLocation) {
        //Sanity check that:
        //  - All payload locations are within the file (and not in the CD).
        for (com.slack.keeper.internal.zipflinger.Entry e : entries.values()) {
            com.slack.keeper.internal.zipflinger.Location loc = e.getLocation();
            if (loc.first < 0) {
                throw new IllegalStateException("Invalid first loc '" + e.getName() + "' " + loc);
            }
            if (loc.last >= fileSize) {
                throw new IllegalStateException(
                        fileSize + "Invalid last loc '" + e.getName() + "' " + loc);
            }
            com.slack.keeper.internal.zipflinger.Location cdLoc = e.getCdLocation();
            if (cdLoc.first < 0) {
                throw new IllegalStateException(
                        "Invalid first cdloc '" + e.getName() + "' " + cdLoc);
            }
            long cdSize = cdLocation.size();
            if (cdLoc.last >= cdSize) {
                throw new IllegalStateException(
                        cdSize + "Invalid last loc '" + e.getName() + "' " + cdLoc);
            }
        }
    }

    @NonNull
    public Map<String, com.slack.keeper.internal.zipflinger.Entry> getEntries() {
        return entries;
    }

    @NonNull com.slack.keeper.internal.zipflinger.CentralDirectory getCentralDirectory() {
        return cd;
    }

    public void parseCentralDirectoryRecord(
            @NonNull ByteBuffer buf, @NonNull FileChannel channel, @NonNull
        com.slack.keeper.internal.zipflinger.Entry entry)
            throws IOException {
        long cdEntryStart = buf.position() - 4;

        buf.position(buf.position() + 4);
        //short versionMadeBy = buf.getShort();
        //short versionNeededToExtract = buf.getShort();
        short flags = buf.getShort();
        short compressionFlag = buf.getShort();
        entry.setCompressionFlag(compressionFlag);
        buf.position(buf.position() + 4);
        //short modTime = buf.getShort();
        //short modDate = buf.getShort();

        int crc = buf.getInt();
        entry.setCrc(crc);

        entry.setCompressedSize(com.slack.keeper.internal.zipflinger.Ints.uintToLong(buf.getInt()));
        entry.setUncompressedSize(com.slack.keeper.internal.zipflinger.Ints.uintToLong(buf.getInt()));

        int pathLength = com.slack.keeper.internal.zipflinger.Ints.ushortToInt(buf.getShort());
        int extraLength = com.slack.keeper.internal.zipflinger.Ints.ushortToInt(buf.getShort());
        int commentLength = com.slack.keeper.internal.zipflinger.Ints.ushortToInt(buf.getShort());
        buf.position(buf.position() + 8);
        // short diskNumber = buf.getShort();
        // short intAttributes = buf.getShort();
        // int extAttributes = bug.getInt();

        entry.setLocation(new com.slack.keeper.internal.zipflinger.Location(com.slack.keeper.internal.zipflinger.Ints.uintToLong(buf.getInt()), 0));

        parseName(buf, pathLength, entry);

        // Process extra field. If the entry is zip64, this may change size, csize, and offset.
        if (extraLength > 0) {
            int position = buf.position();
            int limit = buf.limit();
            buf.limit(position + extraLength);
            parseExtra(buf.slice(), entry);
            buf.limit(limit);
            buf.position(position + extraLength);
        }

        // Skip comment field
        buf.position(buf.position() + commentLength);

        // Retrieve the local header extra size since there are no guarantee it is the same as the
        // central directory size.
        // Semi-paranoid mode: Also check that the local name size is the same as the cd name size.
        ByteBuffer localFieldBuffer =
                readLocalFields(
                        entry.getLocation().first + com.slack.keeper.internal.zipflinger.LocalFileHeader.OFFSET_TO_NAME, entry, channel);
        int localPathLength = com.slack.keeper.internal.zipflinger.Ints.ushortToInt(localFieldBuffer.getShort());
        int localExtraLength = com.slack.keeper.internal.zipflinger.Ints.ushortToInt(localFieldBuffer.getShort());
        if (pathLength != localPathLength) {
            String message =
                    String.format(
                            "Entry '%s' name differ (%d vs %d)",
                            entry.getName(), localPathLength, pathLength);
            throw new IllegalStateException(message);
        }

        // At this point we have everything we need to calculate payload location.
        boolean isCompressed = compressionFlag != 0;
        long payloadSize = isCompressed ? entry.getCompressedSize() : entry.getUncompressedSize();
        long start = entry.getLocation().first;
        long end =
                start
                        + com.slack.keeper.internal.zipflinger.LocalFileHeader.LOCAL_FILE_HEADER_SIZE
                        + pathLength
                        + localExtraLength
                        + payloadSize;
        entry.setLocation(new com.slack.keeper.internal.zipflinger.Location(start, end - start));

        com.slack.keeper.internal.zipflinger.Location payloadLocation =
                new com.slack.keeper.internal.zipflinger.Location(
                        start
                                + com.slack.keeper.internal.zipflinger.LocalFileHeader.LOCAL_FILE_HEADER_SIZE
                                + pathLength
                                + localExtraLength,
                        payloadSize);
        entry.setPayloadLocation(payloadLocation);

        // At this point we have everything we need to calculate CD location.
        long cdEntrySize = com.slack.keeper.internal.zipflinger.CentralDirectoryRecord.SIZE + pathLength + extraLength + commentLength;
        entry.setCdLocation(new com.slack.keeper.internal.zipflinger.Location(cdEntryStart, cdEntrySize));

        // Parse data descriptor to adjust crc, compressed size, and uncompressed size.
        boolean hasDataDescriptor =
                ((flags & com.slack.keeper.internal.zipflinger.CentralDirectoryRecord.DATA_DESCRIPTOR_FLAG)
                        == com.slack.keeper.internal.zipflinger.CentralDirectoryRecord.DATA_DESCRIPTOR_FLAG);
        if (hasDataDescriptor) {
            if (accountDataDescriptors) {
                // This is expensive. Fortunately ZIP archive rarely use DD nowadays.
                channel.position(end);
                parseDataDescriptor(channel, entry);
            } else {
                entry.setLocation(com.slack.keeper.internal.zipflinger.Location.INVALID);
            }
        }
    }

    private static void parseExtra(@NonNull ByteBuffer buf, @NonNull
        com.slack.keeper.internal.zipflinger.Entry entry) {
        buf.order(ByteOrder.LITTLE_ENDIAN);
        while (buf.remaining() >= 4) {
            short id = buf.getShort();
            int size = com.slack.keeper.internal.zipflinger.Ints.ushortToInt(buf.getShort());
            if (id == com.slack.keeper.internal.zipflinger.Zip64.EXTRA_ID) {
                parseZip64Extra(buf, entry);
            }
            if (buf.remaining() >= size) {
                buf.position(buf.position() + size);
            }
        }
    }

    private static void parseZip64Extra(@NonNull ByteBuffer buf, @NonNull
        com.slack.keeper.internal.zipflinger.Entry entry) {
        if (entry.getUncompressedSize() == com.slack.keeper.internal.zipflinger.Zip64.LONG_MAGIC) {
            if (buf.remaining() < 8) {
                throw new IllegalStateException("Bad zip64 extra for entry " + entry.getName());
            }
            entry.setUncompressedSize(com.slack.keeper.internal.zipflinger.Ints.ulongToLong(buf.getLong()));
        }
        if (entry.getCompressedSize() == com.slack.keeper.internal.zipflinger.Zip64.LONG_MAGIC) {
            if (buf.remaining() < 8) {
                throw new IllegalStateException("Bad zip64 extra for entry " + entry.getName());
            }
            entry.setCompressedSize(com.slack.keeper.internal.zipflinger.Ints.ulongToLong(buf.getLong()));
        }
        if (entry.getLocation().first == Zip64.LONG_MAGIC) {
            if (buf.remaining() < 8) {
                throw new IllegalStateException("Bad zip64 extra for entry " + entry.getName());
            }
            long offset = com.slack.keeper.internal.zipflinger.Ints.ulongToLong(buf.getLong());
            entry.setLocation(new com.slack.keeper.internal.zipflinger.Location(offset, 0));
        }
    }

    private ByteBuffer readLocalFields(long offset, com.slack.keeper.internal.zipflinger.Entry entry, FileChannel channel)
            throws IOException {
        // The extra field is not guaranteed to be the same in the LFH and in the CDH. In practice there is
        // often padding space that is not in the CD. We need to read the LFH.
        ByteBuffer localFieldsBuffer = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
        if (offset < 0 || (offset + 4) > fileSize) {
            throw new IllegalStateException(
                    "Entry :" + entry.getName() + " invalid offset (" + offset + ")");
        }
        channel.read(localFieldsBuffer, offset);
        localFieldsBuffer.rewind();
        return localFieldsBuffer;
    }

    private static void parseName(@NonNull ByteBuffer buf, int length, @NonNull
        com.slack.keeper.internal.zipflinger.Entry entry) {
        byte[] pathBytes = new byte[length];
        buf.get(pathBytes);
        entry.setNameBytes(pathBytes);
    }

    private static void parseDataDescriptor(@NonNull FileChannel channel, @NonNull Entry entry)
            throws IOException {
        // If zip entries have data descriptor, we need to go an fetch every single entry to look if
        // the "optional" marker is there. Adjust zip entry area accordingly.

        ByteBuffer dataDescriptorBuffer = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
        channel.read(dataDescriptorBuffer);
        dataDescriptorBuffer.rewind();

        int dataDescriptorLength = 12;
        if (dataDescriptorBuffer.getInt() == com.slack.keeper.internal.zipflinger.CentralDirectoryRecord.DATA_DESCRIPTOR_SIGNATURE) {
            dataDescriptorLength += 4;
        }

        com.slack.keeper.internal.zipflinger.Location adjustedLocation =
                new Location(
                        entry.getLocation().first,
                        entry.getLocation().size() + dataDescriptorLength);
        entry.setLocation(adjustedLocation);
    }

    public File getFile() {
        return file;
    }
}
