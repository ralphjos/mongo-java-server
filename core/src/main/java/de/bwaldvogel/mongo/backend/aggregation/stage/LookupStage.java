package de.bwaldvogel.mongo.backend.aggregation.stage;

import de.bwaldvogel.mongo.MongoCollection;
import de.bwaldvogel.mongo.MongoDatabase;
import de.bwaldvogel.mongo.bson.Document;
import de.bwaldvogel.mongo.exception.MongoServerError;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static java.util.stream.Collectors.toList;

public class LookupStage implements AggregationStage {
    private static final String FROM = "from";
    private static final String LOCAL_FIELD = "localField";
    private static final String FOREIGN_FIELD = "foreignField";
    private static final String AS = "as";
    private static final Set<String> CONFIGURATION_KEYS;
    private final String localField;
    private final String foreignField;
    private final String as;
    private final MongoCollection<?> collection;

    static {
        CONFIGURATION_KEYS = new HashSet<>();
        CONFIGURATION_KEYS.add(FROM);
        CONFIGURATION_KEYS.add(LOCAL_FIELD);
        CONFIGURATION_KEYS.add(FOREIGN_FIELD);
        CONFIGURATION_KEYS.add(AS);
    }

    public LookupStage(Document configuration, MongoDatabase mongoDatabase) {
        String from = readConfigurationProperty(configuration, FROM);
        collection = mongoDatabase.resolveCollection(from, false);
        localField = readConfigurationProperty(configuration, LOCAL_FIELD);
        foreignField = readConfigurationProperty(configuration, FOREIGN_FIELD);
        as = readConfigurationProperty(configuration, AS);
        ensureAllConfigurationPropertiesExist(configuration);
    }

    private String readConfigurationProperty(Document configuration, String name) {
        Object value = configuration.get(name);
        if (value == null) {
            throw buildConfigurationError("missing '" + name + "' option to $lookup stage specification: " + configuration);
        }
        if (value instanceof String) {
            return (String) value;
        }
        throw buildConfigurationError("'" + name + "' option to $lookup must be a string, but was type " +
                value.getClass().getName());
    }

    private void ensureAllConfigurationPropertiesExist(Document configuration) {
        for (String name : configuration.keySet()) {
            if (!CONFIGURATION_KEYS.contains(name)) {
                String message = "unknown argument to $lookup: " + name;
                throw buildConfigurationError(message);
            }
        }
    }

    private MongoServerError buildConfigurationError(String message) {
        return new MongoServerError(9, "FailedToParse", message);
    }

    @Override
    public Stream<Document> apply(Stream<Document> stream) {
        return stream.map(this::resolveRemoteField)
                .filter(Objects::nonNull);
    }

    private Document resolveRemoteField(Document document) {
        Object value = document.get(localField);
        List<Document> documents = lookupValue(value);
        if (documents.isEmpty()) {
            return null;
        }
        Document result = document.clone();
        result.put(as, documents);
        return result;
    }

    private List<Document> lookupValue(Object value) {
        if (value instanceof List) {
            return ((List<?>) value).stream()
                    .flatMap(item -> lookupValue(item).stream())
                    .collect(toList());
        }
        Document query = new Document().append(foreignField, value);
        Iterable<Document> queryResult = collection.handleQuery(query);
        return StreamSupport.stream(queryResult.spliterator(), false)
                .collect(toList());
    }
}
