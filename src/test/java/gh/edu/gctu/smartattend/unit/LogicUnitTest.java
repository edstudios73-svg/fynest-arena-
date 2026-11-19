package gh.edu.gctu.smartattend.unit;

import gh.edu.gctu.smartattend.attendance.PresenceServiceAccess;
import gh.edu.gctu.smartattend.face.LivenessEvaluator;
import gh.edu.gctu.smartattend.reports.ReportRendererAccess;
import gh.edu.gctu.smartattend.security.Crypto;
import gh.edu.gctu.smartattend.student.StudentDirectory;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class LogicUnitTest {

    @Test
    void livenessAcceptsCorrectSequenceInEitherCameraOrientation() {
        var steps = List.of("CENTER", "LEFT", "RIGHT", "CENTER");
        assertTrue(LivenessEvaluator.evaluate(steps, List.of(0.02, 0.33, -0.31, -0.03)).passed());
        assertTrue(LivenessEvaluator.evaluate(steps, List.of(0.02, -0.33, 0.31, -0.03)).passed(), "mirrored stream");
    }

    @Test
    void livenessRejectsFlatOrWrongSequences() {
        var steps = List.of("CENTER", "LEFT", "RIGHT", "CENTER");
        assertFalse(LivenessEvaluator.evaluate(steps, List.of(0.0, 0.01, -0.01, 0.0)).passed(), "static photo");
        assertFalse(LivenessEvaluator.evaluate(steps, List.of(0.0, 0.33, 0.31, 0.0)).passed(), "same direction twice");
        assertFalse(LivenessEvaluator.evaluate(steps, List.of(0.3, 0.33, -0.31, 0.0)).passed(), "not facing camera at start");
        assertFalse(LivenessEvaluator.evaluate(steps, List.of(0.0, 0.33, -0.31)).passed(), "missing frame");
    }

    @Test
    void otpIsSixDigitsAndWellDistributed() {
        Set<String> seen = new HashSet<>();
        int[] firstDigit = new int[10];
        for (int i = 0; i < 20000; i++) {
            String o = Crypto.sixDigitCode();
            assertTrue(o.matches("\\d{6}"));
            seen.add(o);
            firstDigit[o.charAt(0) - '0']++;
        }
        assertTrue(seen.size() > 19000, "codes should rarely repeat");
        for (int c : firstDigit) assertTrue(c > 1700 && c < 2300, "leading digit roughly uniform: " + Arrays.toString(firstDigit));
    }

    @Test
    void aesGcmDetectsTamperingAndWrongContext() {
        byte[] key = Crypto.randomBytes(32);
        var sealed = Crypto.aesGcmEncrypt(key, "template".getBytes(), "student-A".getBytes());
        assertArrayEquals("template".getBytes(), Crypto.aesGcmDecrypt(key, sealed.iv(), sealed.ciphertext(), "student-A".getBytes()));
        assertThrows(IllegalStateException.class, () -> Crypto.aesGcmDecrypt(key, sealed.iv(), sealed.ciphertext(), "student-B".getBytes()));
        sealed.ciphertext()[0] ^= 1;
        assertThrows(IllegalStateException.class, () -> Crypto.aesGcmDecrypt(key, sealed.iv(), sealed.ciphertext(), "student-A".getBytes()));
    }

    @Test
    void cidrAndDistance() {
        assertTrue(PresenceServiceAccess.inCidr("10.99.4.20", "10.99.0.0/16"));
        assertFalse(PresenceServiceAccess.inCidr("10.98.4.20", "10.99.0.0/16"));
        assertTrue(PresenceServiceAccess.inCidr("196.1.2.3", "196.1.2.3"));
        assertFalse(PresenceServiceAccess.inCidr("::1", "10.0.0.0/8"));
        double d = PresenceServiceAccess.haversine(5.5600, -0.2000, 5.5609, -0.2000);
        assertEquals(100, d, 2);
    }

    @Test
    void spreadsheetFormulaInjectionIsNeutralised() {
        assertEquals("'=HYPERLINK(\"x\")", ReportRendererAccess.safe("=HYPERLINK(\"x\")"));
        assertEquals("'@SUM(A1)", ReportRendererAccess.safe("@SUM(A1)"));
        assertEquals("-12.5", ReportRendererAccess.safe("-12.5"));
        assertEquals("Mensah", ReportRendererAccess.safe("Mensah"));
    }

    @Test
    void indexNormalisationAndNamesPreserveSpelling() {
        assertEquals("ABC/123", StudentDirectory.normaliseIndex("  abc/ 123 "));
        Map<String, Object> s = new HashMap<>();
        s.put("firstName", "Kwame");
        s.put("otherNames", "  Nana ");
        s.put("lastName", "Asante-Boakye");
        assertEquals("Kwame Nana Asante-Boakye", StudentDirectory.fullName(s));
    }
}
