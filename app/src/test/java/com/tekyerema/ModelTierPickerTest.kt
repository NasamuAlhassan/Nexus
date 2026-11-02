package com.tekyerema

import com.tekyerema.agent.ModelTierPicker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Device tiers for the on-phone planner (MASTER_STATUS #30): RAM size x installed files -> which file, or none. */
class ModelTierPickerTest {
    private val all = setOf("qwen3.5-4b.litertlm", "qwen3.5-2b.litertlm", "granite-4.0-1b.litertlm")

    private fun pick(total: Long, free: Long, files: Set<String> = all) = ModelTierPicker.pick(total, free, files)

    @Test fun an_8gb_phone_gets_the_4b() {
        assertEquals("qwen3.5-4b.litertlm", pick(7_500, 4_500).file)
        assertEquals("qwen3.5-4b-v2", pick(7_500, 4_500).tierId)
    }

    @Test fun a_4_to_6gb_phone_gets_the_2b() {
        assertEquals("qwen3.5-2b.litertlm", pick(3_900, 2_400).file)
        assertEquals("qwen3.5-2b.litertlm", pick(5_600, 3_000).file)
    }

    @Test fun a_3gb_phone_gets_granite() {
        assertEquals("granite-4.0-1b.litertlm", pick(3_000, 1_500).file)
        assertEquals("granite-4.0-1b.litertlm", pick(3_500, 1_500).file)
    }

    @Test fun below_the_floor_gets_no_model_and_says_why() {
        val p = pick(2_700, 1_500)
        assertNull(p.file)
        assertFalse(p.loads)
        assertTrue(p.reason, p.reason.contains("no installed model fits"))
    }

    @Test fun low_free_ram_steps_down_one_tier() {
        assertEquals("qwen3.5-2b.litertlm", pick(7_500, 2_500).file)
        assertEquals("granite-4.0-1b.litertlm", pick(7_500, 1_300).file)
        assertNull(pick(7_500, 800).file)
    }

    @Test fun a_missing_bigger_file_falls_to_the_next_installed() {
        assertEquals("qwen3.5-2b.litertlm", pick(7_500, 5_000, setOf("qwen3.5-2b.litertlm", "granite-4.0-1b.litertlm")).file)
        assertEquals("granite-4.0-1b.litertlm", pick(7_500, 5_000, setOf("granite-4.0-1b.litertlm")).file)
    }

    @Test fun a_big_file_on_a_small_phone_is_never_loaded() {
        val p = pick(3_000, 1_500, setOf("qwen3.5-4b.litertlm"))
        assertNull(p.file)
        assertTrue(p.reason, p.reason.contains("qwen3.5-4b-v2"))
    }

    @Test fun no_file_installed_means_no_model_and_no_change() {
        assertNull(pick(8_000, 5_000, emptySet()).file)
        assertEquals("no model file installed", pick(8_000, 5_000, emptySet()).reason)
        assertNull(pick(8_000, 5_000, setOf("readme.txt")).file)
    }

    @Test fun the_old_single_file_still_loads_above_its_ram_floor() {
        val only = setOf("model.litertlm")
        assertEquals("model.litertlm", pick(3_000, 1_500, only).file)
        assertEquals("model.litertlm", pick(7_500, 4_500, only).file)
        assertNull(pick(2_400, 1_500, only).file)
    }

    @Test fun a_tier_file_wins_over_the_old_file() {
        assertEquals("granite-4.0-1b.litertlm", pick(3_000, 1_500, setOf("model.litertlm", "granite-4.0-1b.litertlm")).file)
    }

    @Test fun the_old_file_is_the_last_resort_when_tiers_do_not_fit() {
        // Qwen 4B present on a 3 GB phone: too big, but the legacy file (some small model) may still load.
        assertEquals("model.litertlm", pick(3_000, 1_500, setOf("qwen3.5-4b.litertlm", "model.litertlm")).file)
    }

    @Test fun unknown_ram_never_blocks_and_picks_the_smallest_installed() {
        assertEquals("granite-4.0-1b.litertlm", pick(0, 0).file)
        assertEquals("qwen3.5-2b.litertlm", pick(0, 0, setOf("qwen3.5-4b.litertlm", "qwen3.5-2b.litertlm")).file)
        assertEquals("model.litertlm", pick(0, 0, setOf("model.litertlm")).file)
    }

    @Test fun unknown_free_ram_is_ignored() {
        assertEquals("qwen3.5-4b.litertlm", pick(7_500, 0).file)
    }

    @Test fun a_custom_table_is_honoured() {
        val cfg = ModelTierPicker.Config(listOf(ModelTierPicker.Tier("x", "x.litertlm", 5_000, 0)), "model.litertlm", 2_500)
        assertEquals("x.litertlm", ModelTierPicker.pick(5_000, 0, setOf("x.litertlm"), cfg).file)
        assertNull(ModelTierPicker.pick(4_999, 0, setOf("x.litertlm"), cfg).file)
    }

    @Test fun the_log_line_carries_the_tag_and_the_choice() {
        val p = pick(3_000, 1_500)
        val line = ModelTierPicker.logLine(3_000, 1_500, all, p)
        assertTrue(line, line.startsWith("[TIER] "))
        assertTrue(line, line.contains("granite-4.0-1b.litertlm"))
        assertTrue(ModelTierPicker.logLine(2_000, 900, all, pick(2_000, 900)).contains("→ none"))
    }
}
