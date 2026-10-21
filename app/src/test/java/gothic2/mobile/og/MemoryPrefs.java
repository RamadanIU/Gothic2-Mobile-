package gothic2.mobile.og;

import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** SharedPreferences в памяти для тестов без Android. */
final class MemoryPrefs implements SharedPreferences {
    final Map<String, Object> values = new HashMap<>();

    @Override public Map<String, ?> getAll() { return values; }
    @Override public String getString(String key, String def) { Object v = values.get(key); return v != null ? (String) v : def; }
    @SuppressWarnings("unchecked")
    @Override public Set<String> getStringSet(String key, Set<String> def) { Object v = values.get(key); return v != null ? (Set<String>) v : def; }
    @Override public int getInt(String key, int def) { Object v = values.get(key); return v != null ? (Integer) v : def; }
    @Override public long getLong(String key, long def) { Object v = values.get(key); return v != null ? (Long) v : def; }
    @Override public float getFloat(String key, float def) { Object v = values.get(key); return v != null ? (Float) v : def; }
    @Override public boolean getBoolean(String key, boolean def) { Object v = values.get(key); return v != null ? (Boolean) v : def; }
    @Override public boolean contains(String key) { return values.containsKey(key); }
    @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) { }
    @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) { }

    @Override
    public Editor edit() {
        return new Editor() {
            final Map<String, Object> pending = new HashMap<>();
            @Override public Editor putString(String k, String v) { pending.put(k, v); return this; }
            @Override public Editor putStringSet(String k, Set<String> v) { pending.put(k, v); return this; }
            @Override public Editor putInt(String k, int v) { pending.put(k, v); return this; }
            @Override public Editor putLong(String k, long v) { pending.put(k, v); return this; }
            @Override public Editor putFloat(String k, float v) { pending.put(k, v); return this; }
            @Override public Editor putBoolean(String k, boolean v) { pending.put(k, v); return this; }
            @Override public Editor remove(String k) { pending.put(k, null); return this; }
            @Override public Editor clear() { values.clear(); return this; }
            @Override public boolean commit() { apply(); return true; }
            @Override public void apply() {
                for (Map.Entry<String, Object> e : pending.entrySet()) {
                    if (e.getValue() == null) values.remove(e.getKey()); else values.put(e.getKey(), e.getValue());
                }
            }
        };
    }
}
