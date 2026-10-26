package com.tekyerema.money

/**
 * MTN MoMo's own SERVICES — Yello Save, savings, susu — are named like a person to the ear ("send 5 cedis to my yello
 * save", heard "yellow save") and the phone searched the contacts for one (owner, 26 Oct 2026). They are rows on the MoMo
 * menu, so the request is a menu walk (`mobile_money` `_kind menu_goal`, which stops at the PIN), never a send to a contact.
 *
 * One definition for both places that must agree: the local money parser steps aside for a name matching [NAME]
 * ([MoneyAsk]), and the planner is told what it is ([RULE], added to requests naming one — agent/BrainPrompts.goalRules).
 * The recogniser's spellings are covered by the pattern (yello / yellow / yelo / yolo + save / safe / saving).
 */
object MoneyServices {
    val NAME = Regex("(?i)\\by[eo]ll?[eo]w?\\s?(?:sa[vf]e|sa[vf]ings?)\\b|\\bsusu\\b|\\bsavings?\\b")

    const val RULE = "MoMo SERVICES: Yello Save, savings and susu are services on MTN's own MoMo menu, not people. For money to or from " +
        "them call mobile_money with _kind menu_goal (_goal in the user's words, _amount if said, _root *170#) — never momo_send and " +
        "never a contact search."
}
