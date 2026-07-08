package dev.kortex.app

data class ParsedMessage(val text: String, val tables: List<String>)

fun parseMarkdownTables(markdown: String): ParsedMessage {
    val lines = markdown.lines()
    val textLines = mutableListOf<String>()
    val tables = mutableListOf<String>()
    
    var inTable = false
    val currentTable = mutableListOf<String>()
    
    for (line in lines) {
        val trimmed = line.trim()
        val isTableLine = trimmed.startsWith("|") && trimmed.endsWith("|")
        if (isTableLine) {
            inTable = true
            currentTable.add(line)
        } else {
            if (inTable) {
                inTable = false
                tables.add(currentTable.joinToString("\n"))
                currentTable.clear()
            }
            textLines.add(line)
        }
    }
    if (inTable) {
        tables.add(currentTable.joinToString("\n"))
    }
    
    return ParsedMessage(textLines.joinToString("\n").trim(), tables)
}

fun convertTableToTsv(table: String): String {
    val lines = table.lines().filter { it.isNotBlank() }
    val result = mutableListOf<String>()
    for (line in lines) {
        // Ignore lines with only dashes and pipes (the separator row)
        val stripped = line.replace(" ", "").replace("-", "").replace(":", "").replace("|", "")
        if (stripped.isEmpty()) {
            continue
        }
        val cells = line.trim().removePrefix("|").removeSuffix("|").split("|").map { it.trim() }
        result.add(cells.joinToString("\t"))
    }
    return result.joinToString("\n")
}
