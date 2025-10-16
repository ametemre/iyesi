package com.kurmez.iyesi.kayra.Classes.Nodes;

import androidx.annotation.Keep;

/**
 * Yuva (Nest) düğümü - basit erişim yardımcıları
 */
@Keep
public class YuvaNode extends Node {

    public YuvaNode() {
        // Varsayılan tip ataması (NodeType.NEST'in tanımlı olduğundan emin ol)
        setTypeEnum(NodeType.NEST);
    }

    /**
     * capacity alanını Integer olarak döner.
     * @return capacity değeri yoksa null
     */
    public Integer getCapacity() {
        Object v = getAttrs() != null ? getAttrs().get("capacity") : null;
        if (v instanceof Number) {
            return ((Number) v).intValue();
        }
        if (v != null) {
            try {
                return Integer.parseInt(v.toString());
            } catch (NumberFormatException e) {
                // İstersen burada Log.w veya hata izleme ekle
                return null;
            }
        }
        return null;
    }

    public void setCapacity(int capacity) {
        safeAttrs().put("capacity", capacity);
    }

    public String getMaterial() {
        Object v = getAttrs() != null ? getAttrs().get("material") : null;
        return v == null ? null : v.toString();
    }

    public void setMaterial(String material) {
        safeAttrs().put("material", material);
    }

    public String getCondition() {
        Object v = getAttrs() != null ? getAttrs().get("condition") : null;
        return v == null ? null : v.toString();
    }

    public void setCondition(String condition) {
        safeAttrs().put("condition", condition);
    }

    @Override
    public String toString() {
        return "YuvaNode{" +
                "capacity=" + getCapacity() +
                ", material=" + getMaterial() +
                ", condition=" + getCondition() +
                ", type=" + getType() +
                '}';
    }
}
