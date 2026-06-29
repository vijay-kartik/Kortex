package dev.kortex.core.tool

import kotlinx.serialization.json.JsonObject

/**
 * Ergonomic tool definition — the "define tools" half of the goal.
 *
 *   val weather = tool("get_weather", "Look up current weather for a city") {
 *       param("city", "string", "City name, e.g. 'Tokyo'")
 *       risk(RiskLevel.LOW)
 *       execute { args -> ToolResult(true, weatherApi(args.string("city"))) }
 *   }
 */
fun tool(name: String, description: String, block: ToolBuilder.() -> Unit): Tool =
    ToolBuilder(name, description).apply(block).build()

class ToolBuilder(private val name: String, private val description: String) {
    private val params = mutableListOf<ToolParam>()
    private var risk = RiskLevel.LOW
    private var body: (suspend (JsonObject) -> ToolResult)? = null

    fun param(name: String, type: String, description: String, required: Boolean = true) =
        apply { params += ToolParam(name, type, description, required) }

    fun risk(level: RiskLevel) = apply { risk = level }
    fun execute(body: suspend (JsonObject) -> ToolResult) = apply { this.body = body }

    fun build(): Tool {
        val exec = requireNotNull(body) { "tool '$name' needs an execute { } block" }
        val schema = ToolSchema(params)
        val r = risk
        return object : Tool {
            override val name = this@ToolBuilder.name
            override val description = this@ToolBuilder.description
            override val parameters = schema
            override val risk = r
            override suspend fun execute(args: JsonObject) = exec(args)
        }
    }
}
