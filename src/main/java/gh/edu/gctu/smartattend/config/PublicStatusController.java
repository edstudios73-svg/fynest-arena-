package gh.edu.gctu.smartattend.config;

import gh.edu.gctu.smartattend.SmartAttendApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class PublicStatusController {
    @GetMapping("/api/public/status")
    public Map<String, Object> status() {
        return Map.of("previewMode", SmartAttendApplication.previewMode);
    }
}
