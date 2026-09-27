package com.tekyerema

import com.tekyerema.models.ModelFile
import com.tekyerema.models.ModelSpec
import com.tekyerema.models.ModelStatus
import com.tekyerema.models.ModelStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Directive §13: model store logic — checksum verification, versioning, readiness, corruption, not-hosted. */
class ModelStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun sha(bytes: ByteArray): String {
        val f = File.createTempFile("mdl", ".bin"); f.writeBytes(bytes)
        return ModelStore.sha256(f).also { f.delete() }
    }

    private fun spec(hosted: Boolean, files: List<ModelFile>, version: String = "v1") =
        ModelSpec("twi-asr", "t", version, "sherpa", 100, files, hosted)

    @Test fun not_hosted_is_reported_honestly() {
        val store = ModelStore(tmp.newFolder("m1"))
        assertTrue(store.status(spec(false, emptyList())) is ModelStatus.NotHosted)
    }

    @Test fun missing_then_verified_ready_after_files_present() {
        val root = tmp.newFolder("m2"); val store = ModelStore(root)
        val bytes = "encoder-weights".toByteArray()
        val s = spec(true, listOf(ModelFile("enc.onnx", "http://x", sha(bytes), bytes.size.toLong())))
        assertTrue(store.status(s) is ModelStatus.Missing)
        // simulate a completed download
        File(store.dir(s), "enc.onnx").apply { parentFile!!.mkdirs(); writeBytes(bytes) }
        assertEquals(ModelStatus.Ready, store.verify(s))
        assertTrue(store.isReady(s))
    }

    @Test fun corrupt_file_is_detected_and_not_ready() {
        val store = ModelStore(tmp.newFolder("m3"))
        val s = spec(true, listOf(ModelFile("enc.onnx", "http://x", sha("good".toByteArray()), 4)))
        File(store.dir(s), "enc.onnx").apply { parentFile!!.mkdirs(); writeBytes("BAD!".toByteArray()) }
        val v = store.verify(s)
        assertTrue("expected Corrupt, got $v", v is ModelStatus.Corrupt)
        assertTrue(!store.isReady(s))
    }

    @Test fun version_bump_invalidates_old_ready_marker() {
        val root = tmp.newFolder("m4")
        val bytes = "w".toByteArray()
        val v1 = ModelStore(root); val s1 = spec(true, listOf(ModelFile("m.onnx", "u", sha(bytes), 1)), "v1")
        File(v1.dir(s1), "m.onnx").apply { parentFile!!.mkdirs(); writeBytes(bytes) }
        v1.verify(s1); assertTrue(v1.isReady(s1))
        // a new version points at a different dir → not ready until its own files verify
        val s2 = spec(true, listOf(ModelFile("m.onnx", "u", sha(bytes), 1)), "v2")
        assertTrue(v1.status(s2) is ModelStatus.Missing)
    }
}
