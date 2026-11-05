package com.patchlens.service;

import com.patchlens.model.PatchSuggestion;
import com.patchlens.model.PatchValidation;
import com.patchlens.model.ReviewFinding;
import com.patchlens.model.ValidationStatus;
import com.patchlens.sandbox.RepositorySnapshot;
import com.patchlens.util.Redactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Orchestrates validation and bounded repair of a candidate patch. Shared by the webhook
 * worker and the offline evaluation harness, so both exercise the identical code path.
 *
 * <pre>
 * attempt 0: policy → fresh snapshot → apply → compile → [static analysis] → tests
 *   repairable failure and budget left → repair model → attempt n (fresh snapshot again)
 * stops at: VALIDATED, a non-repairable outcome (NOT_VALIDATED / VALIDATION_ERROR),
 *           repair-model failure, or MAX_REPAIR_ATTEMPTS repairs.
 * </pre>
 */
@Service
public class PatchPipelineService {

    private static final Logger log = LoggerFactory.getLogger(PatchPipelineService.class);

    private final PatchGenerationService generationService;
    private final PatchValidationService validationService;

    public PatchPipelineService(PatchGenerationService generationService,
                                PatchValidationService validationService) {
        this.generationService = generationService;
        this.validationService = validationService;
    }

    /** All attempts for one finding's patch, in order (index 0 = initial candidate). */
    public record PatchResult(ReviewFinding finding, PatchSuggestion patch, List<PatchValidation> attempts) {
        public ValidationStatus initialStatus() { return attempts.isEmpty() ? ValidationStatus.PENDING : attempts.get(0).getStatus(); }
        public ValidationStatus finalStatus()   { return attempts.isEmpty() ? ValidationStatus.PENDING : attempts.get(attempts.size() - 1).getStatus(); }
        public PatchValidation finalAttempt()   { return attempts.isEmpty() ? null : attempts.get(attempts.size() - 1); }
        public String initialPatchText()        { return attempts.isEmpty() ? patch.getPatchText() : attempts.get(0).getPatchText(); }
    }

    /**
     * Validates {@code candidate} against {@code snapshot}, repairing at most
     * {@link PatchGenerationService#MAX_REPAIR_ATTEMPTS} times. The patch may only modify the
     * finding's own file.
     */
    public PatchResult validateAndRepair(ReviewFinding finding, PatchSuggestion candidate, RepositorySnapshot snapshot) {
        Set<String> allowedFiles = Set.of(finding.getFilePath());
        List<PatchValidation> attempts = new ArrayList<>();
        PatchSuggestion patch = candidate;

        PatchValidation pv = validationService.validate(patch, snapshot, allowedFiles, patch.getRepairAttempts());
        attempts.add(pv);

        while (pv.getStatus() != ValidationStatus.VALIDATED
                && pv.getStatus().isRepairable()
                && patch.getRepairAttempts() < PatchGenerationService.MAX_REPAIR_ATTEMPTS) {
            String feedback = feedback(pv);
            try {
                patch = generationService.repair(finding, patch, feedback);
            } catch (RuntimeException e) {
                log.warn("Repair model failed for finding {}: {}", finding.getId(), Redactor.redact(e.getMessage()));
                break;
            }
            pv = validationService.validate(patch, snapshot, allowedFiles, patch.getRepairAttempts());
            attempts.add(pv);
        }

        log.info("Patch {} for finding {}: {} → {} after {} repair(s)", patch.getId(), finding.getId(),
                attempts.get(0).getStatus(), pv.getStatus(), patch.getRepairAttempts());
        return new PatchResult(finding, patch, List.copyOf(attempts));
    }

    /** Feedback given to the repair model: which step failed and the relevant error output. */
    static String feedback(PatchValidation pv) {
        String stage = pv.getFailureStage() == null ? "UNKNOWN" : pv.getFailureStage().name();
        return "Validation status: " + pv.getStatus() + "\nFailed step: " + stage + "\n"
                + (pv.getFailureReason() == null ? "" : pv.getFailureReason());
    }
}
