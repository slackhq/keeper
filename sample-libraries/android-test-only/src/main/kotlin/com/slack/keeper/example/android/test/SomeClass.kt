package com.slack.keeper.example.android.test

import androidx.test.platform.app.InstrumentationRegistry

class SomeClass {

  private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

  private val isTablet: Boolean
    get() = context.resources.getBoolean(com.slack.keeper.sample.main.R.bool.is_tablet)

  fun test() {
    if (isTablet) println("is tablet") else println("is no tablet")
  }
}