package dev.kortex.core.eval

import dev.kortex.core.ambient.Direction
import dev.kortex.core.ambient.Handle
import dev.kortex.core.ambient.HandleType
import dev.kortex.core.ambient.Signal
import dev.kortex.core.ambient.SignalKind
import dev.kortex.core.ambient.SignalSource
import dev.kortex.core.ambient.TriageContext
import dev.kortex.core.ambient.TriageDecision
import dev.kortex.core.pattern.ReflectNode
import dev.kortex.core.state.Message

/**
 * The committed eval sets (T0.3). Fixture responses live in
 * `src/test/resources/eval/<suite>/<caseId>.txt`.
 *
 * Router history (T1.3/T1.5/T4.2): the 4 F3 known-failure follow-up cases were retired —
 * Kortex is a tool-execution agent, not conversational, per the design direction in
 * docs/PROMPT_IMPROVEMENT_PLAN.md §2. The `plan` route, dropped as a dead label in T1.3,
 * was restored in T4.2 with a real PlanNode: `plan_*` cases expect it for multi-sub-goal
 * requests, while the `multi_step_*` single-goal chains stay `tool_task` (boundary
 * documented in docs/eval-baselines.md). The router prompt carries the tool inventory
 * (F6), so requests no registered tool can help with expect `simple_qa`;
 * [RouterEvalSuite] runs with the `defaultTools()` registry to match production.
 */
object EvalSets {

    private fun user(text: String) = Message(Message.Role.USER, text)
    private fun assistant(text: String) = Message(Message.Role.ASSISTANT, text)

    val router: List<RouterEvalCase> = listOf(
        // --- single-turn: simple_qa ---
        RouterEvalCase(
            id = "simple_qa_capital",
            conversation = listOf(user("What is the capital of France?")),
            expectedRoute = "simple_qa",
        ),
        RouterEvalCase(
            id = "simple_qa_definition",
            conversation = listOf(user("Explain what a black hole is in simple terms")),
            expectedRoute = "simple_qa",
        ),
        RouterEvalCase(
            id = "simple_qa_why_sky_blue",
            conversation = listOf(user("Why is the sky blue?")),
            expectedRoute = "simple_qa",
        ),
        // --- single-turn: tool_task (a registered tool directly applies) ---
        RouterEvalCase(
            id = "tool_task_time",
            conversation = listOf(user("What time is it right now?")),
            expectedRoute = "tool_task",
        ),
        RouterEvalCase(
            id = "tool_task_web_search",
            conversation = listOf(user("Search the web for the latest Android 16 release notes")),
            expectedRoute = "tool_task",
        ),
        RouterEvalCase(
            id = "tool_task_calculation",
            conversation = listOf(user("What's 234823 multiplied by 98123?")),
            expectedRoute = "tool_task",
        ),
        // --- plan (T4.2: real PlanNode; multiple distinct sub-goals / cross-topic deps) ---
        RouterEvalCase(
            id = "plan_compare_recommend",
            conversation = listOf(
                user("Compare the iPhone 17 and the Pixel 11 on price, camera quality, and battery life, then recommend one"),
            ),
            expectedRoute = "plan",
        ),
        RouterEvalCase(
            id = "plan_multi_topic",
            conversation = listOf(
                user("Find the weather in Tokyo this weekend, and also get the latest USD to JPY exchange rate"),
            ),
            expectedRoute = "plan",
        ),
        // --- multi-step but single-goal: stays tool_task (T4.2 boundary cases; see
        // docs/eval-baselines.md for where the tool_task/plan line is drawn) ---
        RouterEvalCase(
            id = "multi_step_trip",
            conversation = listOf(
                user("Plan a 3-day trip to Jaipur for me with a budget breakdown and a day-by-day itinerary"),
            ),
            expectedRoute = "tool_task",
        ),
        RouterEvalCase(
            id = "multi_step_research_compare",
            conversation = listOf(
                user("Research the top 5 mid-range phones this year, compare their cameras and battery life, and recommend one"),
            ),
            expectedRoute = "tool_task",
        ),
        // Chains web_search + calculator — multi-step within a single run (T1.5).
        RouterEvalCase(
            id = "tool_task_chained_currency",
            conversation = listOf(
                user("Find the current USD to INR exchange rate and work out how much $2,500 is in rupees"),
            ),
            expectedRoute = "tool_task",
        ),
        // --- tool-awareness (T1.5, finding F6): no registered tool plausibly helps ---
        RouterEvalCase(
            id = "simple_qa_unavailable_lights",
            conversation = listOf(user("Turn on my living room lights")),
            expectedRoute = "simple_qa",
        ),
        // The eval registry (defaultTools) has no messaging tool, so a tool-aware router
        // must NOT pick tool_task here. In-app registries with messaging tools differ.
        RouterEvalCase(
            id = "simple_qa_no_messaging_tool",
            conversation = listOf(user("Send a message to Neha saying I'll be 20 minutes late")),
            expectedRoute = "simple_qa",
        ),
        // --- pleasantries (finding F5: no chitchat route; simple_qa is the cheapest home) ---
        RouterEvalCase(
            id = "followup_thanks",
            conversation = listOf(
                user("What's the capital of Australia?"),
                assistant("Canberra."),
                user("thanks!"),
            ),
            expectedRoute = "simple_qa",
        ),
        RouterEvalCase(
            id = "followup_ok_cool",
            conversation = listOf(
                user("Remind me what time my flight is?"),
                assistant("Your flight to Bengaluru departs at 18:40 from T2."),
                user("ok cool"),
            ),
            expectedRoute = "simple_qa",
        ),
    )

