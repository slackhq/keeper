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
import java.io.IOException;

public interface Archive extends Closeable {

    /**
     * Add a source to the archive.
     *
     * @param source The source to add to this zip archive.
     * @throws IllegalStateException if the entry name already exists in the archive.
     * @throws IOException if writing to the zip archive fails.
     */
    void add(@NonNull BytesSource source) throws IOException;

    /**
     * Add a set of selected entries from an other zip archive.
     *
     * @param sources A zip archive with selected entries to add to this zip archive.
     * @throws IllegalStateException if the entry name already exists in the archive.
     * @throws IOException if writing to the zip archive fails.
     */
    void add(@NonNull ZipSource sources) throws IOException;

    /**
     * Delete an entry from this archive. If the entry did not exist, this method does nothing. To
     * avoid creating "holes" in the archive, it is mendatory to delete all entries first and add
     * sources second.
     *
     * @param name The name of the entry to delete.
     * @throws IllegalStateException if entries have been added.
     */
    void delete(@NonNull String name) throws IOException;

    @Override
    void close() throws IOException;
}
