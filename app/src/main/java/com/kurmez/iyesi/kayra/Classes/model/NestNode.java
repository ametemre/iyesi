package com.kurmez.iyesi.kayra.Classes.model;// NestMarker.java
// package com.kurmez.iyesi.model;

public class NestNode extends Node {
    public NestNode() { setTypeEnum(NodeType.NEST); }

    public Integer getCapacity() {
        Object v = getAttrs() != null ? getAttrs().get("capacity") : null;
        if (v instanceof Number) return ((Number) v).intValue();
        try { return v == null ? null : Integer.parseInt(v.toString()); } catch(Exception e){ return null; }
    }
    public void setCapacity(int capacity) { safeAttrs().put("capacity", capacity); }

    public String getMaterial() {
        Object v = getAttrs() != null ? getAttrs().get("material") : null;
        return v == null ? null : v.toString();
    }
    public void setMaterial(String material) { safeAttrs().put("material", material); }

    public String getCondition() {
        Object v = getAttrs() != null ? getAttrs().get("condition") : null;
        return v == null ? null : v.toString();
    }
    public void setCondition(String condition) { safeAttrs().put("condition", condition); }
}