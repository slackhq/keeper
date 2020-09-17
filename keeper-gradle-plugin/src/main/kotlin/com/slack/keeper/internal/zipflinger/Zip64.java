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

public class Zip64 {
    static final short EXTRA_ID = 0x0001;

    static final long LONG_MAGIC = 0xFF_FF_FF_FFL;
    static final int INT_MAGIC = (int) LONG_MAGIC;
    static final int SHORT_MAGIC = (short) LONG_MAGIC;

    static final short VERSION_NEEDED = 0x2D;

    public static boolean needZip64Footer(long numEntries, Location cdLocation) {
        return numEntries > com.slack.keeper.internal.zipflinger.Ints.USHRT_MAX
                || cdLocation.first > com.slack.keeper.internal.zipflinger.Ints.UINT_MAX
                || cdLocation.size() > com.slack.keeper.internal.zipflinger.Ints.UINT_MAX;
    }

    public enum Policy {
        ALLOW,
        FORBID
    };
}
