package com.livingobjects.neo4j.schema;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.livingobjects.neo4j.model.iwan.RelationshipTypes;
import com.livingobjects.neo4j.model.schema.CounterNode;
import com.livingobjects.neo4j.model.schema.MemdexPathNode;
import com.livingobjects.neo4j.model.schema.RealmNode;
import com.livingobjects.neo4j.model.schema.managed.CountersDefinition;
import com.livingobjects.neo4j.model.schema.type.type.CountCounterType;
import com.livingobjects.neo4j.model.schema.type.type.CounterType;
import org.neo4j.graphdb.Direction;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.NotFoundException;
import org.neo4j.graphdb.Relationship;
import org.neo4j.logging.Log;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.livingobjects.neo4j.model.iwan.GraphModelConstants.DESCRIPTION;
import static com.livingobjects.neo4j.model.iwan.GraphModelConstants.ID;
import static com.livingobjects.neo4j.model.iwan.GraphModelConstants.KEYTYPE_SEPARATOR;
import static com.livingobjects.neo4j.model.iwan.GraphModelConstants.LINK_PROP_SPECIALIZER;
import static com.livingobjects.neo4j.model.iwan.GraphModelConstants.MANAGED;
import static com.livingobjects.neo4j.model.iwan.GraphModelConstants.NAME;
import static com.livingobjects.neo4j.model.iwan.GraphModelConstants._TYPE;
import static com.livingobjects.neo4j.model.schema.type.type.CounterType.COUNT;

public class SchemaReader {

    private final Log logger;

    public SchemaReader(Log logger) {
        this.logger = logger;
    }

    public Optional<RealmNode> readRealm(Node realmTemplateNode, boolean onlyUnamanagedCounters, CountersDefinition.Builder countersDefinitionBuilder) {
        String name = realmTemplateNode.getProperty(NAME).toString();
        try {
            Relationship firstMemdexPath = realmTemplateNode.getSingleRelationship(RelationshipTypes.MEMDEXPATH, Direction.OUTGOING);
            if (firstMemdexPath != null) {
                Node segment = firstMemdexPath.getEndNode();
                Optional<MemdexPathNode> memdexPathNode = readMemdexPath(segment, onlyUnamanagedCounters, countersDefinitionBuilder);
                if (!memdexPathNode.isPresent()) {
                    logger.warn("No counter for realm '{}'. Realm is ignored.", name);
                }
                return memdexPathNode
                        .map(n -> {
                            Set<String> attributes = Sets.newHashSet();
                            Iterable<Relationship> attributesRel = realmTemplateNode.getRelationships(RelationshipTypes.ATTRIBUTE, Direction.OUTGOING);
                            for (Relationship attribute : attributesRel) {
                                String attType = attribute.getEndNode().getProperty(_TYPE).toString();
                                String attName = attribute.getEndNode().getProperty(NAME).toString();
                                attributes.add(attType + ":" + attName);
                            }
                            return new RealmNode(name, attributes, n);
                        });
            } else {
                logger.warn("Empty RealmTemplate '{}' : no MdxPath relationship found. Ignoring it", name);
                return Optional.empty();
            }
        } catch (NotFoundException e) {
            throw new IllegalStateException(String.format("Malformed RealmTemplate '%s' : more than one root MdxPath relationships found.", name));
        }
    }

    Optional<MemdexPathNode> readMemdexPath(Node segment, boolean onlyUnamanagedCounters, CountersDefinition.Builder countersDefinitionBuilder) {
        String segmentName = segment.getProperty("path").toString();
        Integer topCount = null;
        if (segment.hasProperty("topCount")) {
            topCount = Integer.parseInt(segment.getProperty("topCount").toString());
        }

        List<String> counters = readAllCounters(segment, onlyUnamanagedCounters, countersDefinitionBuilder);

        List<MemdexPathNode> children = Lists.newArrayList();
        segment.getRelationships(RelationshipTypes.MEMDEXPATH, Direction.OUTGOING).forEach(path -> {
            readMemdexPath(path.getEndNode(), onlyUnamanagedCounters, countersDefinitionBuilder)
                    .ifPresent(children::add);
        });

        String attribute = getAttribute(segment);
        if (counters.isEmpty() && children.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new MemdexPathNode(segmentName, attribute, counters, children, topCount));
    }

    private ImmutableList<String> readAllCounters(Node segment, boolean onlyUnamanagedCounters, CountersDefinition.Builder countersDefinitionBuilder) {
        List<String> counters = Lists.newArrayList();
        segment.getRelationships(RelationshipTypes.PROVIDED, Direction.INCOMING).forEach(link -> {
            Node counterNode = link.getStartNode();
            if (!counterNode.hasProperty(ID)) return;

            String name = counterNode.getProperty(NAME).toString();
            CounterNode counter = readCounter(counterNode);
            Boolean managed = isManaged(counterNode);
            if (onlyUnamanagedCounters) {
                if (!managed) {
                    counters.add(name);
                    countersDefinitionBuilder.add(name, counter, managed);
                }
            } else {
                counters.add(name);
                countersDefinitionBuilder.add(name, counter, managed);
            }
        });
        return ImmutableList.copyOf(counters);
    }

    private CounterNode readCounter(Node node) {
        String unit = node.getProperty("unit").toString();
        String defaultValue = Optional.ofNullable(node.getProperty("defaultValue", null)).map(Object::toString).orElse(null);
        String defaultAggregation = node.getProperty("defaultAggregation").toString();
        String name = node.getProperty(NAME).toString();
        String valueType = node.getProperty("valueType").toString();

        String description = Optional.ofNullable(node.getProperty(DESCRIPTION, null)).map(Object::toString).orElse(null);

        CounterType counterType = Optional.ofNullable(node.getProperty("type", null))
                .map(Object::toString)
                .flatMap(type -> {
                    switch (type) {
                        case COUNT:
                            return Optional.ofNullable(node.getProperty("countType", null))
                                    .map(Object::toString)
                                    .map(CountCounterType::new);
                        default:
                            return Optional.empty();
                    }
                })
                .orElse(null);

        return new CounterNode(unit, defaultValue, defaultAggregation, valueType, name, counterType, description);
    }

    Boolean isManaged(Node counterNode) {
        return Optional.ofNullable(counterNode.getProperty(MANAGED, ""))
                .map(Object::toString)
                .map(Boolean::parseBoolean)
                .orElse(false);
    }

    private String getAttribute(Node node) {
        List<String> attributes = Lists.newArrayList();
        node.getRelationships(RelationshipTypes.ATTRIBUTE, Direction.OUTGOING).forEach(link -> {
            Node attributeNode = link.getEndNode();
            Object specializer = link.getProperty(LINK_PROP_SPECIALIZER, null);
            Map<String, Object> properties = attributeNode.getProperties(_TYPE, NAME);
            String attribute = properties.get(_TYPE).toString() + KEYTYPE_SEPARATOR + properties.get(NAME).toString();
            if (specializer != null) {
                attribute = attribute + KEYTYPE_SEPARATOR + specializer.toString();
            }
            attributes.add(attribute);
        });
        return Iterables.getOnlyElement(attributes);
    }

}
