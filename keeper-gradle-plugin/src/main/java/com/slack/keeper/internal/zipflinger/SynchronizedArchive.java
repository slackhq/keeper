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

public class SynchronizedArchive implements com.slack.keeper.internal.zipflinger.Archive {

    private final com.slack.keeper.internal.zipflinger.Archive archive;

    public SynchronizedArchive(Archive archive) throws IOException {
        this.archive = archive;
    }

    @Override
    public void add(@NonNull BytesSource source) throws IOException {
        synchronized (archive) {
            archive.add(source);
        }
    }

    @Override
    public void add(@NonNull ZipSource sources) throws IOException {
        synchronized (archive) {
            archive.add(sources);
        }
    }

    @Override
    public void delete(@NonNull String name) throws IOException {
        synchronized (archive) {
            archive.delete(name);
        }
    }

    @Override
    public void close() throws IOException {
        synchronized (archive) {
            archive.close();
        }
    }
}
