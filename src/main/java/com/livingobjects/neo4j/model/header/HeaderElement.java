package com.livingobjects.neo4j.model.header;

import com.livingobjects.neo4j.model.PropertyType;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public abstract class HeaderElement {
    public static final char ELEMENT_SEPARATOR = '~';
    private static final String NAME_PATTERN = "[^:" + ELEMENT_SEPARATOR + "]*:[^:" + ELEMENT_SEPARATOR + "]*";
    private static final String PROP_PATTERN = "[^:]*";
    private static final String ARRAY_PATTERN = "\\[]";
    private static final String TYPE_PATTERN = "\\w+";
    private static final Pattern COLUMN_PATTERN = Pattern.compile(
            "\\(?(?<name>" + NAME_PATTERN + ")(" + ELEMENT_SEPARATOR + "?(?<target>" + NAME_PATTERN + ")\\))?\\.(?<prop>" + PROP_PATTERN + "):?(?<type>" + TYPE_PATTERN + ")?(?<isArray>" + ARRAY_PATTERN + ")?",
            Pattern.UNICODE_CHARACTER_CLASS);

    public final String elementName;
    public final String propertyName;
    public final PropertyType type;
    public final boolean isArray;
    public final int index;

    HeaderElement(String elementName, String propertyName, PropertyType type, boolean isArray, int idx) {
        this.elementName = elementName;
        this.propertyName = propertyName;
        this.type = type;
        this.isArray = isArray;
        this.index = idx;
    }

    public static HeaderElement of(String columnName, int idx) {
        Matcher m = COLUMN_PATTERN.matcher(columnName);
        if (!m.matches()) {
            throw new IllegalArgumentException("The header '" + columnName + "' don't match pattern !");
        }

        String name = m.group("name");
        String target = m.group("target");
        String prop = m.group("prop");
        PropertyType type = Optional.ofNullable(m.group("type"))
                .map(String::toUpperCase)
                .map(PropertyType::valueOf)
                .orElse(PropertyType.STRING);
        boolean isArray = m.group("isArray") != null;

        if (target != null) {
            return new MultiElementHeader(name, target, prop, type, isArray, idx);
        } else {
            return new SimpleElementHeader(name, prop, type, isArray, idx);
        }
    }

    public abstract boolean isSimple();

    public abstract <R> R visit(Visitor<R> visitor);

    public interface Visitor<R> {
        R visitSimple(SimpleElementHeader header);

        R visitMulti(MultiElementHeader header);
    }

    public String columnIdentifier() {
        return visit(new Visitor<String>() {
            @Override
            public String visitSimple(SimpleElementHeader header) {
                return header.elementName + '.' + header.propertyName;
            }

            @Override
            public String visitMulti(MultiElementHeader header) {
                return '(' + header.elementName + ELEMENT_SEPARATOR + header.targetElementName + ')' + '.' + header.propertyName;
            }
        });
    }

}
