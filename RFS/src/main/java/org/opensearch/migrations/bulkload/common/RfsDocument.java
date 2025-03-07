package org.opensearch.migrations.bulkload.common;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.opensearch.migrations.transform.IJsonTransformer;

import lombok.AllArgsConstructor;

/** 
 * This class represents a document within RFS during the Reindexing process.  It tracks:
 * * The original Lucene context of the document (Lucene segment and document identifiers)
 * * The original Elasticsearch/OpenSearch context of the document (Index and Shard)
 * * The final shape of the document as needed for reindexing
 */
@AllArgsConstructor
public class RfsDocument {
    // The Lucene index doc number of the document (global over shard / lucene-index)
    public final int luceneDocNumber;

    // The Elasticsearch/OpenSearch document to be reindexed
    public final BulkDocSection document;

    public static RfsDocument fromLuceneDocument(RfsLuceneDocument doc, String indexName) {
        return new RfsDocument(
            doc.luceneDocNumber,
            new BulkDocSection(
                doc.id,
                indexName,
                doc.type,
                doc.source,
                doc.routing
            )
        );
    }

    @SuppressWarnings("unchecked")
    public static List<RfsDocument> transform(IJsonTransformer transformer, List<RfsDocument> docs) {
        var preppedMap = Map.of("flatten",
                docs.stream().map(doc -> doc.document.toMap()).collect(Collectors.toList())
        );
        var firstDocNum = docs.get(0).luceneDocNumber;
        var transformedObject = transformer.transformJson(preppedMap);
        if (transformedObject instanceof Map) {
            Map<String, Object> transformedMap = (Map<String, Object>) transformedObject;
            if (transformedMap.containsKey("flatten")) {
                var flattenedMap = (List<Map<String, Object>>) transformedMap.get("flatten");
                return flattenedMap.stream().map(
                        transformedMapInner -> new RfsDocument(
                                firstDocNum,
                                BulkDocSection.fromMap(transformedMapInner)
                )).collect(Collectors.toList());
            }
            return List.of(new RfsDocument(
                    firstDocNum,
                BulkDocSection.fromMap(transformedMap)
            ));
        } else if (transformedObject instanceof List) {
            var transformedList = (List<Map<String, Object>>) transformedObject;
            return transformedList.stream()
                .map(item -> new RfsDocument(
                    firstDocNum,
                    BulkDocSection.fromMap(item)
                ))
                .collect(Collectors.toList());
        } else {
            throw new IllegalArgumentException(
                "Unsupported transformed document type: " + transformedObject.getClass().getName()
            );
        }
    }
}
