package com.kurmez.iyesi.umay.sokak.Nodes;// FeedingMarker.java
// package com.kurmez.iyesi.model;

public class FeedingNode extends Node {
    public FeedingNode() { setTypeEnum(NodeType.FEEDING); }

    public String getFoodType() {
        Object v = getAttrs() != null ? getAttrs().get("foodType") : null;
        return v == null ? null : v.toString();
    }
    public void setFoodType(String foodType) {
        safeAttrs().put("foodType", foodType);
    }

    public Boolean getWater() {
        Object v = getAttrs() != null ? getAttrs().get("water") : null;
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof String) return Boolean.parseBoolean((String) v);
        return null;
    }
    public void setWater(boolean water) {
        safeAttrs().put("water", water);
    }
}