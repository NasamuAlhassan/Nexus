package com.tekyerema.tools

/**
 * The model-facing vocabulary (plan §7): a small set of universal PRIMITIVES, one decision tool,
 * and the few native accelerators that pass the five-question test (plan §14). Nothing here is a
 * user command or an app recipe; new goals and new apps must not require new entries.
 */
fun defaultTools(): List<Tool> = listOf(
    // Perception
    ReadStateTool,
    ReadScreenTool,       // F-01: the screen read out word for word, offline
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
    // C-13 ask the user and wait inside the task (intercepted by the engine)
    AskUserTool,
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
    NotificationActionTool,   // E-17 dismiss / press a notification button
    // W4 deterministic phone actions (loaded per step only when the request needs them — D-02)
    SendSmsTool,          // E-03
    CallControlTool,      // E-02 answer / end
    CallLogTool,          // E-28
    ReadSmsTool,          // E-29
    CalendarTool,         // E-06
    CreateContactTool,    // E-07
    LocationTool,         // E-13
    ShowAlarmsTool,       // E-05
    ClipboardTool,        // E-19
    ShareTextTool,        // E-20
    OpenIntentTool,       // D-14 / E-04 intent + deep-link templates
    EmergencyTool,        // L-04 call + text the emergency contact (confirmed)
    // W7 GUI primitives beyond tap: node actions, find-by-scrolling, waiting, whole-page reading
    NodeActionTool,       // G-01 / G-02 / G-03
    ScrollToFindTool,     // G-18
    WaitForElementTool,   // G-18
    ReadAllTool,          // F-15
    WebSearchTool,        // I-03 current facts with sources (owner-approved web search)
    // §A.2 reopened offline intents
    PlayMusicTool,
    OpenSettingTool,
    MobileMoneyTool,
    // Deterministic *170# send-money driver (reads each screen, stops at the PIN). Owner-requested for the
    // demo; MUST be live-tested with a tiny amount before trusting it — never enters the PIN.
    MoMoSendTool
)
