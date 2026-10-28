package com.tekyerema

import com.tekyerema.agent.FastIntent
import com.tekyerema.agent.FastIntentClassifier
import com.tekyerema.operator.Capabilities
import com.tekyerema.operator.Capability
import com.tekyerema.operator.OpCommand
import com.tekyerema.operator.OperatorCommands
import com.tekyerema.policy.CapabilityScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServiceSwitchTest {
    @Test fun servicesAreSwitchedOffAndOnByVoice() {
        assertEquals(OpCommand.Revoke(Capability.CONTACTS), OperatorCommands.classify("Turn off contact access"))
        assertEquals(OpCommand.Revoke(Capability.CONTACTS), OperatorCommands.classify("switch my contacts off"))
        assertEquals(OpCommand.Restore(Capability.CONTACTS), OperatorCommands.classify("turn on contact access"))
        assertEquals(OpCommand.Revoke(Capability.NOTIFICATIONS), OperatorCommands.classify("stop reading my notifications"))
        assertEquals(OpCommand.Restore(Capability.NOTIFICATIONS), OperatorCommands.classify("turn on notification reading"))
        assertEquals(OpCommand.Revoke(Capability.LOCATION), OperatorCommands.classify("turn off location"))
        assertEquals(OpCommand.Restore(Capability.LOCATION), OperatorCommands.classify("turn location back on"))
        assertEquals(OpCommand.Revoke(Capability.TEXTS), OperatorCommands.classify("don't read my text messages"))
        assertEquals(OpCommand.Revoke(Capability.CAMERA), OperatorCommands.classify("disable the camera"))
        assertNull(OperatorCommands.classify("turn on wifi"))
        assertNull(OperatorCommands.classify("call my contact Ama"))
    }

    @Test fun eachServiceCoversItsActions() {
        assertEquals(Capability.CONTACTS, Capabilities.of("search_contacts", CapabilityScope.COMMS_READ, changesPhone = false))
        assertEquals(Capability.NOTIFICATIONS, Capabilities.of("read_notifications", CapabilityScope.COMMS_READ, changesPhone = false))
        assertEquals(Capability.TEXTS, Capabilities.of("read_sms", CapabilityScope.COMMS_READ, changesPhone = false))
        assertEquals(Capability.LOCATION, Capabilities.of("location", CapabilityScope.PERCEIVE, changesPhone = false))
        assertEquals(Capability.NOTIFICATIONS, Capabilities.ofFast(FastIntent.Notifications))
        assertEquals(Capability.LOCATION, Capabilities.ofFast(FastIntent.WhereAmI))
        assertNull(Capabilities.of("emergency", CapabilityScope.COMMS_SEND, changesPhone = true))   // emergency help is never switched off
    }

    @Test fun theLanguageIsChangedInEitherDirection() {
        assertEquals(FastIntent.Preference("language", "twi"), FastIntentClassifier.classify("change the language to Twi"))
        assertEquals(FastIntent.Preference("language", "english"), FastIntentClassifier.classify("set your language to English"))
        assertEquals(FastIntent.Preference("language", "twi"), FastIntentClassifier.classify("switch to Twi"))
        assertEquals(FastIntent.Preference("language", "english"), FastIntentClassifier.classify("kasa Borɔfo"))
    }
}
