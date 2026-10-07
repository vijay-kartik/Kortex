package dev.kortex.app.ui.screens.graph

import dev.kortex.design.Amber
import dev.kortex.design.Muted
import dev.kortex.design.Synapse
import dev.kortex.design.Teal
import dev.kortex.graph_core.NodeCategory
import org.junit.Assert.assertEquals
import org.junit.Test

/** Graph node colours come from the Theme page palette, one per category. */
class NodeCategoryColorTest {
    @Test
    fun `each category maps to its theme token`() {
        assertEquals(Synapse, NodeCategory.IDENTITY.color())
        assertEquals(Amber, NodeCategory.EVENT.color())
        assertEquals(Teal, NodeCategory.KNOWLEDGE.color())
        assertEquals(Muted, NodeCategory.ASSET.color())
    }

    @Test
    fun `no two categories share a colour`() {
        val colors = NodeCategory.entries.map { it.color() }
        assertEquals(colors.size, colors.toSet().size)
    }
}
