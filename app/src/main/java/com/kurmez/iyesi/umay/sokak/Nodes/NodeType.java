package com.kurmez.iyesi.umay.sokak.Nodes;// MarkerType.java
// Enum mapping for Turkish <-> English marker types
// package com.kurmez.iyesi.model;  // <-- paket adını istersen ayarla

public enum NodeType {
    FEEDING("Besleme"),
    NEST("Yuva"),
    SHELTER("Barınak"),
    TASK("Görev");

    private final String wire; // Firestore'da saklanan değer (TR)

    NodeType(String wire) { this.wire = wire; }
    public String wire() { return wire; }

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
}