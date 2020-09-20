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
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterOutputStream;

public class Compressor {

    @NonNull
    public static ByteBuffer deflate(
            @NonNull byte[] bytes, int offset, int size, int compressionLevel) throws IOException {
        NoCopyByteArrayOutputStream out = new NoCopyByteArrayOutputStream(size);

        Deflater deflater = new Deflater(compressionLevel, true);

        try (DeflaterOutputStream dout = new DeflaterOutputStream(out, deflater)) {
            dout.write(bytes, offset, size);
            dout.flush();
        }

        return out.getByteBuffer();
    }

    @NonNull
    public static ByteBuffer deflate(@NonNull byte[] bytes, int compressionLevel)
            throws IOException {
        return deflate(bytes, 0, bytes.length, compressionLevel);
    }

    @NonNull
    public static ByteBuffer inflate(@NonNull byte[] bytes) throws IOException {
        NoCopyByteArrayOutputStream out = new NoCopyByteArrayOutputStream(bytes.length);
        Inflater inflater = new Inflater(true);

        try (InflaterOutputStream dout = new InflaterOutputStream(out, inflater)) {
            dout.write(bytes);
            dout.flush();
        }

        return out.getByteBuffer();
    }
}
