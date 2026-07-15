package dev.kortex.core.eval

import dev.kortex.core.ambient.Direction
import dev.kortex.core.ambient.Handle
import dev.kortex.core.ambient.HandleType
import dev.kortex.core.ambient.Signal
import dev.kortex.core.ambient.SignalKind
import dev.kortex.core.ambient.SignalSource
import dev.kortex.core.ambient.TriageContext
import dev.kortex.core.ambient.TriageDecision
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
}
