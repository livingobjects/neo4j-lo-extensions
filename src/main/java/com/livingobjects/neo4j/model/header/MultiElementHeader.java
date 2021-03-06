package com.livingobjects.neo4j.model.header;

import com.livingobjects.neo4j.model.PropertyType;

public final class MultiElementHeader extends HeaderElement {
    public final String targetElementName;

    MultiElementHeader(String elementName, String targetElementName, String propertyName, PropertyType type, boolean isArray, int idx) {
        super(elementName, propertyName, type, isArray, idx);
        this.targetElementName = targetElementName;
    }

    @Override
    public boolean isSimple() {
        return false;
    }

    @Override
    public <R> R visit(Visitor<R> visitor) {
        return visitor.visitMulti(this);
    }
}
