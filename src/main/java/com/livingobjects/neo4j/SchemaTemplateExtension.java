package com.livingobjects.neo4j;

import com.google.common.collect.Lists;
import com.livingobjects.neo4j.model.iwan.Labels;
import com.livingobjects.neo4j.model.iwan.RelationshipTypes;
import com.livingobjects.neo4j.model.result.Neo4jErrorResult;
import com.livingobjects.neo4j.model.schema.RealmNode;
import com.livingobjects.neo4j.model.schema.SchemaAndPlanets;
import com.livingobjects.neo4j.model.schema.SchemaAndPlanetsUpdate;
import com.livingobjects.neo4j.model.schema.managed.CountersDefinition;
import com.livingobjects.neo4j.schema.SchemaLoader;
import com.livingobjects.neo4j.schema.SchemaReader;
import org.codehaus.jackson.JsonEncoding;
import org.codehaus.jackson.JsonGenerator;
import org.codehaus.jackson.JsonParser;
import org.codehaus.jackson.map.ObjectMapper;
import org.neo4j.graphdb.Direction;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Transaction;
import org.neo4j.logging.Log;

import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.stream.Collectors;

import static com.livingobjects.neo4j.model.iwan.GraphModelConstants.ID;
import static com.livingobjects.neo4j.model.iwan.GraphModelConstants.VERSION;

@Path("/schema")
public class SchemaTemplateExtension {

    private final Log logger;

    private final GraphDatabaseService graphDb;
    private final ObjectMapper json = new ObjectMapper();

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    public SchemaTemplateExtension(@Context GraphDatabaseService graphDb, @Context Log log) {
        this.graphDb = graphDb;
        this.logger = log;
    }

    @POST
    @Produces({"application/json", "text/plain"})
    @Consumes(MediaType.APPLICATION_JSON)
    public Response loadSchema(String jsonBody) throws IOException {
        try (JsonParser jsonParser = json.getJsonFactory().createJsonParser(jsonBody)) {
            SchemaLoader schemaLoader = new SchemaLoader(graphDb, logger);
            SchemaAndPlanets schema = jsonParser.readValueAs(SchemaAndPlanets.class);
            boolean updated = schemaLoader.load(schema);
            return Response.ok().entity('"' + String.valueOf(updated) + '"').type(MediaType.APPLICATION_JSON).build();
        } catch (Throwable e) {
            logger.error("Unable to load schema", e);
            return errorResponse(e);
        }
    }

    @PUT
    @Produces({"application/json", "text/plain"})
    @Consumes(MediaType.APPLICATION_JSON)
    public Response updateSchema(String jsonBody) throws IOException {
        try (JsonParser jsonParser = json.getJsonFactory().createJsonParser(jsonBody)) {
            SchemaLoader schemaLoader = new SchemaLoader(graphDb, logger);
            SchemaAndPlanetsUpdate schemaAndPlanetsUpdate = jsonParser.readValueAs(SchemaAndPlanetsUpdate.class);
            boolean updated = schemaLoader.update(schemaAndPlanetsUpdate);
            return Response.ok().entity('"' + String.valueOf(updated) + '"').type(MediaType.APPLICATION_JSON).build();
        } catch (Throwable e) {
            logger.error("Unable to load schema", e);
            return errorResponse(e);
        }
    }

    @GET
    @Path("{id}")
    @Produces({"application/json", "text/plain"})
    public Response getSchema(@PathParam("id") String schemaId) throws IOException {
        SchemaReader schemaReader = new SchemaReader(logger);
        try (Transaction ignore = graphDb.beginTx()) {
            Node schemaNode = graphDb.findNode(Labels.SCHEMA, ID, schemaId);

            if (schemaNode == null) {
                return errorResponse(new NoSuchElementException("Schema " + schemaId + " not found in database !"));
            }
        }

        StreamingOutput stream = outputStream -> {
            List<Node> realmNodes = Lists.newArrayList();
            try (JsonGenerator jg = json.getJsonFactory().createJsonGenerator(outputStream, JsonEncoding.UTF8);
                 Transaction tx = graphDb.beginTx()) {
                Node schemaNode = graphDb.findNode(Labels.SCHEMA, ID, schemaId);

                if (schemaNode == null) {
                    throw new NoSuchElementException("Schema " + schemaId + " not found in database !");
                }

                jg.writeStartObject();
                jg.writeStringField(ID, schemaId);
                jg.writeStringField(VERSION, schemaNode.getProperty(VERSION, "0").toString());

                schemaNode.getRelationships(Direction.OUTGOING, RelationshipTypes.PROVIDED).forEach(rel -> {
                    Node targetNode = rel.getEndNode();
                    if (targetNode.hasLabel(Labels.REALM_TEMPLATE)) {
                        realmNodes.add(targetNode);
                    }
                });

                CountersDefinition.Builder countersDefinitionBuilder = CountersDefinition.builder();
                Map<String, RealmNode> realms = realmNodes.stream()
                        .map(n -> schemaReader.readRealm(n, false, countersDefinitionBuilder))
                        .filter(Optional::isPresent)
                        .map(Optional::get)
                        .collect(Collectors.toMap(r -> "realm:" + r.name, r -> r));

                jg.writeObjectFieldStart("counters");
                jg.flush();

                CountersDefinition countersDefinition = countersDefinitionBuilder.build();

                countersDefinition.counters.forEach((key, value) -> {
                    try {
                        jg.writeObjectField(key, value);
                    } catch (IOException e) {
                        logger.error("{}: {}", e.getClass(), e.getLocalizedMessage());
                        if (logger.isDebugEnabled()) {
                            logger.debug("STACKTRACE", e);
                        }
                    }
                });
                jg.writeEndObject();

                jg.writeObjectFieldStart("realms");
                jg.flush();
                realms.forEach((key, value) -> {
                    try {
                        jg.writeObjectField(key, value);
                        jg.flush();
                    } catch (IOException e) {
                        logger.error("{}: {}", e.getClass(), e.getLocalizedMessage());
                        if (logger.isDebugEnabled()) {
                            logger.debug("STACKTRACE", e);
                        }
                    }
                });

                jg.writeEndObject();
                jg.writeEndObject();
                jg.flush();
            } catch (Throwable e) {
                logger.error("Unable to load schema '{}'", schemaId, e);
            }
        };
        return Response.ok().entity(stream).type(MediaType.APPLICATION_JSON).build();
    }

    private Response errorResponse(Throwable cause) throws IOException {
        String code = cause.getClass().getName();
        Neo4jErrorResult error = new Neo4jErrorResult(code, cause.getMessage());
        String json = JSON_MAPPER.writeValueAsString(error);
        return Response.serverError().entity(json).type(MediaType.APPLICATION_JSON_TYPE).build();
    }

}