    // ---------------------------------------------------------------------------------

    private fun signal(
        id: String,
        content: String,
        appLabel: String = "WhatsApp",
        appId: String = "com.whatsapp",
        kind: SignalKind = SignalKind.MESSAGE,
        minutesAgo: Long = 5,
    ) = Signal(
        id = id,
        source = SignalSource(appId, appLabel),
        kind = kind,
        direction = Direction.INCOMING,
        senderHandle = Handle(HandleType.PHONE, "+919812345678"),
        content = content,
        timestampMillis = System.currentTimeMillis() - minutesAgo * 60_000,
    )

    val triage: List<TriageEvalCase> = listOf(
        // --- GENERATE_CARD: something to see or act on now ---
        TriageEvalCase(
            id = "card_pickup_request",
            context = TriageContext(
                contactName = "Neha",
                newSignals = listOf(signal("s1", "Just landed! Flight was early. Can you pick me up at 6 from T2?")),
            ),
            expected = TriageDecision.GENERATE_CARD,
        ),
        TriageEvalCase(
            id = "card_rsvp_deadline",
            context = TriageContext(
                contactName = "Rohit",
                newSignals = listOf(signal("s1", "Are you coming to the wedding next Saturday? I need the headcount by tonight")),
            ),
            expected = TriageDecision.GENERATE_CARD,
        ),
        TriageEvalCase(
            id = "card_plan_confirm",
            context = TriageContext(
                contactName = "Aisha",
                newSignals = listOf(signal("s1", "Dinner at 8 at Mamagoto works? Confirm in the next hour so I can book")),
                conversationSummary = "Planning a group dinner this Friday.",
            ),
            expected = TriageDecision.GENERATE_CARD,
        ),
        TriageEvalCase(
            id = "card_payment_due",
            context = TriageContext(
                contactName = "HDFC Bank",
                newSignals = listOf(
                    signal(
                        id = "s1",
                        content = "Your credit card payment of Rs 45,230 is due tomorrow. Pay now to avoid late fees.",
                        appLabel = "Messages", appId = "com.google.android.apps.messaging",
                        kind = SignalKind.NOTIFICATION,
                    )
                ),
            ),
            expected = TriageDecision.GENERATE_CARD,
        ),
        // --- STORE_MEMORY: useful context, nothing to act on ---
        TriageEvalCase(
            id = "memory_new_job",
            context = TriageContext(
                contactName = "Priya",
                newSignals = listOf(signal("s1", "Guess what — I got the job at Infosys!! Start next month :)")),
            ),
            expected = TriageDecision.STORE_MEMORY,
        ),
        TriageEvalCase(
            id = "memory_vegetarian",
            context = TriageContext(
                contactName = "Rahul",
                newSignals = listOf(signal("s1", "btw I've gone vegetarian, so no more butter chicken for me lol")),
            ),
            expected = TriageDecision.STORE_MEMORY,
        ),
        TriageEvalCase(
            id = "memory_moving_city",
            context = TriageContext(
                contactName = "Sameer",
                newSignals = listOf(signal("s1", "we finally signed the lease! moving to Pune in August")),
                recentMemory = listOf("Sameer has been apartment hunting in Pune since May"),
            ),
            expected = TriageDecision.STORE_MEMORY,
        ),
        // --- IGNORE: noise ---
        TriageEvalCase(
            id = "ignore_thumbs_up",
            context = TriageContext(
                contactName = "Rohit",
                newSignals = listOf(signal("s1", "👍")),
            ),
            expected = TriageDecision.IGNORE,
        ),
        TriageEvalCase(
            id = "ignore_ok_cool",
            context = TriageContext(
                contactName = "Aisha",
                newSignals = listOf(signal("s1", "ok"), signal("s2", "cool, see ya", minutesAgo = 4)),
                conversationSummary = "Confirmed meeting for coffee tomorrow at 11.",
            ),
            expected = TriageDecision.IGNORE,
        ),
        TriageEvalCase(
            id = "ignore_promo_spam",
            context = TriageContext(
                contactName = "Zomato",
                newSignals = listOf(
                    signal(
                        id = "s1",
                        content = "Get 50% off on your next 3 orders! Use code YUM50. T&C apply.",
                        appLabel = "Zomato", appId = "com.application.zomato",
                        kind = SignalKind.NOTIFICATION,
                    )
                ),
            ),
            expected = TriageDecision.IGNORE,
        ),
    )

