package com.tekyerema

import com.tekyerema.memory.MemCmd
import com.tekyerema.memory.MemKind
import com.tekyerema.memory.MemoryParse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryParseTest {
    private fun c(s: String) = MemoryParse.classify(s)

    @Test fun whatDoYouRememberAboutMe() {
        for (s in listOf("What do you remember about me?", "what do you know about me", "what have you remembered about me",
            "what do you remember", "Show me my memory", "what's in your memory"))
            assertEquals(s, MemCmd.Summary, c(s))
    }

    @Test fun whatDoYouRememberAboutAName() {
        assertEquals(MemCmd.About("Ama"), c("What do you remember about Ama?"))
        assertEquals(MemCmd.About("mother"), c("what do you know about my mother"))
        assertNull(c("what do you know about you"))
    }

    @Test fun usualTasks() {
        assertEquals(MemCmd.Usual, c("what do I usually ask you"))
        assertEquals(MemCmd.Usual, c("What are my usual tasks?"))
    }

    @Test fun forgetEverythingIsItsOwnCommand() {
        for (s in listOf("forget everything", "Forget everything about me.", "forget everything you remember about me", "clear my memory", "wipe your memory"))
            assertEquals(s, MemCmd.ForgetAll, c(s))
    }

    @Test fun forgetACategory() {
        assertEquals(MemCmd.ForgetKind(MemKind.CONTACT), c("forget my contacts history"))
        assertEquals(MemCmd.ForgetKind(MemKind.CONTACT), c("forget who I call most"))
        assertEquals(MemCmd.ForgetKind(MemKind.TASK), c("forget what I usually ask"))
        assertEquals(MemCmd.ForgetKind(MemKind.PREF), c("forget my preferences"))
        assertEquals(MemCmd.ForgetKind(MemKind.FACT), c("forget the things I told you"))
    }

    @Test fun forgetAPersonButNotTheOldForgetPhrases() {
        assertEquals(MemCmd.ForgetAbout("Ama"), c("forget Ama"))
        assertEquals(MemCmd.ForgetAbout("Ama Mensah"), c("forget everything about Ama Mensah"))
        assertEquals(MemCmd.ForgetAbout("Ama"), c("forget Ama's messages"))
        assertNull(c("forget that"))
        assertNull(c("forget it"))
        assertNull(c("forget the choice"))
        assertNull(c("forget my choices"))
    }

    @Test fun stopAndStartRemembering() {
        assertEquals(MemCmd.Switch(MemKind.CONTACT, false), c("stop remembering my contacts"))
        assertEquals(MemCmd.Switch(MemKind.TASK, false), c("stop remembering my tasks"))
        assertEquals(MemCmd.Switch(MemKind.PREF, false), c("stop keeping my preferences"))
        assertEquals(MemCmd.Switch(null, false), c("stop remembering anything"))
        assertEquals(MemCmd.Switch(MemKind.CONTACT, true), c("start remembering my contacts"))
    }

    @Test fun rememberAPreference() {
        assertEquals(MemCmd.SetPref("rate", "slow"), c("remember that I prefer slow speech"))
        assertEquals(MemCmd.SetPref("rate", "slow"), c("remember that I like you to speak slowly"))
        assertEquals(MemCmd.SetPref("rate", "fast"), c("remember I want faster reading"))
        assertEquals(MemCmd.SetPref("language", "twi"), c("remember that I prefer Twi"))
        assertEquals(MemCmd.SetPref("language:messages", "twi"), c("remember that I prefer Twi for messages"))
        assertEquals(MemCmd.SetPref("verbosity", "brief"), c("remember that I like short answers"))
    }

    @Test fun rememberAFact() {
        assertEquals(MemCmd.SetFact("my church", "Holy Trinity"), c("remember that my church is Holy Trinity"))
        assertEquals(MemCmd.SetFact("i live in", "Accra"), c("Remember that I live in Accra."))
        assertEquals(MemCmd.SetFact("i am from", "Kumasi"), c("remember that I am from Kumasi"))
    }

    @Test fun relationshipsStayWithTheContactCheckedPath() {
        assertNull(c("remember that Ama Mensah is my mother"))
        assertNull(c("remember that my boss is Kofi"))
        assertNull(c("remember that my wife is Ama"))
    }

    @Test fun otherRememberRequestsAreNotOurs() {
        assertNull(c("remember to call Ama at five"))
        assertNull(c("remember that the meeting is at nine"))
    }

    @Test fun aSecretIsParsedButTheStoreRefusesIt() {
        assertEquals(MemCmd.SetFact("my pin", "one two three four"), c("remember that my pin is one two three four"))
        assertTrue(!com.tekyerema.memory.MemoryCore.check(MemKind.FACT, "my pin", "one two three four"))
    }

    @Test fun correcting() {
        assertEquals(MemCmd.Correct("Kumasi", false), c("that's wrong, it is Kumasi"))
        assertEquals(MemCmd.Correct("St Peter's", false), c("No, that is wrong. It's St Peter's"))
        assertEquals(MemCmd.Correct("forget it", false), c("that's wrong, forget it"))
        assertEquals(MemCmd.Correct("Kumasi", true), c("no, it is Kumasi"))
    }

    @Test fun moreOnlyAsAWord() {
        assertEquals(MemCmd.More, c("more"))
        assertEquals(MemCmd.More, c("Yes please"))
        assertNull(c("more music please"))
    }

    @Test fun compoundOrLongRequestsAreNotMemoryCommands() {
        assertNull(c("what do you remember about me and call Ama"))
    }

    @Test fun answerToTheEraseQuestion() {
        assertEquals(MemoryParse.Wipe.YES, MemoryParse.answerToWipe("yes"))
        assertEquals(MemoryParse.Wipe.YES, MemoryParse.answerToWipe("Yes, forget everything"))
        assertEquals(MemoryParse.Wipe.YES, MemoryParse.answerToWipe("yes please"))
        assertEquals(MemoryParse.Wipe.NO, MemoryParse.answerToWipe("no"))
        assertEquals(MemoryParse.Wipe.NO, MemoryParse.answerToWipe("no, keep it"))
        assertEquals(MemoryParse.Wipe.OTHER, MemoryParse.answerToWipe("okay"))
        assertEquals(MemoryParse.Wipe.OTHER, MemoryParse.answerToWipe("what time is it"))
    }

    @Test fun factsAndPreferencesParseDirectly() {
        assertEquals(MemoryParse.PrefReq("rate", "normal"), MemoryParse.parsePref("I prefer normal speed"))
        assertNull(MemoryParse.parsePref("I prefer rice"))
        assertEquals(MemoryParse.FactReq("my church", "Holy Trinity"), MemoryParse.parseFact("my church is Holy Trinity"))
        assertNull(MemoryParse.parseFact("my mother is Ama"))   // a relation word: Relations owns it
        assertTrue(MemoryParse.parseFact("I like football") != null)
    }
}
