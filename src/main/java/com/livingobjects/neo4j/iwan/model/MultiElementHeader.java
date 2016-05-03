package com.livingobjects.neo4j.iwan.model;

public final class MultiElementHeader extends HeaderElement {
    public final String targetElementName;

    MultiElementHeader(String elementName, String targetElementName, String propertyName, Type type, boolean isArray, int idx) {
        super(elementName, propertyName, type, isArray, idx);
        this.targetElementName = targetElementName;
    }

    @Override
    public boolean isSimple() {
        return false;
    }
}
