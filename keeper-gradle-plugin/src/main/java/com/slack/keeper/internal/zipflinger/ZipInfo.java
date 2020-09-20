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

public class ZipInfo {
    public final com.slack.keeper.internal.zipflinger.Location payload;
    public final com.slack.keeper.internal.zipflinger.Location cd;
    public final com.slack.keeper.internal.zipflinger.Location eocd;

    public ZipInfo() {
        this(com.slack.keeper.internal.zipflinger.Location.INVALID, com.slack.keeper.internal.zipflinger.Location.INVALID,
            com.slack.keeper.internal.zipflinger.Location.INVALID);
    }

    public ZipInfo(com.slack.keeper.internal.zipflinger.Location payload, com.slack.keeper.internal.zipflinger.Location cd, Location eocd) {
        this.payload = payload;
        this.cd = cd;
        this.eocd = eocd;
    }
}
