package com.livingobjects.neo4j.iwan.model.schema;

import au.com.bytecode.opencsv.CSVReader;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.livingobjects.neo4j.iwan.model.CacheNodeFactory;
import com.livingobjects.neo4j.iwan.model.HeaderElement;
import com.livingobjects.neo4j.iwan.model.UniqueEntity;
import com.livingobjects.neo4j.iwan.model.exception.SchemaTemplateException;
import com.livingobjects.neo4j.iwan.model.schema.model.Node;
import com.livingobjects.neo4j.iwan.model.schema.model.Property;
import com.livingobjects.neo4j.iwan.model.schema.model.Relationships;
import com.livingobjects.neo4j.iwan.model.schema.model.SchemaTemplate;
import com.livingobjects.neo4j.iwan.model.schema.model.SchemaVersion;
import org.neo4j.graphdb.Direction;
import org.neo4j.graphdb.DynamicLabel;
import org.neo4j.graphdb.DynamicRelationshipType;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.Transaction;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.transform.sax.SAXSource;
import javax.xml.validation.SchemaFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class SchemaTemplateLoader {

    public static final DynamicRelationshipType APPLIED_TO_LINK = DynamicRelationshipType.withName("AppliedTo");
    private final GraphDatabaseService graphDB;

    private final Map<NodeType, CacheNodeFactory> nodeFactories = Maps.newHashMap();

    public SchemaTemplateLoader(GraphDatabaseService graphDB) {
        this.graphDB = graphDB;
    }

    public int loadAndApplyTemplate(InputStream csv, InputStream xmlTemplate) throws IOException, IllegalStateException {
        CSVReader reader = new CSVReader(new InputStreamReader(csv));
        ImmutableMap<String, Integer> header = readCsvHeader(reader);

        SchemaTemplate template = parseTemplate(xmlTemplate);

        int updated = 0;
        int committed = 0;
        String[] line = reader.readNext();

        Transaction tx = graphDB.beginTx();
        try {
            while (line != null) {

                if (applyTemplate(template, header, line)) {
                    updated++;
                }

                if (updated % 100 == 0) {
                    committed = commitTx(updated, committed, tx);
                    updated = 0;
                    tx = graphDB.beginTx();
                }

                line = reader.readNext();
            }
        } finally {
            committed = commitTx(updated, committed, tx);
        }
        return committed;
    }

    private int commitTx(int applied, int committed, Transaction tx) {
        tx.success();
        tx.close();
        nodeFactories.clear();
        committed += applied;
        return committed;
    }

    private boolean applyTemplate(SchemaTemplate template, ImmutableMap<String, Integer> header, String[] line) {
        UniqueEntity<org.neo4j.graphdb.Node> templateNode = createNode(template.templateNode, header, line);

        if (shouldApplyTemplate(template, templateNode)) {
            createAndLinkTemplateVersion(template, templateNode);

            Map<String, org.neo4j.graphdb.Node> identifiedNodes = Maps.newHashMap();
            Set<CreatedNode> nodeWithRelationships = Sets.newHashSet();
            for (Node node : template.nodes) {
                UniqueEntity<org.neo4j.graphdb.Node> entity = createNode(node, header, line);
                node.id.ifPresent(id -> identifiedNodes.put(id, entity.entity));
                if (!node.relationships.isEmpty()) {
                    nodeWithRelationships.add(new CreatedNode(node, entity.entity));
                }
            }

            for (CreatedNode node : nodeWithRelationships) {
                for (Relationships relationships : node.node.relationships) {
                    DynamicRelationshipType relationshipType = DynamicRelationshipType.withName(relationships.type);
                    Iterable<Relationship> existingRelationship = node.createdNode.getRelationships(relationshipType, relationships.direction.neo4jDirection);
                    for (Relationship relationship : existingRelationship) {
                        relationship.delete();
                    }
                    for (com.livingobjects.neo4j.iwan.model.schema.model.Relationship relationshipToCreate : relationships.relationships) {
                        org.neo4j.graphdb.Node otherSideNode = identifiedNodes.get(relationshipToCreate.node);
                        if (otherSideNode == null) {
                            throw new IllegalStateException("Unable to create relationship involving node '" + relationshipToCreate.node + "' because this node is not found in template file.");
                        } else {
                            if (relationships.direction == Relationships.Direction.incoming) {
                                otherSideNode.createRelationshipTo(node.createdNode, relationshipType);
                            } else {
                                node.createdNode.createRelationshipTo(otherSideNode, relationshipType);
                            }
                        }
                    }
                }
            }

            return true;
        } else {
            return false;
        }
    }

    private void createAndLinkTemplateVersion(SchemaTemplate template, UniqueEntity<org.neo4j.graphdb.Node> templateNode) {
        CacheNodeFactory factory = nodeFactory(ImmutableList.of("Template"), ImmutableSet.of("name", "version"));
        UniqueEntity<org.neo4j.graphdb.Node> node = factory.getOrCreate(ImmutableSet.of(template.name, template.version.toString()));
        node.entity.createRelationshipTo(templateNode.entity, APPLIED_TO_LINK);
    }

    private boolean shouldApplyTemplate(SchemaTemplate template, UniqueEntity<org.neo4j.graphdb.Node> templateNode) {
        Iterable<Relationship> relationships = templateNode.entity.getRelationships(Direction.INCOMING, APPLIED_TO_LINK);
        for (Relationship relationship : relationships) {
            org.neo4j.graphdb.Node appliedTemplateNode = relationship.getStartNode();
            boolean isTemplateNode = false;
            for (Label label : appliedTemplateNode.getLabels()) {
                if (label.equals(DynamicLabel.label("Template"))) {
                    isTemplateNode = true;
                    break;
                }
            }
            if (isTemplateNode) {
                String templateName = appliedTemplateNode.getProperty("name", "").toString();
                if (templateName.equals(template.name)) {
                    String templateVersion = appliedTemplateNode.getProperty("version", "0").toString();
                    SchemaVersion schemaVersion = SchemaVersion.of(templateVersion);
                    if (schemaVersion.compareTo(template.version) < 0) {
                        relationship.delete();
                        if (!appliedTemplateNode.hasRelationship()) {
                            appliedTemplateNode.delete();
                        }
                        return true;
                    } else {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private UniqueEntity<org.neo4j.graphdb.Node> createNode(Node node, ImmutableMap<String, Integer> header, String[] line) {
        CacheNodeFactory factory = nodeFactory(node.labels, node.keys.keySet());

        ImmutableSet.Builder<String> transformedKeys = ImmutableSet.builder();
        for (Map.Entry<String, String> keyProperty : node.keys.entrySet()) {
            String value = keyProperty.getValue();
            String transformedValue = StringTemplate.template(value, header, line);
            transformedKeys.add(transformedValue);
        }

        UniqueEntity<org.neo4j.graphdb.Node> entity = factory.getOrCreate(transformedKeys.build());
        if (entity.wasCreated) {
            for (Property property : node.properties) {
                String value = StringTemplate.template(property.value, header, line);
                entity.entity.setProperty(property.name, value);
            }
        }
        return entity;
    }

    private SchemaTemplate parseTemplate(InputStream xmlTemplate) throws SchemaTemplateException {
        try {
            SchemaFactory schemaFactory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
            SAXParserFactory factory = SAXParserFactory.newInstance();
            javax.xml.validation.Schema xsdSchema = schemaFactory.newSchema(new SAXSource(new InputSource(getClass().getResourceAsStream("/schema-template.xsd"))));
            factory.setSchema(xsdSchema);
            SAXParser saxParser = factory.newSAXParser();
            XMLSchemaTemplateHandler handler = new XMLSchemaTemplateHandler();
            saxParser.parse(xmlTemplate, handler);
            return handler.getTemplate();
        } catch (ParserConfigurationException | SAXException e) {
            throw new SchemaTemplateException("Unable to instanciate XML parser", e);
        } catch (IOException e) {
            throw new SchemaTemplateException("Unable to parse XML template", e);
        }
    }

    private ImmutableMap<String, Integer> readCsvHeader(CSVReader reader) throws IOException {
        ImmutableMap.Builder<String, Integer> headerBuilder = ImmutableMap.builder();
        String[] headerLine = reader.readNext();
        if (headerLine != null) {
            for (int index = 0; index < headerLine.length; index++) {
                String column = headerLine[index];
                HeaderElement headerElement = HeaderElement.of(column, index);
                headerBuilder.put(headerElement.elementName + '.' + headerElement.propertyName, index);
            }
        }
        return headerBuilder.build();
    }

    private CacheNodeFactory nodeFactory(ImmutableList<String> labels, ImmutableSet<String> keys) {
        NodeType nodeType = new NodeType(labels, keys);
        return nodeFactories.computeIfAbsent(nodeType, k -> CacheNodeFactory.of(graphDB, labels, keys));
    }

    private final class NodeType {
        public final ImmutableList<String> labels;
        public final ImmutableSet<String> keys;

        public NodeType(ImmutableList<String> labels, ImmutableSet<String> keys) {
            this.labels = labels;
            this.keys = keys;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            NodeType nodeType = (NodeType) o;
            return Objects.equals(labels, nodeType.labels) &&
                    Objects.equals(keys, nodeType.keys);
        }

        @Override
        public int hashCode() {
            return Objects.hash(labels, keys);
        }

    }

    private final class CreatedNode {
        public final Node node;
        public final org.neo4j.graphdb.Node createdNode;

        public CreatedNode(Node node, org.neo4j.graphdb.Node createdNode) {
            this.node = node;
            this.createdNode = createdNode;
        }
    }

}
