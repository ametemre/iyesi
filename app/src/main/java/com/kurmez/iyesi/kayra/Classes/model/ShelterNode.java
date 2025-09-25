package com.kurmez.iyesi.kayra.Classes.model;// ShelterMarker.java
// package com.kurmez.iyesi.model;

public class ShelterNode extends Node {
    public ShelterNode() { setTypeEnum(NodeType.SHELTER); }

    public String getShelterName() {
        Object v = getAttrs() != null ? getAttrs().get("shelterName") : null;
        return v == null ? null : v.toString();
    }
    public void setShelterName(String name) { safeAttrs().put("shelterName", name); }

    public String getContact() {
        Object v = getAttrs() != null ? getAttrs().get("contact") : null;
        return v == null ? null : v.toString();
    }
    public void setContact(String contact) { safeAttrs().put("contact", contact); }

    public Integer getCapacity() {
        Object v = getAttrs() != null ? getAttrs().get("capacity") : null;
        if (v instanceof Number) return ((Number) v).intValue();
        try { return v == null ? null : Integer.parseInt(v.toString()); } catch(Exception e){ return null; }
    }
    public void setCapacity(int capacity) { safeAttrs().put("capacity", capacity); }
}