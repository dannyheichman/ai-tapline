package app.handsfree.copilot.core.loop

import app.handsfree.copilot.core.config.CopilotSelectors
import app.handsfree.copilot.core.config.NodeSelector
import app.handsfree.copilot.core.model.UiNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatControlFinderTest {
    private val selectors = CopilotSelectors(
        targetPackage = "com.example.copilot",
        input = NodeSelector(
            resourceIdSuffixes = listOf("chat_input"),
            contentDescriptions = listOf("Ask me anything"),
            classNames = listOf("android.widget.EditText"),
        ),
        send = NodeSelector(contentDescriptions = listOf("Send")),
        stopGenerating = NodeSelector(contentDescriptions = listOf("Stop generating")),
        messageList = NodeSelector(resourceIds = listOf("com.example:id/messages")),
        minAssistantChars = 1,
    )

    @Test
    fun editableFieldWinsOverAHigherScoredButton() {
        val nodes = listOf(
            node(desc = "Ask me anything", className = "android.widget.Button", clickable = true, top = 10, bottom = 40),
            node(className = "android.widget.EditText", editable = true, top = 800, bottom = 900, left = 0, right = 700),
        )
        assertEquals(1, ChatControlFinder.findInput(nodes, selectors.input))
    }

    @Test
    fun resourceIdSuffixScoresTheComposer() {
        val nodes = listOf(
            node(className = "android.widget.EditText", id = "com.example:id/chat_input", editable = true, bottom = 100),
            node(className = "android.widget.EditText", editable = true, bottom = 900),
        )
        assertEquals(0, ChatControlFinder.findInput(nodes, selectors.input))
        assertTrue(ChatControlFinder.score(nodes[0], selectors.input) > ChatControlFinder.score(nodes[1], selectors.input))
    }

    @Test
    fun sendControlIsTheLabeledButtonBesideTheInput() {
        val input = node(className = "android.widget.EditText", editable = true, top = 800, bottom = 900, left = 0, right = 600)
        val send = node(desc = "Send", className = "android.widget.ImageButton", clickable = true, top = 820, bottom = 880, left = 620, right = 720)
        val other = node(text = "Copy", clickable = true, top = 100, bottom = 140, left = 0, right = 80)
        val nodes = listOf(other, input, send)
        val inputIndex = ChatControlFinder.findInput(nodes, selectors.input)
        assertEquals(2, ChatControlFinder.findSend(nodes, selectors.send, inputIndex))
    }

    @Test
    fun newestReplyIsTheLowerBubbleThatIsNotTheUserOrThePreviousAnswer() {
        val nodes = listOf(
            node(text = "Older answer that should stay quiet.", top = 100, bottom = 200),
            node(text = "What is a sprint?", top = 220, bottom = 280),
            node(text = "A sprint is a short planning cycle.", top = 300, bottom = 420),
            node(className = "android.widget.EditText", editable = true, top = 800, bottom = 900),
        )
        val text = ChatControlFinder.assistantCandidate(
            nodes = nodes,
            selectors = selectors,
            userUtterance = "What is a sprint?",
            baseline = "Older answer that should stay quiet.",
        )
        assertEquals("A sprint is a short planning cycle.", text)
    }

    @Test
    fun messageListSelectorIgnoresChromeOutsideTheList() {
        val list = node(id = "com.example:id/messages", className = "android.widget.ListView", path = listOf(0), top = 0, bottom = 700)
        val outside = node(text = "Upgrade now", path = listOf(1), top = 0, bottom = 40)
        val inside = node(text = "Inside the chat.", path = listOf(0, 1), top = 200, bottom = 280)
        val text = ChatControlFinder.assistantCandidate(
            nodes = listOf(list, outside, inside),
            selectors = selectors,
            userUtterance = "",
            baseline = "",
        )
        assertEquals("Inside the chat.", text)
    }

    @Test
    fun shortStopControlCountsAsGeneratingAndAParagraphDoesNot() {
        val stop = node(desc = "Stop generating", clickable = true, text = "Stop generating")
        val paragraph = node(text = "Stop generating is mentioned inside this much longer assistant paragraph about the project.")
        assertTrue(ChatControlFinder.generatingVisible(listOf(stop), selectors))
        assertTrue(!ChatControlFinder.generatingVisible(listOf(paragraph), selectors))
    }

    private fun node(
        text: String = "",
        desc: String = "",
        className: String = "android.widget.TextView",
        id: String = "",
        top: Int = 0,
        bottom: Int = 20,
        left: Int = 0,
        right: Int = 100,
        editable: Boolean = false,
        clickable: Boolean = false,
        path: List<Int> = emptyList(),
    ) = UiNode(
        text = text,
        contentDescription = desc,
        className = className,
        viewId = id,
        top = top,
        bottom = bottom,
        left = left,
        right = right,
        editable = editable,
        clickable = clickable,
        path = path,
    )
}
