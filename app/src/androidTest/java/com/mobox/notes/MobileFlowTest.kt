package com.mobox.notes

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MobileFlowTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private fun waitFor(text: String) { ui.waitUntil(30000) { ui.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() } }
    private fun inDialog(text: String) = ui.onNode(hasText(text) and hasAnyAncestor(hasTestTag("appDialog")))
    @Test fun automaticEntryReturnSaveMoveAndPrivateAccess() {
        waitFor("选择记录")
        ui.onNodeWithText("主密码").assertDoesNotExist()
        ui.runOnUiThread { org.junit.Assert.assertEquals(0, ui.activity.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_SECURE) }
        ui.onNodeWithContentDescription("打开左侧分类与记录").performClick()
        ui.onNodeWithContentDescription("新建分类").performScrollTo().performClick()
        inDialog("分类名称").performTextInput("UI新分类")
        inDialog("确认").performClick()
        waitFor("UI新分类 (0)")
        ui.runOnUiThread { ui.activity.onBackPressedDispatcher.onBackPressed() }
        ui.onNodeWithContentDescription("新建记录").performClick()
        inDialog("空白笔记").performClick()
        ui.onNodeWithTag("noteBody").performTextInput("UITest首行\n完整正文")
        ui.onNodeWithContentDescription("撤销").assertExists()
        ui.onNodeWithContentDescription("重做").assertExists()
        ui.runOnUiThread { ui.activity.onBackPressedDispatcher.onBackPressed() }
        waitFor("UITest首行")
        ui.onNode(hasText("UITest首行") and hasAnyAncestor(hasTestTag("mainNoteList"))).assertExists()
        ui.onNodeWithText("选择记录").performClick()
        ui.onNode(hasText("UITest首行") and hasAnyAncestor(hasTestTag("mainNoteList"))).performClick()
        ui.onNodeWithText("移入分类 (1)").performClick()
        inDialog("▤ 账号").performClick()
        waitFor("已移入 账号")
        ui.onNodeWithContentDescription("打开左侧分类与记录").performClick()
        ui.onNodeWithText("账号 (1)").assertExists()
        ui.runOnUiThread { ui.activity.onBackPressedDispatcher.onBackPressed() }
        ui.onNodeWithText("选择记录").performClick()
        ui.onNode(hasText("UITest首行") and hasAnyAncestor(hasTestTag("mainNoteList"))).performClick()
        ui.onNodeWithText("移入分类 (1)").performClick()
        inDialog("▣ 私密（首次设置密码）").performClick()
        inDialog("私密密码（至少 8 位）").performTextInput("UI-Private2026")
        inDialog("再次输入").performTextInput("UI-Private2026")
        inDialog("确认").performClick()
        waitFor("已移入私密")
        ui.onNode(hasText("UITest首行") and hasAnyAncestor(hasTestTag("mainNoteList"))).assertDoesNotExist()
        ui.onNodeWithContentDescription("打开左侧分类与记录").performClick()
        ui.onNodeWithTag("category-${Session.BOX_ID}").performScrollTo().performClick()
        waitFor("独立分类密码")
        inDialog("独立分类密码").performTextInput("wrong")
        inDialog("确认").performClick()
        waitFor("密码错误或文件已损坏，请检查密码和备份")
        inDialog("独立分类密码").performTextReplacement("UI-Private2026")
        inDialog("确认").performClick()
        waitFor("UITest首行")
        ui.runOnUiThread { org.junit.Assert.assertNotEquals(0, ui.activity.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_SECURE) }
        ui.activityRule.scenario.recreate()
        waitFor("选择记录")
        ui.onNode(hasText("UITest首行") and hasAnyAncestor(hasTestTag("mainNoteList"))).assertDoesNotExist()
        ui.onNodeWithText("主密码").assertDoesNotExist()
    }
}
