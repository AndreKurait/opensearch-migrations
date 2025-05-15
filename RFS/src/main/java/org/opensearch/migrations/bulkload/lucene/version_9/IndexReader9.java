package org.opensearch.migrations.bulkload.lucene.version_9;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.opensearch.migrations.bulkload.lucene.LuceneDirectoryReader;
import org.opensearch.migrations.bulkload.lucene.LuceneIndexReader;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import shadow.lucene9.org.apache.lucene.index.DirectoryReader;
import shadow.lucene9.org.apache.lucene.index.LeafReader;
import shadow.lucene9.org.apache.lucene.index.SegmentCommitInfo;
import shadow.lucene9.org.apache.lucene.index.SegmentInfos;
import shadow.lucene9.org.apache.lucene.index.StandardDirectoryReader;
import shadow.lucene9.org.apache.lucene.index.SoftDeletesDirectoryReaderWrapper;
import shadow.lucene9.org.apache.lucene.store.FSDirectory;
import shadow.lucene9.org.apache.lucene.util.FixedBitSet;

@AllArgsConstructor
@Slf4j
public class IndexReader9 implements LuceneIndexReader {

    protected final Path indexDirectoryPath;
    protected final boolean softDeletesPossible;
    protected final String softDeletesField;

    public LuceneDirectoryReader getReader() throws IOException {
        try (var directory = FSDirectory.open(indexDirectoryPath)) {
            var commits = DirectoryReader.listCommits(directory);

            var oldReaders = new SoftDeletesDirectoryReaderWrapper(DirectoryReader.open(commits.get(0)), softDeletesField);
            var newReaders = new SoftDeletesDirectoryReaderWrapper(DirectoryReader.open(commits.get(1)), softDeletesField);

            var firstOldReader = oldReaders.getContext().leaves().get(0).reader();
            var firstNewReader = newReaders.getContext().leaves().get(0).reader();

            var firstBitSet = ((FixedBitSet) firstOldReader.getLiveDocs());
            var secondBitSet = ((FixedBitSet) firstNewReader.getLiveDocs());

            if (firstBitSet == null) {
                firstBitSet = new FixedBitSet(firstOldReader.maxDoc());
                firstBitSet.set(0, firstOldReader.maxDoc());
            }

            if (secondBitSet == null) {
                secondBitSet = new FixedBitSet(firstNewReader.maxDoc());
                secondBitSet.set(0, firstNewReader.maxDoc());
            }

            var docsToRemoveBits = firstBitSet.clone();
            docsToRemoveBits.andNot(secondBitSet.clone());

            var docsToAddBits = secondBitSet.clone();
            docsToAddBits.andNot(firstBitSet.clone());

            var docsToAdd = getSetBitIndexes(docsToAddBits);
            var docsToRemove = getSetBitIndexes(docsToRemoveBits);

            var latestCommitInfo = SegmentInfos.readLatestCommit(directory, 0);

            var reader = StandardDirectoryReader.open(directory, latestCommitInfo, null, null);

            SoftDeletesDirectoryReaderWrapper.open(commits.get(1));

            if (softDeletesPossible) {
                reader = new SoftDeletesDirectoryReaderWrapper(reader, softDeletesField);
            }
            return new DirectoryReader9(reader, indexDirectoryPath);
        }
    }

    int[] getSetBitIndexes(FixedBitSet bitSet) {
        int max = bitSet.length();
        List<Integer> list = new ArrayList<>();
        for (int i = bitSet.nextSetBit(0); i < max; i = (i + 1 < max) ? bitSet.nextSetBit(i + 1) : Integer.MAX_VALUE) {
            list.add(i);
        }
        return list.stream().mapToInt(Integer::intValue).toArray();
    }

}
