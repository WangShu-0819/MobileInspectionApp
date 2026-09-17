package com.wearable.inspection.mobile.ui.screens;

import android.graphics.Bitmap;

import com.wearable.inspection.mobile.data.image.MobileImageStore;
import com.wearable.inspection.mobile.data.repository.InspectionRepository;

import org.mockito.Mockito;
import org.mockito.stubbing.Answer;
import org.mockito.verification.VerificationMode;

/**
 * Java helpers to stub/verify Mockito mocks for use from Kotlin.
 *
 * Kotlin's null-safety inserts {@code Intrinsics.checkNotNull} at every call site
 * where a non-null parameter receives a value.  Mockito's matchers ({@code any()},
 * {@code isA()}, …) return {@code null} at runtime, which triggers the NPE before
 * Mockito can intercept the call.  Calling these helpers from Java avoids the check.
 *
 * For suspend functions on a final class (InspectionRepository), the inline mock
 * maker handles the compiler-inserted Continuation parameter internally — we pass
 * {@code null} for it to satisfy the Java compiler's arity check.
 *
 * JVM signatures (javap):
 *   replaceViewRoiConfirmsForPhoto(String, long, List, Continuation)  → 3 visible matchers
 *   getViewRoiConfirmsByPhoto(String, long, Continuation)             → 2 visible matchers
 *   saveRoiEvidence(Bitmap, String)                                   → 2 matchers
 *   deleteRoiEvidence(String)                                         → 1 matcher
 */
public final class MockitoSuspendStubber {

    private MockitoSuspendStubber() {}

    // ── InspectionRepository suspend stubs ──────────────────────────────

    /**
     * Stub InspectionRepository.replaceViewRoiConfirmsForPhoto (suspend).
     * Kotlin-visible params: String, long, List  → 3 matchers + null Continuation.
     */
    @SuppressWarnings("unchecked")
    public static void stubReplace(InspectionRepository repo, Answer<?> answer) {
        Mockito.doAnswer(answer)
                .when(repo)
                .replaceViewRoiConfirmsForPhoto(
                        Mockito.anyString(),
                        Mockito.anyLong(),
                        Mockito.anyList(),
                        null   // Continuation — handled internally by inline mock maker
                );
    }

    /**
     * Stub InspectionRepository.getViewRoiConfirmsByPhoto (suspend).
     * Kotlin-visible params: String, long  → 2 matchers + null Continuation.
     */
    @SuppressWarnings("unchecked")
    public static void stubGetConfirms(InspectionRepository repo, Answer<?> answer) {
        Mockito.doAnswer(answer)
                .when(repo)
                .getViewRoiConfirmsByPhoto(
                        Mockito.anyString(),
                        Mockito.anyLong(),
                        null   // Continuation — handled internally by inline mock maker
                );
    }

    /**
     * Verify InspectionRepository.replaceViewRoiConfirmsForPhoto was called.
     * Kotlin-visible params: eq(batchId), eq(photoId), anyList()  → 3 matchers + null Continuation.
     */
    @SuppressWarnings("unchecked")
    public static void verifyReplaceCalled(
            InspectionRepository repo,
            VerificationMode mode,
            String batchId,
            long photoId
    ) {
        Mockito.verify(repo, mode)
                .replaceViewRoiConfirmsForPhoto(
                        Mockito.eq(batchId),
                        Mockito.eq(photoId),
                        Mockito.anyList(),
                        null   // Continuation — handled internally by inline mock maker
                );
    }

    // ── MobileImageStore stubs (avoid Kotlin null-safety NPE with matchers) ──

    /**
     * Stub MobileImageStore.saveRoiEvidence(Bitmap, String) for any Bitmap.
     * Uses {@code isA(Bitmap.class)} + {@code anyString()} from Java so Kotlin's
     * null check never fires.
     */
    @SuppressWarnings("unchecked")
    public static void stubSaveRoiEvidence(MobileImageStore store, Answer<?> answer) {
        Mockito.doAnswer(answer)
                .when(store)
                .saveRoiEvidence(
                        (Bitmap) Mockito.any(),
                        Mockito.anyString()
                );
    }

    /**
     * Stub MobileImageStore.deleteRoiEvidence(String).
     */
    @SuppressWarnings("unchecked")
    public static void stubDeleteRoiEvidence(MobileImageStore store, Answer<?> answer) {
        Mockito.doAnswer(answer)
                .when(store)
                .deleteRoiEvidence(Mockito.anyString());
    }
}
