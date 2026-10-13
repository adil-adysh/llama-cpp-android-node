package com.example.llamanode.ui.main

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** UI tests for [com.example.llamanode.ui.main.MainScreen]. */
class MainScreenTest {

  @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

  @Before
  fun setup() {
    composeTestRule.setContent { MainScreen() }
  }

  @Test
  fun appTitleAndControls_exist() {
    composeTestRule.onNodeWithText("LlamaNode").assertExists()
    composeTestRule.onNodeWithText("START RPC SERVER").assertExists()
  }
}
