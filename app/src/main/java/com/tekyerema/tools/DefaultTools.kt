package com.tekyerema.tools

/**
 * The model-facing vocabulary (plan §7): a small set of universal PRIMITIVES, one decision tool,
 * and the few native accelerators that pass the five-question test (plan §14). Nothing here is a
 * user command or an app recipe; new goals and new apps must not require new entries.
 */
fun defaultTools(): List<Tool> = listOf(
    // Perception
    ReadStateTool,
    WaitTool,
    // Universal interaction primitives — the ONLY way the operator touches the screen
    TapTool,
    LongPressTool,
    SwipeTool,
    DragTool,
    PinchTool,
    ScrollTool,
    TypeTool,
    SetRangeTool,
    KeyEventTool,
    OpenAppTool,
    // Honest stop (a decision, intercepted by the engine — never executed)
    CannotDoTool,
    // D-02 tool search (intercepted by the engine): loads tools the current step's subset left out
    FindTool,
    // Native accelerators (justified case-by-case in the plan's §14 table)
    ReadNotificationsTool,
    SearchContactsTool,
    // Grounding accelerators: candidates + evidence for contact/message resolution (never guess).
    FindContactTool,
    SearchMessagesTool,
    SetAlarmTool,
    SetTimerTool,
    // Direct system-control accelerators: absolute + incremental volume via AudioManager
    // (fast semantic path; General Operator UI remains the fallback on miss/failure).
    SetVolumeTool,
    VolumeUpTool,
    VolumeDownTool,
    RunUssdTool,
    MakeCallTool,
    ReplyToNotificationTool,
    // §A.2 reopened offline intents
    PlayMusicTool,
    OpenSettingTool,
    MobileMoneyTool,
    // Deterministic *170# send-money driver (reads each screen, stops at the PIN). Owner-requested for the
    // demo; MUST be live-tested with a tiny amount before trusting it — never enters the PIN.
    MoMoSendTool
)
