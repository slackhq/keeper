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
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

class CentralDirectory {

    // The Central Directory as it was read when an archive already existed.
    private final ByteBuffer buf;

    private final List<Location> deletedLocations = new ArrayList<>();
    private final Map<String, Entry> entries;
    private final Map<String, com.slack.keeper.internal.zipflinger.CentralDirectoryRecord> addedEntries = new HashMap<>();

    CentralDirectory(@NonNull ByteBuffer buf, @NonNull Map<String, Entry> entries) {
        this.buf = buf;
        this.entries = entries;
    }

    @NonNull
    Location delete(@NonNull String name) {
        if (entries.containsKey(name)) {
            Entry entry = entries.get(name);
            deletedLocations.add(entry.getCdLocation());
            entries.remove(name);
            return entry.getLocation();
        }
        if (addedEntries.containsKey(name)) {
            com.slack.keeper.internal.zipflinger.CentralDirectoryRecord record = addedEntries.remove(name);
            return record.getLocation();
        }
        return Location.INVALID;
    }

    long getNumEntries() {
        return entries.size() + addedEntries.size();
    }

    void write(@NonNull com.slack.keeper.internal.zipflinger.ZipWriter writer) throws IOException {
        // Four steps operations (first write old entries then new entries):
        // 1/ Sort deleted entries by location.
        // 2/ Create a list of "clean" (not deleted) locations.
        // 3/ Write all old (non-deleted) locations.
        // 4/ Write all new entries.

        // Step 1
        Collections.sort(deletedLocations);

        // Step 2 (Build list of non-deleted locations).
        List<Location> cleanCDLocations = new ArrayList<>();
        long remainingStart = 0;
        long remainingSize = buf.capacity();

        for (Location deletedLocation : deletedLocations) {
            Location cleanLoc =
                    new Location(remainingStart, deletedLocation.first - remainingStart);

            // If cleanLoc is the left end of the remaining CD, cleanLoc size is 0.
            if (cleanLoc.size() > 0) {
                cleanCDLocations.add(cleanLoc);
            }
            remainingStart = deletedLocation.last + 1;
            remainingSize -= (deletedLocation.size() + cleanLoc.size());
        }
        // Add the remaining of the CD as a clear location
        if (remainingSize > 0) {
            cleanCDLocations.add(new Location(remainingStart, remainingSize));
        }

        // Step 3: write clean CD chunks
        for (Location toWrite : cleanCDLocations) {
            buf.limit(Math.toIntExact(toWrite.first + toWrite.size()));
            buf.position(Math.toIntExact(toWrite.first));
            ByteBuffer view = buf.slice();
            writer.write(view);
        }

        // Step 4: write new entries

        // Assess how much data the CD requires
        long totalSize = 0;
        for (com.slack.keeper.internal.zipflinger.CentralDirectoryRecord record : addedEntries.values()) {
            totalSize += record.getSize();
        }
        // Generate the CD portion of new entries
        ByteBuffer cdBuffer =
                ByteBuffer.allocate(Math.toIntExact(totalSize)).order(ByteOrder.LITTLE_ENDIAN);
        for (com.slack.keeper.internal.zipflinger.CentralDirectoryRecord record : addedEntries.values()) {
            record.write(cdBuffer);
        }

        // Write new entries
        cdBuffer.rewind();
        writer.write(cdBuffer);
    }

    void add(@NonNull String name, @NonNull com.slack.keeper.internal.zipflinger.CentralDirectoryRecord record) {
        addedEntries.put(name, record);
    }

    boolean contains(@NonNull String name) {
        return entries.containsKey(name) || addedEntries.containsKey(name);
    }

    @NonNull
    List<String> listEntries() {
        List<String> list = new ArrayList<>();
        list.addAll(entries.keySet());
        list.addAll(addedEntries.keySet());
        return list;
    }

    @Nullable
    public ExtractionInfo getExtractionInfo(@NonNull String name) {
        Entry entry = entries.get(name);
        if (entry != null) {
            return new ExtractionInfo(entry.getPayloadLocation(), entry.isCompressed());
        }

        com.slack.keeper.internal.zipflinger.CentralDirectoryRecord cd = addedEntries.get(name);
        if (cd != null) {
            boolean isCompressed = cd.getCompressionFlag() != com.slack.keeper.internal.zipflinger.LocalFileHeader.COMPRESSION_NONE;
            return new ExtractionInfo(cd.getPayloadLocation(), isCompressed);
        }

        return null;
    }
}
