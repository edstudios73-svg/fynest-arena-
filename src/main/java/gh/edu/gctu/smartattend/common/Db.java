package gh.edu.gctu.smartattend.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.postgresql.util.PGobject;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Array;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;

/**
 * Thin, parameterised SQL helper. All values are bound as parameters (never concatenated),
 * which prevents SQL injection. Results are normalised to JSON-friendly Java types.
 */
@Component
public class Db {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper om;

    public Db(NamedParameterJdbcTemplate jdbc, ObjectMapper om) {
        this.jdbc = jdbc;
        this.om = om;
    }

    public NamedParameterJdbcTemplate jdbc() { return jdbc; }

    public static MapSqlParameterSource params(Object... kv) {
        if (kv.length % 2 != 0) throw new IllegalStateException("params must be key/value pairs");
        MapSqlParameterSource p = new MapSqlParameterSource();
        for (int i = 0; i < kv.length; i += 2) {
            Object v = kv[i + 1];
            if (v instanceof Instant in) v = in.atOffset(ZoneOffset.UTC);
            p.addValue((String) kv[i], v);
        }
        return p;
    }

    public List<Map<String, Object>> list(String sql, Object... kv) {
        List<Map<String, Object>> rows = jdbc.queryForList(sql, params(kv));
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (Map<String, Object> r : rows) out.add(normalise(r));
        return out;
    }

    public Map<String, Object> one(String sql, Object... kv) {
        List<Map<String, Object>> l = list(sql, kv);
        return l.isEmpty() ? null : l.get(0);
    }

    public Optional<Map<String, Object>> opt(String sql, Object... kv) {
        return Optional.ofNullable(one(sql, kv));
    }

    public int update(String sql, Object... kv) {
        return jdbc.update(sql, params(kv));
    }

    public <T> T scalar(String sql, Class<T> type, Object... kv) {
        List<T> l = jdbc.queryForList(sql, params(kv), type);
        return l.isEmpty() ? null : l.get(0);
    }

    public UUID insertReturningId(String sql, Object... kv) {
        return scalar(sql, UUID.class, kv);
    }

    public String json(Object o) {
        try {
            return om.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private Map<String, Object> normalise(Map<String, Object> r) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (var e : r.entrySet()) m.put(toCamel(e.getKey()), value(e.getValue()));
        return m;
    }

    private Object value(Object v) {
        try {
            if (v instanceof PGobject pg) {
                if (pg.getValue() == null) return null;
                if ("json".equals(pg.getType()) || "jsonb".equals(pg.getType())) return om.readValue(pg.getValue(), Object.class);
                return pg.getValue();
            }
            if (v instanceof Array a) {
                Object arr = a.getArray();
                return arr instanceof Object[] oa ? Arrays.asList(oa) : arr;
            }
            if (v instanceof java.sql.Timestamp t) return t.toInstant();
            if (v instanceof java.sql.Date d) return d.toLocalDate();
            if (v instanceof java.sql.Time t) return t.toLocalTime().toString();
            return v;
        } catch (SQLException | JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    static String toCamel(String s) {
        StringBuilder b = new StringBuilder();
        boolean up = false;
        for (char c : s.toCharArray()) {
            if (c == '_') { up = true; continue; }
            b.append(up ? Character.toUpperCase(c) : c);
            up = false;
        }
        return b.toString();
    }
}
