package com.ferbotz.billanta.media

import com.ferbotz.billanta.core.AppResult
import com.ferbotz.billanta.core.asSuccess
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The guard in front of `POST /media`. A photo can easily be 12 MB, and the server rejects anything
 * over 8 — so refusing locally saves a slow upload that was always going to fail on a phone
 * connection.
 */
class ImagePickerTest {

    private fun image(bytes: Int, type: String = "image/jpeg") =
        PickedImage("sig.jpg", type, ByteArray(bytes))

    @Test
    fun a_reasonable_photo_is_accepted() {
        assertNull(ImagePickerCoordinator.rejectionReason(image(2 * 1024 * 1024)))
        assertNull(ImagePickerCoordinator.rejectionReason(image(1, "image/png")))
        assertNull(ImagePickerCoordinator.rejectionReason(image(1, "image/webp")))
    }

    @Test
    fun an_oversized_photo_is_refused_before_the_upload() {
        val reason = assertNotNull(ImagePickerCoordinator.rejectionReason(image(9 * 1024 * 1024)))
        assertTrue(reason.contains("8 MB"), "the message should say the limit: $reason")
        assertTrue(reason.contains("9 MB"), "and what was actually chosen: $reason")
    }

    @Test
    fun the_boundary_is_inclusive() {
        assertNull(
            ImagePickerCoordinator.rejectionReason(image(ImagePickerCoordinator.MAX_BYTES)),
            "exactly 8 MB is within the limit",
        )
        assertNotNull(ImagePickerCoordinator.rejectionReason(image(ImagePickerCoordinator.MAX_BYTES + 1)))
    }

    @Test
    fun an_empty_file_is_refused() {
        assertNotNull(ImagePickerCoordinator.rejectionReason(image(0)))
    }

    @Test
    fun a_non_image_is_refused_but_an_unfamiliar_image_type_is_not() {
        assertNotNull(ImagePickerCoordinator.rejectionReason(image(100, "application/pdf")))
        // The picker's reported type is not always accurate and the server is the authority, so an
        // image subtype this build has not heard of still goes through.
        assertNull(ImagePickerCoordinator.rejectionReason(image(100, "image/avif")))
    }

    @Test
    fun a_platform_with_no_picker_reports_it_rather_than_failing_silently() = runTest {
        val result = ImagePickerCoordinator().pick()
        assertTrue(result is AppResult.Failure, "an unwired platform should report a failure")
        assertTrue(
            result.error.userMessage().contains("isn't available"),
            "the message should say the feature is missing: ${result.error.userMessage()}",
        )
    }

    /** Backing out is a decision, not an error — the UI must not show a message for it. */
    @Test
    fun backing_out_of_the_picker_is_a_success_with_nothing_in_it() = runTest {
        val coordinator = ImagePickerCoordinator().apply { picker = ImagePicker { null.asSuccess() } }
        val result = coordinator.pick()
        assertTrue(result is AppResult.Success, "cancelling is not a failure")
        assertNull(result.value)
    }

    @Test
    fun a_chosen_image_arrives_intact() = runTest {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val coordinator = ImagePickerCoordinator().apply {
            picker = ImagePicker { PickedImage("sign.png", "image/png", bytes).asSuccess() }
        }
        val picked = assertNotNull((coordinator.pick() as AppResult.Success).value)
        assertEquals("sign.png", picked.fileName)
        assertEquals("image/png", picked.contentType)
        assertEquals(4, picked.bytes.size)
    }
}
