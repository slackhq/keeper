/*
 * Copyright (C) 2020. Slack Technologies, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.slack.keeper.sample

import com.slack.keeper.example.c.TestOnlyCClass
import java.time.Duration
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8

object TestOnlyCClassCaller {
  fun callCClass() {
    // Duration usage to trigger L8, different than the L8 usage in the app.
    val days = Duration.ofDays(1)
    TestOnlyCClass.sampleMethod()
    val byteString: ByteString = "Hello C caller! See you in $days day.".encodeUtf8()
    println(byteString.hex())
  }
}
