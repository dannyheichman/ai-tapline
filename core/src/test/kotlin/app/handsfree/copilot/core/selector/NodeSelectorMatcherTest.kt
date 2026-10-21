package app.handsfree.copilot.core.selector

import app.handsfree.copilot.core.config.MatchRule
import app.handsfree.copilot.core.config.PickStrategy
import app.handsfree.copilot.core.config.SelectorSet
import app.handsfree.copilot.core.model.NodeSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeSelectorMatcherTest {
    @Test
    fun matchesAnEditableInputAndPicksTheBottomMost() {
        val selector = SelectorSet(
            anyOf = listOf(MatchRule(editable = true, password = false)),
            pick = PickStrategy.BOTTOM_MOST,
        )
        val search = node(top = 10, bottom = 40, editable = true, className = "android.widget.EditText")
        val composer = node(top = 800, bottom = 900, editable = true, className = "android.widget.EditText")
        val label = node(top = 850, bottom = 950, editable = false, text = "Send")

        assertTrue(NodeSelectorMatcher.matches(composer, selector))
        assertFalse(NodeSelectorMatcher.matches(label, selector))
        assertEquals(composer, NodeSelectorMatcher.pick(listOf(search, composer), selector.pick))
    }

    @Test
    fun sendRuleRejectsTheVoiceButton() {
        val rule = MatchRule(
            clickable = true,
            contentDescriptionContains = listOf("send"),
            contentDescriptionNotContains = listOf("voice", "mic"),
        )
        val send = node(contentDescription = "Send message", clickable = true)
        val voice = node(contentDescription = "Send a voice message", clickable = true)
        assertTrue(NodeSelectorMatcher.matches(send, rule))
        assertFalse(NodeSelectorMatcher.matches(voice, rule))
    }

    @Test
    fun anyRuleCanMatchAndResourceIdsAreCaseInsensitive() {
        val selector = SelectorSet(
            anyOf = listOf(
                MatchRule(resourceIdContains = listOf("composer")),
                MatchRule(classNameContains = listOf("Button"), textEquals = listOf("Send")),
            ),
        )
        val button = node(
            className = "android.widget.Button",
            text = "send",
            viewId = "com.example:id/Other",
        )
        assertTrue(NodeSelectorMatcher.matches(button, selector))
        val byId = node(viewId = "com.microsoft.office.officehubrow:id/Composer_Input")
        assertTrue(NodeSelectorMatcher.matches(byId, selector))
    }

    @Test
    fun emptySelectorMatchesNothing() {
        assertFalse(NodeSelectorMatcher.matches(node(editable = true), SelectorSet()))
    }

    private fun node(
        top: Int = 0,
        bottom: Int = 10,
        editable: Boolean = false,
        clickable: Boolean = false,
        className: String? = null,
        text: String? = null,
        contentDescription: String? = null,
        viewId: String? = null,
    ) = NodeSnapshot(
        className = className,
        text = text,
        contentDescription = contentDescription,
        viewIdResourceName = viewId,
        isEditable = editable,
        isClickable = clickable,
        boundsTop = top,
        boundsBottom = bottom,
    )
}
