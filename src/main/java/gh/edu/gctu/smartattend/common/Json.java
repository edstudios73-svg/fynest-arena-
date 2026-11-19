package gh.edu.gctu.smartattend.common;

import java.util.LinkedHashMap;
import java.util.Map;

public final class Json {
    private Json() {}

    /** Ordered map builder that (unlike Map.of) accepts null values. */
    public static Map<String, Object> obj(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }
}
