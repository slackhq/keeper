/*
 * Copyright (C) 2020 The Android Open Source Project
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
import java.util.ArrayList;
import java.util.Comparator;

public class StableArchive implements com.slack.keeper.internal.zipflinger.Archive {

    private final com.slack.keeper.internal.zipflinger.Archive archive;
    private final ArrayList<com.slack.keeper.internal.zipflinger.BytesSource> bytesSources;
    private final ArrayList<ZipSource> zipSources;
    private final ArrayList<String> deletedEntries;

    public StableArchive(com.slack.keeper.internal.zipflinger.Archive archive) {
        this.archive = archive;
        bytesSources = new ArrayList<>();
        zipSources = new ArrayList<>();
        deletedEntries = new ArrayList<>();
    }

    @Override
    public void add(@NonNull com.slack.keeper.internal.zipflinger.BytesSource source) {
        bytesSources.add(source);
    }

    @Override
    public void add(@NonNull ZipSource sources) {
        zipSources.add(sources);
    }

    @Override
    public void delete(@NonNull String name) {
        deletedEntries.add(name);
    }

    @Override
    public void close() throws IOException {
        bytesSources.sort(Comparator.comparing(com.slack.keeper.internal.zipflinger.Source::getName));
        zipSources.sort(Comparator.comparing(ZipSource::getName));
        for (ZipSource zipSource : zipSources) {
            zipSource.getSelectedEntries().sort(Comparator.comparing(Source::getName));
        }
        deletedEntries.sort(Comparator.naturalOrder());

        try (Archive arch = archive) {
            for (String toDelete : deletedEntries) {
                arch.delete(toDelete);
            }

            for (BytesSource source : bytesSources) {
                arch.add(source);
            }

            for (ZipSource zipSource : zipSources) {
                arch.add(zipSource);
            }
        }
    }
}
