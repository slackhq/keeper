/*
 * Copyright (C) 2020 Slack Technologies, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.slack.keeper.example.a;

import com.slack.keeper.example.b.BClass;
import java.time.Instant;

public class AClass {
  public static void sampleMethod() {
    // java.time usage to trigger L8
    System.out.println("Time is " + Instant.now());
    BClass.sampleMethod();
  }
}
