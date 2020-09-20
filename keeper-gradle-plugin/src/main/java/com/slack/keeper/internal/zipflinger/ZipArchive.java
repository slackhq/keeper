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
import com.android.annotations.Nullable;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ZipArchive implements Archive {

    private final com.slack.keeper.internal.zipflinger.FreeStore freestore;
    private boolean closed;
    private final File file;
    private final com.slack.keeper.internal.zipflinger.CentralDirectory cd;
    private final com.slack.keeper.internal.zipflinger.ZipWriter writer;
    private final ZipReader reader;
    private final com.slack.keeper.internal.zipflinger.Zip64.Policy policy;
    private ZipInfo zipInfo;
    private boolean modified;

    public ZipArchive(@NonNull File file) throws IOException {
        this(file, com.slack.keeper.internal.zipflinger.Zip64.Policy.ALLOW);
    }

    /**
     * The object used to manipulate a zip archive.
     *
     * @param file the file object
     * @throws IOException
     */
    public ZipArchive(@NonNull File file, com.slack.keeper.internal.zipflinger.Zip64.Policy policy) throws IOException {
        this.file = file;
        this.policy = policy;
        if (Files.exists(file.toPath())) {
            ZipMap map = ZipMap.from(file, true, policy);
            zipInfo = new ZipInfo(map.getPayloadLocation(), map.getCdLoc(), map.getEocdLoc());
            cd = map.getCentralDirectory();
            freestore = new com.slack.keeper.internal.zipflinger.FreeStore(map.getEntries());
        } else {
            zipInfo = new ZipInfo();
            HashMap<String, com.slack.keeper.internal.zipflinger.Entry> entries = new HashMap<>();
            cd = new com.slack.keeper.internal.zipflinger.CentralDirectory(ByteBuffer.allocate(0), entries);
            freestore = new com.slack.keeper.internal.zipflinger.FreeStore(entries);
        }

        writer = new com.slack.keeper.internal.zipflinger.ZipWriter(file);
        reader = new ZipReader(file);
        closed = false;
        modified = false;
    }

    /**
     * Returns the list of zip entries found in the archive. Note that these are the entries found
     * in the central directory via bottom-up parsing, not all entries present in the payload as a
     * top-down parser may return.
     *
     * @param file the zip archive to list.
     * @return the list of entries in the archive, parsed bottom-up (via the Central Directory).
     * @throws IOException
     */
    @NonNull
    public static Map<String, Entry> listEntries(@NonNull File file) throws IOException {
        return ZipMap.from(file, false).getEntries();
    }

    @NonNull
    public List<String> listEntries() {
        return cd.listEntries();
    }

    @Nullable
    public ByteBuffer getContent(@NonNull String name) throws IOException {
        ExtractionInfo extractInfo = cd.getExtractionInfo(name);
        if (extractInfo == null) {
            return null;
        }
        com.slack.keeper.internal.zipflinger.Location loc = extractInfo.getLocation();
        ByteBuffer payloadByteBuffer = ByteBuffer.allocate(Math.toIntExact(loc.size()));
        reader.read(payloadByteBuffer, loc.first);
        if (extractInfo.isCompressed()) {
            return Compressor.inflate(payloadByteBuffer.array());
        } else {
            return payloadByteBuffer;
        }
    }

    /** See Archive.add documentation */
    @Override
    public void add(@NonNull BytesSource source) throws IOException {
        if (closed) {
            throw new IllegalStateException(
                    String.format("Cannot add source to closed archive %s", file));
        }
        writeSource(source);
    }

    /** See Archive.add documentation */
    @Override
    public void add(@NonNull ZipSource sources) throws IOException {
        if (closed) {
            throw new IllegalStateException(
                    String.format("Cannot add zip source to closed archive %s", file));
        }

        try {
            sources.open();
            for (com.slack.keeper.internal.zipflinger.Source source : sources.getSelectedEntries()) {
                writeSource(source);
            }
        } finally {
            sources.close();
        }
    }

    /** See Archive.delete documentation */
    @Override
    public void delete(@NonNull String name) {
        if (closed) {
            throw new IllegalStateException(
                    String.format("Cannot delete '%s' from closed archive %s", name, file));
        }
        com.slack.keeper.internal.zipflinger.Location loc = cd.delete(name);
        if (loc != com.slack.keeper.internal.zipflinger.Location.INVALID) {
            freestore.free(loc);
            modified = true;
        }
    }

    /**
     * Carry all write operations to the storage system to reflect the delete/add operations
     * requested via add/delete methods.
     *
     * @throws IOException
     */
    // TODO: Zip64 -> Add boolean allowZip64
    @Override
    public void close() throws IOException {
        closeWithInfo();
    }

    @NonNull
    public ZipInfo closeWithInfo() throws IOException {
        if (closed) {
            throw new IllegalStateException("Attempt to close a closed archive");
        }
        closed = true;
        try (com.slack.keeper.internal.zipflinger.ZipWriter w = writer;
             ZipReader r = reader) {
            writeArchive(w);
        }
        return zipInfo;
    }

    @NonNull
    public File getFile() {
        return file;
    }

    public boolean isClosed() {
        return closed;
    }

    private void writeArchive(@NonNull com.slack.keeper.internal.zipflinger.ZipWriter writer) throws IOException {
        // There is no need to fill space and write footers if an already existing archive
        // has not been modified.
        if (zipInfo.eocd != com.slack.keeper.internal.zipflinger.Location.INVALID && !modified) {
            return;
        }

        // Fill all empty space with virtual entry (not the last one since it represent all of
        // the unused file space.
        List<com.slack.keeper.internal.zipflinger.Location> freeLocations = freestore.getFreeLocations();
        for (int i = 0; i < freeLocations.size() - 1; i++) {
            fillFreeLocation(freeLocations.get(i), writer);
        }

        // Write the Central Directory
        com.slack.keeper.internal.zipflinger.Location lastFreeLocation = freestore.getLastFreeLocation();
        long cdStart = lastFreeLocation.first;
        writer.position(cdStart);
        cd.write(writer);
        com.slack.keeper.internal.zipflinger.Location
            cdLocation = new com.slack.keeper.internal.zipflinger.Location(cdStart, writer.position() - cdStart);
        long numEntries = cd.getNumEntries();

        // Write zip64 EOCD and Locator (only if needed)
        writeZip64Footers(writer, cdLocation, numEntries);

        // Write EOCD
        com.slack.keeper.internal.zipflinger.Location eocdLocation = com.slack.keeper.internal.zipflinger.EndOfCentralDirectory.write(writer, cdLocation, numEntries);
        writer.truncate(writer.position());

        // Build and return location map
        com.slack.keeper.internal.zipflinger.Location
            payLoadLocation = new com.slack.keeper.internal.zipflinger.Location(0, cdStart);

        zipInfo = new ZipInfo(payLoadLocation, cdLocation, eocdLocation);
    }

    private void writeZip64Footers(
            @NonNull com.slack.keeper.internal.zipflinger.ZipWriter writer, @NonNull
        com.slack.keeper.internal.zipflinger.Location cdLocation, long numEntries)
            throws IOException {
        if (!com.slack.keeper.internal.zipflinger.Zip64.needZip64Footer(numEntries, cdLocation)) {
            return;
        }

        if (policy == com.slack.keeper.internal.zipflinger.Zip64.Policy.FORBID) {
            String message =
                    String.format(
                            "Zip64 required but forbidden (#entries=%d, cd=%s)",
                            numEntries, cdLocation);
            throw new IllegalStateException(message);
        }

        com.slack.keeper.internal.zipflinger.Zip64Eocd eocd = new Zip64Eocd(numEntries, cdLocation);
        com.slack.keeper.internal.zipflinger.Location eocdLocation = eocd.write(writer);

        Zip64Locator.write(writer, eocdLocation);
    }

    // Fill archive holes with virtual entries. Use extra field to fill as much as possible.
    private static void fillFreeLocation(@NonNull com.slack.keeper.internal.zipflinger.Location location, @NonNull
        com.slack.keeper.internal.zipflinger.ZipWriter writer)
            throws IOException {
        long spaceToFill = location.size();

        if (spaceToFill < com.slack.keeper.internal.zipflinger.LocalFileHeader.VIRTUAL_HEADER_SIZE) {
            // There is not enough space to create a virtual entry here. The FreeStore
            // never creates such gaps so it was already in the zip. Leave it as it is.
            return;
        }

        while (spaceToFill > 0) {
            long entrySize;
            if (spaceToFill <= com.slack.keeper.internal.zipflinger.LocalFileHeader.VIRTUAL_ENTRY_MAX_SIZE) {
                // Consume all the remaining space.
                entrySize = spaceToFill;
            } else {
                // Consume as much as possible while leaving enough for the next LFH entry.
                entrySize = com.slack.keeper.internal.zipflinger.Ints.USHRT_MAX;
            }
            int size = Math.toIntExact(entrySize);
            ByteBuffer virtualEntry = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
            com.slack.keeper.internal.zipflinger.LocalFileHeader.fillVirtualEntry(virtualEntry);
            writer.write(virtualEntry, location.first + location.size() - spaceToFill);
            spaceToFill -= virtualEntry.capacity();
        }
    }

    private void writeSource(@NonNull com.slack.keeper.internal.zipflinger.Source source) throws IOException {
        modified = true;
        source.prepare();
        validateName(source);

        // Calculate the size we need (header + payload)
        com.slack.keeper.internal.zipflinger.LocalFileHeader lfh = new com.slack.keeper.internal.zipflinger.LocalFileHeader(source);
        long headerSize = lfh.getSize();
        long bytesNeeded = headerSize + source.getCompressedSize();

        // Allocate file space
        com.slack.keeper.internal.zipflinger.Location loc;
        if (source.isAligned()) {
            loc = freestore.alloc(bytesNeeded, headerSize, source.getAlignment());
            lfh.setPadding(Math.toIntExact(loc.size() - bytesNeeded));
        } else {
            loc = freestore.ualloc(bytesNeeded);
        }

        writer.position(loc.first);
        lfh.write(writer);

        // Write payload
        long payloadStart = writer.position();
        long payloadSize = source.writeTo(writer);
        com.slack.keeper.internal.zipflinger.Location
            payloadLocation = new com.slack.keeper.internal.zipflinger.Location(payloadStart, payloadSize);

        // Update Central Directory record
        com.slack.keeper.internal.zipflinger.CentralDirectoryRecord cdRecord =
                new com.slack.keeper.internal.zipflinger.CentralDirectoryRecord(
                        source.getNameBytes(),
                        source.getCrc(),
                        source.getCompressedSize(),
                        source.getUncompressedSize(),
                        loc,
                        source.getCompressionFlag(),
                        payloadLocation);
        cd.add(source.getName(), cdRecord);

        checkPolicy(source, loc, payloadLocation);
    }

    private void checkPolicy(
            @NonNull com.slack.keeper.internal.zipflinger.Source source, @NonNull
        com.slack.keeper.internal.zipflinger.Location cdloc, @NonNull Location payloadLoc) {
        if (policy == com.slack.keeper.internal.zipflinger.Zip64.Policy.ALLOW) {
            return;
        }

        if (source.getUncompressedSize() >= com.slack.keeper.internal.zipflinger.Zip64.LONG_MAGIC
                || source.getCompressedSize() >= com.slack.keeper.internal.zipflinger.Zip64.LONG_MAGIC
                || cdloc.first >= com.slack.keeper.internal.zipflinger.Zip64.LONG_MAGIC
                || payloadLoc.first >= Zip64.LONG_MAGIC) {
            String message =
                    String.format(
                            "Zip64 forbidden but required in entry %s size=%d, csize=%d, cdloc=%s, loc=%s",
                            source.getName(),
                            source.getUncompressedSize(),
                            source.getCompressedSize(),
                            cdloc,
                            payloadLoc);
            throw new IllegalStateException(message);
        }
    }

    private void validateName(@NonNull Source source) {
        byte[] nameBytes = source.getNameBytes();
        String name = source.getName();
        if (nameBytes.length > com.slack.keeper.internal.zipflinger.Ints.USHRT_MAX) {
            throw new IllegalStateException(
                    String.format("Name '%s' is more than %d bytes", name, com.slack.keeper.internal.zipflinger.Ints.USHRT_MAX));
        }

        if (cd.contains(name)) {
            throw new IllegalStateException(String.format("Entry name '%s' collided", name));
        }
    }
}