    // ---------------------------------------------------------------------------------

    val memory: List<MemoryEvalCase> = listOf(
        MemoryEvalCase(
            id = "mem_single_fact",
            context = TriageContext(
                contactName = "Priya",
                newSignals = listOf(signal("s1", "I got promoted to Senior Engineer at TCS today!")),
            ),
            expectedCount = 1,
        ),
        MemoryEvalCase(
            id = "mem_multiple_facts",
            context = TriageContext(
                contactName = "Sameer",
                newSignals = listOf(
                    signal("s1", "We're moving to Pune in August."),
                    signal("s2", "Also Diya got into DPS there, she starts in June", minutesAgo = 4),
                ),
            ),
            expectedCount = 2,
        ),
        // Dedup instruction: everything in the activity is already in "What we already know".
        MemoryEvalCase(
            id = "mem_dedup_all_known",
            context = TriageContext(
                contactName = "Rahul",
                newSignals = listOf(signal("s1", "just a reminder, I'm vegetarian now, order accordingly")),
                recentMemory = listOf("Rahul is vegetarian (since June 2026)"),
            ),
            expectedCount = 0,
        ),
        // Pure chit-chat: nothing durable to keep.
        MemoryEvalCase(
            id = "mem_chitchat_nothing",
            context = TriageContext(
                contactName = "Rohit",
                newSignals = listOf(signal("s1", "haha yeah"), signal("s2", "see you tomorrow", minutesAgo = 4)),
            ),
            expectedCount = 0,
        ),
        // Fixture is wrapped in ```json fences — verifies the real fence-stripping parse path.
        MemoryEvalCase(
            id = "mem_code_fenced_response",
            context = TriageContext(
                contactName = "Aisha",
                newSignals = listOf(signal("s1", "my new number is +91 98200 11223, save it")),
            ),
            expectedCount = 1,
        ),
    )

    // ---------------------------------------------------------------------------------

    private fun tool(name: String, args: String, result: String?) =
        ReflectToolStep(name, args, result)

