package com.livingobjects.neo4j.model.schema;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableSet;
import org.codehaus.jackson.annotate.JsonProperty;

import java.util.List;

public final class MemdexPathNode {
    public final String segment;
    public final String keyAttribute;
    public final ImmutableSet<String> counters;
    public final ImmutableSet<MemdexPathNode> children;

    public MemdexPathNode(@JsonProperty("segment") String segment,
                          @JsonProperty("keyAttribute") String keyAttribute,
                          @JsonProperty("counters") List<String> counters,
                          @JsonProperty("children") List<MemdexPathNode> children) {
        this.segment = segment;
        this.keyAttribute = keyAttribute;
        this.counters = ImmutableSet.copyOf(counters);
        this.children = ImmutableSet.copyOf(children);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        MemdexPathNode that = (MemdexPathNode) o;

        if (segment != null ? !segment.equals(that.segment) : that.segment != null) return false;
        if (keyAttribute != null ? !keyAttribute.equals(that.keyAttribute) : that.keyAttribute != null) return false;
        if (counters != null ? !counters.equals(that.counters) : that.counters != null) return false;
        return children != null ? children.equals(that.children) : that.children == null;
    }

    @Override
    public int hashCode() {
        int result = segment != null ? segment.hashCode() : 0;
        result = 31 * result + (segment != null ? segment.hashCode() : 0);
        result = 31 * result + (counters != null ? counters.hashCode() : 0);
        result = 31 * result + (children != null ? children.hashCode() : 0);
        return result;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("segment", segment)
                .add("segment", segment)
                .add("counters", counters)
                .add("children", children)
                .toString();
    }
}
