package com.ferbotz.billanta.fields

import java.io.File
import kotlin.test.assertNotNull

/**
 * The BE-021 example documents, copied verbatim from the contract folder's `backend/examples/`.
 * The backend generates them from its own code (`npm run docs:examples`), so a test that agrees
 * with them agrees with the server. Refresh them whenever the backend regenerates its examples.
 */
object Be021Fixtures {

    fun file(name: String): File {
        val file = listOf(
            File("src/androidHostTest/resources/be021/$name"),
            File("shared/src/androidHostTest/resources/be021/$name"),
        ).firstOrNull { it.exists() }
        return assertNotNull(file, "fixture be021/$name not found")
    }

    fun text(name: String): String = file(name).readText()

    val forms: FormsDoc by lazy { assertNotNull(FormsDoc.parse(text("forms.json")), "forms.json did not parse") }
}