    /**
     * Reflect reviewer cases (T4.4), spanning the T2.2 rubric: (a) factual consistency
     * with tool results, (b) answers what was asked, (c) no explicitly-requested item
     * missing — plus the anti-style-revision rule and the grounding preamble (tool
     * results that postdate the model's training must not be rejected).
     *
     * Every case has ≥2 tool calls (or a hedging answer) so ReflectPolicy never
     * fast-paths it — each case exercises a real reviewer LLM call.
     */
    val reflect: List<ReflectEvalCase> = listOf(
        // --- criterion (a): factual consistency with the tool results ---
        ReflectEvalCase(
            id = "revise_contradicts_tools",
            request = "Who is the CEO of Acme Corp?",
            answer = "The CEO of Acme Corp is John Smith.",
            tools = listOf(
                tool(
                    "web_search", "{\"query\":\"Acme Corp CEO\"}",
                    "Acme Corp announces Jane Doe as chief executive officer, effective March 2026. " +
                        "Doe succeeds retiring CEO Mark Lin after nine years.",
                ),
                tool(
                    "open_url", "{\"url\":\"https://acme.com/leadership\"}",
                    "Leadership — Jane Doe, Chief Executive Officer. Arun Mehta, CFO. Sofia Reyes, CTO.",
                ),
            ),
            expectedVerdict = ReflectNode.REVISE,
        ),
        ReflectEvalCase(
            id = "revise_fabricated_number",
            request = "How much is an adult day pass to the Shedd Aquarium?",
            answer = "An adult day pass to the Shedd Aquarium costs \$89.",
            tools = listOf(
                tool(
                    "web_search", "{\"query\":\"Shedd Aquarium adult day pass price\"}",
                    "Shedd Aquarium tickets: general admission adult day pass \$129; discounts for Chicago residents.",
                ),
                tool(
                    "open_url", "{\"url\":\"https://www.sheddaquarium.org/tickets\"}",
                    "Tickets — Adult (12+): \$129. Child (3–11): \$99. Members free.",
                ),
            ),
            expectedVerdict = ReflectNode.REVISE,
        ),
        // --- anti-style-revision rule: correct but terse must pass ---
        ReflectEvalCase(
            id = "ok_correct_terse",
            request = "Who is the CEO of Acme Corp?",
            answer = "Jane Doe.",
            tools = listOf(
                tool(
                    "web_search", "{\"query\":\"Acme Corp CEO\"}",
                    "Acme Corp announces Jane Doe as chief executive officer, effective March 2026.",
                ),
                tool(
                    "open_url", "{\"url\":\"https://acme.com/leadership\"}",
                    "Leadership — Jane Doe, Chief Executive Officer. Arun Mehta, CFO.",
                ),
            ),
            expectedVerdict = ReflectNode.OK,
        ),
        // --- criterion (c): explicitly requested item missing ---
        ReflectEvalCase(
            id = "revise_missing_requested_item",
            request = "What's the price and battery life of the Pixel 11?",
            answer = "The Pixel 11 is priced at \$799 for the 128 GB model.",
            tools = listOf(
                tool(
                    "web_search", "{\"query\":\"Pixel 11 price\"}",
                    "Google Pixel 11 launches at \$799 (128 GB) and \$899 (256 GB).",
                ),
                tool(
                    "web_search", "{\"query\":\"Pixel 11 battery life\"}",
                    "Pixel 11 battery: 5,000 mAh, rated at 31 hours of typical use in reviews.",
                ),
            ),
            expectedVerdict = ReflectNode.REVISE,
        ),
        // --- criterion (b): doesn't answer what was asked ---
        ReflectEvalCase(
            id = "revise_wrong_question",
            request = "What time does the Apple Store on Fifth Avenue close today?",
            answer = "The Apple Store is located at 767 5th Ave, New York, NY 10153.",
            tools = listOf(
                tool(
                    "web_search", "{\"query\":\"Apple Store Fifth Avenue hours today\"}",
                    "Apple Fifth Avenue — 767 5th Ave, New York, NY 10153. Today's hours: 8:00 AM – 9:00 PM.",
                ),
                tool(
                    "open_url", "{\"url\":\"https://www.apple.com/retail/fifthavenue/\"}",
                    "Apple Fifth Avenue. Address: 767 5th Ave. Hours today: 8:00 AM – 9:00 PM.",
                ),
            ),
            expectedVerdict = ReflectNode.REVISE,
        ),
        // --- consistent multi-tool synthesis passes ---
        ReflectEvalCase(
            id = "ok_synthesized_conversion",
            request = "Find the current USD to INR exchange rate and work out how much \$2,500 is in rupees",
            answer = "At the current rate of 88.40 INR per USD, \$2,500 is about ₹221,000.",
            tools = listOf(
                tool(
                    "web_search", "{\"query\":\"USD to INR exchange rate\"}",
                    "1 USD = 88.40 INR (mid-market rate, updated 5 minutes ago).",
                ),
                tool("calculator", "{\"expression\":\"2500*88.40\"}", "221000.0"),
            ),
            expectedVerdict = ReflectNode.OK,
        ),
        // --- truncated long results (per-result 500-char cap) must not cause a false REVISE;
        // the claims the answer makes appear early in each result, before the cut ---
        ReflectEvalCase(
            id = "ok_truncated_multi_results",
            request = "Summarize today's top tech headlines",
            answer = "Top tech stories today: the EU approved the AI Liability Directive, and TSMC " +
                "broke ground on its second Dresden fab. Both moves are expected to shape " +
                "chip supply and AI regulation across Europe.",
            tools = listOf(
                tool(
                    "web_search", "{\"query\":\"top tech news today\"}",
                    "EU approves AI Liability Directive: the European Parliament voted today to adopt " +
                        "the AI Liability Directive, harmonizing compensation rules for AI-caused harm " +
                        "across member states. The directive complements the AI Act and takes effect in " +
                        "2028 after a two-year transposition period. Rapporteur Ana Kovač called it the " +
                        "final piece of the EU's AI framework. Industry groups warned about compliance " +
                        "costs for small model providers, while consumer organizations welcomed the " +
                        "reversal of the burden of proof for high-risk systems. Legal analysts note the " +
                        "directive's presumption of causality will significantly ease claims. Member " +
                        "states must now map national tort rules onto the new framework, a process " +
                        "Brussels expects to be contentious in states with strict liability regimes.",
                ),
                tool(
                    "open_url", "{\"url\":\"https://news.example.com/tech\"}",
                    "TSMC breaks ground on second Dresden fab: the chipmaker began construction of a " +
                        "second fabrication plant in Dresden today, doubling planned European capacity. " +
                        "The €11 billion facility will produce 16nm and 12nm automotive-grade chips from " +
                        "2029, supported by EU Chips Act subsidies. Saxony's premier called it the " +
                        "largest industrial investment in the state's history. Analysts said the move " +
                        "signals confidence in European automotive demand despite the recent slowdown, " +
                        "and noted that local suppliers have already announced expansion plans around " +
                        "the site. Construction of the first Dresden fab remains on schedule for " +
                        "production later this year, TSMC said in its statement.",
                ),
            ),
            expectedVerdict = ReflectNode.OK,
        ),
        // --- grounding preamble: tool results that postdate training must not be rejected ---
        ReflectEvalCase(
            id = "ok_postdates_training",
            request = "Who won the 2026 FIFA World Cup final?",
            answer = "Argentina won the 2026 FIFA World Cup, beating France 2–1 in the final at " +
                "MetLife Stadium on July 19, 2026.",
            tools = listOf(
                tool(
                    "web_search", "{\"query\":\"2026 FIFA World Cup final winner\"}",
                    "July 19, 2026 — Argentina defeated France 2–1 in the FIFA World Cup final at " +
                        "MetLife Stadium, New Jersey, claiming their fourth title.",
                ),
                tool(
                    "open_url", "{\"url\":\"https://www.fifa.com/worldcup/2026/final\"}",
                    "Final result: Argentina 2–1 France. Venue: MetLife Stadium. Date: 19 July 2026.",
                ),
            ),
            expectedVerdict = ReflectNode.OK,
        ),
        // --- unmatched tool call renders "(no result recorded)"; the answer is grounded
        // in the OTHER result, so the missing one must not sink it ---
        ReflectEvalCase(
            id = "ok_unmatched_tool_call",
            request = "What's the weather in Tokyo right now?",
            answer = "It's currently 31°C and partly cloudy in Tokyo, with high humidity.",
            tools = listOf(
                tool("get_time", "{\"timezone\":\"Asia/Tokyo\"}", null),
                tool(
                    "web_search", "{\"query\":\"Tokyo weather now\"}",
                    "Tokyo current conditions: 31°C, partly cloudy, humidity 78%.",
                ),
            ),
            expectedVerdict = ReflectNode.OK,
        ),
        // --- honest empty-handed answer, consistent with what the tools returned, passes;
        // (the hedging wording is also what forces ReflectPolicy to review this one) ---
        ReflectEvalCase(
            id = "ok_honest_failure",
            request = "Find the release date for the Framework tablet",
            answer = "I couldn't find any announced Framework tablet — Framework's current lineup " +
                "is the Laptop 13, Laptop 16, and Desktop, with no tablet release date published.",
            tools = listOf(
                tool(
                    "web_search", "{\"query\":\"Framework tablet release date\"}",
                    "No results found for \"Framework tablet release date\".",
                ),
                tool(
                    "web_search", "{\"query\":\"Framework new products 2026\"}",
                    "Framework's product lineup: Framework Laptop 13, Framework Laptop 16, and the " +
                        "Framework Desktop. No tablet has been announced.",
                ),
            ),
            expectedVerdict = ReflectNode.OK,
        ),
    )
}
