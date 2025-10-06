package com.kurmez.iyesi.umay.sokak.Nodes;// MarkerType.java
// Enum mapping for Turkish <-> English marker types
// package com.kurmez.iyesi.model;  // <-- paket adını istersen ayarla

// NodeType.java - String sabitleri ekle
public enum NodeType {
    FEEDING("Besleme", "feeding"),
    NEST("Yuva", "nest"),
    SHELTER("Barınak", "shelter"),
    TASK("Görev", "task");

    private final String displayName;
    private final String apiValue;

    NodeType(String displayName, String apiValue) {
        this.displayName = displayName;
        this.apiValue = apiValue;
    }

    public String wire() { return apiValue; }

    public String getDisplayName() { return displayName; }
    public String getApiValue() { return apiValue; }
    public static NodeType from(String v) {
        if (v == null) return null;
        String s = v.trim().toLowerCase();
        switch (s) {
            case "besleme": case "feeding": return FEEDING;
            case "yuva": case "nest": return NEST;
            case "barınak": case "shelter": return SHELTER;
            case "görev": case "gorev": case "task": return TASK;
            default: return null;
        }
    }

    public static NodeType fromDisplayName(String displayName) {
        for (NodeType type : values()) {
            if (type.displayName.equalsIgnoreCase(displayName)) {
                return type;
            }
        }
        return TASK; // default
    }
}