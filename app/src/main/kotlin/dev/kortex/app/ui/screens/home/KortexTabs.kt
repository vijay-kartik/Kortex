package dev.kortex.app.ui.screens.home

/** Top-level grouping of tabs. The selected category expands; the others collapse to a chip. */
enum class TabCategory(val label: String) {
    Agent("Agent"),
    MyInfo("My Info"),
}

/** What the agent does lives under [TabCategory.Agent]; what Kortex keeps for the user lives under [TabCategory.MyInfo]. */
enum class KortexTab(val label: String, val category: TabCategory) {
    Chat("Chat", TabCategory.Agent),
    Graph("Graph", TabCategory.Agent),
    History("History", TabCategory.Agent),
    Runs("Runs", TabCategory.Agent),
    Links("Links", TabCategory.MyInfo),
    Topics("Topics", TabCategory.MyInfo);

    companion object {
        fun of(category: TabCategory): List<KortexTab> = entries.filter { it.category == category }
    }
}
