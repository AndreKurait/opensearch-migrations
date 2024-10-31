package org.opensearch.migrations.bulkload.common;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.io.SegmentedStringWriter;
import com.fasterxml.jackson.core.io.SerializedString;
import com.fasterxml.jackson.core.util.BufferRecycler;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.SneakyThrows;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@Slf4j
public class BulkDocSection {
    private static final JsonFactory JSON_FACTORY = JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder()
                    .maxStringLength(100_000_000) // 100 MB
                    .build())
            .build();

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper(JSON_FACTORY);
    @SuppressWarnings("unchecked")
    private static final ObjectMapper BULK_INDEX_REQUEST_MAPPER = OBJECT_MAPPER.copy()
            .registerModule(new SimpleModule()
                    .addSerializer((Class<Collection<BulkDocSection>>) (Class<?>) Collection.class,
                            new BulkIndexRequestBulkDocSectionCollectionSerializer()))
            .registerModule(new SimpleModule()
                    .addSerializer(BulkIndex.class, new BulkIndex.BulkIndexRequestSerializer()));
    private static final String NEWLINE = "\n";

    private static final LoadingCache<Map<String, Object>, String> SOURCE_DOC_BYTES_CACHE = Caffeine.newBuilder()
            .maximumWeight(500L*1000*1000) // 0.5 GB
            .weigher((k, v) -> ((String) v).length())
            .weakKeys()
            .build(OBJECT_MAPPER::writeValueAsString);

    @EqualsAndHashCode.Include
    @Getter
    private final String docId;
    private final BulkIndex bulkIndex;

    public BulkDocSection(String id, String indexName, String type, String docBody) {
        this.docId = id;
        this.bulkIndex = new BulkIndex(
            new BulkIndex.Metadata(id, type, indexName),
            parseSource(docBody)
        );
    }

    private BulkDocSection(BulkIndex bulkIndex) {
        this.docId = bulkIndex.metadata.id;
        this.bulkIndex = bulkIndex;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseSource(final String doc) {
        try {
            return OBJECT_MAPPER.readValue(doc, Map.class);
        } catch (IOException e) {
            throw new DeserializationException("Failed to parse source doc:  " + e.getMessage());
        }
    }

    public static String convertToBulkRequestBody(Collection<BulkDocSection> bulkSections) {
        try (SegmentedStringWriter writer = new SegmentedStringWriter(new BufferRecycler())) {
            BULK_INDEX_REQUEST_MAPPER.writeValue(writer, bulkSections);
            return writer.getAndClear();
        } catch (IOException e) {
            throw new SerializationException("Failed to serialize ingestion request: "+ e.getMessage());
        }
    }

    public long getSerializedLength() {
        try (var countingNullOutputStream = new OutputStream() {
            long length = 0;
            @Override
            public void write(int b) {
                length += String.valueOf(b).length();
            }

            @Override
            public void write(byte[] b, int off, int len) {
                Objects.checkFromIndexSize(off, len, b.length);
                length += len;
            }
        }) {
            BULK_INDEX_REQUEST_MAPPER.writeValue(countingNullOutputStream, this.bulkIndex);
            return countingNullOutputStream.length;
        } catch (IOException e) {
            log.atError().setMessage("Failed to get bulk index length").setCause(e).log();
            throw new SerializationException("Failed to get bulk index length " + this.bulkIndex +
                    " from string: " + e.getMessage());
        }
    }


    public String asString() {
        try (SegmentedStringWriter writer = new SegmentedStringWriter(new BufferRecycler())) {
            BULK_INDEX_REQUEST_MAPPER.writeValue(writer, this.bulkIndex);
            return writer.getAndClear();
        } catch (IOException e) {
            throw new SerializationException("Failed to write bulk index " + this.bulkIndex +
                    " from string: " + e.getMessage());
        }
    }

    public static BulkDocSection fromMap(Map<String, Object> map) {
        BulkIndex bulkIndex = OBJECT_MAPPER.convertValue(map, BulkIndex.class);
        return new BulkDocSection(bulkIndex);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> toMap() {
        return (Map<String, Object>) OBJECT_MAPPER.convertValue(bulkIndex, Map.class);
    }

    @NoArgsConstructor(force = true) // For Jackson
    @AllArgsConstructor
    @ToString
    @EqualsAndHashCode
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private static class BulkIndex {
        @JsonProperty("index")
        private final Metadata metadata;
        @ToString.Exclude
        @JsonProperty("source")
        private final Map<String, Object> sourceDoc;

        @NoArgsConstructor(force = true) // For Jackson
        @AllArgsConstructor
        @ToString
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private static class Metadata {
            @JsonProperty("_id")
            private final String id;
            @JsonProperty("_type")
            private final String type;
            @JsonProperty("_index")
            private final String index;
        }

        public static class BulkIndexRequestSerializer extends JsonSerializer<BulkIndex> {
            public static final String BULK_INDEX_COMMAND = "index";
            @SneakyThrows
            @Override
            public void serialize(BulkIndex value, JsonGenerator gen, SerializerProvider serializers) {
                gen.setRootValueSeparator(new SerializedString(NEWLINE));
                gen.writeStartObject();
                gen.writePOJOField(BULK_INDEX_COMMAND, value.metadata);
                gen.writeEndObject();
//                gen.writePOJO(value.sourceDoc);
                String sourceDocString = SOURCE_DOC_BYTES_CACHE.get(value.sourceDoc);
                gen.writeRawValue(sourceDocString);
//                gen.writeRawValue(OBJECT_MAPPER.writeValueAsString(value.sourceDoc));
            }
        }
    }

    public static class BulkIndexRequestBulkDocSectionCollectionSerializer extends JsonSerializer<Collection<BulkDocSection>> {
        private static final BulkIndex.BulkIndexRequestSerializer INSTANCE = new BulkIndex.BulkIndexRequestSerializer();
        @Override
        public void serialize(Collection<BulkDocSection> collection, JsonGenerator gen, SerializerProvider serializers) throws IOException {
            gen.setRootValueSeparator(new SerializedString(NEWLINE));
            for (BulkDocSection item : collection) {
                INSTANCE.serialize(item.bulkIndex, gen, serializers);
            }
            gen.writeRaw(NEWLINE);
        }
    }

    public static class DeserializationException extends RuntimeException {
        public DeserializationException(String message) {
            super(message);
        }
    }

    public static class SerializationException extends RuntimeException {
        public SerializationException(String message) {
            super(message);
        }
    }
}
